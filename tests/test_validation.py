"""Tests for validation rules."""

import unittest
from datetime import date
from decimal import Decimal

from ecl_engine.errors import ValidationError
from ecl_engine.models import AssumptionSet, Contract, Curve, Scenario
from ecl_engine import validation


def make_contract(stage: int = 1, periods: int = 3, *, pd=None, lgd=None,
                  ead=None, discount=None) -> Contract:
    zero3 = [Decimal("0")] * periods
    one3 = [Decimal("1")] * periods
    return Contract(
        "C1", stage, periods,
        Curve(pd if pd is not None else zero3),
        Curve(lgd if lgd is not None else one3),
        Curve(ead if ead is not None else one3),
        Curve(discount if discount is not None else one3),
    )


class CumulativePDTest(unittest.TestCase):
    def test_valid_flat_and_rising(self) -> None:
        validation.validate_cumulative_pd(
            [Decimal("0"), Decimal("0.1"), Decimal("0.1")])

    def test_negative(self) -> None:
        with self.assertRaises(ValidationError):
            validation.validate_cumulative_pd([Decimal("-0.0000000001")])

    def test_above_one(self) -> None:
        with self.assertRaises(ValidationError):
            validation.validate_cumulative_pd([Decimal("1.0000000001")])

    def test_declining(self) -> None:
        with self.assertRaises(ValidationError):
            validation.validate_cumulative_pd(
                [Decimal("0.2"), Decimal("0.1")])

    def test_non_finite(self) -> None:
        with self.assertRaises(ValidationError):
            validation.validate_cumulative_pd([Decimal("NaN")])


class ContractValidationTest(unittest.TestCase):
    def test_valid_contract(self) -> None:
        validation.validate_contract(make_contract())

    def test_bad_stage(self) -> None:
        with self.assertRaises(ValidationError):
            validation.validate_contract(make_contract(stage=4))

    def test_non_positive_periods(self) -> None:
        with self.assertRaises(ValidationError):
            validation.validate_contract(make_contract(periods=0))

    def test_negative_ead(self) -> None:
        with self.assertRaises(ValidationError):
            validation.validate_contract(
                make_contract(ead=[Decimal("1"), Decimal("-1"),
                                   Decimal("1")]))

    def test_term_gap(self) -> None:
        with self.assertRaises(ValidationError):
            validation.validate_contract(make_contract(
                pd=[Decimal("0"), Decimal("0")]))

    def test_lgd_above_one(self) -> None:
        with self.assertRaises(ValidationError):
            validation.validate_contract(
                make_contract(lgd=[Decimal("2")] * 3))


class WeightValidationTest(unittest.TestCase):
    def test_closes(self) -> None:
        total = validation.validate_weights([
            Scenario("base", Decimal("0.6")),
            Scenario("down", Decimal("0.4")),
        ])
        self.assertEqual(total, Decimal("1.0000000000"))

    def test_does_not_close(self) -> None:
        with self.assertRaises(ValidationError):
            validation.validate_weights([
                Scenario("base", Decimal("0.6")),
                Scenario("down", Decimal("0.3")),
            ])

    def test_negative_weight(self) -> None:
        with self.assertRaises(ValidationError):
            validation.validate_weights([
                Scenario("base", Decimal("1.1")),
                Scenario("down", Decimal("-0.1")),
            ])

    def test_duplicate_name(self) -> None:
        with self.assertRaises(ValidationError):
            validation.validate_weights([
                Scenario("base", Decimal("0.5")),
                Scenario("base", Decimal("0.5")),
            ])

    def test_empty(self) -> None:
        with self.assertRaises(ValidationError):
            validation.validate_weights([])


class AssumptionValidationTest(unittest.TestCase):
    def test_valid(self) -> None:
        assumptions = AssumptionSet(
            "v1", date(2026, 1, 1),
            scenarios=(Scenario("base", Decimal("1")),))
        validation.validate_assumptions(assumptions)

    def test_empty_version(self) -> None:
        with self.assertRaises(ValidationError):
            validation.validate_assumptions(AssumptionSet(
                "", date(2026, 1, 1),
                scenarios=(Scenario("base", Decimal("1")),)))


if __name__ == "__main__":
    unittest.main()
