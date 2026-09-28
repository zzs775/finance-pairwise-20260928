"""Model present values and the independent cash-flow PV oracle."""

from datetime import date
from decimal import Decimal
from typing import Callable

from .calendar import (
    HolidayCalendar,
    SchedulePeriod,
    generate_schedule,
    year_fraction,
)
from .errors import NonFiniteValueError
from .instruments import Bond, Deposit, FRA, Swap

Discounter = Callable[[date], Decimal]


def _finite(value: Decimal, label: str) -> Decimal:
    if not isinstance(value, Decimal) or not value.is_finite():
        raise NonFiniteValueError(f"{label} evaluated to a non-finite value")
    return value


def deposit_residual(deposit: Deposit, discount: Discounter) -> Decimal:
    tau = year_fraction(deposit.start, deposit.maturity, deposit.day_count)
    df_end = discount(deposit.maturity)
    df_start = discount(deposit.start)
    # Long the maturing deposit: pay 1 at start, receive 1 + r*tau at maturity.
    return _finite(
        deposit.notional * (df_start - (Decimal(1) + deposit.rate * tau) * df_end),
        "deposit residual",
    )


def deposit_model_pv(deposit: Deposit, discount: Discounter) -> Decimal:
    return -deposit_residual(deposit, discount)


def deposit_market_target(deposit: Deposit) -> Decimal:
    return Decimal(0)


def fra_residual(fra: FRA, discount: Discounter) -> Decimal:
    tau = year_fraction(fra.start, fra.maturity, fra.day_count)
    df_end = discount(fra.maturity)
    df_start = discount(fra.start)
    # Receiver FRA settled at maturity: receive fixed, pay floating.
    return _finite(
        fra.notional
        * ((Decimal(1) + fra.rate * tau) * df_end - df_start),
        "FRA residual",
    )


def fra_model_pv(fra: FRA, discount: Discounter) -> Decimal:
    return fra_residual(fra, discount)


def fra_market_target(fra: FRA) -> Decimal:
    return Decimal(0)


def swap_schedule(swap: Swap, calendar: HolidayCalendar) -> tuple[SchedulePeriod, ...]:
    return generate_schedule(
        swap.effective,
        swap.maturity,
        swap.period_months,
        calendar,
        swap.business_day_convention,
        swap.stub,
    )


def swap_residual(
    swap: Swap,
    discount: Discounter,
    schedule: tuple[SchedulePeriod, ...] | None = None,
) -> Decimal:
    if schedule is None:
        raise ValueError("swap_residual requires a precomputed schedule")
    # Par fixed-for-floating swap, floating-leg PV = N * (DF0 - DFn).
    fixed_pv = Decimal(0)
    for period in schedule:
        tau = year_fraction(
            period.adjusted_start,
            period.adjusted_end,
            swap.day_count,
        )
        fixed_pv += swap.fixed_rate * tau * discount(period.adjusted_end)
    df_first = discount(schedule[0].adjusted_start)
    df_last = discount(schedule[-1].adjusted_end)
    float_pv = df_first - df_last
    return _finite(
        swap.notional * (fixed_pv + df_last - df_first),
        "swap residual",
    )


def swap_model_pv(
    swap: Swap,
    discount: Discounter,
    schedule: tuple[SchedulePeriod, ...],
) -> Decimal:
    return swap_residual(swap, discount, schedule)


def swap_market_target(swap: Swap) -> Decimal:
    return Decimal(0)


def bond_pv(bond: Bond, discount: Discounter) -> Decimal:
    return cashflows_pv(
        tuple((flow.payment_date, flow.amount) for flow in bond.cash_flows),
        discount,
    )


def cashflows_pv(
    cash_flows: tuple[tuple[date, Decimal], ...], discount: Discounter
) -> Decimal:
    """Reference PV: sum of amount * DF, used by hand-computable oracles."""
    total = Decimal(0)
    for payment_date, amount in cash_flows:
        if not amount.is_finite():
            raise NonFiniteValueError("cash-flow amount must be finite")
        term = amount * discount(payment_date)
        if not term.is_finite():
            raise NonFiniteValueError("cash-flow PV term must be finite")
        total += term
    return _finite(total, "cash-flow PV")
