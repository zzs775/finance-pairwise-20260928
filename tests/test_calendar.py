import unittest
from datetime import date
from decimal import Decimal

from discurve.calendar import (
    HolidayCalendar,
    add_months,
    generate_schedule,
    year_fraction,
)
from discurve.errors import IllegalDateError, ScheduleError


CAL = HolidayCalendar(
    "embedded",
    frozenset({date(2026, 7, 3)}),
)


class CalendarTest(unittest.TestCase):
    def test_weekend_and_holiday_detection(self) -> None:
        self.assertTrue(CAL.is_business_day(date(2026, 10, 1)))
        self.assertFalse(CAL.is_business_day(date(2026, 10, 3)))
        self.assertFalse(CAL.is_business_day(date(2026, 10, 4)))
        self.assertFalse(CAL.is_business_day(date(2026, 7, 3)))

    def test_following(self) -> None:
        # Saturday rolls forward to Monday (skipping the weekend).
        self.assertEqual(
            CAL.adjust(date(2026, 10, 3), "following"), date(2026, 10, 5)
        )

    def test_preceding(self) -> None:
        self.assertEqual(
            CAL.adjust(date(2026, 10, 4), "preceding"), date(2026, 10, 2)
        )

    def test_modified_following_rolls_back_across_month_end(self) -> None:
        # 2026-10-31 is a Saturday; following would land in November, so
        # modified-following rolls back to Friday the 30th.
        self.assertEqual(
            CAL.adjust(date(2026, 10, 31), "modified_following"),
            date(2026, 10, 30),
        )

    def test_unadjusted_returns_input(self) -> None:
        self.assertEqual(
            CAL.adjust(date(2026, 10, 3), "unadjusted"), date(2026, 10, 3)
        )

    def test_day_counts(self) -> None:
        self.assertEqual(
            year_fraction(date(2026, 1, 1), date(2026, 2, 1), "ACT/360"),
            Decimal(31) / Decimal(360),
        )
        self.assertEqual(
            year_fraction(date(2026, 1, 1), date(2026, 1, 31), "30/360"),
            Decimal(30) / Decimal(360),
        )
        self.assertEqual(
            year_fraction(date(2026, 1, 1), date(2027, 1, 1), "30/360"),
            Decimal(1),
        )

    def test_year_fraction_rejects_non_positive_period(self) -> None:
        with self.assertRaises(IllegalDateError):
            year_fraction(date(2026, 2, 1), date(2026, 2, 1), "ACT/360")

    def test_add_months_clamps_month_end(self) -> None:
        self.assertEqual(add_months(date(2026, 1, 31), 1), date(2026, 2, 28))
        self.assertEqual(add_months(date(2026, 1, 15), 12), date(2027, 1, 15))

    def test_short_front_stub(self) -> None:
        periods = generate_schedule(
            date(2026, 2, 15), date(2027, 2, 15), 3, CAL, "unadjusted",
            "short_front",
        )
        ends = [p.unadjusted_end for p in periods]
        self.assertEqual(
            ends,
            [
                date(2026, 5, 15),
                date(2026, 8, 15),
                date(2026, 11, 15),
                date(2027, 2, 15),
            ],
        )
        # The short front stub is the irregular first period.
        self.assertEqual(periods[0].unadjusted_start, date(2026, 2, 15))

    def test_long_front_stub_merges_first_regular_period(self) -> None:
        periods = generate_schedule(
            date(2026, 2, 15), date(2027, 2, 15), 3, CAL, "unadjusted",
            "long_front",
        )
        self.assertEqual(len(periods), 3)
        self.assertEqual(periods[0].unadjusted_start, date(2026, 2, 15))
        self.assertEqual(periods[0].unadjusted_end, date(2026, 8, 15))

    def test_short_back_stub(self) -> None:
        periods = generate_schedule(
            date(2026, 1, 1), date(2026, 12, 20), 3, CAL, "unadjusted",
            "short_back",
        )
        self.assertEqual(periods[-1].unadjusted_end, date(2026, 12, 20))
        self.assertLess(
            periods[-1].unadjusted_end,
            add_months(periods[-2].unadjusted_end, 3),
        )

    def test_long_back_stub_merges_last_regular_period(self) -> None:
        periods = generate_schedule(
            date(2026, 1, 1), date(2026, 12, 20), 3, CAL, "unadjusted",
            "long_back",
        )
        self.assertEqual(len(periods), 3)
        self.assertEqual(periods[-1].unadjusted_start, date(2026, 7, 1))
        self.assertEqual(periods[-1].unadjusted_end, date(2026, 12, 20))

    def test_schedule_applies_adjustments(self) -> None:
        periods = generate_schedule(
            date(2026, 6, 1), date(2026, 8, 1), 3, CAL, "following",
            "short_front",
        )
        # 2026-08-01 is a Saturday and moves to Monday 2026-08-03.
        self.assertEqual(periods[-1].unadjusted_end, date(2026, 8, 1))
        self.assertEqual(periods[-1].adjusted_end, date(2026, 8, 3))

    def test_schedule_rejects_bad_endpoints(self) -> None:
        with self.assertRaises(IllegalDateError):
            generate_schedule(
                date(2026, 2, 1), date(2026, 2, 1), 3, CAL,
                "unadjusted", "short_front",
            )
        with self.assertRaises(ScheduleError):
            generate_schedule(
                date(2026, 1, 1), date(2026, 1, 15), 0, CAL,
                "unadjusted", "short_front",
            )


if __name__ == "__main__":
    unittest.main()
