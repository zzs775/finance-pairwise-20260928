import unittest

from ecl_engine.engine import price_contract
from ecl_engine.models import Contract, Stage
from tests.test_engine import make_assumptions, make_inputs
from ecl_engine.scales import D


class RuleBookTest(unittest.TestCase):
    def _assumptions(self, periods: int):
        return make_assumptions(
            make_inputs(
                "C1",
                [f"{min(0.01 * p, 1.0):.2f}" for p in range(1, periods + 1)],
                ["0.4"] * periods,
                ["1000"] * periods,
                ["0.99"] * periods,
            )
        )

    def test_stage_migration_no_duplicate_no_missing_period(self) -> None:
        periods = 30
        assumptions = self._assumptions(periods)
        stage1 = price_contract(Contract("C1", Stage.STAGE1, periods), assumptions)
        stage2 = price_contract(Contract("C1", Stage.STAGE2, periods), assumptions)
        s1_periods = {line.period for line in stage1.lines}
        s2_periods = {line.period for line in stage2.lines}
        self.assertEqual(s1_periods, set(range(1, 13)))
        self.assertEqual(s2_periods, set(range(1, 31)))
        # Stage 1 -> Stage 2 migration adds periods 13..30 only;
        # periods 1..12 are priced from the new run, never double counted.
        migrated = s1_periods | s2_periods
        self.assertEqual(migrated, set(range(1, 31)))
        self.assertEqual(len(s2_periods), len(s2_periods | set()))
        for period in range(1, 13):
            before = next(
                line
                for line in stage1.lines
                if line.scenario == "base" and line.period == period
            )
            after = next(
                line
                for line in stage2.lines
                if line.scenario == "base" and line.period == period
            )
            self.assertEqual(before.marginal_pd, after.marginal_pd)

    def test_cumulative_pd_reaches_one(self) -> None:
        assumptions = make_assumptions(
            make_inputs(
                "C1",
                ["0.99999999", "1.00000000", "1.00000000"],
                ["0.4"] * 3,
                ["1000"] * 3,
                ["0.99"] * 3,
            )
        )
        priced = price_contract(Contract("C1", Stage.STAGE3, 3), assumptions)
        marginals = [
            line.marginal_pd
            for line in priced.lines
            if line.scenario == "base"
        ]
        self.assertEqual(marginals, [D("0.99999999"), D("0.00000001"), D("0")])
        self.assertEqual(sum(marginals, D("0")), D("1.00000000"))

    def test_cumulative_pd_zero_throughout(self) -> None:
        assumptions = make_assumptions(
            make_inputs(
                "C1",
                ["0", "0", "0"],
                ["0.4"] * 3,
                ["1000"] * 3,
                ["0.99"] * 3,
            )
        )
        priced = price_contract(Contract("C1", Stage.STAGE2, 3), assumptions)
        self.assertEqual(priced.total_ecl, D("0.00"))
        self.assertTrue(all(line.weighted_ecl == 0 for line in priced.lines))

    def test_stage1_truncation_below_12_periods(self) -> None:
        assumptions = self._assumptions(5)
        priced = price_contract(Contract("C1", Stage.STAGE1, 5), assumptions)
        periods = {line.period for line in priced.lines}
        self.assertEqual(periods, {1, 2, 3, 4, 5})

    def test_term_gap_beyond_coverage_fails(self) -> None:
        contract = Contract("C1", Stage.STAGE3, 6)
        assumptions = make_assumptions(
            make_inputs(
                "C1",
                ["0.01"] * 5,
                ["0.4"] * 5,
                ["1000"] * 5,
                ["0.99"] * 5,
            )
        )
        from ecl_engine.validation import ValidationError

        with self.assertRaises(ValidationError):
            price_contract(contract, assumptions)

    def test_huge_amounts_do_not_overflow(self) -> None:
        periods = 12
        assumptions = make_assumptions(
            make_inputs(
                "C1",
                ["1"] * periods,
                ["1"] * periods,
                ["9999999999999999999999.99"] * periods,
                ["1"] * periods,
            )
        )
        priced = price_contract(Contract("C1", Stage.STAGE1, periods), assumptions)
        # Decimal never overflows; exact line identity still holds.
        lines_sum = sum((line.weighted_ecl for line in priced.lines), D("0"))
        self.assertEqual(lines_sum, priced.total_ecl)
        self.assertGreater(priced.total_ecl, D("1e21"))

    def test_non_finite_input_classified_failure(self) -> None:
        from decimal import Decimal

        from ecl_engine.validation import validate_nonnegative_amounts

        for token in ("NaN", "Infinity", "-Infinity", "sNaN"):
            with self.subTest(token=token):
                with self.assertRaises(ValueError):
                    D(token)
                with self.assertRaises(ValueError):
                    validate_nonnegative_amounts([Decimal(token)])


if __name__ == "__main__":
    unittest.main()
