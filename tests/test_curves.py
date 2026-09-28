"""Tests for curve conversions and period slicing."""

import unittest
from decimal import Decimal

from ecl_engine.errors import ValidationError
from ecl_engine import curves


class MarginalPDTest(unittest.TestCase):
    def test_basic_differences(self) -> None:
        marginals = curves.marginal_pd(
            [Decimal("0.1"), Decimal("0.25"), Decimal("0.25")])
        self.assertEqual(marginals, [
            Decimal("0.1000000000"),
            Decimal("0.1500000000"),
            Decimal("0.0000000000"),
        ])

    def test_starts_at_zero(self) -> None:
        marginals = curves.marginal_pd(
            [Decimal("0"), Decimal("0.2"), Decimal("0.9")])
        self.assertEqual(sum(marginals), Decimal("0.9000000000"))


class SlicePeriodsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.values = [Decimal(i) for i in range(1, 6)]

    def test_full_slice(self) -> None:
        self.assertEqual(curves.slice_periods(self.values, 1, 5),
                         self.values)

    def test_partial_slice(self) -> None:
        self.assertEqual(curves.slice_periods(self.values, 2, 4),
                         [Decimal(2), Decimal(3), Decimal(4)])

    def test_invalid_ranges(self) -> None:
        with self.assertRaises(ValidationError):
            curves.slice_periods(self.values, 0, 3)
        with self.assertRaises(ValidationError):
            curves.slice_periods(self.values, 3, 2)
        with self.assertRaises(ValidationError):
            curves.slice_periods(self.values, 1, 6)


if __name__ == "__main__":
    unittest.main()
