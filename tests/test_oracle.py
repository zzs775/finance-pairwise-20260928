"""Mechanical oracle checks run independently of implementation helpers.

These tests recompute every invariant from the emitted tables with plain
Decimal arithmetic and assert exact/near-exact identities.
"""

import unittest
from decimal import Decimal

from life_reserves.engine import run
from life_reserves.models import Expenses, Inputs, Timing


def _zero_mortality_inputs(timing: Timing, rate: str, term: int = 4) -> Inputs:
    return Inputs(
        issue_age=35,
        term=term,
        sum_assured=Decimal("250000"),
        timing=timing,
        interest_rate=Decimal(rate),
        mortality={a: Decimal(0) for a in range(35, 35 + term)},
        expenses=Expenses(
            initial=Decimal("100"), renewal=Decimal("20"), claim=Decimal("75")
        ),
    )


class ZeroMortalityOracle(unittest.TestCase):
    def test_benefit_pv_zero_and_premium_closes_expense(self) -> None:
        for timing in Timing:
            for rate in ("0", "0.03", "0.25"):
                with self.subTest(timing=timing.value, rate=rate):
                    result = run(_zero_mortality_inputs(timing, rate))
                    self.assertEqual(result.pricing.pv_benefit, Decimal(0))
                    self.assertEqual(result.pricing.net_premium, Decimal(0))

                    pv_premium = sum(c.premium_pv for c in result.cashflows)
                    pv_expense = sum(c.expense_pv for c in result.cashflows)
                    pv_benefit = sum(c.benefit_pv for c in result.cashflows)
                    self.assertEqual(pv_benefit, Decimal(0))
                    self.assertLess(
                        abs(pv_premium - pv_expense), Decimal("1e-22")
                    )
                    self.assertEqual(
                        result.pricing.pv_gross_premium,
                        result.pricing.pv_benefit + result.pricing.pv_expense,
                    )


class ProbabilityMassOracle(unittest.TestCase):
    def test_branch_sum_equals_entry_mass_and_total_partition(self) -> None:
        inp = Inputs(
            issue_age=40,
            term=5,
            sum_assured=Decimal("1000"),
            timing=Timing.END,
            interest_rate=Decimal("0.04"),
            mortality={
                40: Decimal("0.1"),
                41: Decimal("0.2"),
                42: Decimal("0.3"),
                43: Decimal("0.4"),
                44: Decimal("0.5"),
            },
        )
        result = run(inp)
        # Per-year identity p_death_k + p_survive_k = p_enter_k.
        for path in result.paths:
            self.assertEqual(path.p_death + path.p_survive, path.p_enter)
        # Recursion p_enter_{k+1} = p_survive_k from emitted values alone.
        for k in range(inp.term - 1):
            self.assertEqual(
                result.paths[k + 1].p_enter, result.paths[k].p_survive
            )
        # Global partition: all death masses + mass surviving the horizon = 1.
        total_death = sum(p.p_death for p in result.paths)
        self.assertEqual(total_death + result.paths[-1].p_survive, Decimal(1))


class RecursionOracle(unittest.TestCase):
    def test_each_backward_step_recomputes_prior_reserve(self) -> None:
        inp = _zero_mortality_inputs(Timing.END, "0.03", term=3)
        inp = Inputs(
            issue_age=40,
            term=3,
            sum_assured=Decimal("1000"),
            timing=Timing.END,
            interest_rate=Decimal("0.05"),
            mortality={
                40: Decimal("0.1"),
                41: Decimal("0.2"),
                42: Decimal("0.5"),
            },
            expenses=Expenses(
                initial=Decimal("10"), renewal=Decimal("2"), claim=Decimal("5")
            ),
        )
        result = run(inp, tolerance=Decimal("1e-20"))
        self.assertEqual(result.diagnostics, [])
        v = Decimal(1) / Decimal("1.05")
        premium = result.pricing.gross_premium
        reserves = result.reserves
        for k in range(1, inp.term + 1):
            path = result.paths[k - 1]
            entry_expense = (
                inp.expenses.initial if k == 1 else inp.expenses.renewal
            )
            claim = inp.sum_assured + inp.expenses.claim
            rebuilt = v * (
                path.q_mortality * claim
                + path.p_conditional * reserves[k].reserve_backward
                - premium
                + entry_expense
            )
            self.assertLess(
                abs(rebuilt - reserves[k - 1].reserve_backward),
                Decimal("1e-20"),
                f"year {k}",
            )
        # Contract boundary: terminal reserve exactly zero.
        self.assertEqual(reserves[-1].reserve_backward, Decimal(0))
        self.assertEqual(reserves[-1].reserve_state_sum, Decimal(0))


if __name__ == "__main__":
    unittest.main()
