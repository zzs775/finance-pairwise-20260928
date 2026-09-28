from datetime import date
from decimal import Decimal
from unittest import TestCase

from dcurve.curve import CurveNode, DiscountCurve
from dcurve.errors import (
    DuplicateNodeError,
    ExtrapolationError,
    IllegalDateError,
    NonPositiveDiscountFactorError,
)
from dcurve.pricing import reference_cashflow_pv


def _sample_curve() -> DiscountCurve:
    return DiscountCurve.create(
        date(2025, 1, 1),
        (CurveNode(date(2025, 2, 1), Decimal("0.99")),
         CurveNode(date(2025, 3, 1), Decimal("0.98"))),
        "v1")


class CurveValidationTest(TestCase):
    def test_value_date_df_is_one(self) -> None:
        self.assertEqual(_sample_curve().discount_factor(date(2025, 1, 1)),
                         Decimal(1))

    def test_nodes_must_be_strictly_increasing(self) -> None:
        with self.assertRaises(IllegalDateError):
            DiscountCurve.create(
                date(2025, 1, 1),
                (CurveNode(date(2024, 12, 31), Decimal("1")),), "v")
        with self.assertRaises(DuplicateNodeError):
            DiscountCurve.create(
                date(2025, 1, 1),
                (CurveNode(date(2025, 2, 1), Decimal("0.99")),
                 CurveNode(date(2025, 2, 1), Decimal("0.98"))), "v")

    def test_non_positive_and_non_finite_df_rejected(self) -> None:
        with self.assertRaises(NonPositiveDiscountFactorError):
            DiscountCurve.create(
                date(2025, 1, 1),
                (CurveNode(date(2025, 2, 1), Decimal("0")),), "v")
        with self.assertRaises(NonPositiveDiscountFactorError):
            DiscountCurve.create(
                date(2025, 1, 1),
                (CurveNode(date(2025, 2, 1), Decimal("NaN")),), "v")


class InterpolationTest(TestCase):
    def test_exact_nodes_returned(self) -> None:
        curve = _sample_curve()
        self.assertEqual(curve.discount_factor(date(2025, 2, 1)),
                         Decimal("0.99"))
        self.assertEqual(curve.discount_factor(date(2025, 3, 1)),
                         Decimal("0.98"))

    def test_midpoint_is_loglinear(self) -> None:
        curve = _sample_curve()
        # Linear interpolation in ln(df): geometric mean of the factors.
        expected = (Decimal("0.99") * Decimal("0.98")).sqrt()
        self.assertEqual(curve.discount_factor(date(2025, 2, 15)), expected)

    def test_extrapolation_rejected_on_both_sides(self) -> None:
        curve = _sample_curve()
        for day in (date(2024, 12, 31), date(2025, 3, 2)):
            with self.subTest(day=day):
                with self.assertRaises(ExtrapolationError):
                    curve.discount_factor(day)
        self.assertTrue(curve.covered(date(2025, 3, 1)))
        self.assertFalse(curve.covered(date(2025, 3, 2)))


class OracleTest(TestCase):
    def test_reference_sums_explicit_discount_factors(self) -> None:
        flows = ((date(2025, 7, 14), Decimal("0.02")),
                 (date(2026, 1, 14), Decimal("1.02")))
        factors = (Decimal("0.98"), Decimal("0.95"))
        self.assertEqual(
            reference_cashflow_pv(flows, factors),
            Decimal("0.02") * Decimal("0.98")
            + Decimal("1.02") * Decimal("0.95"))

    def test_oracle_rejects_length_mismatch_and_bad_factors(self) -> None:
        with self.assertRaises(IllegalDateError):
            reference_cashflow_pv(
                ((date(2025, 7, 14), Decimal("1")),), ())
        with self.assertRaises(IllegalDateError):
            reference_cashflow_pv(
                ((date(2025, 7, 14), Decimal("1")),),
                (Decimal("-1"),))
