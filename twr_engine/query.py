"""Range queries over sub-period returns."""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from .errors import CapitalStatus


def query_return(sub_periods: list, start_day: date,
                 end_day: date) -> Decimal | None:
    """Geometrically link sub-periods intersecting ``[start_day, end_day]``.

    A sub-period contributes whenever its span overlaps the query window.
    Returns ``None`` with a caller-checkable reason if any contributing
    sub-period has a zero/negative capital base.
    """
    overlapping = [
        period
        for period in sub_periods
        if period.start_day <= end_day and period.end_day >= start_day
    ]
    statuses = {period.status for period in overlapping}
    if CapitalStatus.NEGATIVE_BASE.value in statuses:
        return None
    if CapitalStatus.ZERO_BASE.value in statuses:
        return None

    factor = Decimal(1)
    for period in overlapping:
        if period.rate is None:
            return None
        factor *= Decimal(1) + period.rate
    return factor - Decimal(1)


def query_status(sub_periods: list, start_day: date, end_day: date) -> str:
    """Report the worst capital status within the query window."""
    overlapping = [
        period
        for period in sub_periods
        if period.start_day <= end_day and period.end_day >= start_day
    ]
    statuses = {period.status for period in overlapping}
    if CapitalStatus.NEGATIVE_BASE.value in statuses:
        return CapitalStatus.NEGATIVE_BASE.value
    if CapitalStatus.ZERO_BASE.value in statuses:
        return CapitalStatus.ZERO_BASE.value
    return CapitalStatus.OK.value
