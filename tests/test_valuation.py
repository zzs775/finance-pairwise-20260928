"""Tests for version-bound valuation runs."""

import unittest
from datetime import date
from decimal import Decimal

from ecl_engine import engine
from ecl_engine.errors import ValidationError
from ecl_engine.models import AssumptionSet, Contract, Curve, Scenario
from ecl_engine.valuation import ValuationRun, affected_contract_ids


def make_contract(contract_id: str, pd_values) -> Contract:
    periods = len(pd_values)
    one = [Decimal("1")] * periods
    return Contract(
        contract_id, 2, periods,
        Curve(pd_values), Curve(one), Curve(one), Curve(one))


def make_assumptions(version: str = "v1", weights=(Decimal("1"),)):
    return AssumptionSet(
        version, date(2026, 1, 1),
        scenarios=tuple(Scenario(f"s{i}", w)
                        for i, w in enumerate(weights)))


class ValuationRunTest(unittest.TestCase):
    def setUp(self) -> None:
        self.contracts = [
            make_contract("A", [Decimal("0.1"), Decimal("0.2")]),
            make_contract("B", [Decimal("0.5")]),
        ]

    def test_run_binds_version_and_publishes(self) -> None:
        run = ValuationRun(make_assumptions("v1"))
        results = run.run(self.contracts)
        self.assertEqual(run.published_version, "v1")
        self.assertEqual(results["A"].total_ecl, Decimal("0.2000000000"))
        self.assertEqual(results["B"].total_ecl, Decimal("0.5000000000"))

    def test_weight_revision_recomputes_all(self) -> None:
        run = ValuationRun(make_assumptions("v1"))
        run.run(self.contracts)
        revised = make_assumptions(
            "v2", weights=(Decimal("0.6"), Decimal("0.4")))
        affected = affected_contract_ids(run.assumptions, revised)
        self.assertEqual(affected, set())
        results = run.revise(revised, self.contracts)
        self.assertEqual(run.published_version, "v1")  # binding stays
        self.assertEqual(run.assumptions.version, "v2")
        self.assertEqual(results["A"].total_ecl,
                         Decimal("0.2000000000"))  # weights sum to 1

    def test_failed_revision_keeps_published_results(self) -> None:
        run = ValuationRun(make_assumptions("v1"))
        before = run.run(self.contracts)
        bad = make_assumptions("v2", weights=(Decimal("0.5"),))
        with self.assertRaises(ValidationError):
            run.revise(bad, self.contracts)
        self.assertEqual(run.assumptions.version, "v1")
        self.assertEqual(run.results(), before)

    def test_pd_revision_only_changes_listed_contract(self) -> None:
        v1 = make_assumptions("v1")
        run = ValuationRun(v1)
        run.run(self.contracts)
        changed_b = make_contract("B", [Decimal("0.9")])
        v2 = AssumptionSet(
            "v2", date(2026, 7, 1),
            scenarios=v1.scenarios,
            curves={"B": {"cumulative_pd": Curve([Decimal("0.9")])}})
        affected = affected_contract_ids(v1, v2)
        self.assertEqual(affected, {"B"})
        contracts = [self.contracts[0], changed_b]
        results = run.revise(v2, contracts)
        self.assertEqual(results["A"].total_ecl, Decimal("0.2000000000"))
        self.assertEqual(results["B"].total_ecl, Decimal("0.9000000000"))


if __name__ == "__main__":
    unittest.main()
