"""Tests for core data types."""

import unittest
from datetime import date
from decimal import Decimal

from ecl_engine.models import Curve, Scenario, Contract, AssumptionSet
from ecl_engine.errors import ValidationError


class ModelsTest(unittest.TestCase):
    def test_curve_quantizes_and_indexes(self) -> None:
        curve = Curve([Decimal("0.1"), "0.25"])
        self.assertEqual(curve.values,
                         (Decimal("0.1000000000"), Decimal("0.2500000000")))
        self.assertEqual(len(curve), 2)
        self.assertEqual(curve.at(2), Decimal("0.2500000000"))

    def test_scenario_quantizes_weight(self) -> None:
        scenario = Scenario("base", Decimal("0.5"))
        self.assertEqual(scenario.weight, Decimal("0.5000000000"))

    def test_non_finite_curve_rejected(self) -> None:
        with self.assertRaises(ValidationError):
            Curve([Decimal("NaN")])

    def test_assumption_set_defaults(self) -> None:
        assumptions = AssumptionSet("v1", date(2026, 1, 1))
        self.assertEqual(assumptions.scenarios, ())
        self.assertEqual(assumptions.curves, {})

    def test_contract_construction(self) -> None:
        curve = Curve([Decimal("0")])
        contract = Contract("C1", 1, 1, curve, curve, curve, curve)
        self.assertEqual(contract.contract_id, "C1")


if __name__ == "__main__":
    unittest.main()
