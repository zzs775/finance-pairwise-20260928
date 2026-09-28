"""Holiday calendar, business-day adjustment, day counts and stubs."""

from dataclasses import dataclass
from datetime import date, timedelta
from decimal import Decimal
from typing import Literal, NamedTuple

from .errors import IllegalDateError, ScheduleError

DayCountConvention = Literal["ACT/360", "ACT/365F", "30/360"]
BusinessDayConvention = Literal[
    "unadjusted", "following", "modified_following", "preceding"
]
StubType = Literal["short_front", "long_front", "short_back", "long_back"]


@dataclass(frozen=True)
class HolidayCalendar:
    """Immutable week-end + explicit-holiday calendar."""

    name: str
    holidays: frozenset[date]

    def is_business_day(self, day: date) -> bool:
        return day.weekday() < 5 and day not in self.holidays

    def adjust(
        self, day: date, convention: BusinessDayConvention = "modified_following"
    ) -> date:
        if convention == "unadjusted":
            return day

        if convention == "following":
            current = day
            while not self.is_business_day(current):
                current += timedelta(days=1)
            return current
        if convention == "preceding":
            current = day
            while not self.is_business_day(current):
                current -= timedelta(days=1)
            return current
        if convention == "modified_following":
            current = day
            while not self.is_business_day(current):
                current += timedelta(days=1)
            if current.month != day.month:
                current = day
                while not self.is_business_day(current):
                    current -= timedelta(days=1)
            return current
        raise IllegalDateError(f"unknown business-day convention: {convention!r}")


class SchedulePeriod(NamedTuple):
    unadjusted_start: date
    unadjusted_end: date
    adjusted_start: date
    adjusted_end: date


def year_fraction(
    start: date, end: date, convention: DayCountConvention = "ACT/360"
) -> Decimal:
    if not isinstance(start, date) or not isinstance(end, date):
        raise IllegalDateError("year-fraction endpoints must be dates")
    if end <= start:
        raise IllegalDateError(
            f"year-fraction end {end} must be strictly after start {start}"
        )
    actual_days = (end - start).days
    if convention == "ACT/360":
        return Decimal(actual_days) / Decimal(360)
    if convention == "ACT/365F":
        return Decimal(actual_days) / Decimal(365)
    if convention == "30/360":
        d1 = start.day
        d2 = end.day
        if d1 == 31:
            d1 = 30
        if d2 == 31 and d1 == 30:
            d2 = 30
        bond_basis_days = (
            360 * (end.year - start.year)
            + 30 * (end.month - start.month)
            + (d2 - d1)
        )
        return Decimal(bond_basis_days) / Decimal(360)
    raise IllegalDateError(f"unknown day-count convention: {convention!r}")


def add_months(day: date, months: int) -> date:
    if not isinstance(day, date):
        raise IllegalDateError("add_months expects a date")
    zero_based = (day.year * 12 + day.month - 1) + months
    year, month = divmod(zero_based, 12)
    month += 1
    if month == 12:
        next_month_first = date(year + 1, 1, 1)
    else:
        next_month_first = date(year, month + 1, 1)
    last_day = (next_month_first - timedelta(days=1)).day
    return date(year, month, min(day.day, last_day))


def generate_schedule(
    effective: date,
    maturity: date,
    period_months: int,
    calendar: HolidayCalendar,
    business_day_convention: BusinessDayConvention = "modified_following",
    stub: StubType = "short_front",
) -> tuple[SchedulePeriod, ...]:
    if not isinstance(effective, date) or not isinstance(maturity, date):
        raise IllegalDateError("schedule endpoints must be dates")
    if maturity <= effective:
        raise IllegalDateError(
            f"maturity {maturity} must be strictly after effective {effective}"
        )
    if period_months < 1:
        raise ScheduleError(
            f"period months must be positive, got {period_months}"
        )

    unadjusted = _unadjusted_dates(effective, maturity, period_months, stub)

    periods: list[SchedulePeriod] = []
    for i in range(len(unadjusted) - 1):
        u_start = unadjusted[i]
        u_end = unadjusted[i + 1]
        periods.append(
            SchedulePeriod(
                unadjusted_start=u_start,
                unadjusted_end=u_end,
                adjusted_start=calendar.adjust(u_start, business_day_convention),
                adjusted_end=calendar.adjust(u_end, business_day_convention),
            )
        )
    return tuple(periods)


def _unadjusted_dates(
    effective: date, maturity: date, period_months: int, stub: StubType
) -> tuple[date, ...]:
    if stub in ("short_front", "long_front"):
        backward: list[date] = []
        current = maturity
        while current > effective:
            backward.append(current)
            current = add_months(current, -period_months)
        backward.reverse()
        if backward[0] == effective:
            return tuple(backward)
        if stub == "short_front":
            return (effective, *backward)
        if len(backward) < 2:
            raise ScheduleError(
                "long front stub requires at least one full regular period"
            )
        return (effective, *backward[1:])

    if stub in ("short_back", "long_back"):
        forward: list[date] = [effective]
        current = effective
        while current < maturity:
            current = add_months(current, period_months)
            forward.append(current)
        if forward[-1] == maturity:
            return tuple(forward)
        if stub == "short_back":
            return (*forward[:-1], maturity)
        if len(forward) < 3:
            raise ScheduleError(
                "long back stub requires at least one full regular period"
            )
        return (*forward[:-2], maturity)

    raise IllegalDateError(f"unknown stub type: {stub!r}")
