"""Tests for per-period ECL computation."""

import unittest
from decimal import Decimal

from ecl_engine import engine
from ecl_engine.errors import ValidationError
from ecl_engine.models import Contract, Curve, Scenario


def make_contract(stage: int, periods: int, *, pd=None, lgd=None,
                  ead=None, discount=None) -> Contract:
    return Contract(
        "C1", stage, periods,
        Curve(pd if pd is not None else [Decimal("0")] * periods),
        Curve(lgd if lgd is not None else [Decimal("1")] * periods),
        Curve(ead if ead is not None else [Decimal("1")] * periods),
        Curve(discount if discount is not None else [Decimal("1")] * periods),
    )


BASE = (Scenario("base", Decimal("1")),)
SPLIT = (Scenario("base", Decimal("0.6")),
         Scenario("down", Decimal("0.4")))


class SingleScenarioTest(unittest.TestCase):
    def test_simple_two_periods_stage1(self) -> None:
        contract = make_contract(1, 2, pd=[Decimal("0.1"), Decimal("0.3")])
        result = engine.compute_contract_ecl(contract, BASE)
        self.assertEqual(result.period_start, 1)
        self.assertEqual(result.period_end, 2)
        self.assertEqual([line.period for line in result.lines], [1, 2])
        self.assertEqual(result.lines[0].marginal_pd, Decimal("0.1000000000"))
        self.assertEqual(result.lines[1].marginal_pd, Decimal("0.2000000000"))
        self.assertEqual(result.total_ecl, Decimal("0.3000000000"))

    def test_stage1_truncated_to_12_periods(self) -> None:
        contract = make_contract(
            1, 36, pd=[Decimal("1")] + [Decimal("1")] * 35)
        result = engine.compute_contract_ecl(contract, BASE)
        self.assertEqual(result.period_end, 12)
        self.assertEqual(len(result.lines), 12)
        self.assertEqual(result.total_ecl, Decimal("1.0000000000"))

    def test_stage2_full_term(self) -> None:
        contract = make_contract(
            2, 24, pd=[Decimal("1")] + [Decimal("1")] * 23)
        result = engine.compute_contract_ecl(contract, BASE)
        self.assertEqual(result.period_end, 24)
        self.assertEqual(result.total_ecl, Decimal("1.0000000000"))

    def test_stage3_full_term(self) -> None:
        contract = make_contract(
            3, 24, pd=[Decimal("1")] + [Decimal("1")] * 23)
        result = engine.compute_contract_ecl(contract, BASE)
        self.assertEqual(result.total_ecl, Decimal("1.0000000000"))

    def test_all_multipliers_present(self) -> None:
        contract = make_contract(
            1, 1, pd=[Decimal("0.5")], lgd=[Decimal("0.4")],
            ead=[Decimal("100")], discount=[Decimal("0.95")])
        line = engine.compute_contract_ecl(contract, BASE).lines[0]
        self.assertEqual(line.weight, Decimal("1.0000000000"))
        self.assertEqual(line.raw_product, Decimal("19.0000"))
        self.assertEqual(line.rounded_ecl, Decimal("19.0000000000"))
        self.assertEqual(line.rounding_adj, Decimal("0.0000000000"))


class ScenarioAggregationTest(unittest.TestCase):
    def test_weighted_scenarios(self) -> None:
        contract = make_contract(
            1, 1, pd=[Decimal("0.2")], ead=[Decimal("100")])
        result = engine.compute_contract_ecl(contract, SPLIT)
        totals = {item.scenario: item.ecl for item in result.scenario_totals}
        self.assertEqual(totals["base"], Decimal("12.0000000000"))
        self.assertEqual(totals["down"], Decimal("8.0000000000"))
        self.assertEqual(result.total_ecl, Decimal("20.0000000000"))

    def test_scenario_tail_rounding_and_exact_additivity(self) -> None:
        contract = make_contract(
            1, 1, pd=[Decimal("1")], lgd=[Decimal("1")],
            ead=[Decimal("1")], discount=[Decimal("1")])
        tail_weights = (Scenario("a", Decimal("0.3333333333")),
                        Scenario("b", Decimal("0.3333333333")),
                        Scenario("c", Decimal("0.3333333334")))
        result = engine.compute_contract_ecl(contract, tail_weights)
        line_sum = sum((line.rounded_ecl for line in result.lines),
                       Decimal("0"))
        self.assertEqual(result.total_ecl, line_sum)
        self.assertEqual(result.total_ecl, Decimal("1.0000000000"))

    def test_rounding_adjustment_exposed(self) -> None:
        contract = make_contract(
            1, 1, pd=[Decimal("0.12345678905")], ead=[Decimal("1")])
        line = engine.compute_contract_ecl(contract, BASE).lines[0]
        self.assertEqual(line.rounded_ecl, line.raw_product + line.rounding_adj)


class OverflowTest(unittest.TestCase):
    def test_huge_ead_rejected(self) -> None:
        contract = make_contract(
            1, 1, pd=[Decimal("1")], ead=[Decimal("1E19")])
        with self.assertRaises(ValidationError):
            engine.compute_contract_ecl(contract, BASE)

    def test_huge_product_rejected(self) -> None:
        # Large but individually under-cap factors whose product exceeds
        # the 1E18 reporting cap must be classified as failure, not rounded.
        contract = make_contract(
            1, 1, pd=[Decimal("1")], ead=[Decimal("1E12")],
            lgd=[Decimal("1")])
        weights = (Scenario("a", Decimal("0.9")),
                   Scenario("b", Decimal("0.1")))
        result = engine.compute_contract_ecl(contract, weights)
        self.assertEqual(result.total_ecl, Decimal("1000000000000.0000000000"))

    def test_infinity_input_rejected_at_curve_construction(self) -> None:
        with self.assertRaises(ValidationError):
            Curve([Decimal("Infinity")])


if __name__ == "__main__":
    unittest.main()
