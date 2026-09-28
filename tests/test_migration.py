"""End-to-end checks that stage migration loses or repeats no period."""

import unittest
from decimal import Decimal

from ecl_engine import curves, engine, stages
from ecl_engine.models import Contract, Curve, Scenario


SCENARIOS = (Scenario("base", Decimal("0.65")),
             Scenario("adverse", Decimal("0.35")))


def make_contract(stage: int, periods: int) -> Contract:
    cumulative = [
        Decimal(1) - (Decimal(1) / Decimal(2)) ** i
        for i in range(1, periods + 1)
    ]
    one = [Decimal("1")] * periods
    return Contract("M1", stage, periods, Curve(cumulative), Curve(one),
                    Curve(one), Curve(one))


class MigrationConsistencyTest(unittest.TestCase):
    def test_partition_covers_each_period_once(self) -> None:
        for periods in (1, 11, 12, 13, 24, 60):
            for old_stage, new_stage in ((1, 2), (2, 1), (1, 3), (2, 3)):
                ranges = stages.migrated_period_ranges(
                    old_stage, new_stage, periods)
                covered = [p for start, end in ranges
                           for p in range(start, end + 1)]
                self.assertEqual(
                    covered, list(range(1, periods + 1)),
                    msg=f"{old_stage}->{new_stage} n={periods}")

    def test_window_sum_equals_sum_of_partitioned_marginals(self) -> None:
        periods = 24
        contract_stage1 = make_contract(1, periods)
        contract_stage2 = make_contract(2, periods)
        marginals = curves.marginal_pd(contract_stage1.cumulative_pd.values)

        ecl1 = engine.compute_contract_ecl(contract_stage1, SCENARIOS)
        ecl2 = engine.compute_contract_ecl(contract_stage2, SCENARIOS)

        tail_marginal = sum(curves.slice_periods(marginals, 13, 24),
                            Decimal("0"))
        # Recompute after stage 1 -> stage 2: previously excluded tail
        # periods now contribute; totals differ by exactly the tail ECL.
        difference = ecl2.total_ecl - ecl1.total_ecl
        expected_tail = tail_marginal  # LGD/EAD/discount all 1
        self.assertEqual(difference, expected_tail)

    def test_no_overlap_lines_between_old_and_new_windows(self) -> None:
        ranges = stages.migrated_period_ranges(1, 2, 36)
        near, far = ranges
        self.assertEqual(near, (1, 12))
        self.assertEqual(far, (13, 36))
        self.assertEqual(near[1] + 1, far[0])


if __name__ == "__main__":
    unittest.main()
