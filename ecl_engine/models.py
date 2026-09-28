"""Core data types: contracts, curves, scenarios, assumption sets."""

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from typing import Mapping, Sequence

from .fixed import q


def _as_curve(values: Sequence[Decimal]) -> tuple[Decimal, ...]:
    return tuple(q(v) for v in values)


@dataclass(frozen=True)
class Curve:
    """Period-indexed term structure (period 1..n values)."""

    values: tuple[Decimal, ...]

    def __post_init__(self) -> None:
        object.__setattr__(self, "values", _as_curve(self.values))

    def __len__(self) -> int:
        return len(self.values)

    def at(self, period: int) -> Decimal:
        """1-based period accessor."""
        return self.values[period - 1]


@dataclass(frozen=True)
class Contract:
    """An anonymized synthetic contract."""

    contract_id: str
    stage: int
    remaining_periods: int
    cumulative_pd: Curve
    lgd: Curve
    ead: Curve
    discount: Curve


@dataclass(frozen=True)
class Scenario:
    """A named scenario weight."""

    name: str
    weight: Decimal

    def __post_init__(self) -> None:
        object.__setattr__(self, "weight", q(self.weight))


@dataclass(frozen=True)
class AssumptionSet:
    """Versioned collection of assumptions with an effective date."""

    version: str
    effective_date: date
    scenarios: Sequence[Scenario] = field(default_factory=tuple)
    curves: Mapping[str, Mapping[str, Curve]] = field(default_factory=dict)
