"""Core immutable data models for the ECL engine."""

from types import MappingProxyType
from dataclasses import dataclass, field
from decimal import Decimal
from enum import IntEnum
from typing import Mapping


class Stage(IntEnum):
    """IFRS9-style credit-risk staging."""

    STAGE1 = 1
    STAGE2 = 2
    STAGE3 = 3


@dataclass(frozen=True)
class TermPoint:
    """A single (period, value) point on a term structure."""

    period: int
    value: Decimal


@dataclass(frozen=True)
class Curve:
    """Term-structure curve keyed by 1-based period indices."""

    points: tuple[TermPoint, ...]

    def by_period(self) -> Mapping[int, Decimal]:
        """Return a read-only period -> value mapping."""
        return MappingProxyType({p.period: p.value for p in self.points})

    def value_at(self, period: int) -> Decimal:
        """Look up the value for a 1-based period (KeyError if missing)."""
        for point in self.points:
            if point.period == period:
                return point.value
        raise KeyError(period)

    @property
    def max_period(self) -> int:
        return self.points[-1].period


@dataclass(frozen=True)
class ScenarioWeight:
    """Named scenario with a Decimal weight."""

    name: str
    weight: Decimal


@dataclass(frozen=True)
class ScenarioInput:
    """Per-scenario term-structure inputs for one contract."""

    name: str
    cumulative_pd: Curve
    lgd: Curve
    ead: Curve
    discount: Curve


@dataclass(frozen=True)
class AssumptionSet:
    """Versioned, date-effective assumption bundle."""

    version: str
    effective_date: str
    scenarios: tuple[ScenarioWeight, ...]
    #: contract_id -> (scenario inputs, one per scenario name)
    inputs: Mapping[str, tuple[ScenarioInput, ...]] = field(default_factory=dict)


@dataclass(frozen=True)
class Contract:
    """An anonymous synthetic contract."""

    contract_id: str
    stage: Stage
    remaining_periods: int


@dataclass(frozen=True)
class PeriodLine:
    """One fully expanded per-period ECL detail line."""

    contract_id: str
    scenario: str
    period: int
    marginal_pd: Decimal = Decimal(0)
    lgd: Decimal = Decimal(0)
    ead: Decimal = Decimal(0)
    discount_factor: Decimal = Decimal(0)
    weight: Decimal = Decimal(0)
    #: raw multiplier m*LGD*EAD*DF at detail scale (rounded once)
    period_ecl: Decimal = Decimal(0)
    #: weight * period_ecl, at detail scale
    weighted_ecl: Decimal = Decimal(0)
    #: rounding delta recorded when the line is scaled
    rounding_delta: Decimal = Decimal(0)


@dataclass(frozen=True)
class ContractECL:
    """Result for one contract under one bound assumption version."""

    contract_id: str
    total_ecl: Decimal
    lines: tuple[PeriodLine, ...] = ()
    #: residual scenario weight absorbed by the designated tail scenario
    weight_residual: Decimal = Decimal(0)


@dataclass(frozen=True)
class ValuationResult:
    """An immutable published valuation result."""

    valuation_id: str
    version: str
    run_date: str
    contracts: tuple[ContractECL, ...]

    def total_ecl(self) -> Decimal:
        return sum((c.total_ecl for c in self.contracts), Decimal(0))
