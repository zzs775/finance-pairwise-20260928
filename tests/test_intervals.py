import unittest
from decimal import Decimal

from xbrlcheck.intervals import Interval
from xbrlcheck.intervals import fact_interval
from xbrlcheck.intervals import parse_decimals
from xbrlcheck.intervals import sum_intervals
from xbrlcheck.intervals import validate_numeric_fact
from xbrlcheck.model import Fact


class IntervalsTest(unittest.TestCase):
    def test_negative_decimals_widens_interval(self):
        fact = Fact("A", "c", "u", "-3", "1000")
        self.assertEqual(fact_interval(fact), Interval(Decimal("500"), Decimal("1500")))

    def test_positive_decimals(self):
        fact = Fact("A", "c", "u", "2", "400.00")
        self.assertEqual(fact_interval(fact), Interval(Decimal("399.995"), Decimal("400.005")))

    def test_inf_and_absent_are_exact(self):
        self.assertEqual(parse_decimals("INF"), None)
        exact = fact_interval(Fact("A", "c", "u", "INF", "12.5"))
        self.assertEqual(exact, Interval(Decimal("12.5"), Decimal("12.5")))
        absent = fact_interval(Fact("A", "c", "u", "", "12.5"))
        self.assertEqual(absent.low, absent.high)

    def test_boundary_touch_is_intersection(self):
        left = Interval(Decimal("0"), Decimal("1"))
        right = Interval(Decimal("1"), Decimal("2"))
        self.assertTrue(left.intersects(right))
        self.assertEqual(left.gap_to(right), Decimal("0"))

    def test_gap_is_minimum_difference(self):
        left = Interval(Decimal("0"), Decimal("1"))
        right = Interval(Decimal("2.5"), Decimal("3"))
        self.assertEqual(left.gap_to(right), Decimal("1.5"))
        self.assertEqual(right.gap_to(left), Decimal("1.5"))

    def test_negative_weight_flips_interval(self):
        interval = Interval(Decimal("10"), Decimal("20"))
        self.assertEqual(interval.scale(-1), Interval(Decimal("-20"), Decimal("-10")))
        total = sum_intervals([(1, Interval(Decimal("1"), Decimal("2"))),
                               (-1, Interval(Decimal("3"), Decimal("5")))])
        self.assertEqual(total, Interval(Decimal("-4"), Decimal("-1")))

    def test_no_premature_rounding(self):
        fact = Fact("A", "c", "u", "0", "3")
        self.assertEqual(fact_interval(fact).low, Decimal("2.5"))

    def test_invalid_decimals_and_values(self):
        diagnostics = validate_numeric_fact(Fact("A", "c", "u", "bad", "1,0"))
        self.assertEqual([d.code for d in diagnostics], ["E106", "E106"])
        missing = validate_numeric_fact(Fact("A", "c", "u", "", "1"))
        self.assertEqual(missing[0].code, "E106")


if __name__ == "__main__":
    unittest.main()
