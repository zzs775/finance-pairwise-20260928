"""Small-graph enumeration oracle and hand-computed fixtures."""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal

from .intervals import Interval
from .intervals import fact_interval
from .graph import resolve_arcs
from .model import Arc
from .model import Context
from .model import Fact
from .model import Instance
from .model import Taxonomy
from .model import Unit


@dataclass(frozen=True)
class OracleFinding:
    code: str
    role: str = ""
    concept: str = ""
    context: str = ""
    unit: str = ""
    parent_interval: str = ""
    children_interval: str = ""
    minimum_difference: str = ""
    contributors: tuple[str, ...] = ()


def _facts_by_concept(instance: Instance) -> dict[str, list[Fact]]:
    grouped: dict[str, list[Fact]] = {}
    for fact in instance.facts:
        grouped.setdefault(fact.concept, []).append(fact)
    return grouped


def _detect_cycle(arcs: list[Arc]) -> tuple[str, ...] | None:
    adjacency: dict[str, set[str]] = {}
    for arc in arcs:
        adjacency.setdefault(arc.parent, set()).add(arc.child)
    best: tuple[str, ...] | None = None

    def search(start: str, current: str, path: list[str]) -> None:
        nonlocal best
        for nxt in sorted(adjacency.get(current, ())):
            if nxt == start and path:
                candidate = tuple(path)
                pivot = min(range(len(candidate)), key=lambda i: candidate[i])
                rotations = [candidate[i:] + candidate[:i] for i, node in enumerate(candidate) if node == candidate[pivot]]
                rotated = min(rotations)
                if best is None or rotated < best:
                    best = rotated
            elif nxt not in path and nxt >= start:
                path.append(nxt)
                search(start, nxt, path)
                path.pop()

    for node in sorted(adjacency):
        search(node, node, [node])
    return best


def oracle_check(taxonomy: Taxonomy, instance: Instance) -> list[OracleFinding]:
    """Brute-force independent check: enumerate every parent/child fact pair."""
    findings: list[OracleFinding] = []
    resolutions, _ = resolve_arcs(taxonomy)
    by_role: dict[str, list] = {}
    for resolution in resolutions:
        by_role.setdefault(resolution.role, []).append(resolution)
    grouped = _facts_by_concept(instance)

    for role in sorted(by_role):
        role_resolutions = by_role[role]
        role_arcs = [item.winner for item in role_resolutions]
        cycle = _detect_cycle(role_arcs)
        if cycle is not None:
            findings.append(OracleFinding("E204", role=role, concept=cycle[0]))
            continue
        children_by_parent: dict[str, dict[str, int]] = {}
        for resolution in role_resolutions:
            children_by_parent.setdefault(resolution.parent, {})[resolution.child] = (
                resolution.winner.weight
            )
        for parent in sorted(children_by_parent):
            for parent_fact in grouped.get(parent, []):
                low = Decimal(0)
                high = Decimal(0)
                contributors: list[str] = []
                has_conflict = False
                for child in sorted(children_by_parent[parent]):
                    weight = children_by_parent[parent][child]
                    child_facts = grouped.get(child, [])
                    same = [
                        child_fact
                        for child_fact in child_facts
                        if child_fact.context_ref == parent_fact.context_ref
                        and child_fact.unit_ref == parent_fact.unit_ref
                    ]
                    if not same:
                        other_context = sorted(
                            {child_fact.context_ref for child_fact in child_facts
                             if child_fact.context_ref != parent_fact.context_ref}
                        )
                        other_unit = sorted(
                            {child_fact.unit_ref for child_fact in child_facts
                             if child_fact.context_ref == parent_fact.context_ref
                             and child_fact.unit_ref != parent_fact.unit_ref}
                        )
                        if other_context:
                            has_conflict = True
                            findings.append(
                                OracleFinding(
                                    "E202",
                                    role=role,
                                    concept=parent,
                                    context=parent_fact.context_ref,
                                )
                            )
                        elif other_unit:
                            has_conflict = True
                            findings.append(
                                OracleFinding(
                                    "E203",
                                    role=role,
                                    concept=parent,
                                    context=parent_fact.context_ref,
                                    unit=parent_fact.unit_ref,
                                )
                            )
                        continue
                    child_fact = same[0]
                    interval = fact_interval(child_fact)
                    if weight >= 0:
                        low += interval.low * weight
                        high += interval.high * weight
                    else:
                        low += interval.high * weight
                        high += interval.low * weight
                    contributors.append(f"{child}*({weight})={interval.render()}")
                parent_interval = fact_interval(parent_fact)
                child_interval = Interval(low, high)
                gap = parent_interval.gap_to(child_interval)
                if gap > 0 and not has_conflict:
                    findings.append(
                        OracleFinding(
                            "W301",
                            role=role,
                            concept=parent,
                            context=parent_fact.context_ref,
                            unit=parent_fact.unit_ref,
                            parent_interval=parent_interval.render(),
                            children_interval=child_interval.render(),
                            minimum_difference=format(gap, "f"),
                            contributors=tuple(contributors),
                        )
                    )
    return findings


def hand_computed_fixtures() -> dict[str, tuple[Taxonomy, Instance, list[OracleFinding]]]:
    """Deterministic, hand-derived small datasets for cross-checking."""
    fixtures: dict[str, tuple[Taxonomy, Instance, list[OracleFinding]]] = {}
    context = Context("c", "instant", instant="2025-12-31")
    duration = Context("d", "duration", start="2025-01-01", end="2025-12-31")
    usd = Unit("usd", ("iso4217:USD",))
    eur = Unit("eur", ("iso4217:EUR",))

    def monetary(names: list[str]) -> Taxonomy:
        return Taxonomy(
            concepts=set(names),
            concept_types={name: "monetary" for name in names},
            arcs=[],
        )

    # 1. Exact balance sheet: parent equals children, no finding.
    tax = monetary(["Assets", "Cash", "AR"])
    tax.arcs = [Arc("bs", "Assets", "Cash", 1, 0, 1.0), Arc("bs", "Assets", "AR", 1, 0, 2.0)]
    instance = Instance(
        contexts={"c": context},
        units={"usd": usd},
        facts=[
            Fact("Assets", "c", "usd", "INF", "1000"),
            Fact("Cash", "c", "usd", "INF", "600"),
            Fact("AR", "c", "usd", "INF", "400"),
        ],
    )
    fixtures["exact_sum"] = (tax, instance, [])

    # 2. Boundary touching: decimals=0 intervals touch at 999.5 == no finding.
    tax = monetary(["Assets", "Cash", "AR"])
    tax.arcs = [Arc("bs", "Assets", "Cash", 1, 0, 1.0), Arc("bs", "Assets", "AR", 1, 0, 2.0)]
    instance = Instance(
        contexts={"c": context},
        units={"usd": usd},
        facts=[
            Fact("Assets", "c", "usd", "0", "1000"),  # [999.5,1000.5]
            Fact("Cash", "c", "usd", "0", "600"),  # [599.5,600.5]
            Fact("AR", "c", "usd", "0", "399"),  # [398.5,399.5] -> sum touches 999.5
        ],
    )
    fixtures["boundary_touch"] = (tax, instance, [])

    # 3. Disjoint by exactly 1: gap = 1.
    tax = monetary(["Assets", "Cash", "AR"])
    tax.arcs = [Arc("bs", "Assets", "Cash", 1, 0, 1.0), Arc("bs", "Assets", "AR", 1, 0, 2.0)]
    instance = Instance(
        contexts={"c": context},
        units={"usd": usd},
        facts=[
            Fact("Assets", "c", "usd", "INF", "1000"),
            Fact("Cash", "c", "usd", "INF", "600"),
            Fact("AR", "c", "usd", "INF", "399"),
        ],
    )
    fixtures["gap_one"] = (
        tax,
        instance,
        [
            OracleFinding(
                "W301",
                role="bs",
                concept="Assets",
                context="c",
                unit="usd",
                parent_interval="[1000, 1000]",
                children_interval="[999, 999]",
                minimum_difference="1",
            )
        ],
    )

    # 4. Negative weight: NetIncome = Income - Expense.
    tax = monetary(["NetIncome", "Income", "Expense"])
    tax.arcs = [
        Arc("is", "NetIncome", "Income", 1, 0, 1.0),
        Arc("is", "NetIncome", "Expense", -1, 0, 2.0),
    ]
    instance = Instance(
        contexts={"d": duration},
        units={"usd": usd},
        facts=[
            Fact("NetIncome", "d", "usd", "INF", "400"),
            Fact("Income", "d", "usd", "INF", "1000"),
            Fact("Expense", "d", "usd", "INF", "600"),
        ],
    )
    fixtures["negative_weight"] = (tax, instance, [])

    # 5. Cross-unit summation.
    tax = monetary(["Assets", "Cash"])
    tax.arcs = [Arc("bs", "Assets", "Cash", 1, 0, 1.0)]
    instance = Instance(
        contexts={"c": context},
        units={"usd": usd, "eur": eur},
        facts=[
            Fact("Assets", "c", "usd", "INF", "100"),
            Fact("Cash", "c", "eur", "INF", "100"),
        ],
    )
    fixtures["cross_unit"] = (
        tax,
        instance,
        [OracleFinding("E203", role="bs", concept="Assets", context="c", unit="usd")],
    )

    # 6. Multi-period: each context independently consistent, no finding.
    tax = monetary(["Assets", "Cash"])
    tax.arcs = [Arc("bs", "Assets", "Cash", 1, 0, 1.0)]
    instance = Instance(
        contexts={"c": context, "d": duration},
        units={"usd": usd},
        facts=[
            Fact("Assets", "c", "usd", "INF", "100"),
            Fact("Cash", "c", "usd", "INF", "100"),
            Fact("Assets", "d", "usd", "INF", "200"),
            Fact("Cash", "d", "usd", "INF", "200"),
        ],
    )
    fixtures["multi_period"] = (tax, instance, [])

    # 7. Cycle A -> B -> A.
    tax = monetary(["A", "B"])
    tax.arcs = [Arc("cy", "A", "B", 1, 0, 1.0), Arc("cy", "B", "A", 1, 0, 2.0)]
    instance = Instance(
        contexts={"c": context},
        units={"usd": usd},
        facts=[Fact("A", "c", "usd", "INF", "1"), Fact("B", "c", "usd", "INF", "1")],
    )
    fixtures["cycle"] = (tax, instance, [OracleFinding("E204", role="cy", concept="A")])

    # 8. Duplicate arcs: low priority ignored, effective weight 2 makes 300 != 100*2.
    tax = monetary(["P", "C"])
    tax.arcs = [
        Arc("r", "P", "C", 1, 0, 1.0),
        Arc("r", "P", "C", 2, 5, 1.0),
    ]
    instance = Instance(
        contexts={"c": context},
        units={"usd": usd},
        facts=[Fact("P", "c", "usd", "INF", "300"), Fact("C", "c", "usd", "INF", "100")],
    )
    fixtures["duplicate_arc_priority"] = (
        tax,
        instance,
        [
            OracleFinding(
                "W301",
                role="r",
                concept="P",
                context="c",
                unit="usd",
                parent_interval="[300, 300]",
                children_interval="[200, 200]",
                minimum_difference="100",
            )
        ],
    )
    return fixtures


def cross_check(taxonomy: Taxonomy, instance: Instance, production_diagnostics) -> list[str]:
    """Return human-readable disagreements between oracle and production."""
    oracle_findings = oracle_check(taxonomy, instance)
    production_calc = {
        (d.code, d.role, d.concept, d.context, d.unit)
        for d in production_diagnostics
        if d.code in {"W301", "E202", "E203", "E204"}
    }
    oracle_calc = {
        (f.code, f.role, f.concept, f.context, f.unit) for f in oracle_findings
    }
    disagreements: list[str] = []
    for missing in sorted(oracle_calc - production_calc):
        disagreements.append(f"production missing {missing}")
    for extra in sorted(production_calc - oracle_calc):
        disagreements.append(f"production extra {extra}")
    return disagreements
