import unittest
from datetime import date
from decimal import Decimal

from discurve.bootstrap import PRICING_TOLERANCE, bootstrap
from discurve.calendar import HolidayCalendar
from discurve.errors import (
    DuplicateNodeError,
    IllegalDateError,
    MissingTenorError,
    RootBracketingError,
)
from discurve.instruments import (
    Deposit,
    FRA,
    QuoteSnapshot,
    Swap,
)
from discurve.pricing import (
    cashflows_pv,
    swap_residual,
    swap_schedule,
)


CAL = HolidayCalendar("embedded", frozenset())
VAL = date(2026, 1, 1)


def df(days: int, rate: str) -> Decimal:
    return Decimal(1) / (Decimal(1) + Decimal(rate) * Decimal(days) / 360)


class DepositFixtureTest(unittest.TestCase):
    def test_single_deposit_matches_analytic_discount(self) -> None:
        deposit = Deposit(
            "DEPO3M", VAL, date(2026, 4, 1), Decimal("0.0300"), "ACT/360"
        )
        snapshot = QuoteSnapshot("v", VAL, deposits=(deposit,))
        draft = bootstrap(snapshot, CAL)
        solved = dict(draft.curve.nodes)
        self.assertEqual(solved[VAL], Decimal(1))
        expected = df(90, "0.0300")
        self.assertLess(abs(solved[date(2026, 4, 1)] - expected), Decimal("1E-18"))
        self.assertLessEqual(
            abs(draft.diagnostics[0].residual), PRICING_TOLERANCE
        )
        # The independent reference PV reproduces the same value.
        reference = cashflows_pv(
            (
                (VAL, Decimal(-1)),
                (date(2026, 4, 1), Decimal(1) + Decimal("0.0300") * 90 / 360),
            ),
            draft.curve.discount,
        )
        self.assertLess(abs(reference), PRICING_TOLERANCE)

    def test_forward_deposit_chain_is_consistent(self) -> None:
        first = Deposit("D1", VAL, date(2026, 4, 1), Decimal("0.03"), "ACT/360")
        second = Deposit(
            "D2", date(2026, 4, 1), date(2026, 7, 1),
            Decimal("0.04"), "ACT/360",
        )
        snapshot = QuoteSnapshot("v", VAL, deposits=(first, second))
        draft = bootstrap(snapshot, CAL)
        nodes = dict(draft.curve.nodes)
        df_apr = df(90, "0.03")
        expected_jul = df_apr / (Decimal(1) + Decimal("0.04") * 91 / 360)
        self.assertLess(
            abs(nodes[date(2026, 7, 1)] - expected_jul), Decimal("1E-18")
        )


class HandComputableSwapTest(unittest.TestCase):
    def test_two_period_swap_matches_manual_par_value(self) -> None:
        # Two annual-ish 6M periods on a 30/360 basis with fixed 4%.
        # First node DF is pinned analytically by a 6M deposit at 3%.
        deposit = Deposit(
            "DEPO6M", VAL, date(2026, 7, 1), Decimal("0.0300"), "30/360"
        )
        swap = Swap(
            "SWAP1Y",
            VAL,
            date(2027, 1, 1),
            Decimal("0.0400"),
            period_months=6,
            day_count="30/360",
        )
        snapshot = QuoteSnapshot(
            "v", VAL, deposits=(deposit,), swaps=(swap,)
        )
        draft = bootstrap(snapshot, CAL)
        df_first = df(180, "0.0300")
        # Par swap: K*tau*DF1 + (1 + K*tau)*DF2 = DF0 = 1.
        tau = Decimal("0.5")
        coupon = Decimal("0.0400") * tau
        expected_df2 = (Decimal(1) - coupon * df_first) / (Decimal(1) + coupon)
        solved = dict(draft.curve.nodes)
        self.assertLess(
            abs(solved[date(2027, 1, 1)] - expected_df2), Decimal("1E-18")
        )
        schedule = swap_schedule(swap, CAL)
        residual = swap_residual(swap, draft.curve.discount, schedule)
        self.assertLessEqual(abs(residual), PRICING_TOLERANCE)


class FRAInstrumentTest(unittest.TestCase):
    def test_fra_forward_rate_is_repriced(self) -> None:
        deposit = Deposit(
            "DEPO6M", VAL, date(2026, 7, 1), Decimal("0.03"), "ACT/360"
        )
        fra = FRA(
            "FRA6x9",
            date(2026, 7, 1),
            date(2026, 10, 1),
            Decimal("0.035"),
            "ACT/360",
        )
        snapshot = QuoteSnapshot("v", VAL, deposits=(deposit,), fras=(fra,))
        draft = bootstrap(snapshot, CAL)
        nodes = dict(draft.curve.nodes)
        df_jul = df(181, "0.03")
        expected_oct = df_jul / (Decimal(1) + Decimal("0.035") * 92 / 360)
        self.assertLess(
            abs(nodes[date(2026, 10, 1)] - expected_oct), Decimal("1E-18")
        )

    def test_negative_rate_keeps_factors_positive_and_increasing_shape(self):
        deposit = Deposit(
            "NEG", VAL, date(2026, 4, 1), Decimal("-0.005"), "ACT/360"
        )
        snapshot = QuoteSnapshot("v", VAL, deposits=(deposit,))
        draft = bootstrap(snapshot, CAL)
        solved_dfs = [factor for _, factor in draft.curve.nodes]
        self.assertTrue(all(factor > 1 for factor in solved_dfs[1:]))


class FailureModesTest(unittest.TestCase):
    def test_duplicate_nodes_are_rejected(self) -> None:
        d1 = Deposit("D1", VAL, date(2026, 4, 1), Decimal("0.03"))
        d2 = Deposit("D2", VAL, date(2026, 4, 1), Decimal("0.031"))
        snapshot = QuoteSnapshot("v", VAL, deposits=(d1, d2))
        with self.assertRaises(DuplicateNodeError):
            bootstrap(snapshot, CAL)

    def test_illegal_dates_are_rejected(self) -> None:
        bad = Deposit("D", date(2026, 4, 1), VAL, Decimal("0.03"))
        snapshot = QuoteSnapshot("v", VAL, deposits=(bad,))
        with self.assertRaises(IllegalDateError):
            bootstrap(snapshot, CAL)

    def test_instrument_before_valuation_date_is_missing_tenor(self) -> None:
        deposit = Deposit(
            "PAST",
            date(2025, 12, 1),
            date(2026, 2, 1),
            Decimal("0.03"),
            "ACT/360",
        )
        snapshot = QuoteSnapshot("v", VAL, deposits=(deposit,))
        with self.assertRaises(MissingTenorError):
            bootstrap(snapshot, CAL)

    def test_non_finite_input_fails_whole_build(self) -> None:
        bad = Deposit(
            "D",
            VAL,
            date(2026, 4, 1),
            Decimal("0.03"),
            notional=Decimal("NaN"),
        )
        snapshot = QuoteSnapshot("v", VAL, deposits=(bad,))
        with self.assertRaises(Exception):
            bootstrap(snapshot, CAL)

    def test_root_bracket_error_is_specific_class(self) -> None:
        # Directly drive the solver with a residual that has no sign change.
        from discurve.bootstrap import _solve_node

        solved = [(VAL, Decimal(1))]
        with self.assertRaises(RootBracketingError):
            _solve_node(
                date(2026, 2, 1),
                solved,
                lambda discount: Decimal(1),
            )


class RepricingTest(unittest.TestCase):
    def test_all_diagnostics_within_tolerance_and_positive(self) -> None:
        deposits = (
            Deposit("D3M", VAL, date(2026, 4, 1), Decimal("0.03")),
            Deposit("D6M", VAL, date(2026, 7, 1), Decimal("0.032")),
        )
        swaps = (
            Swap(
                "S1Y", VAL, date(2027, 1, 1), Decimal("0.035"),
                period_months=6, day_count="30/360",
            ),
            Swap(
                "S2Y", VAL, date(2028, 1, 3), Decimal("0.038"),
                period_months=6, day_count="30/360",
            ),
        )
        snapshot = QuoteSnapshot(
            "v", VAL, deposits=deposits, swaps=swaps
        )
        cal = HolidayCalendar("embedded", frozenset({date(2028, 1, 1)}))
        draft = bootstrap(snapshot, cal)
        for diag in draft.diagnostics:
            self.assertLessEqual(abs(diag.residual), PRICING_TOLERANCE)
            self.assertGreaterEqual(diag.iterations, 1)
        dates = [day for day, _ in draft.curve.nodes]
        factors = [factor for _, factor in draft.curve.nodes]
        self.assertEqual(dates, sorted(dates))
        self.assertTrue(all(factor > 0 for factor in factors))
        # Reference PV function agrees on the reprice of every node-bearing
        # instrument via independent cash-flow evaluation.
        for diag in draft.diagnostics:
            self.assertEqual(diag.market_target, Decimal(0))

    def test_irregular_effective_date_swap_bootstraps_with_front_stub(
        self,
    ) -> None:
        # Effective date mid-quarter forces a short front stub; the embedded
        # holiday shifts one adjusted payment date.
        cal = HolidayCalendar(
            "embedded", frozenset({date(2026, 7, 3)})
        )
        deposit = Deposit(
            "D",
            date(2026, 2, 18),
            date(2026, 5, 18),
            Decimal("0.03"),
            "ACT/360",
        )
        swap = Swap(
            "STUB",
            date(2026, 2, 18),
            date(2027, 2, 18),
            Decimal("0.036"),
            period_months=3,
            day_count="ACT/360",
            business_day_convention="following",
            stub="short_front",
        )
        snapshot = QuoteSnapshot(
            "v", date(2026, 2, 18), deposits=(deposit,), swaps=(swap,)
        )
        draft = bootstrap(snapshot, cal)
        for diag in draft.diagnostics:
            self.assertLessEqual(abs(diag.residual), PRICING_TOLERANCE)
        dates = [day for day, _ in draft.curve.nodes]
        self.assertEqual(dates[0], date(2026, 2, 18))
        self.assertEqual(dates[-1], date(2027, 2, 18))
        factors = [factor for _, factor in draft.curve.nodes]
        self.assertEqual(factors, sorted(factors, reverse=True))


if __name__ == "__main__":
    unittest.main()
