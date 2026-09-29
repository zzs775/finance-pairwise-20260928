"""Core domain types (amounts are always :class:`decimal.Decimal`)."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from enum import Enum


class RecordType(str, Enum):
    VALUATION = "valuation"
    CASH_FLOW = "cash_flow"
    ACCOUNT = "account"
    MEMBERSHIP = "membership"


class CashFlowTiming(str, Enum):
    """Frozen intra-day ordering anchor for external cash flows."""

    OPEN = "open"
    CLOSE = "close"


class SeqOrder(int, Enum):
    """Frozen order of same-day events.

    1. opening valuation, 2. opening cash flow, 3. period P&L,
    4. closing cash flow, 5. closing valuation.
    """

    OPEN_VALUATION = 1
    OPEN_CASH_FLOW = 2
    PERIOD_PNL = 3
    CLOSE_CASH_FLOW = 4
    CLOSE_VALUATION = 5


@dataclass(frozen=True)
class Valuation:
    account_id: str
    day: date
    opening: Decimal
    closing: Decimal
    pnl: Decimal | None = None


@dataclass(frozen=True)
class CashFlow:
    account_id: str
    day: date
    amount: Decimal
    timing: CashFlowTiming


@dataclass(frozen=True)
class Account:
    account_id: str


@dataclass(frozen=True)
class Membership:
    composite_id: str
    account_id: str
    start_day: date
    end_day: date


@dataclass(frozen=True)
class SubPeriod:
    account_id: str
    start_day: date
    end_day: date
    begin_capital: Decimal
    external_flow: Decimal
    pnl: Decimal
    end_capital: Decimal
    rate: Decimal | None
    status: str


@dataclass(frozen=True)
class AccountResult:
    account_id: str
    sub_periods: list[SubPeriod] = field(default_factory=list)
    twr: Decimal | None = None
    status: str = "ok"


@dataclass(frozen=True)
class CompositeSubPeriod:
    composite_id: str
    start_day: date
    end_day: date
    rate: Decimal
    status: str = "ok"
    weights: dict[str, Decimal] = field(default_factory=dict)


@dataclass(frozen=True)
class CompositeResult:
    composite_id: str
    sub_periods: list[CompositeSubPeriod] = field(default_factory=list)
    twr: Decimal | None = None
    status: str = "ok"


@dataclass
class EngineInput:
    """Raw, possibly unordered input bundle."""

    valuations: list[Valuation] = field(default_factory=list)
    cash_flows: list[CashFlow] = field(default_factory=list)
    accounts: list[Account] = field(default_factory=list)
    memberships: list[Membership] = field(default_factory=list)
