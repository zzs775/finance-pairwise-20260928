import unittest
from decimal import Decimal

from life_reserves.cashflows import build_cashflows, build_paths
from life_reserves.models import Expenses, Inputs, Timing
from life_reserves.pricing import price
from life_reserves.reserves import (
    reconcile,
    reserves_backward,
    reserves_state_sum,
)


def _inputs(timing=Timing.END, term=3):
    return Inputs(
        issue_age=40,
        term=term,
        sum_assured=Decimal("1000"),
        timing=timing,
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


class ReserveMethodAgreementTest(unittest.TestCase):
    def test_methods_agree_for_both_timings(self) -> None:
        for timing in Timing:
            inp = _inputs(timing)
            paths = build_paths(inp)
            pricing = price(inp, paths)
            rows = build_cashflows(inp, paths, pricing.gross_premium)
            state = reserves_state_sum(inp, paths, pricing)
            back = reserves_backward(inp, paths, pricing)
            for a, b in zip(state, back):
                self.assertLess(abs(a - b), Decimal("1e-18"))
            diagnostics = reconcile(inp, paths, state, back, Decimal("1e-12"))
            self.assertEqual(diagnostics, [])

    def test_terminal_reserve_is_zero(self) -> None:
        inp = _inputs()
        paths = build_paths(inp)
        pricing = price(inp, paths)
        self.assertEqual(reserves_state_sum(inp, paths, pricing)[-1], 0)
        self.assertEqual(reserves_backward(inp, paths, pricing)[-1], 0)

    def test_initial_reserve_zero_by_equivalence(self) -> None:
        inp = _inputs(Timing.BEGINNING)
        paths = build_paths(inp)
        pricing = price(inp, paths)
        state = reserves_state_sum(inp, paths, pricing)
        back = reserves_backward(inp, paths, pricing)
        self.assertEqual(state[0], Decimal(0))
        self.assertEqual(back[0], Decimal(0))


class RecursionEquationTest(unittest.TestCase):
    """Independently verify the year-by-year recursion equation."""

    def test_backward_recursion_holds_beginning(self) -> None:
        inp = _inputs(Timing.BEGINNING)
        v = Decimal(1) / Decimal("1.05")
        paths = build_paths(inp)
        pricing = price(inp, paths)
        reserves = reserves_backward(inp, paths, pricing)
        for k in range(1, inp.term + 1):
            path = paths[k - 1]
            entry_expense = (
                inp.expenses.initial if k == 1 else inp.expenses.renewal
            )
            claim = inp.sum_assured + inp.expenses.claim
            lhs = reserves[k - 1] + pricing.gross_premium - entry_expense
            rhs = path.q_mortality * claim + v * path.p_conditional * reserves[k]
            self.assertLess(abs(lhs - rhs), Decimal("1e-18"), f"year {k}")

    def test_backward_recursion_holds_end(self) -> None:
        inp = _inputs(Timing.END)
        v = Decimal(1) / Decimal("1.05")
        paths = build_paths(inp)
        pricing = price(inp, paths)
        reserves = reserves_backward(inp, paths, pricing)
        for k in range(1, inp.term + 1):
            path = paths[k - 1]
            entry_expense = (
                inp.expenses.initial if k == 1 else inp.expenses.renewal
            )
            claim = inp.sum_assured + inp.expenses.claim
            lhs = reserves[k - 1]
            rhs = v * (
                path.q_mortality * claim
                + path.p_conditional * reserves[k]
                - pricing.gross_premium
                + entry_expense
            )
            self.assertLess(abs(lhs - rhs), Decimal("1e-18"), f"year {k}")


class DiagnosisTest(unittest.TestCase):
    def test_mismatch_produces_diagnostic_not_a_choice(self) -> None:
        inp = _inputs()
        paths = build_paths(inp)
        fake = [Decimal("1")] * (inp.term + 1)
        true = [Decimal("0")] * (inp.term + 1)
        diagnostics = reconcile(inp, paths, fake, true, Decimal("1e-9"))
        self.assertEqual(len(diagnostics), inp.term + 1)
        self.assertEqual(diagnostics[0].code, "RESERVE_METHOD_MISMATCH")


class UnreachableBoundaryTest(unittest.TestCase):
    def test_certain_first_year_death_methods_still_agree(self) -> None:
        for timing in Timing:
            inp = Inputs(
                issue_age=40,
                term=3,
                sum_assured=Decimal("1000"),
                timing=timing,
                interest_rate=Decimal("0.05"),
                mortality={
                    40: Decimal(1),
                    41: Decimal("0.2"),
                    42: Decimal("0.5"),
                },
                expenses=Expenses(
                    initial=Decimal("10"),
                    renewal=Decimal("2"),
                    claim=Decimal("5"),
                ),
            )
            paths = build_paths(inp)
            pricing = price(inp, paths)
            state = reserves_state_sum(inp, paths, pricing)
            back = reserves_backward(inp, paths, pricing)
            diagnostics = reconcile(inp, paths, state, back, Decimal("1e-18"))
            self.assertEqual(diagnostics, [], timing.value)


if __name__ == "__main__":
    unittest.main()
