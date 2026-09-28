"""Immutable market quotes and bond cash flows."""

import hashlib
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal

from .dates import (
    BUILTIN_CALENDARS,
    BusinessDayConvention,
    DayCountConvention,
    StubType,
    Tenor,
)
from .errors import QuoteValidationError

Date = date


@dataclass(frozen=True, slots=True)
class DepositQuote:
    id: str
    start: Date
    maturity: Date
    rate: Decimal
    day_count: DayCountConvention
    notional: Decimal = Decimal(1)

    @property
    def maturity_date(self) -> Date:
        return self.maturity


@dataclass(frozen=True, slots=True)
class FRAQuote:
    id: str
    start: Date
    maturity: Date
    rate: Decimal
    day_count: DayCountConvention
    notional: Decimal = Decimal(1)

    @property
    def maturity_date(self) -> Date:
        return self.maturity


@dataclass(frozen=True, slots=True)
class SwapQuote:
    id: str
    effective: Date
    maturity: Date
    payment_tenor: Tenor
    fixed_rate: Decimal
    day_count: DayCountConvention
    calendar_name: str
    business_day_convention: BusinessDayConvention
    stub: StubType
    notional: Decimal = Decimal(1)

    @property
    def maturity_date(self) -> Date:
        return self.maturity


@dataclass(frozen=True, slots=True)
class BondCashflows:
    id: str
    flows: tuple[tuple[Date, Decimal], ...]

    def __post_init__(self) -> None:
        if not self.id:
            raise QuoteValidationError("bond id must be non-empty")
        if not self.flows:
            raise QuoteValidationError(f"bond {self.id!r} has no cash flows")
        previous: Date | None = None
        for flow_date, amount in self.flows:
            if not isinstance(flow_date, Date):
                raise QuoteValidationError(
                    f"bond {self.id!r} cash-flow date must be a date")
            if not isinstance(amount, Decimal) or not amount.is_finite():
                raise QuoteValidationError(
                    f"bond {self.id!r} cash flow on {flow_date} is not finite")
            if previous is not None and flow_date <= previous:
                raise QuoteValidationError(
                    f"bond {self.id!r} cash flows must be strictly increasing")
            previous = flow_date


@dataclass(frozen=True, slots=True)
class QuoteBook:
    """An immutable versioned bundle of bootstrapping instruments."""

    value_date: Date
    deposits: tuple[DepositQuote, ...]
    fras: tuple[FRAQuote, ...]
    swaps: tuple[SwapQuote, ...]
    version_id: str = field(default="")

    def all_quotes(self):
        return tuple(self.deposits) + tuple(self.fras) + tuple(self.swaps)

    def with_version(self) -> "QuoteBook":
        if self.version_id:
            return self
        digest = hashlib.sha256(self._fingerprint().encode("utf-8")).hexdigest()
        return QuoteBook(
            value_date=self.value_date,
            deposits=self.deposits,
            fras=self.fras,
            swaps=self.swaps,
            version_id=digest[:16],
        )

    def validate(self) -> None:
        if not isinstance(self.value_date, Date):
            raise QuoteValidationError("value_date must be a date")
        seen_ids: set[str] = set()
        for quote in self.all_quotes():
            if not quote.id:
                raise QuoteValidationError("instrument id must be non-empty")
            if quote.id in seen_ids:
                raise QuoteValidationError(f"duplicate instrument id {quote.id!r}")
            seen_ids.add(quote.id)
            notional = quote.notional
            if not isinstance(notional, Decimal) \
                    or not notional.is_finite() or notional <= 0:
                raise QuoteValidationError(
                    f"{quote.id}: notional must be a positive finite Decimal")
            rate = getattr(quote, "rate", getattr(quote, "fixed_rate", None))
            if not isinstance(rate, Decimal) or not rate.is_finite():
                raise QuoteValidationError(
                    f"{quote.id}: rate must be a finite Decimal")
            start = (quote.start if isinstance(quote, (DepositQuote, FRAQuote))
                     else quote.effective)
            end = quote.maturity
            if start >= end:
                raise QuoteValidationError(
                    f"{quote.id}: end date must be strictly after start date")
        for swap in self.swaps:
            if swap.calendar_name not in BUILTIN_CALENDARS:
                raise QuoteValidationError(
                    f"{swap.id}: unknown calendar {swap.calendar_name!r}")
            if swap.payment_tenor.months() <= 0:
                raise QuoteValidationError(
                    f"{swap.id}: payment tenor must be monthly or longer")

    def _fingerprint(self) -> str:
        def emit() -> object:
            deposits = [(q.id, q.start.isoformat(), q.maturity.isoformat(),
                         str(q.rate), str(q.day_count), str(q.notional))
                        for q in sorted(self.deposits, key=lambda q: q.id)]
            fras = [(q.id, q.start.isoformat(), q.maturity.isoformat(),
                     str(q.rate), str(q.day_count), str(q.notional))
                    for q in sorted(self.fras, key=lambda q: q.id)]
            swaps = [(q.id, q.effective.isoformat(), q.maturity.isoformat(),
                      f"{q.payment_tenor.amount}{q.payment_tenor.unit}",
                      str(q.fixed_rate), str(q.day_count), q.calendar_name,
                      str(q.business_day_convention), str(q.stub),
                      str(q.notional))
                     for q in sorted(self.swaps, key=lambda q: q.id)]
            return [self.value_date.isoformat(), deposits, fras, swaps]
        return repr(emit())


@dataclass(frozen=True, slots=True)
class BondPortfolio:
    bonds: tuple[BondCashflows, ...]

    def __post_init__(self) -> None:
        seen: set[str] = set()
        for bond in self.bonds:
            if not isinstance(bond, BondCashflows):
                raise QuoteValidationError("portfolio entries must be bonds")
            if bond.id in seen:
                raise QuoteValidationError(f"duplicate bond id {bond.id!r}")
            seen.add(bond.id)
