import unittest
from decimal import Decimal

from ecl_engine.scales import D
from ecl_engine.validation import (
    ValidationError,
    validate_cumulative_pd,
    validate_curve_periods,
    validate_nonnegative_amounts,
    validate_term_coverage,
    validate_weights,
)
from ecl_engine.models import Curve, TermPoint


class ValidationTest(unittest.TestCase):
    def test_pd_boundaries_ok(self) -> None:
        validate_cumulative_pd(["0", "0.01", "0.01", "1"])

    def test_pd_above_one_fails(self) -> None:
        with self.assertRaises(ValidationError):
            validate_cumulative_pd(["0", "1.00000001"])

    def test_pd_decrease_fails(self) -> None:
        with self.assertRaises(ValidationError):
            validate_cumulative_pd(["0.1", "0.09"])

    def test_negative_ead_fails(self) -> None:
        with self.assertRaises(ValidationError):
            validate_nonnegative_amounts(["100", "-0.01"])

    def test_weights_closure_and_residual(self) -> None:
        residual = validate_weights(
            [("base", "0.7"), ("upside", "0.2"), ("down", "0.1")],
            Decimal("0.00000001"),
        )
        self.assertEqual(residual, D("0.00000000"))

    def test_weights_within_tail_tolerance(self) -> None:
        residual = validate_weights(
            [("base", "0.7"), ("upside", "0.29999999")],
            Decimal("0.00000001"),
        )
        self.assertEqual(residual, D("0.00000001"))

    def test_weights_not_closed_fails(self) -> None:
        with self.assertRaises(ValidationError):
            validate_weights([("a", "0.6"), ("b", "0.3")], Decimal("0.00000001"))

    def test_negative_weight_fails(self) -> None:
        with self.assertRaises(ValidationError):
            validate_weights([("a", "1.1"), ("b", "-0.1")], Decimal("0.00000001"))

    def test_term_gap_fails(self) -> None:
        with self.assertRaises(ValidationError):
            validate_term_coverage(2, 3)

    def test_curve_contiguous(self) -> None:
        validate_curve_periods(
            (TermPoint(1, D("0.1")), TermPoint(2, D("0.2"))), 2
        )
        with self.assertRaises(ValidationError):
            validate_curve_periods((TermPoint(1, D("0.1")),), 2)
        with self.assertRaises(ValidationError):
            validate_curve_periods(
                (TermPoint(1, D("0.1")), TermPoint(3, D("0.2"))), 2
            )


if __name__ == "__main__":
    unittest.main()
