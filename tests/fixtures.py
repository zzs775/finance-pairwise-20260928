"""Hand-computable fixtures shared by the test suite."""

from datetime import date
from decimal import Decimal

from dcurve.dates import (
    BusinessDayConvention,
    DayCountConvention,
    StubType,
    Tenor,
)
from dcurve.instruments import (
    BondCashflows,
    DepositQuote,
    FRAQuote,
    QuoteBook,
    SwapQuote,
)

VALUE_DATE = date(2025, 1, 13)
RATE = Decimal("0.04")


def deposit_book() -> QuoteBook:
    """Single 91-day act/360 deposit at 4%; its df is analytic."""
    return QuoteBook(
        value_date=VALUE_DATE,
        deposits=(DepositQuote(
            id="D91D",
            start=VALUE_DATE,
            maturity=date(2025, 4, 14),  # 91 days
            rate=RATE,
            day_count=DayCountConvention.ACT_360,
        ),),
        fras=(),
        swaps=(),
    )


def analytic_deposit_df() -> Decimal:
    tau = Decimal(91) / Decimal(360)
    return Decimal(1) / (Decimal(1) + RATE * tau)


def two_period_swap_book() -> QuoteBook:
    """Deposit anchors T1 and a one-year par swap anchors T2.

    Dates fall on a regular semi-annual grid (2025-01-15 -> 2025-07-15 ->
    2026-01-15) with no holiday adjustment.  Year fractions are 181/360
    and 184/360, so the par equation stays fully hand-computable.
    """
    effective = date(2025, 1, 15)
    t1 = date(2025, 7, 15)
    t2 = date(2026, 1, 15)
    deposit = DepositQuote(
        id="D6M", start=effective, maturity=t1,
        rate=Decimal("0.03"), day_count=DayCountConvention.ACT_360)
    swap = SwapQuote(
        id="S1Y", effective=effective, maturity=t2,
        payment_tenor=Tenor.parse("6M"),
        fixed_rate=Decimal("0.05"),
        day_count=DayCountConvention.ACT_360,
        calendar_name="TARGET",
        business_day_convention=BusinessDayConvention.FOLLOWING,
        stub=StubType.NONE,
    )
    return QuoteBook(
        value_date=effective, deposits=(deposit,), fras=(),
        swaps=(swap,))


def analytic_swap_nodes() -> tuple[Decimal, Decimal]:
    tau1 = Decimal(181) / Decimal(360)
    tau2 = Decimal(184) / Decimal(360)
    df1 = Decimal(1) / (Decimal(1) + Decimal("0.03") * tau1)
    rate = Decimal("0.05")
    # Par swap: K*(tau1*df1 + tau2*df2) + df2 - 1 = 0
    df2 = (Decimal(1) - rate * tau1 * df1) / (Decimal(1) + rate * tau2)
    return df1, df2


def sample_bond() -> BondCashflows:
    return BondCashflows(id="B1", flows=(
        (date(2025, 7, 15), Decimal("0.025")),
        (date(2026, 1, 15), Decimal("1.025")),
    ))


def standard_book() -> QuoteBook:
    """Deposit + FRA + 1Y swap covering the example workflow."""
    return QuoteBook(
        value_date=VALUE_DATE,
        deposits=(DepositQuote(
            "D3M", VALUE_DATE, date(2025, 4, 14),
            Decimal("0.03"), DayCountConvention.ACT_360),),
        fras=(FRAQuote(
            "F3Mx6M", date(2025, 4, 14), date(2025, 7, 14),
            Decimal("0.032"), DayCountConvention.ACT_360),),
        swaps=(SwapQuote(
            "S1Y", VALUE_DATE, date(2026, 1, 13),
            Tenor.parse("6M"), Decimal("0.035"),
            DayCountConvention.ACT_360, "TARGET",
            BusinessDayConvention.MODIFIED_FOLLOWING, StubType.NONE),),
    )


def standard_bond() -> BondCashflows:
    """Bond whose last flow coincides with the standard book's 1Y node."""
    return BondCashflows(id="B-STD", flows=(
        (date(2025, 7, 14), Decimal("0.0175")),
        (date(2026, 1, 13), Decimal("1.0175")),
    ))
