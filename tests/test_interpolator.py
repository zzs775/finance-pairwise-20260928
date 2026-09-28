import math
import unittest
from datetime import date
from decimal import Decimal

from discurve.errors import (
    ExtrapolationError,
    IllegalDateError,
    NonPositiveDiscountError,
)
from discurve.interpolator import DiscountCurve


VAL = date(2026, 1, 1)
NODES = (
    (VAL, Decimal(1)),
    (date(2026, 7, 1), Decimal("0.98")),
    (date(2027, 1, 1), Decimal("0.96")),
)


class InterpolatorTest(unittest.TestCase):
    def test_nodes_returned_exactly(self) -> None:
        curve = DiscountCurve(VAL, NODES)
        self.assertEqual(curve.discount(VAL), Decimal(1))
        self.assertEqual(curve.discount(date(2026, 7, 1)), Decimal("0.98"))
        self.assertEqual(curve.discount(date(2027, 1, 1)), Decimal("0.96"))

    def test_loglinear_midpoint_is_geometric_mean(self) -> None:
        curve = DiscountCurve(VAL, NODES)
        query = date(2026, 4, 1)
        mid = curve.discount(query)
        elapsed = Decimal((query - VAL).days)
        total = Decimal((date(2026, 7, 1) - VAL).days)
        weight = elapsed / total
        expected = (weight * Decimal("0.98").ln()).exp()
        self.assertAlmostEqual(float(mid), float(expected), places=15)
        # Log-linearity means log(DF) is linear in elapsed calendar days.
        self.assertAlmostEqual(
            math.log(float(mid)),
            float(weight) * math.log(0.98),
            places=12,
        )

    def test_boundary_queries_are_accepted(self) -> None:
        curve = DiscountCurve(VAL, NODES)
        self.assertEqual(curve.discount(VAL), Decimal(1))
        self.assertEqual(curve.discount(date(2027, 1, 1)), Decimal("0.96"))

    def test_queries_outside_range_are_rejected(self) -> None:
        curve = DiscountCurve(VAL, NODES)
        with self.assertRaises(ExtrapolationError):
            curve.discount(date(2025, 12, 31))
        with self.assertRaises(ExtrapolationError):
            curve.discount(date(2027, 1, 2))

    def test_non_positive_factor_rejected(self) -> None:
        with self.assertRaises(NonPositiveDiscountError):
            DiscountCurve(VAL, ((VAL, Decimal(0)),))
        with self.assertRaises(NonPositiveDiscountError):
            DiscountCurve(
                VAL, ((VAL, Decimal(1)), (date(2026, 2, 1), Decimal(-1)))
            )

    def test_non_increasing_nodes_rejected(self) -> None:
        with self.assertRaises(IllegalDateError):
            DiscountCurve(
                VAL,
                (
                    (VAL, Decimal(1)),
                    (date(2026, 2, 1), Decimal("0.99")),
                    (date(2026, 2, 1), Decimal("0.98")),
                ),
            )

    def test_first_node_must_be_valuation_date(self) -> None:
        with self.assertRaises(IllegalDateError):
            DiscountCurve(
                date(2026, 1, 2),
                ((VAL, Decimal(1)),),
            )


if __name__ == "__main__":
    unittest.main()
