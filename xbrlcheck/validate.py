"""Validation orchestration and interval consistency checks."""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal

from .errors import Diagnostic
from .graph import build_role_graphs
from .graph import canonical_min_cycles
from .intervals import Interval
from .intervals import fact_interval
from .intervals import validate_numeric_fact
from .model import Instance, Taxonomy


@dataclass(frozen=True)
class Contributor:
    concept: str
    weight: int
    interval: Interval


def _dedup(diagnostics: list[Diagnostic]) -> list[Diagnostic]:
    seen: set[tuple] = set()
    result: list[Diagnostic] = []
    for diagnostic in diagnostics:
        key = (
            diagnostic.code,
            diagnostic.message,
            diagnostic.role,
            diagnostic.concept,
            diagnostic.context,
            diagnostic.unit,
            diagnostic.location.source,
            diagnostic.location.line,
            diagnostic.location.column,
        )
        if key not in seen:
            seen.add(key)
            result.append(diagnostic)
    return result


def _check_structure(taxonomy: Taxonomy, instance: Instance) -> tuple[list[Diagnostic], dict, dict, set]:
    diagnostics: list[Diagnostic] = []
    fact_index: dict[tuple[str, str, str], list] = {}
    concept_context_units: dict[str, set[tuple[str, str]]] = {}
    valid_fact_keys: set[tuple[str, str, str]] = set()

    for fact in instance.facts:
        numeric = taxonomy.is_numeric(fact.concept)
        known = fact.concept in taxonomy.concepts
        if not known:
            diagnostics.append(
                Diagnostic(
                    "E102",
                    f"fact references unknown concept {fact.concept!r}",
                    fact.location,
                    concept=fact.concept,
                    context=fact.context_ref,
                    unit=fact.unit_ref,
                )
            )
        context_ok = fact.context_ref in instance.contexts
        if not fact.context_ref or not context_ok:
            diagnostics.append(
                Diagnostic(
                    "E103",
                    f"fact {fact.concept!r} references missing context {fact.context_ref!r}",
                    fact.location,
                    concept=fact.concept,
                    context=fact.context_ref,
                    unit=fact.unit_ref,
                )
            )
        if numeric:
            unit_ok = fact.unit_ref in instance.units
            if not fact.unit_ref or not unit_ok:
                diagnostics.append(
                    Diagnostic(
                        "E104",
                        f"fact {fact.concept!r} references missing unit {fact.unit_ref!r}",
                        fact.location,
                        concept=fact.concept,
                        context=fact.context_ref,
                    unit=fact.unit_ref,
                )
            )
            diagnostics.extend(validate_numeric_fact(fact))
        elif fact.unit_ref:
            diagnostics.append(
                Diagnostic(
                    "E105",
                    f"non-numeric fact {fact.concept!r} must not declare a unit",
                    fact.location,
                    concept=fact.concept,
                    context=fact.context_ref,
                    unit=fact.unit_ref,
                )
            )

        key = fact.key()
        bucket = fact_index.setdefault(key, [])
        if bucket:
            diagnostics.append(
                Diagnostic(
                    "E101",
                    f"duplicate fact for concept {fact.concept!r} context {fact.context_ref!r} "
                    f"unit {fact.unit_ref!r}",
                    fact.location,
                    concept=fact.concept,
                    context=fact.context_ref,
                    unit=fact.unit_ref,
                )
            )
        bucket.append(fact)
        concept_context_units.setdefault(fact.concept, set()).add((fact.context_ref, fact.unit_ref))
        if known and context_ok and (not numeric or fact.unit_ref in instance.units):
            valid_fact_keys.add(key)
    return diagnostics, fact_index, concept_context_units, valid_fact_keys


def _check_arc_concepts(taxonomy: Taxonomy) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []
    for arc in taxonomy.arcs:
        for endpoint in (arc.parent, arc.child):
            if endpoint not in taxonomy.concepts:
                diagnostics.append(
                    Diagnostic(
                        "E102",
                        f"arc references unknown concept {endpoint!r}",
                        arc.location,
                        role=arc.role,
                        concept=endpoint,
                    )
                )
        if not taxonomy.is_numeric(arc.parent) or not taxonomy.is_numeric(arc.child):
            diagnostics.append(
                Diagnostic(
                    "E201",
                    f"calculation arc {arc.parent} -> {arc.child} must connect numeric concepts",
                    arc.location,
                    role=arc.role,
                    concept=arc.parent,
                )
            )
    return diagnostics


def _check_calculations(
    taxonomy: Taxonomy,
    instance: Instance,
    graph: RoleGraph,
    fact_index: dict,
) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []
    unit_by_id = {unit.unit_id: unit.measures for unit in instance.units.values()}

    for parent_concept in sorted(graph.children_of):
        arcs = graph.outgoing(parent_concept)
        child_concepts = sorted({arc.child for arc in arcs})
        weight_by_child = {arc.child: arc.weight for arc in arcs}

        for (context_id, unit_id), facts in sorted(
            (item for item in _facts_for(fact_index, parent_concept)),
            key=lambda item: (item[0][0], item[0][1]),
        ):
            for occurrence, fact in enumerate(facts):
                if occurrence > 0:
                    continue  # duplicates already reported as E101
                if context_id not in instance.contexts:
                    continue
                if unit_id and unit_id not in instance.units:
                    continue
                contributors: list[Contributor] = []
                has_conflict = False
                for child in child_concepts:
                    same_bucket: list = []
                    other_context: list[tuple[str, object]] = []
                    other_unit: list[tuple[str, object]] = []
                    for (child_context, child_unit), child_facts in _facts_for(fact_index, child):
                        if child_context == context_id and child_unit == unit_id:
                            same_bucket.extend(child_facts)
                        elif child_context != context_id:
                            other_context.append((child_context, child_facts[0]))
                        else:
                            other_unit.append((child_unit, child_facts[0]))
                    if same_bucket:
                        child_fact = same_bucket[0]
                        try:
                            interval = fact_interval(child_fact)
                        except Exception:
                            continue
                        contributors.append(
                            Contributor(child, weight_by_child[child], interval)
                        )
                        continue
                    if other_context:
                        has_conflict = True
                        other_context.sort(key=lambda item: item[0])
                        child_context, child_fact = other_context[0]
                        diagnostics.append(
                            Diagnostic(
                                "E202",
                                f"summation {parent_concept} in context {context_id!r} cannot add "
                                f"contributor {child} reported in context {child_context!r}",
                                child_fact.location,
                                role=graph.role,
                                concept=parent_concept,
                                context=context_id,
                                unit=unit_id,
                            )
                        )
                    elif other_unit:
                        has_conflict = True
                        other_unit.sort(key=lambda item: item[0])
                        child_unit, child_fact = other_unit[0]
                        diagnostics.append(
                            Diagnostic(
                                "E203",
                                f"summation {parent_concept} unit {unit_id!r} cannot add "
                                f"contributor {child} reported with unit {child_unit!r}",
                                child_fact.location,
                                role=graph.role,
                                concept=parent_concept,
                                context=context_id,
                                unit=unit_id,
                            )
                        )
                try:
                    parent_interval = fact_interval(fact)
                except Exception:
                    continue
                if has_conflict:
                    continue
                total_low = sum(
                    (item.interval.low * item.weight for item in contributors), Decimal(0)
                )
                total_high = sum(
                    (item.interval.high * item.weight for item in contributors), Decimal(0)
                )
                child_bounds = Interval(total_low, total_high)
                gap = parent_interval.gap_to(child_bounds)
                if gap > 0:
                    lines = [
                        f"parent {parent_concept}={parent_interval.render()} disjoint from "
                        f"weighted children {child_bounds.render()} "
                        f"(minimum difference {format(gap, 'f')})",
                    ]
                    for contributor in sorted(contributors, key=lambda item: item.concept):
                        sign = "+" if contributor.weight >= 0 else ""
                        lines.append(
                            f"  contributor {contributor.concept} weight {sign}{contributor.weight} "
                            f"interval {contributor.interval.render()}"
                        )
                    diagnostics.append(
                        Diagnostic(
                            "W301",
                            "\n".join(lines),
                            fact.location,
                            role=graph.role,
                            concept=parent_concept,
                            context=context_id,
                            unit=unit_id,
                        )
                    )
    return diagnostics

def _facts_for(fact_index: dict, concept: str):
    for (concept_name, context_id, unit_id), facts in fact_index.items():
        if concept_name == concept:
            yield (context_id, unit_id), facts


def validate(taxonomy: Taxonomy, instance: Instance) -> list[Diagnostic]:
    """Run all checks and return diagnostics in canonical stable order."""
    diagnostics: list[Diagnostic] = []
    structural, fact_index, _, _ = _check_structure(taxonomy, instance)
    diagnostics.extend(structural)
    diagnostics.extend(_check_arc_concepts(taxonomy))

    graphs, graph_diagnostics = build_role_graphs(taxonomy)
    diagnostics.extend(graph_diagnostics)
    for role in sorted(graphs):
        graph = graphs[role]
        cycles = canonical_min_cycles(graph)
        for cycle in cycles:
            arc_lookup = {(arc.parent, arc.child): arc for arcs in graph.children_of.values() for arc in arcs}
            edges = " ; ".join(
                f"{node} -> {cycle[(idx + 1) % len(cycle)]}"
                f"@{arc_lookup[(node, cycle[(idx + 1) % len(cycle)])].location.render()}"
                for idx, node in enumerate(cycle)
            )
            diagnostics.append(
                Diagnostic(
                    "E204",
                    f"cycle in role {role!r}: {edges}",
                    role=role,
                    concept=cycle[0],
                )
            )
        if cycles:
            continue
        diagnostics.extend(_check_calculations(taxonomy, instance, graph, fact_index))

    return sorted(_dedup(diagnostics), key=lambda diagnostic: diagnostic.sort_key())
