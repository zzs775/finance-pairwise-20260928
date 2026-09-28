from datetime import date
from decimal import Decimal
from unittest import TestCase

from dcurve.dates import (
    BUILTIN_CALENDARS,
    BusinessDayConvention,
    DayCountConvention,
    StubType,
    Tenor,
    add_tenor,
    build_schedule,
    day_count_fraction,
)
from dcurve.errors import IllegalDateError


class TenorTest(TestCase):
    def test_parse_and_months(self) -> None:
        self.assertEqual(Tenor.parse("3M").months(), 3)
        self.assertEqual(Tenor.parse("2y").months(), 24)
        self.assertEqual(Tenor.parse("1W").months(), 0)

    def test_invalid_tenor_is_classified(self) -> None:
        for text in ("", "M", "0M", "5X"):
            with self.subTest(text=text):
                with self.assertRaises(IllegalDateError):
                    Tenor.parse(text)


class DayCountTest(TestCase):
    def test_act360_and_act365f(self) -> None:
        start, end = date(2024, 1, 1), date(2024, 2, 1)
        self.assertEqual(day_count_fraction(start, end,
                         DayCountConvention.ACT_360), Decimal("31") / 360)
        self.assertEqual(day_count_fraction(start, end,
                         DayCountConvention.ACT_365F), Decimal("31") / 365)

    def test_30_360_month_end(self) -> None:
        value = day_count_fraction(
            date(2025, 1, 31), date(2025, 2, 28),
            DayCountConvention.THIRTY_360)
        self.assertEqual(value, Decimal(28) / 360)

    def test_reversed_interval_rejected(self) -> None:
        with self.assertRaises(IllegalDateError):
            day_count_fraction(date(2025, 2, 1), date(2025, 1, 1),
                               DayCountConvention.ACT_360)


class CalendarTest(TestCase):
    def test_target_holidays_and_weekends(self) -> None:
        target = BUILTIN_CALENDARS["TARGET"]
        self.assertFalse(target.is_business_day(date(2025, 12, 25)))
        self.assertFalse(target.is_business_day(date(2025, 4, 18)))  # Good Fri
        self.assertFalse(target.is_business_day(date(2025, 4, 21)))  # Easter Mon
        self.assertTrue(target.is_business_day(date(2025, 4, 22)))

    def test_modified_following_avoids_month_cross(self) -> None:
        target = BUILTIN_CALENDARS["TARGET"]
        # 2025-05-31 is Saturday; following crosses June, modified backs up.
        adjusted = target.adjust(
            date(2025, 5, 31), BusinessDayConvention.MODIFIED_FOLLOWING)
        self.assertEqual(adjusted, date(2025, 5, 30))

    def test_month_end_preserved_on_roll(self) -> None:
        self.assertEqual(add_tenor(date(2024, 1, 31), Tenor.parse("1M")),
                         date(2024, 2, 29))
        self.assertEqual(add_tenor(date(2024, 2, 29), Tenor.parse("1Y")),
                         date(2025, 2, 28))


class ScheduleStubTest(TestCase):
    def test_short_first_stub(self) -> None:
        target = BUILTIN_CALENDARS["TARGET"]
        periods = build_schedule(
            date(2025, 1, 15), date(2025, 7, 14),
            Tenor.parse("3M"), DayCountConvention.ACT_360,
            target, BusinessDayConvention.FOLLOWING, StubType.SHORT_FIRST)
        self.assertEqual(len(periods), 2)
        self.assertEqual(periods[0].start, date(2025, 1, 15))
        self.assertEqual(periods[0].end, date(2025, 4, 14))
        self.assertEqual(periods[1].end, date(2025, 7, 14))

    def test_long_first_stub_merges_first_two_regular_periods(self) -> None:
        target = BUILTIN_CALENDARS["TARGET"]
        periods = build_schedule(
            date(2025, 1, 15), date(2025, 10, 14),
            Tenor.parse("3M"), DayCountConvention.ACT_360,
            target, BusinessDayConvention.FOLLOWING, StubType.LONG_FIRST)
        self.assertEqual([p.end for p in periods],
                         [date(2025, 7, 14), date(2025, 10, 14)])
        self.assertEqual(periods[0].start, date(2025, 1, 15))

    def test_regular_effective_needs_no_stub(self) -> None:
        target = BUILTIN_CALENDARS["TARGET"]
        periods = build_schedule(
            date(2025, 1, 14), date(2025, 7, 14),
            Tenor.parse("3M"), DayCountConvention.ACT_360,
            target, BusinessDayConvention.FOLLOWING, StubType.NONE)
        self.assertEqual(len(periods), 2)

    def test_irregular_effective_without_stub_rejected(self) -> None:
        target = BUILTIN_CALENDARS["TARGET"]
        with self.assertRaises(IllegalDateError):
            build_schedule(
                date(2025, 1, 15), date(2025, 7, 14),
                Tenor.parse("3M"), DayCountConvention.ACT_360,
                target, BusinessDayConvention.FOLLOWING, StubType.NONE)
