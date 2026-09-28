"""Per-role directed calculation graph construction and cycle detection."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Iterable

from .errors import Diagnostic
from .model import Arc, Taxonomy


@dataclass
class RoleGraph:
    role: str
    children_of: dict[str, list[Arc]] = field(default_factory=dict)
    parents_of: dict[str, list[Arc]] = field(default_factory=dict)

    def concepts(self) -> set[str]:
        nodes: set[str] = set(self.children_of)
        for arcs in self.children_of.values():
            nodes.update(arc.child for arc in arcs)
        nodes.update(self.parents_of.keys())
        return nodes

    def outgoing(self, concept: str) -> list[Arc]:
        return sorted(self.children_of.get(concept, []), key=lambda arc: (arc.order, arc.child))


@dataclass(frozen=True)
class ArcResolution:
    role: str
    parent: str
    child: str
    winner: Arc
    ignored: tuple[Arc, ...]
    conflict: bool = False


def resolve_arcs(taxonomy: Taxonomy) -> tuple[list[ArcResolution], list[Diagnostic]]:
    """For each (role, parent, child) keep the highest-priority integer-weight arc."""
    groups: dict[tuple[str, str, str], list[Arc]] = {}
    for arc in taxonomy.arcs:
        groups.setdefault((arc.role, arc.parent, arc.child), []).append(arc)

    resolutions: list[ArcResolution] = []
    diagnostics: list[Diagnostic] = []
    for key in sorted(groups):
        role, parent, child = key
        arcs = groups[key]
        max_priority = max(arc.priority for arc in arcs)
        winners = sorted(
            (arc for arc in arcs if arc.priority == max_priority),
            key=lambda arc: (arc.order, arc.location.line, arc.location.column),
        )
        winner = winners[0]
        conflict = len({arc.weight for arc in winners}) > 1
        if conflict:
            weights = sorted({arc.weight for arc in winners})
            diagnostics.append(
                Diagnostic(
                    "E201",
                    f"arcs {parent} -> {child} at equal priority {max_priority} "
                    f"declare conflicting weights {weights}; using {winner.weight}",
                    winner.location,
                )
            )
        ignored = tuple(arc for arc in arcs if arc is not winner)
        resolutions.append(ArcResolution(role, parent, child, winner, ignored, conflict))
    return resolutions, diagnostics


def build_role_graphs(taxonomy: Taxonomy) -> tuple[dict[str, RoleGraph], list[Diagnostic]]:
    resolutions, diagnostics = resolve_arcs(taxonomy)
    graphs: dict[str, RoleGraph] = {}
    for resolution in resolutions:
        graph = graphs.setdefault(resolution.role, RoleGraph(resolution.role))
        arc = resolution.winner
        graph.children_of.setdefault(arc.parent, []).append(arc)
        graph.parents_of.setdefault(arc.child, []).append(arc)
        for ignored in resolution.ignored:
            if ignored.priority == arc.priority:
                continue
            diagnostics.append(
                Diagnostic(
                    "I401",
                    f"ignored duplicate arc {arc.parent} -> {arc.child} "
                    f"(priority {ignored.priority} < winning {arc.priority})",
                    ignored.location,
                )
            )
    return graphs, diagnostics


def _rotate(cycle: tuple[str, ...]) -> tuple[str, ...]:
    """Canonical rotation: lexicographically smallest, starting at min node."""
    if not cycle:
        return cycle
    start = min(range(len(cycle)), key=lambda idx: cycle[idx])
    rotations = []
    for idx, node in enumerate(cycle):
        if node == cycle[start]:
            rotations.append(cycle[idx:] + cycle[:idx])
    return min(rotations)


def _simple_cycles_from(node: str, graph: RoleGraph) -> list[tuple[str, ...]]:
    """Enumerate simple directed cycles containing node (Johnson-style DFS)."""
    cycles: list[tuple[str, ...]] = []

    def dfs(current: str, path: list[str], on_path: set[str]) -> None:
        for arc in graph.outgoing(current):
            nxt = arc.child
            if nxt == node and len(path) >= 1:
                cycles.append(tuple(path))
            elif nxt not in on_path and nxt >= node:
                on_path.add(nxt)
                path.append(nxt)
                dfs(nxt, path, on_path)
                path.pop()
                on_path.remove(nxt)

    dfs(node, [node], {node})
    return cycles


def canonical_min_cycles(graph: RoleGraph) -> list[tuple[str, ...]]:
    """Canonical minimum simple cycle per weakly-independent cycle class.

    Every elementary directed cycle is enumerated; for each strongly connected
    component only the lexicographically smallest canonical rotation survives.
    """
    nodes = graph.concepts()
    canonical: dict[frozenset[str], tuple[str, ...]] = {}
    components = _strongly_connected_components(graph, nodes)
    for component in components:
        if not _has_internal_cycle(graph, component):
            continue
        best: tuple[str, ...] | None = None
        for node in sorted(component):
            for cycle in _simple_cycles_from(node, graph):
                if not set(cycle) <= component:
                    continue
                rotated = _rotate(cycle)
                if best is None or rotated < best:
                    best = rotated
        if best is not None:
            canonical[frozenset(component)] = best
    return [canonical[key] for key in sorted(canonical, key=lambda item: canonical[item])]


def _has_internal_cycle(graph: RoleGraph, component: frozenset[str]) -> bool:
    for node in component:
        for arc in graph.outgoing(node):
            if arc.child in component:
                return True
    return False


def _strongly_connected_components(graph: RoleGraph, nodes: Iterable[str]) -> list[frozenset[str]]:
    index_counter = [0]
    stack: list[str] = []
    lowlink: dict[str, int] = {}
    index: dict[str, int] = {}
    on_stack: set[str] = set()
    result: list[frozenset[str]] = []

    def strongconnect(start: str) -> None:
        call_stack: list[tuple[str, int]] = [(start, 0)]
        index[start] = index_counter[0]
        lowlink[start] = index_counter[0]
        index_counter[0] += 1
        stack.append(start)
        on_stack.add(start)
        while call_stack:
            node, neighbor_idx = call_stack[-1]
            neighbors = [arc.child for arc in graph.outgoing(node)]
            if neighbor_idx < len(neighbors):
                nxt = neighbors[neighbor_idx]
                call_stack[-1] = (node, neighbor_idx + 1)
                if nxt not in index:
                    index[nxt] = index_counter[0]
                    lowlink[nxt] = index_counter[0]
                    index_counter[0] += 1
                    stack.append(nxt)
                    on_stack.add(nxt)
                    call_stack.append((nxt, 0))
                elif nxt in on_stack:
                    lowlink[node] = min(lowlink[node], index[nxt])
            else:
                if lowlink[node] == index[node]:
                    component = []
                    while True:
                        member = stack.pop()
                        on_stack.remove(member)
                        component.append(member)
                        if member == node:
                            break
                    result.append(frozenset(component))
                call_stack.pop()
                if call_stack:
                    parent = call_stack[-1][0]
                    lowlink[parent] = min(lowlink[parent], lowlink[node])

    for node in sorted(nodes):
        if node not in index:
            strongconnect(node)
    return result


def cycle_diagnostics(graph: RoleGraph) -> list[Diagnostic]:
    diagnostics: list[Diagnostic] = []
    for cycle in canonical_min_cycles(graph):
        edges = [f"{node} -> {cycle[(idx + 1) % len(cycle)]}" for idx, node in enumerate(cycle)]
        diagnostics.append(
            Diagnostic(
                "E204",
                f"cycle in role {graph.role!r}: {' ; '.join(edges)}",
            )
        )
    return diagnostics
