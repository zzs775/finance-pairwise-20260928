"""Tests for the direct term-expansion reference enumerator."""

import unittest
from decimal import Decimal

from ecl_engine import engine, reference
from ecl_engine.models import Contract, Curve, Scenario


def make_contract(stage: int, periods: int) -> Contract:
    pd_values = [Decimal("0")] * periods
    for i in range(periods):
        pd_values[i] = Decimal((i + 1)) / Decimal(100)
    one = [Decimal("1")] * periods
    return Contract(
        "R1", stage, periods, Curve(pd_values), Curve(one),
        Curve(one), Curve(one))


SCENARIOS = (Scenario("base", Decimal("0.7")),
             Scenario("down", Decimal("0.3")))


class ReferenceEnumeratorTest(unittest.TestCase):
    def test_explicit_marginals(self) -> None:
        records = reference.enumerate_reference(make_contract(2, 3),
                                                SCENARIOS)
        base = [r for r in records if r["scenario"] == "base"]
        self.assertEqual([r["marginal_pd"] for r in base], [
            Decimal("0.0100000000"),
            Decimal("0.0100000000"),
            Decimal("0.0100000000"),
        ])
        self.assertTrue(all(r["in_loss_window"] for r in base))

    def test_stage1_window_flags(self) -> None:
        records = reference.enumerate_reference(make_contract(1, 24),
                                                (Scenario("base", Decimal("1")),))
        flags = {(r["period"]): r["in_loss_window"] for r in records}
        self.assertTrue(all(flags[p] for p in range(1, 13)))
        self.assertFalse(any(flags[p] for p in range(13, 25)))

    def test_matches_engine_for_all_stages(self) -> None:
        for stage in (1, 2, 3):
            for periods in (1, 8, 13, 36):
                contract = make_contract(stage, periods)
                engine_result = engine.compute_contract_ecl(
                    contract, SCENARIOS)
                self.assertEqual(
                    reference.reference_total(contract, SCENARIOS),
                    engine_result.total_ecl,
                    msg=f"stage={stage} periods={periods}")

    def test_out_of_window_lines_are_zero(self) -> None:
        records = reference.enumerate_reference(make_contract(1, 24),
                                                SCENARIOS)
        tail = [r for r in records if r["period"] > 12]
        self.assertTrue(all(r["ecl"] == Decimal("0.0000000000") for r in tail))


if __name__ == "__main__":
    unittest.main()
