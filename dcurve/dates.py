"""Date arithmetic: tenors, day counts, holidays, adjustments and stubs."""

import calendar as _calendar
from dataclasses import dataclass
from datetime import date, timedelta
from decimal import Decimal
from enum import StrEnum

from .errors import IllegalDateError

Date = date
_D360 = Decimal(360)
_D365 = Decimal(365)


class BusinessDayConvention(StrEnum):
    FOLLOWING = "following"
    MODIFIED_FOLLOWING = "modified_following"
    PRECEDING = "preceding"
    MODIFIED_PRECEDING = "modified_preceding"
    NONE = "none"


class DayCountConvention(StrEnum):
    ACT_360 = "act/360"
    ACT_365F = "act/365f"
    THIRTY_360 = "30/360"


class StubType(StrEnum):
    NONE = "none"
    SHORT_FIRST = "short_first"
    LONG_FIRST = "long_first"


_TENOR_UNITS = ("D", "W", "M", "Y")


@dataclass(frozen=True, slots=True)
class Tenor:
    amount: int
    unit: str  # one of "D", "W", "M", "Y"

    @classmethod
    def parse(cls, text: str) -> "Tenor":
        if not isinstance(text, str) or not text:
            raise IllegalDateError(
                f"tenor must be a non-empty string, got {text!r}")
        unit = text[-1].upper()
        number = text[:-1]
        if unit not in _TENOR_UNITS or not number.isdigit():
            raise IllegalDateError(f"invalid tenor {text!r}; expected e.g. 3M")
        amount = int(number)
        if amount <= 0:
            raise IllegalDateError(f"tenor amount must be positive in {text!r}")
        return cls(amount=amount, unit=unit)

    def months(self) -> int:
        if self.unit == "M":
            return self.amount
        if self.unit == "Y":
            return self.amount * 12
        return 0


@dataclass(frozen=True, slots=True)
class Period:
    start: Date
    end: Date
    payment: Date
    year_fraction: Decimal


class HolidayCalendar:
    """Weekend rule plus an immutable set of explicit holiday dates."""

    name: str
    holidays: frozenset[Date]

    def __init__(self, name: str, holidays: frozenset[Date]) -> None:
        if not isinstance(name, str) or not name:
            raise IllegalDateError("calendar name must be a non-empty string")
        for day in holidays:
            if not isinstance(day, Date):
                raise IllegalDateError(f"holiday entry is not a date: {day!r}")
        object.__setattr__(self, "name", name)
        object.__setattr__(self, "holidays", frozenset(holidays))

    def __eq__(self, other: object) -> bool:
        return (isinstance(other, HolidayCalendar)
                and self.name == other.name
                and self.holidays == other.holidays)

    def __hash__(self) -> int:
        return hash((self.name, self.holidays))

    def is_business_day(self, day: Date) -> bool:
        if not isinstance(day, Date):
            raise IllegalDateError(f"expected a date, got {day!r}")
        return day.weekday() < 5 and day not in self.holidays

    def adjust(self, day: Date, convention: BusinessDayConvention) -> Date:
        if not isinstance(day, Date):
            raise IllegalDateError(f"expected a date, got {day!r}")
        if convention == BusinessDayConvention.NONE or self.is_business_day(day):
            return day
        if convention in (BusinessDayConvention.FOLLOWING,
                          BusinessDayConvention.MODIFIED_FOLLOWING):
            candidate = day
            while not self.is_business_day(candidate):
                candidate += timedelta(days=1)
            if (convention == BusinessDayConvention.MODIFIED_FOLLOWING
                    and candidate.month != day.month):
                candidate = day
                while not self.is_business_day(candidate):
                    candidate -= timedelta(days=1)
            return candidate
        candidate = day
        while not self.is_business_day(candidate):
            candidate -= timedelta(days=1)
        if (convention == BusinessDayConvention.MODIFIED_PRECEDING
                and candidate.month != day.month):
            candidate = day
            while not self.is_business_day(candidate):
                candidate += timedelta(days=1)
        return candidate


def _fixed_target_holidays() -> frozenset[Date]:
    """Fixed in-problem TARGET-like holiday set (weekday dates only)."""
    fixed_dates = [(1, 1), (5, 1), (12, 25), (12, 26)]
    years = range(2024, 2031)
    holidays = {Date(y, m, d) for y in years for m, d in fixed_dates}
    for year in years:
        a = year % 19
        b, c = divmod(year, 100)
        d_easter = (19 * a + b - b // 4
                    - (b - (b + 8) // 25 + 1) // 3 + 15) % 30
        e = (32 + 2 * (b % 4) + 2 * (c // 4) - d_easter - (c % 4)) % 7
        g = (a + 11 * d_easter + 22 * e) // 451
        month, day_no = divmod(d_easter + e - 7 * g + 114, 31)
        easter_sunday = Date(year, month, day_no + 1)
        holidays.add(easter_sunday - timedelta(days=2))
        holidays.add(easter_sunday + timedelta(days=1))
    return frozenset(h for h in holidays if h.weekday() < 5)


BUILTIN_CALENDARS: dict[str, HolidayCalendar] = {
    "WEEKEND": HolidayCalendar("WEEKEND", frozenset()),
    "TARGET": HolidayCalendar("TARGET", _fixed_target_holidays()),
}


def day_count_fraction(start: Date, end: Date,
                       convention: DayCountConvention) -> Decimal:
    if not isinstance(start, Date) or not isinstance(end, Date):
        raise IllegalDateError("day-count endpoints must be dates")
    if end < start:
        raise IllegalDateError(
            f"day-count end {end.isoformat()} precedes start {start.isoformat()}")
    if convention == DayCountConvention.ACT_360:
        return Decimal((end - start).days) / _D360
    if convention == DayCountConvention.ACT_365F:
        return Decimal((end - start).days) / _D365
    start_day = min(start.day, 30)
    end_day = 30 if end.day == 31 else end.day
    days = (360 * (end.year - start.year)
            + 30 * (end.month - start.month)
            + (end_day - start_day))
    return Decimal(days) / _D360


def _shift(base: Date, tenor: Tenor, direction: int) -> Date:
    if not isinstance(base, Date):
        raise IllegalDateError(f"tenor base must be a date, got {base!r}")
    if tenor.unit in ("D", "W"):
        days = tenor.amount * (7 if tenor.unit == "W" else 1)
        return base + direction * timedelta(days=days)
    total_months = base.year * 12 + base.month - 1 + direction * tenor.months()
    year, month0 = divmod(total_months, 12)
    month = month0 + 1
    last_day = _calendar.monthrange(year, month)[1]
    return Date(year, month, min(base.day, last_day))


def add_tenor(base: Date, tenor: Tenor) -> Date:
    """Roll an unadjusted calendar date by a tenor (month-end preserved)."""
    return _shift(base, tenor, +1)


def build_schedule(
    effective: Date,
    maturity: Date,
    payment_tenor: Tenor,
    day_count: DayCountConvention,
    calendar: HolidayCalendar,
    convention: BusinessDayConvention,
    stub: StubType,
) -> tuple[Period, ...]:
    if not isinstance(effective, Date) or not isinstance(maturity, Date):
        raise IllegalDateError("schedule endpoints must be dates")
    if maturity <= effective:
        raise IllegalDateError(
            f"maturity {maturity.isoformat()} must be strictly after effective "
            f"{effective.isoformat()}")

    unadjusted = [maturity]
    while unadjusted[-1] > effective:
        unadjusted.append(_shift(unadjusted[-1], payment_tenor, -1))
    unadjusted.reverse()

    adjusted = [calendar.adjust(day, convention) for day in unadjusted]
    if unadjusted[0] == effective:
        boundaries = adjusted
    elif stub == StubType.SHORT_FIRST:
        boundaries = [effective] + adjusted[1:]
    elif stub == StubType.LONG_FIRST:
        if len(adjusted) < 3:
            boundaries = [effective, adjusted[-1]]
        else:
            boundaries = [effective] + adjusted[2:]
    else:
        raise IllegalDateError(
            "effective date does not coincide with a regular period start; "
            "a short_first or long_first stub is required "
            f"(effective={effective.isoformat()})")

    periods = []
    for start, end in zip(boundaries, boundaries[1:]):
        if end <= start:
            raise IllegalDateError(
                "business-day adjustment produced a non-positive accrual period")
        periods.append(Period(
            start=start,
            end=end,
            payment=end,
            year_fraction=day_count_fraction(start, end, day_count),
        ))
    return tuple(periods)
