"""Core input / output type definitions."""

from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal
from enum import Enum


class Timing(str, Enum):
    """Cash-flow timing within each policy year."""

    BEGINNING = "beginning"
    END = "end"


@dataclass(frozen=True)
class Expenses:
    """Per-policy expenses (absolute currency amounts).

    initial: paid once at policy issue (t=0).
    renewal: paid at the start of every policy year after the first.
    claim: paid together with the death benefit when a claim occurs.
    """

    initial: Decimal = Decimal("0")
    renewal: Decimal = Decimal("0")
    claim: Decimal = Decimal("0")


@dataclass(frozen=True)
class Inputs:
    """Validated-free raw engine inputs."""

    issue_age: int
    term: int
    sum_assured: Decimal
    timing: Timing
    interest_rate: Decimal
    mortality: dict[int, Decimal]
    expenses: Expenses = field(default_factory=Expenses)


@dataclass(frozen=True)
class YearPath:
    """Probability path information for one policy year."""

    year: int
    age: int
    q_mortality: Decimal
    p_conditional: Decimal
    p_enter: Decimal
    p_death: Decimal
    p_survive: Decimal


@dataclass(frozen=True)
class CashFlowRow:
    """Per-year expected cash flows discounted to issue (t=0)."""

    year: int
    age: int
    premium_expected: Decimal
    expense_expected: Decimal
    benefit_expected: Decimal
    premium_pv: Decimal
    expense_pv: Decimal
    benefit_pv: Decimal


@dataclass(frozen=True)
class ReserveRow:
    """Reserves at the boundary of one policy year."""

    year: int
    age: int
    p_enter: Decimal
    reserve_state_sum: Decimal
    reserve_backward: Decimal


@dataclass(frozen=True)
class SensitivityRow:
    """Pricing output under one shocked interest rate."""

    interest_rate: Decimal
    net_premium: Decimal
    gross_premium: Decimal
    pv_benefit: Decimal
    pv_expense: Decimal


@dataclass(frozen=True)
class Pricing:
    """Intermediate pricing quantities shared by all calculations."""

    net_premium: Decimal
    gross_premium: Decimal
    pv_benefit: Decimal
    pv_expense: Decimal
    pv_net_premium: Decimal
    pv_gross_premium: Decimal
    annuity_factor: Decimal


@dataclass(frozen=True)
class Diagnosis:
    """A reconciliation failure diagnostic (never silently resolved)."""

    code: str
    detail: str
    expected: Decimal
    actual: Decimal
    tolerance: Decimal


@dataclass(frozen=True)
class EngineResult:
    """Full engine output."""

    inputs: Inputs
    pricing: Pricing
    paths: list[YearPath]
    cashflows: list[CashFlowRow]
    reserves: list[ReserveRow]
    sensitivity: list[SensitivityRow]
    diagnostics: list[Diagnosis]
