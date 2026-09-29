"""Automated tests (stdlib unittest, Decimal throughout)."""

from __future__ import annotations

import unittest
from decimal import Decimal

from life_reserve.cashflows import build_cashflows
from life_reserve.engine import rate_sensitivity, run_valuation, synthetic_table
from life_reserve.errors import ValidationError
from life_reserve.model import (
    ExpenseAssumptions,
    MortalityTable,
    Policy,
    Timing,
)
from life_reserve.oracle import run_oracle
from life_reserve.pricing import price
from life_reserve.probability import project_probabilities
from life_reserve.reserve import compute_reserves

D = Decimal
TOL = D("0.0001")


def make_policy(
    issue_age: int = 40,
    term: int = 3,
    rate: Decimal = D("0.03"),
    timing: Timing = Timing.BEGINNING,
    expenses: ExpenseAssumptions | None = None,
) -> Policy:
    return Policy(
        issue_age=issue_age,
        term=term,
        sum_assured=D("100000"),
        interest_rate=rate,
        timing=timing,
        expenses=expenses or ExpenseAssumptions(),
    )


def flat_table(issue_age: int, term: int, q: str = "0.01") -> MortalityTable:
    return MortalityTable(
        {age: D(q) for age in range(issue_age, issue_age + term)}
    )


class ProbabilityTests(unittest.TestCase):
    def test_probability_mass_each_year(self) -> None:
        table = flat_table(40, 4, "0.017")
        states = project_probabilities(make_policy(term=4), table)
        for state in states:
            self.assertEqual(state.death_prob + state.survive_prob, state.in_force)
        total = sum((s.death_prob for s in states), D(0)) + states[-1].survive_prob
        self.assertLessEqual(abs(total - D(1)), TOL)

    def test_first_year_death_probability(self) -> None:
        # Death in year 1 has probability q; survivors carry p.
        table = MortalityTable({40: D("0.1"), 41: D("0.2"), 42: D("0.3")})
        states = project_probabilities(make_policy(), table)
        self.assertEqual(states[0].in_force, D(1))
        self.assertEqual(states[0].death_prob, D("0.1"))
        self.assertEqual(states[0].survive_prob, D("0.9"))


class CashFlowOrderTests(unittest.TestCase):
    def test_premium_then_expense_then_benefit_signs_and_bases(self) -> None:
        policy = make_policy(
            expenses=ExpenseAssumptions(D("0.1"), D("5"), D("50"))
        )
        states = project_probabilities(policy, flat_table(40, 3))
        rows = build_cashflows(policy, states, D("100"))
        # premium base = full in-force cohort; benefit base = deaths only
        self.assertEqual(rows[0].premium, D(100))
        self.assertEqual(rows[0].deaths, D("0.01"))
        self.assertEqual(rows[0].benefit, D("1000"))
        # year 1 expense includes the initial loading; later years do not
        self.assertGreater(rows[0].expense, rows[1].expense)


class FirstYearDeathTests(unittest.TestCase):
    def test_certain_death_first_year(self) -> None:
        # q_40 = 1: every life dies in year 1, nothing survives.
        table = MortalityTable({40: D(1), 41: D(1), 42: D(1)})
        policy = make_policy()
        states = project_probabilities(policy, table)
        self.assertEqual(states[0].death_prob, D(1))
        self.assertEqual(states[0].survive_prob, D(0))
        self.assertEqual(states[1].in_force, D(0))
        result = price(policy, states)
        # one end-of-contract-or-start death benefit, no later premium value
        self.assertGreater(result.benefit_pv, D(0))
        reserves = compute_reserves(policy, states, result.gross_premium, TOL)
        self.assertEqual(reserves.diagnostics, [])
        terminal = {r.year: r for r in reserves.rows}[4]
        self.assertEqual(terminal.reserve_explicit, D(0))


class FinalYearDeathTests(unittest.TestCase):
    def test_death_only_in_final_year(self) -> None:
        table = MortalityTable({40: D(0), 41: D(0), 42: D(1)})
        policy = make_policy()
        states = project_probabilities(policy, table)
        self.assertEqual(states[0].death_prob, D(0))
        self.assertEqual(states[1].death_prob, D(0))
        self.assertEqual(states[2].in_force, D(1))
        self.assertEqual(states[2].death_prob, D(1))
        result = price(policy, states)
        self.assertGreater(result.benefit_pv, D(0))
        reserves = compute_reserves(policy, states, result.gross_premium, TOL)
        # terminal boundary reserve is zero
        last = reserves.rows[-1]
        self.assertEqual(last.year, 4)
        self.assertEqual(last.reserve_recursive, D(0))
        self.assertEqual(reserves.diagnostics, [])


class ZeroMortalityTests(unittest.TestCase):
    def test_zero_mortality_benefit_and_closure(self) -> None:
        policy = make_policy(
            expenses=ExpenseAssumptions(D("0.05"), D("10"), D("100"))
        )
        table = flat_table(40, 3, "0")
        states = project_probabilities(policy, table)
        for state in states:
            self.assertEqual(state.q, D(0))
            self.assertEqual(state.death_prob, D(0))
        result = price(policy, states)
        self.assertEqual(result.benefit_pv, D(0))
        gap = result.gross_premium_pv - result.expense_pv
        self.assertLessEqual(abs(gap), TOL)
        report = run_oracle(policy, table, run_valuation(policy, table, TOL), TOL)
        self.assertTrue(report.passed, [c.detail for c in report.checks if not c.passed])


class ExtremeRateTests(unittest.TestCase):
    def test_very_high_positive_rate(self) -> None:
        table = flat_table(40, 3)
        policy = make_policy(rate=D("9.99"))
        valuation = run_valuation(policy, table, TOL)
        self.assertGreater(valuation.pricing.benefit_pv, D(0))
        self.assertEqual(valuation.reserves.diagnostics, [])

    def test_negative_rate_above_minus_one(self) -> None:
        table = flat_table(40, 3)
        policy = make_policy(rate=D("-0.5"))
        valuation = run_valuation(policy, table, TOL)
        self.assertGreater(valuation.pricing.benefit_pv, D(0))

    def test_rate_at_or_below_minus_one_rejected(self) -> None:
        table = flat_table(40, 3)
        with self.assertRaises(ValidationError):
            make_policy(rate=D("-1")).validate(table)


class AgeCoverageTests(unittest.TestCase):
    def test_missing_age_rejected(self) -> None:
        table = MortalityTable({40: D("0.01")})
        with self.assertRaises(ValidationError):
            project_probabilities(make_policy(term=3), table)

    def test_probability_out_of_range_rejected(self) -> None:
        for bad in ("-0.01", "1.01"):
            with self.subTest(bad=bad):
                table = MortalityTable(
                    {40: D(bad), 41: D("0.01"), 42: D("0.01")}
                )
                with self.assertRaises(ValidationError):
                    project_probabilities(make_policy(), table)

    def test_bad_policy_shape_rejected(self) -> None:
        table = flat_table(40, 3)
        with self.assertRaises(ValidationError):
            make_policy(issue_age=-1).validate(table)
        with self.assertRaises(ValidationError):
            make_policy(term=0).validate(table)


class TimingSwitchTests(unittest.TestCase):
    def test_beginning_vs_end_discounting_and_reserves(self) -> None:
        table = flat_table(40, 3)
        beg = make_policy(timing=Timing.BEGINNING)
        end = make_policy(timing=Timing.END)
        pb = price(beg, project_probabilities(beg, table))
        pe = price(end, project_probabilities(end, table))
        # Same node amounts, shifted one year: end PV = v * beginning PV.
        v = D(1) / (D(1) + beg.interest_rate)
        self.assertLessEqual(abs(pe.benefit_pv - v * pb.benefit_pv), TOL)
        self.assertNotEqual(pb.benefit_pv, pe.benefit_pv)
        rb = compute_reserves(beg, project_probabilities(beg, table), pb.gross_premium, TOL)
        re = compute_reserves(end, project_probabilities(end, table), pe.gross_premium, TOL)
        for b, e in zip(rb.rows, re.rows):
            self.assertEqual(b.year, e.year)
            self.assertLessEqual(
                abs(b.reserve_recursive - e.reserve_recursive), TOL
            )
        self.assertEqual(rb.diagnostics, [])
        self.assertEqual(re.diagnostics, [])


class ReserveMethodTests(unittest.TestCase):
    def test_explicit_equals_recursive_and_terminal_zero(self) -> None:
        table = flat_table(40, 5, "0.013")
        policy = make_policy(
            term=5,
            expenses=ExpenseAssumptions(D("0.05"), D("10"), D("100")),
        )
        states = project_probabilities(policy, table)
        gross = price(policy, states).gross_premium
        result = compute_reserves(policy, states, gross, TOL)
        for row in result.rows[:-1]:
            self.assertLessEqual(
                abs(row.reserve_explicit - row.reserve_recursive), TOL
            )
        self.assertEqual(result.rows[-1].reserve_explicit, D(0))
        self.assertEqual(result.rows[-1].reserve_recursive, D(0))

    def test_mismatch_is_diagnostic_not_selected(self) -> None:
        # Decimal rounding is the only possible disagreement source; a
        # microscopic tolerance forces an honest diagnostic without choosing.
        table = MortalityTable(
            {40: D("0.01"), 41: D("0.02"), 42: D("0.03")}
        )
        policy = make_policy()
        states = project_probabilities(policy, table)
        gross = price(policy, states).gross_premium
        result = compute_reserves(policy, states, gross, D("1E-30"))
        self.assertTrue(result.diagnostics)
        # both values are still reported honestly; neither replaces the other
        for diag in result.diagnostics:
            self.assertGreater(diag.difference, D(0))
            self.assertGreater(diag.difference, diag.tolerance)


class EquivalenceAndSensitivityTests(unittest.TestCase):
    def test_premium_pv_equals_benefit_plus_expense_pv(self) -> None:
        policy = make_policy(
            expenses=ExpenseAssumptions(D("0.08"), D("15"), D("200"))
        )
        result = price(policy, project_probabilities(policy, flat_table(40, 3)))
        gap = result.gross_premium_pv - result.benefit_pv - result.expense_pv
        self.assertLessEqual(abs(gap), TOL)

    def test_sensitivity_sorted_by_rate(self) -> None:
        table = flat_table(40, 3)
        rows = rate_sensitivity(
            make_policy(), table, bumps=(D("0.02"), D("-0.02")), tolerance=TOL
        )
        rates = [r["rate"] for r in rows]
        self.assertEqual(rates, sorted(rates))
        self.assertEqual(len(rates), 3)


class OrderingInvarianceTests(unittest.TestCase):
    def test_table_insertion_order_does_not_change_result(self) -> None:
        asc = MortalityTable({40: D("0.01"), 41: D("0.02"), 42: D("0.03")})
        desc = MortalityTable({42: D("0.03"), 41: D("0.02"), 40: D("0.01")})
        policy = make_policy()
        a = run_valuation(policy, asc, TOL)
        b = run_valuation(policy, desc, TOL)
        self.assertEqual(
            [s.death_prob for s in a.states], [s.death_prob for s in b.states]
        )
        self.assertEqual(a.pricing.gross_premium, b.pricing.gross_premium)
        self.assertEqual(
            [r.reserve_explicit for r in a.reserves.rows],
            [r.reserve_explicit for r in b.reserves.rows],
        )


class SyntheticTableAndOracleTests(unittest.TestCase):
    def test_synthetic_table_valid_and_cover_complete(self) -> None:
        table = synthetic_table(55, 10)
        self.assertEqual(sorted(table.rates), list(range(55, 65)))
        for rate in table.rates.values():
            self.assertGreaterEqual(rate, D(0))
            self.assertLess(rate, D(1))

    def test_oracle_passes_on_full_valuation(self) -> None:
        table = synthetic_table(40, 6)
        policy = make_policy(
            term=6,
            expenses=ExpenseAssumptions(D("0.05"), D("10"), D("100")),
        )
        valuation = run_valuation(policy, table, TOL)
        report = run_oracle(policy, table, valuation, TOL)
        self.assertTrue(
            report.passed, [c.detail for c in report.checks if not c.passed]
        )


if __name__ == "__main__":
    unittest.main()
