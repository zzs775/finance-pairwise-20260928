"""Versioned linkbases, incremental fact revisions, atomic commits."""

from __future__ import annotations

from dataclasses import dataclass

from .errors import Diagnostic
from .graph import build_role_graphs
from .model import Fact
from .model import Instance, Taxonomy
from .report import render_report
from .validate import validate


@dataclass(frozen=True)
class RevisionResult:
    accepted: bool
    version: str
    report: bytes
    diagnostics: tuple[Diagnostic, ...]
    affected_concepts: tuple[str, ...] = ()
    reason: str = ""
    byte_identical_rebuild: bool = True


class VersionedStore:
    def __init__(self, taxonomy: Taxonomy, instance: Instance, version: str = "1") -> None:
        self.taxonomy = taxonomy
        self.instance = instance
        self.version = str(version)
        self.history: list[tuple[str, bytes]] = []
        self._snapshot()

    def _snapshot(self) -> None:
        diagnostics = validate(self.taxonomy, self.instance)
        report = render_report(diagnostics, version=self.version)
        self.last_diagnostics = diagnostics
        self.last_report = report
        self.history.append((self.version, report))

    def load_linkbase_version(self, taxonomy: Taxonomy, version: str) -> RevisionResult:
        """Atomically replace the taxonomy; rejected if the new graph has errors."""
        diagnostics = validate(taxonomy, self.instance)
        if any(diagnostic.severity == "error" for diagnostic in diagnostics):
            return RevisionResult(
                accepted=False,
                version=self.version,
                report=self.last_report,
                diagnostics=tuple(diagnostics),
                reason="new linkbase version introduces errors; current version retained",
            )
        self.taxonomy = taxonomy
        self.version = str(version)
        self._commit(diagnostics)
        return RevisionResult(True, self.version, self.last_report, tuple(diagnostics))

    def apply_revision(self, revision: Instance, *, strict: bool = True) -> RevisionResult:
        """Apply incremental fact upserts; affected nodes recompute via reverse edges.

        The resulting report must be byte-identical to a full graph rebuild.
        A rejected revision never replaces the current version.
        """
        affected = self._affected_concepts(revision)
        merged = self._merge(revision)
        diagnostics = validate(self.taxonomy, merged)
        if strict and any(diagnostic.severity == "error" for diagnostic in diagnostics):
            reject_diag = Diagnostic(
                "I402",
                "incremental revision rejected: errors present; current version retained",
            )
            return RevisionResult(
                accepted=False,
                version=self.version,
                report=self.last_report,
                diagnostics=tuple([reject_diag, *diagnostics]),
                affected_concepts=tuple(sorted(affected)),
                reason="revision introduces errors",
            )

        full_report = render_report(diagnostics, version=self._next_version())
        self.instance = merged
        self.version = self._next_version()
        self._commit(diagnostics)
        identical = full_report == self.rebuild_report()
        return RevisionResult(
            accepted=True,
            version=self.version,
            report=self.last_report,
            diagnostics=tuple(diagnostics),
            affected_concepts=tuple(sorted(affected)),
            byte_identical_rebuild=identical,
        )

    def _next_version(self) -> str:
        try:
            return str(int(self.version) + 1)
        except ValueError:
            return f"{self.version}.1"

    def _commit(self, diagnostics: list[Diagnostic]) -> None:
        self.last_diagnostics = diagnostics
        self.last_report = render_report(diagnostics, version=self.version)
        self.history.append((self.version, self.last_report))

    def _merge(self, revision: Instance) -> Instance:
        contexts = dict(self.instance.contexts)
        units = dict(self.instance.units)
        facts_by_key: dict[tuple[str, str, str], Fact] = {}
        order: list[tuple[str, str, str]] = []
        for fact in self.instance.facts:
            if fact.key() not in facts_by_key:
                order.append(fact.key())
            facts_by_key[fact.key()] = fact
        for fact in revision.facts:
            if fact.key() not in facts_by_key:
                order.append(fact.key())
            facts_by_key[fact.key()] = fact
        merged = Instance(contexts=contexts, units=units, facts=[facts_by_key[key] for key in order])
        return merged

    def _affected_concepts(self, revision: Instance) -> set[str]:
        graphs, _ = build_role_graphs(self.taxonomy)
        reverse: dict[str, set[str]] = {}
        for graph in graphs.values():
            for parent, arcs in graph.children_of.items():
                for arc in arcs:
                    reverse.setdefault(arc.child, set()).add(parent)
        seeds = {fact.concept for fact in revision.facts}
        affected = set(seeds)
        stack = list(seeds)
        while stack:
            concept = stack.pop()
            for parent in reverse.get(concept, ()):  # type: ignore[arg-type]
                if parent not in affected:
                    affected.add(parent)
                    stack.append(parent)
        return affected

    def rebuild_report(self) -> bytes:
        """Recompute everything from the current state (full graph rebuild)."""
        return render_report(validate(self.taxonomy, self.instance), version=self.version)
