"""Core in-memory model for the simplified XBRL subset."""

from __future__ import annotations

from dataclasses import dataclass, field

from .errors import Location

NUMERIC_TYPES = frozenset({"monetary", "decimal", "shares", "percent", "number"})


@dataclass(frozen=True)
class Fact:
    concept: str = ""
    context_ref: str = ""
    unit_ref: str = ""
    decimals: str = ""
    value: str = ""
    location: Location = Location()

    def key(self) -> tuple[str, str, str]:
        return (self.concept, self.context_ref, self.unit_ref)


@dataclass(frozen=True)
class Context:
    context_id: str = ""
    period_kind: str = ""  # "duration" | "instant"
    start: str = ""
    end: str = ""
    instant: str = ""
    location: Location = Location()


@dataclass(frozen=True)
class Unit:
    unit_id: str = ""
    measures: tuple[str, ...] = ()
    location: Location = Location()


@dataclass(frozen=True)
class Arc:
    role: str = ""
    parent: str = ""
    child: str = ""
    weight: int = 1
    priority: int = 0
    order: float = 1.0
    location: Location = Location()


@dataclass
class Taxonomy:
    concepts: set[str] = field(default_factory=set)
    concept_types: dict[str, str] = field(default_factory=dict)
    arcs: list[Arc] = field(default_factory=list)

    def is_numeric(self, concept: str) -> bool:
        return self.concept_types.get(concept, "") in NUMERIC_TYPES


@dataclass
class Instance:
    contexts: dict[str, Context] = field(default_factory=dict)
    units: dict[str, Unit] = field(default_factory=dict)
    facts: list[Fact] = field(default_factory=list)
