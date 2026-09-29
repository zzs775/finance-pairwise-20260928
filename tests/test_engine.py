"""Automated tests for the TWR engine (standard-library ``unittest``)."""

from __future__ import annotations

import unittest
from datetime import date
from decimal import Decimal

from twr_engine.account import compute_account, link_geometric
from twr_engine.composite import aggregate_composites
from twr_engine.data import make_sample_input
from twr_engine.engine import run
from twr_engine.errors import (
    CapitalStatus,
    DuplicateValuationError,
    InvertedMembershipError,
    UnattributedCashFlowError,
)
from twr_engine.intervals import build_all_sub_periods
from twr_engine.jsonio import dumps
from twr_engine.models import (
    Account,
    CashFlow,
    CashFlowTiming,
    EngineInput,
    Membership,
    Valuation,
)
from twr_engine.normalize import normalize_input
from twr_engine.oracle import oracle_twr, recompute_composite, verify_account
from twr_engine.query import query_return, query_status

D = Decimal
DAY1 = date(2026, 1, 1)
DAY2 = date(2026, 1, 2)
DAY3 = date(2026, 1, 3)


class SameDayEventsTest(unittest.TestCase):
    def test_opening_and_closing_flows_split_one_day(self) -> None:
        inp = EngineInput(
            accounts=[Account("a")],
            valuations=[
                Valuation("a", DAY1, D("100"), D("110")),
                Valuation("a", DAY2, D("300"), D("200")),
            ],
            cash_flows=[
                CashFlow("a", DAY2, D("100"), CashFlowTiming.OPEN),
                CashFlow("a", DAY2, D("-110"), CashFlowTiming.CLOSE),
            ],
        )
        normalized = normalize_input(inp)
        periods = build_all_sub_periods(normalized)["a"]

        self.assertEqual(len(periods), 2)
        first, second = periods
        self.assertEqual((first.start_day, first.end_day), (DAY1, DAY1))
        self.assertEqual(first.begin_capital, D("100"))
        self.assertEqual(first.pnl, D("10"))
        self.assertEqual(first.rate, D("0.10"))

        self.assertEqual((second.start_day, second.end_day), (DAY2, DAY2))
        self.assertEqual(second.begin_capital, D("400"))
        self.assertEqual(second.external_flow, D("-10"))
        self.assertEqual(second.pnl, D("-90"))
        self.assertEqual(second.rate, D("-90") / D("400"))

        result = compute_account("a", periods)
        self.assertEqual(result.status, CapitalStatus.OK.value)
        self.assertEqual(
            result.twr,
            link_geometric([D("0.10"), D("-90") / D("400")]),
        )


class FullRedemptionTest(unittest.TestCase):
    def test_redeem_to_zero_then_followup_day_is_zero_base(self) -> None:
        inp = EngineInput(
            accounts=[Account("a")],
            valuations=[
                Valuation("a", DAY1, D("100"), D("0")),
                Valuation("a", DAY2, D("0"), D("0")),
            ],
            cash_flows=[
                CashFlow("a", DAY1, D("-110"), CashFlowTiming.CLOSE),
            ],
        )
        normalized = normalize_input(inp)
        periods = build_all_sub_periods(normalized)["a"]

        self.assertEqual(len(periods), 2)
        self.assertEqual(periods[0].rate, D("0.10"))
        self.assertEqual(periods[1].begin_capital, D("0"))
        self.assertIsNone(periods[1].rate)
        self.assertEqual(periods[1].status, CapitalStatus.ZERO_BASE.value)

        result = compute_account("a", periods)
        self.assertEqual(result.status, CapitalStatus.ZERO_BASE.value)
        self.assertIsNone(result.twr)


class NegativeCapitalTest(unittest.TestCase):
    def test_negative_begin_capital_is_explicit_status(self) -> None:
        inp = EngineInput(
            accounts=[Account("a")],
            valuations=[
                Valuation("a", DAY1, D("10"), D("10")),
                Valuation("a", DAY2, D("-2"), D("-5")),
            ],
            cash_flows=[
                CashFlow("a", DAY1, D("-12"), CashFlowTiming.CLOSE),
            ],
        )
        normalized = normalize_input(inp)
        periods = build_all_sub_periods(normalized)["a"]

        self.assertEqual(periods[-1].begin_capital, D("-2"))
        self.assertEqual(periods[-1].status,
                         CapitalStatus.NEGATIVE_BASE.value)
        self.assertIsNone(periods[-1].rate)

        result = compute_account("a", periods)
        self.assertEqual(result.status, CapitalStatus.NEGATIVE_BASE.value)
        self.assertIsNone(result.twr)


class MemberSwitchTest(unittest.TestCase):
    def test_membership_change_does_not_rewrite_prior_composite(self) -> None:
        inp = EngineInput(
            accounts=[Account("a"), Account("b")],
            valuations=[
                Valuation("a", DAY1, D("100"), D("110")),
                Valuation("a", DAY2, D("110"), D("121")),
                Valuation("b", DAY1, D("100"), D("200")),
                Valuation("b", DAY2, D("200"), D("220")),
            ],
            memberships=[
                Membership("c", "a", DAY1, DAY2),
                Membership("c", "b", DAY2, DAY2),
            ],
        )
        normalized = normalize_input(inp)
        account_periods = build_all_sub_periods(normalized)
        composites = aggregate_composites(normalized, account_periods)

        result = composites["c"]
        self.assertEqual(len(result.sub_periods), 2)
        first, second = result.sub_periods
        self.assertEqual(first.start_day, DAY1)
        self.assertEqual(first.weights, {"a": D("1")})
        self.assertEqual(first.rate, D("0.10"))
        self.assertEqual(second.weights["b"], D("200") / D("310"))
        self.assertEqual(
            result.twr,
            (D("1") + first.rate) * (D("1") + second.rate) - D("1"),
        )


class DuplicateInputTest(unittest.TestCase):
    def test_exact_duplicates_collapse_and_conflict_raises(self) -> None:
        valuation = Valuation("a", DAY1, D("100"), D("101"))
        flow = CashFlow("a", DAY2, D("10"), CashFlowTiming.OPEN)
        base = EngineInput(
            accounts=[Account("a"), Account("a")],
            valuations=[valuation, valuation],
            cash_flows=[flow, flow],
        )
        once = normalize_input(base)
        twice = normalize_input(once)
        self.assertEqual(once, twice)
        self.assertEqual(len(once.valuations), 1)
        self.assertEqual(len(once.cash_flows), 1)
        self.assertEqual(len(once.accounts), 1)

        conflicting = EngineInput(
            accounts=[Account("a")],
            valuations=[
                Valuation("a", DAY1, D("100"), D("101")),
                Valuation("a", DAY1, D("100"), D("102")),
            ],
        )
        with self.assertRaises(DuplicateValuationError):
            normalize_input(conflicting)

    def test_unattributed_flow_and_inverted_interval(self) -> None:
        with self.assertRaises(UnattributedCashFlowError):
            normalize_input(
                EngineInput(
                    accounts=[],
                    cash_flows=[
                        CashFlow("ghost", DAY1, D("1"),
                                 CashFlowTiming.OPEN)
                    ],
                )
            )
        with self.assertRaises(InvertedMembershipError):
            normalize_input(
                EngineInput(
                    accounts=[Account("a")],
                    memberships=[
                        Membership("c", "a", DAY2, DAY1),
                    ],
                )
            )


class DecimalTailTest(unittest.TestCase):
    def test_decimal_tail_is_preserved_and_canonicalized(self) -> None:
        inp = EngineInput(
            accounts=[Account("a")],
            valuations=[
                Valuation("a", DAY1, D("100.000"), D("100.100")),
                Valuation("a", DAY2, D("100.100"), D("100.301")),
            ],
        )
        output = run(inp)
        result = output.accounts["a"]
        expected = (D("100.100") / D("100.000")) * (
            D("100.301") / D("100.100")
        ) - D("1")
        self.assertEqual(result.twr, expected)
        self.assertNotIn("E", dumps(result.twr))

    def test_geometric_link_no_binary_float_artifacts(self) -> None:
        rates = [D("0.1"), D("0.2"), D("-0.05")]
        expected = D("1.1") * D("1.2") * D("0.95") - D("1")
        self.assertEqual(link_geometric(rates), expected)


class OracleTest(unittest.TestCase):
    def test_mechanical_oracle_identity(self) -> None:
        normalized = normalize_input(make_sample_input())
        account_periods = build_all_sub_periods(normalized)

        for account_id, periods in account_periods.items():
            result = compute_account(account_id, periods)
            self.assertTrue(verify_account(result))
            if result.status == CapitalStatus.OK.value:
                rates = [period.rate for period in periods]
                product = D("1")
                for rate in rates:
                    product *= D("1") + rate
                self.assertEqual(D("1") + result.twr, product)
                self.assertEqual(result.twr, oracle_twr(rates))

    def test_zero_return_external_flow_leaves_twr_unchanged(self) -> None:
        base = EngineInput(
            accounts=[Account("a")],
            valuations=[
                Valuation("a", DAY1, D("100"), D("110")),
                Valuation("a", DAY3, D("110"), D("121")),
            ],
        )
        baseline = run(base).accounts["a"].twr
        self.assertEqual(baseline, D("1.1") * D("121") / D("110") - D("1"))

        with_flow = EngineInput(
            accounts=[Account("a")],
            valuations=[
                Valuation("a", DAY1, D("100"), D("110")),
                Valuation("a", DAY2, D("110"), D("100")),
                Valuation("a", DAY3, D("100"), D("110")),
            ],
            cash_flows=[
                CashFlow("a", DAY2, D("100"), CashFlowTiming.OPEN),
                CashFlow("a", DAY2, D("-110"), CashFlowTiming.CLOSE),
            ],
        )
        flowed_result = run(with_flow).accounts["a"]
        flowed = flowed_result.twr
        self.assertEqual(baseline, flowed)

    def test_composite_recomputable_from_account_detail(self) -> None:
        normalized = normalize_input(make_sample_input())
        account_periods = build_all_sub_periods(normalized)
        composites = aggregate_composites(normalized, account_periods)
        rebuilt = recompute_composite(normalized)

        for composite_id, result in composites.items():
            oracle_segments = rebuilt[composite_id]["segments"]
            self.assertEqual(len(result.sub_periods), len(oracle_segments))
            for segment, oracle_segment in zip(
                result.sub_periods, oracle_segments
            ):
                self.assertEqual(segment.start_day,
                                 oracle_segment["start_day"])
                self.assertEqual(segment.end_day,
                                 oracle_segment["end_day"])
                self.assertEqual(segment.rate, oracle_segment["rate"])
                self.assertEqual(segment.weights, oracle_segment["weights"])
            self.assertEqual(result.twr, rebuilt[composite_id]["twr"])


class DeterminismTest(unittest.TestCase):
    def test_account_order_and_chunking_do_not_change_output(self) -> None:
        first = run(make_sample_input())

        reordered = EngineInput(
            valuations=list(reversed(first.normalized.valuations)),
            cash_flows=list(reversed(first.normalized.cash_flows)),
            accounts=list(reversed(first.normalized.accounts)),
            memberships=list(reversed(first.normalized.memberships)),
        )
        second = run(reordered)

        self.assertEqual(dumps(first.accounts), dumps(second.accounts))
        self.assertEqual(dumps(first.composites), dumps(second.composites))

        chunks = EngineInput(
            valuations=(
                first.normalized.valuations[:3]
                + first.normalized.valuations[3:]
            ),
            cash_flows=(
                first.normalized.cash_flows[:1]
                + first.normalized.cash_flows[1:]
            ),
            accounts=first.normalized.accounts,
            memberships=first.normalized.memberships,
        )
        third = run(chunks)
        self.assertEqual(dumps(first.accounts), dumps(third.accounts))
        self.assertEqual(dumps(first.composites), dumps(third.composites))


class QueryTest(unittest.TestCase):
    def test_range_query_links_overlapping_sub_periods(self) -> None:
        output = run(make_sample_input())
        periods = output.accounts["alpha"].sub_periods

        start = date(2026, 1, 5)
        end = date(2026, 1, 8)
        full = query_return(periods, start, end)
        expected = link_geometric([period.rate for period in periods
                                   if period.end_day <= end])
        self.assertEqual(full, expected)

        status = query_status(
            output.accounts["beta"].sub_periods, start, end
        )
        self.assertEqual(status, CapitalStatus.ZERO_BASE.value)


if __name__ == "__main__":
    unittest.main()
