"""Parsing of the simplified instance and linkbase XML vocabularies."""

from __future__ import annotations

from .errors import Diagnostic
from .errors import Location
from .limits import Limits
from .model import Arc
from .model import Context
from .model import Instance, Taxonomy
from .model import Fact
from .model import NUMERIC_TYPES
from .model import Unit
from .xmlsafe import SafeDocument
from .xmlsafe import SafeElement
from .xmlsafe import parse_bytes
from .xmlsafe import parse_file

CONTEXT_LOCAL_NAMES = {"context", "unit"}
RESERVED_WRAPPERS = {"xbrl", "revision", "linkbase"}


def _parse_context(element: SafeElement, source: str) -> tuple[Context, list[Diagnostic]]:
    diagnostics: list[Diagnostic] = []
    location = Location(source, element.line, element.column)
    context_id = element.attr("id", "") or ""
    period = next(iter(element.direct_children("period")), None)
    kind = start = end = instant = ""
    if period is None:
        diagnostics.append(Diagnostic("E107", "context is missing a period", location))
    else:
        period_location = Location(source, period.line, period.column)
        instant_el = next(iter(period.direct_children("instant")), None)
        start_el = next(iter(period.direct_children("startDate")), None)
        end_el = next(iter(period.direct_children("endDate")), None)
        if instant_el is not None:
            kind = "instant"
            instant = instant_el.text.strip()
            if not instant:
                diagnostics.append(Diagnostic("E107", "instant period is empty", period_location))
        elif start_el is not None or end_el is not None:
            kind = "duration"
            start = start_el.text.strip() if start_el is not None else ""
            end = end_el.text.strip() if end_el is not None else ""
            if not start or not end:
                diagnostics.append(
                    Diagnostic("E107", "duration period requires startDate and endDate", period_location)
                )
        else:
            diagnostics.append(Diagnostic("E107", "period must be instant or duration", period_location))
    return Context(context_id, kind, start, end, instant, location), diagnostics


def _parse_unit(element: SafeElement, source: str) -> tuple[Unit, list[Diagnostic]]:
    diagnostics: list[Diagnostic] = []
    location = Location(source, element.line, element.column)
    unit_id = element.attr("id", "") or ""
    measures = tuple(
        child.text.strip()
        for child in element.find_all("measure")
        if child.text.strip()
    )
    divide = next(iter(element.direct_children("divide")), None)
    if divide is not None:
        numerator = [
            measure.text.strip()
            for m in divide.direct_children("unitNumerator")
            for measure in m.direct_children("measure")
            if measure.text.strip()
        ]
        denominator = [
            measure.text.strip()
            for m in divide.direct_children("unitDenominator")
            for measure in m.direct_children("measure")
            if measure.text.strip()
        ]
        if not numerator or not denominator:
            diagnostics.append(
                Diagnostic("E105", "divide unit requires unitNumerator and unitDenominator", location)
            )
        measures = tuple(numerator + [f"per:{m}" for m in denominator])
    if not measures:
        diagnostics.append(Diagnostic("E105", "unit declares no measures", location))
    return Unit(unit_id, measures, location), diagnostics


def _is_fact_element(element: SafeElement) -> bool:
    name = element.local_name()
    return name not in RESERVED_WRAPPERS and name not in CONTEXT_LOCAL_NAMES


def parse_document(
    doc: SafeDocument,
    *,
    facts_only: bool = False,
) -> tuple[Instance, list[Diagnostic]]:
    """Extract contexts/units/facts from an instance or a revision document."""
    instance = Instance()
    diagnostics: list[Diagnostic] = []
    source = doc.source
    roots_to_scan = [doc.root]
    roots_to_scan.extend(child for child in doc.root.direct_children() if child.local_name() == "revision")

    for container in roots_to_scan:
        for element in container.direct_children():
            name = element.local_name()
            if name == "context" and not facts_only:
                context, context_diags = _parse_context(element, source)
                diagnostics.extend(context_diags)
                instance.contexts[context.context_id] = context
            elif name == "unit" and not facts_only:
                unit, unit_diags = _parse_unit(element, source)
                diagnostics.extend(unit_diags)
                instance.units[unit.unit_id] = unit
            elif name == "revision":
                continue
            elif _is_fact_element(element):
                fact = Fact(
                    concept=name,
                    context_ref=element.attr("contextRef", "") or "",
                    unit_ref=element.attr("unitRef", "") or "",
                    decimals=element.attr("decimals", "") or "",
                    value=element.text.strip(),
                    location=Location(source, element.line, element.column),
                )
                instance.facts.append(fact)
    return instance, diagnostics


def parse_instance(path: str, limits: Limits | None = None) -> tuple[Instance, list[Diagnostic]]:
    doc = parse_file(path, limits)
    return parse_document(doc)


def parse_linkbase(path: str, limits: Limits | None = None) -> tuple[Taxonomy, list[Diagnostic]]:
    doc = parse_file(path, limits)
    source = doc.source
    taxonomy = Taxonomy()
    diagnostics: list[Diagnostic] = []

    for element in doc.root.find_all("concept"):
        name = element.attr("name", "") or ""
        kind = element.attr("type", "") or ""
        if not name:
            diagnostics.append(
                Diagnostic("E102", "concept declaration missing name attribute",
                           Location(source, element.line, element.column))
            )
            continue
        if kind and kind not in NUMERIC_TYPES:
            taxonomy.concept_types[name] = "string"
        else:
            taxonomy.concept_types[name] = kind or "string"
        taxonomy.concepts.add(name)

    for element in doc.root.find_all("arc"):
        location = Location(source, element.line, element.column)
        role = element.attr("role", "") or ""
        parent = element.attr("from", "") or ""
        child = element.attr("to", "") or ""
        weight_raw = element.attr("weight", "1") or "1"
        priority_raw = element.attr("priority", "0") or "0"
        order_raw = element.attr("order", "1") or "1"
        try:
            weight = int(weight_raw)
        except ValueError:
            diagnostics.append(Diagnostic("E201", f"arc weight must be an integer, got {weight_raw!r}", location))
            continue
        try:
            priority = int(priority_raw)
            order = float(order_raw)
        except ValueError:
            diagnostics.append(Diagnostic("E201", "arc priority/order must be numeric", location))
            continue
        if not parent or not child:
            diagnostics.append(Diagnostic("E201", "arc requires from and to concepts", location))
            continue
        taxonomy.arcs.append(Arc(role, parent, child, weight, priority, order, location))
    return taxonomy, diagnostics


def parse_revision(path: str, limits: Limits | None = None) -> tuple[Instance, list[Diagnostic]]:
    """Parse an incremental revision file; it may only contain facts."""
    doc = parse_file(path, limits)
    return parse_document(doc, facts_only=True)


def parse_instance_bytes(data: bytes, source: str = "<instance>", limits: Limits | None = None):
    return parse_document(parse_bytes(data, source, limits))
