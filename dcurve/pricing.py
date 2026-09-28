"""Model present-value functions and the independent cash-flow oracle.

Scale: every PV is expressed in currency units per *unit* of the quoted
notional unless the quote carries an explicit notional.  Market target is
zero for every par quote (deposit issued at par, FRA par, par swap).
"""

from datetime import date
from decimal import Decimal

from .constants import ONE, ZERO
from .curve import DiscountCurve
from .dates import (
    BUILTIN_CALENDARS,
    build_schedule,
    day_count_fraction,
)
from .errors import IllegalDateError, MissingTenorError
from .instruments import BondCashflows, DepositQuote, FRAQuote, SwapQuote

Date = date


def _require_covered(curve: DiscountCurve, day: Date, instrument_id: str) -> None:
    if day < curve.value_date:
        raise IllegalDateError(
            f"{instrument_id}: cash flow {day.isoformat()} precedes value date "
            f"{curve.value_date.isoformat()}")
    if day > curve.nodes[-1].date:
        raise MissingTenorError(
            f"{instrument_id}: cash flow {day.isoformat()} is beyond the last "
            f"calibrated node {curve.nodes[-1].date.isoformat()}")


def deposit_year_fraction(quote: DepositQuote | FRAQuote) -> Decimal:
    return day_count_fraction(quote.start, quote.maturity, quote.day_count)


def deposit_proceeds(quote: DepositQuote | FRAQuote) -> Decimal:
    """Terminal unit-notional repayment 1 + r * tau."""
    return ONE + quote.rate * deposit_year_fraction(quote)


def deposit_pv(curve: DiscountCurve, quote: DepositQuote) -> Decimal:
    """PV of a par deposit: -N * df(start) + N * (1 + r tau) * df(maturity)."""
    _require_covered(curve, quote.maturity, quote.id)
    df_start = curve.discount_factor(quote.start)
    df_end = curve.discount_factor(quote.maturity)
    notional = quote.notional
    return notional * (deposit_proceeds(quote) * df_end - df_start)


def fra_pv(curve: DiscountCurve, quote: FRAQuote) -> Decimal:
    """PV of a par FRA treated as a forward-start deposit."""
    _require_covered(curve, quote.maturity, quote.id)
    df_start = curve.discount_factor(quote.start)
    df_end = curve.discount_factor(quote.maturity)
    return quote.notional * (deposit_proceeds(quote) * df_end - df_start)


def swap_fixed_schedule(quote: SwapQuote):
    calendar = BUILTIN_CALENDARS[quote.calendar_name]
    return build_schedule(
        effective=quote.effective,
        maturity=quote.maturity,
        payment_tenor=quote.payment_tenor,
        day_count=quote.day_count,
        calendar=calendar,
        convention=quote.business_day_convention,
        stub=quote.stub,
    )


def swap_annuity(curve: DiscountCurve, quote: SwapQuote):
    """Fixed-leg annuity A and float-leg endpoint discount factors."""
    schedule = swap_fixed_schedule(quote)
    annuity = ZERO
    for period in schedule:
        _require_covered(curve, period.payment, quote.id)
        annuity += period.year_fraction * curve.discount_factor(period.payment)
    df_effective = curve.discount_factor(schedule[0].start)
    df_maturity = curve.discount_factor(schedule[-1].payment)
    return schedule, annuity, df_effective, df_maturity


def swap_pv(curve: DiscountCurve, quote: SwapQuote) -> Decimal:
    """PV of a fixed-vs-float par swap (receive fixed sign):

    N * (K * A + df(T) - df(T0)), with A = sum tau_i df(t_i).
    """
    _, annuity, df_effective, df_maturity = swap_annuity(curve, quote)
    return quote.notional * (
        quote.fixed_rate * annuity + df_maturity - df_effective)


def bond_pv(curve: DiscountCurve, bond: BondCashflows) -> Decimal:
    for flow_date, amount in bond.flows:
        if flow_date < curve.value_date:
            raise IllegalDateError(
                f"bond {bond.id}: cash flow {flow_date.isoformat()} precedes "
                f"value date {curve.value_date.isoformat()}")
        if flow_date > curve.nodes[-1].date:
            raise MissingTenorError(
                f"bond {bond.id}: cash flow {flow_date.isoformat()} is beyond "
                f"the last node {curve.nodes[-1].date.isoformat()}")
    return sum((amount * curve.discount_factor(flow_date)
                for flow_date, amount in bond.flows), ZERO)


def reference_cashflow_pv(
    flows: tuple[tuple[Date, Decimal], ...],
    discount_factors: tuple[Decimal, ...],
) -> Decimal:
    """Independent oracle: sum amount_i * df_i over explicit per-date factors.

    Deliberately independent of :class:`DiscountCurve`: tests hand it
    analytically constructed discount factors (deposit fixture, two-period
    swap) so the curve interpolation path cannot mask a pricing error.
    """
    if len(flows) != len(discount_factors):
        raise IllegalDateError(
            f"oracle given {len(flows)} flows but {len(discount_factors)} "
            "discount factors")
    total = ZERO
    for (flow_date, amount), df in zip(flows, discount_factors):
        if not isinstance(flow_date, Date):
            raise IllegalDateError("oracle flow date must be a date")
        if not isinstance(amount, Decimal) or not amount.is_finite():
            raise IllegalDateError("oracle flow amount must be finite Decimal")
        if not isinstance(df, Decimal) or not df.is_finite() or df <= ZERO:
            raise IllegalDateError("oracle discount factor must be positive")
        total += amount * df
    return total
