import json
import unittest
from decimal import Decimal

from abs_waterfall.engine import run
from abs_waterfall.oracle import assert_cash_closure, assert_loss_closure
from abs_waterfall.report import to_canonical_json

from tests.fixtures import build_config, dlq_trigger, period


class EngineTest(unittest.TestCase):
    def test_multi_period_continuity_and_closure(self) -> None:
        config = build_config(trigger=dlq_trigger())
        periods = [
            period(0, pool="1000", interest="60", principal="100"),
            period(1, pool="900", interest="50", principal="120",
                   losses="30", dlq="0.60"),
            period(2, pool="750", interest="40", principal="80",
                   losses="10", dlq="0.20"),
        ]
        result = run(config, periods)
        self.assertEqual([p.period_index for p in result.periods], [0, 1, 2])
        profiles = [p.profile_id for p in result.periods]
        self.assertEqual(profiles, ["normal", "lockout", "normal"])
        for period_input, period_result in zip(periods, result.periods):
            assert_cash_closure(config, period_input, period_result)
            assert_loss_closure(config, period_input, period_result)
        # continuity: period 1 begins exactly where period 0 ended
        p0_end = result.periods[0].ending_state
        p1_begin = result.periods[1].beginning_state
        self.assertEqual(
            p0_end.tranche_balances, p1_begin.tranche_balances
        )
        self.assertEqual(p0_end.reserve_cash, p1_begin.reserve_cash)

    def test_input_reordering_gives_identical_canonical_report(self) -> None:
        config = build_config(trigger=dlq_trigger())
        periods = [
            period(1, pool="900", interest="50", principal="120",
                   losses="30", dlq="0.60"),
            period(0, pool="1000", interest="60", principal="100"),
        ]
        first = to_canonical_json(run(config, list(reversed(periods))))
        second = to_canonical_json(run(config, periods))
        self.assertEqual(first, second)
        parsed = json.loads(first)
        self.assertEqual(len(parsed["periods"]), 2)


if __name__ == "__main__":
    unittest.main()
