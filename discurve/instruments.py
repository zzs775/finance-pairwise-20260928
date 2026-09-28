"""Immutable quote and cash-flow types."""

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from typing import Literal

from .errors import IllegalDateError, InvalidQuoteError

from .calendar import (
    BusinessDayConvention,
    DayCountConvention,
    StubType,
)

InstrumentKind = Literal["deposit", "fra", "swap"]


@dataclass(frozen=True)
class Deposit:
    name: str
    start: date
    maturity: date
    rate: Decimal
    day_count: DayCountConvention = "ACT/360"
    notional: Decimal = field(default_factory=lambda: Decimal(1))

    @property
    def node_date(self) -> date:
        return self.maturity


@dataclass(frozen=True)
class FRA:
    name: str
    start: date
    maturity: date
    rate: Decimal
    day_count: DayCountConvention = "ACT/360"
    notional: Decimal = field(default_factory=lambda: Decimal(1))

    @property
    def node_date(self) -> date:
        return self.maturity


@dataclass(frozen=True)
class Swap:
    name: str
    effective: date
    maturity: date
    fixed_rate: Decimal
    period_months: int = 6
    day_count: DayCountConvention = "30/360"
    business_day_convention: BusinessDayConvention = "modified_following"
    stub: StubType = "short_front"
    notional: Decimal = field(default_factory=lambda: Decimal(1))

    @property
    def node_date(self) -> date:
        return self.maturity


@dataclass(frozen=True)
class BondCashFlow:
    payment_date: date
    amount: Decimal


@dataclass(frozen=True)
class Bond:
    name: str
    cash_flows: tuple[BondCashFlow, ...]


@dataclass(frozen=True)
class QuoteSnapshot:
    """One immutable version of every market input."""

    version_id: str
    valuation_date: date
    deposits: tuple[Deposit, ...] = ()
    fras: tuple[FRA, ...] = ()
    swaps: tuple[Swap, ...] = ()
    bonds: tuple[Bond, ...] = ()


def _require_date(value, label: str) -> date:
    if not isinstance(value, date):
        raise IllegalDateError(f"{label} must be a date")
    return value


def _require_finite(value: Decimal, label: str) -> Decimal:
    if not isinstance(value, Decimal) or not value.is_finite():
        raise InvalidQuoteError(f"{label} must be a finite Decimal")
    return value


def validate_deposit(deposit: Deposit) -> None:
    _require_date(deposit.start, "deposit start")
    _require_date(deposit.maturity, "deposit maturity")
    if deposit.maturity <= deposit.start:
        raise IllegalDateError(
            f"deposit {deposit.name!r}: maturity {deposit.maturity} must be "
            f"strictly after start {deposit.start}"
        )
    _require_finite(deposit.rate, "deposit rate")
    _require_finite(deposit.notional, "deposit notional")
    if deposit.notional <= 0:
        raise InvalidQuoteError(
            f"deposit {deposit.name!r}: notional must be positive"
        )


def validate_fra(fra: FRA) -> None:
    _require_date(fra.start, "FRA start")
    _require_date(fra.maturity, "FRA maturity")
    if fra.maturity <= fra.start:
        raise IllegalDateError(
            f"FRA {fra.name!r}: maturity {fra.maturity} must be strictly "
            f"after start {fra.start}"
        )
    _require_finite(fra.rate, "FRA rate")
    _require_finite(fra.notional, "FRA notional")
    if fra.notional <= 0:
        raise InvalidQuoteError(f"FRA {fra.name!r}: notional must be positive")


def validate_swap(swap: Swap) -> None:
    _require_date(swap.effective, "swap effective date")
    _require_date(swap.maturity, "swap maturity")
    if swap.maturity <= swap.effective:
        raise IllegalDateError(
            f"swap {swap.name!r}: maturity {swap.maturity} must be strictly "
            f"after effective {swap.effective}"
        )
    if swap.period_months < 1:
        raise InvalidQuoteError(
            f"swap {swap.name!r}: period_months must be >= 1"
        )
    _require_finite(swap.fixed_rate, "swap fixed rate")
    _require_finite(swap.notional, "swap notional")
    if swap.notional <= 0:
        raise InvalidQuoteError(f"swap {swap.name!r}: notional must be positive")


def validate_bond(bond: Bond) -> None:
    if not bond.cash_flows:
        raise InvalidQuoteError(f"bond {bond.name!r}: needs at least one flow")
    previous: date | None = None
    for cash_flow in bond.cash_flows:
        day = _require_date(cash_flow.payment_date, "bond payment date")
        _require_finite(cash_flow.amount, "bond flow amount")
        if cash_flow.amount <= 0:
            raise InvalidQuoteError(
                f"bond {bond.name!r}: flow amounts must be positive"
            )
        if previous is not None and day <= previous:
            raise IllegalDateError(
                f"bond {bond.name!r}: payment dates must be strictly increasing"
            )
        previous = day


def validate_snapshot(snapshot: QuoteSnapshot) -> None:
    _require_date(snapshot.valuation_date, "valuation date")
    for deposit in snapshot.deposits:
        validate_deposit(deposit)
    for fra in snapshot.fras:
        validate_fra(fra)
    for swap in snapshot.swaps:
        validate_swap(swap)
    for bond in snapshot.bonds:
        validate_bond(bond)


def ordered_instruments(
    snapshot: QuoteSnapshot,
) -> tuple[tuple[InstrumentKind, object], ...]:
    """All curve-building instruments with a stable kind tag."""
    items: list[tuple[InstrumentKind, object]] = []
    items.extend(("deposit", d) for d in snapshot.deposits)
    items.extend(("fra", f) for f in snapshot.fras)
    items.extend(("swap", s) for s in snapshot.swaps)
    return tuple(items)
