"""Tests for fixed-scale Decimal helpers."""

import unittest
from decimal import Decimal

from ecl_engine.errors import ValidationError
from ecl_engine import fixed


class FixedTest(unittest.TestCase):
    def test_quantize(self) -> None:
        self.assertEqual(fixed.q(Decimal("1.23456789015")),
                         Decimal("1.2345678902"))  # half-even
        self.assertEqual(fixed.q(1), Decimal("1.0000000000"))

    def test_zero_and_one(self) -> None:
        self.assertEqual(fixed.ZERO, Decimal("0.0000000000"))
        self.assertEqual(fixed.ONE, Decimal("1.0000000000"))

    def test_non_finite_rejected(self) -> None:
        for bad in (Decimal("Inf"), Decimal("-Inf"), Decimal("NaN")):
            with self.assertRaises(ValidationError):
                fixed.q(bad)
        self.assertFalse(fixed.is_finite(Decimal("NaN")))
        self.assertTrue(fixed.is_finite(Decimal("0")))


if __name__ == "__main__":
    unittest.main()
