"""Account-level time-weighted return via geometric linking."""

from __future__ import annotations

from decimal import Decimal

from .errors import CapitalStatus
from .models import AccountResult, SubPeriod


def sub_period_rate(begin_capital: Decimal, external_flow: Decimal,
                    end_capital: Decimal, pnl: Decimal) -> Decimal | None:
    """Return ``r = pnl / begin_capital`` for one sub-period.

    A zero or negative capital base cannot support a time-weighted rate, so
    ``None`` is returned; callers surface the explicit capital status instead.
    """
    if begin_capital <= 0:
        return None
    return pnl / begin_capital


def link_geometric(rates: list[Decimal]) -> Decimal:
    """Geometrically link sub-period rates: ``product(1 + r) - 1``."""
    factor = Decimal(1)
    for rate in rates:
        factor *= Decimal(1) + rate
    return factor - Decimal(1)


def compute_account(account_id: str, sub_periods: list[SubPeriod]) -> AccountResult:
    """Compute the account TWR from its ordered sub-periods.

    Every sub-period with a positive base contributes its rate; if any
    sub-period has a zero/negative base the account result carries the worst
    such status and no overall TWR is claimed.
    """
    rates = [period.rate for period in sub_periods if period.rate is not None]
    statuses = {period.status for period in sub_periods}

    if CapitalStatus.NEGATIVE_BASE.value in statuses:
        overall_status = CapitalStatus.NEGATIVE_BASE.value
    elif CapitalStatus.ZERO_BASE.value in statuses:
        overall_status = CapitalStatus.ZERO_BASE.value
    else:
        overall_status = CapitalStatus.OK.value

    if overall_status != CapitalStatus.OK.value:
        return AccountResult(
            account_id=account_id,
            sub_periods=sub_periods,
            twr=None,
            status=overall_status,
        )

    return AccountResult(
        account_id=account_id,
        sub_periods=sub_periods,
        twr=link_geometric(rates),
        status=overall_status,
    )
