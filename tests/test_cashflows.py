import unittest
from decimal import Decimal

from life_reserves.cashflows import build_cashflows, build_paths
from life_reserves.models import Expenses, Inputs, Timing


def _inputs(mortality, timing=Timing.END, term=3, issue_age=40, rate="0.05"):
    return Inputs(
        issue_age=issue_age,
        term=term,
        sum_assured=Decimal("1000"),
        timing=timing,
        interest_rate=Decimal(rate),
        mortality=dict(mortality),
        expenses=Expenses(
            initial=Decimal("10"), renewal=Decimal("2"), claim=Decimal("5")
        ),
    )


class ProbabilityPathTest(unittest.TestCase):
    def test_branch_masses_equal_entry_mass_each_year(self) -> None:
        inp = _inputs(
            {40: Decimal("0.1"), 41: Decimal("0.2"), 42: Decimal("0.5")}
        )
        paths = build_paths(inp)
        for path in paths:
            self.assertEqual(path.p_death + path.p_survive, path.p_enter)
        # Recursion spot checks.
        self.assertEqual(paths[0].p_enter, Decimal(1))
        self.assertEqual(paths[1].p_enter, Decimal("0.9"))
        self.assertEqual(paths[2].p_enter, Decimal("0.72"))

    def test_first_year_certain_death(self) -> None:
        inp = _inputs(
            {40: Decimal(1), 41: Decimal("0.2"), 42: Decimal("0.5")}
        )
        paths = build_paths(inp)
        self.assertEqual(paths[0].p_death, Decimal(1))
        self.assertEqual(paths[0].p_survive, Decimal(0))
        # Nobody alive enters later years.
        for later in paths[1:]:
            self.assertEqual(later.p_enter, Decimal(0))
            self.assertEqual(later.p_death, Decimal(0))

    def test_last_year_certain_death(self) -> None:
        inp = _inputs(
            {40: Decimal(0), 41: Decimal(0), 42: Decimal(1)}
        )
        paths = build_paths(inp)
        self.assertEqual(paths[0].p_survive, Decimal(1))
        self.assertEqual(paths[1].p_survive, Decimal(1))
        self.assertEqual(paths[2].p_enter, Decimal(1))
        self.assertEqual(paths[2].p_death, Decimal(1))
        self.assertEqual(paths[2].p_survive, Decimal(0))


class CashFlowTimingTest(unittest.TestCase):
    def test_pv_timing_switch_beginning_vs_end(self) -> None:
        table = {
            40: Decimal("0.1"), 41: Decimal("0.2"), 42: Decimal("0.5")
        }
        end = build_cashflows(
            _inputs(table, Timing.END),
            build_paths(_inputs(table, Timing.END)),
            Decimal("100"),
        )
        beginning = build_cashflows(
            _inputs(table, Timing.BEGINNING),
            build_paths(_inputs(table, Timing.BEGINNING)),
            Decimal("100"),
        )
        v = Decimal(1) / Decimal("1.05")
        for b, e in zip(beginning, end):
            # End-timing PVs are exactly one discount factor smaller.
            for x, y in (
                (b.premium_pv * v, e.premium_pv),
                (b.benefit_pv * v, e.benefit_pv),
                (b.expense_pv * v, e.expense_pv),
            ):
                self.assertLess(abs(x - y), Decimal("1e-25"))

    def test_expected_values_are_independent_of_timing(self) -> None:
        table = {40: Decimal("0.1"), 41: Decimal("0.2"), 42: Decimal("0.5")}
        rows_end = build_cashflows(
            _inputs(table, Timing.END),
            build_paths(_inputs(table, Timing.END)),
            Decimal("100"),
        )
        rows_beg = build_cashflows(
            _inputs(table, Timing.BEGINNING),
            build_paths(_inputs(table, Timing.BEGINNING)),
            Decimal("100"),
        )
        for a, b in zip(rows_end, rows_beg):
            self.assertEqual(a.premium_expected, b.premium_expected)
            self.assertEqual(a.benefit_expected, b.benefit_expected)

    def test_issue_expense_paid_once_year_one(self) -> None:
        table = {a: Decimal(0) for a in (40, 41, 42)}
        rows = build_cashflows(
            _inputs(table), build_paths(_inputs(table)), Decimal("0")
        )
        # Initial 10 in year 1, renewal 2 in years 2 and 3, claim none.
        self.assertEqual(rows[0].expense_expected, Decimal("10"))
        self.assertEqual(rows[1].expense_expected, Decimal("2"))
        self.assertEqual(rows[2].expense_expected, Decimal("2"))


if __name__ == "__main__":
    unittest.main()
