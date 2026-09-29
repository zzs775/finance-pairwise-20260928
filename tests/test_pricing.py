import unittest
from decimal import Decimal

from life_reserves.cashflows import build_cashflows, build_paths
from life_reserves.models import Expenses, Inputs, Timing
from life_reserves.pricing import price


def _inputs(timing=Timing.END, rate="0.05", zero=True, term=3):
    table = {
        a: (Decimal(0) if zero else Decimal("0.1"))
        for a in range(40, 40 + term)
    }
    return Inputs(
        issue_age=40,
        term=term,
        sum_assured=Decimal("1000"),
        timing=timing,
        interest_rate=Decimal(rate),
        mortality=table,
        expenses=Expenses(
            initial=Decimal("10"), renewal=Decimal("2"), claim=Decimal("5")
        ),
    )


class ZeroMortalityOracleTest(unittest.TestCase):
    def test_benefit_pv_is_zero(self) -> None:
        for timing in Timing:
            inp = _inputs(timing)
            pricing = price(inp, build_paths(inp))
            self.assertEqual(pricing.pv_benefit, Decimal(0))
            self.assertEqual(pricing.net_premium, Decimal(0))

    def test_premium_pv_closes_with_expense_pv(self) -> None:
        for timing in Timing:
            inp = _inputs(timing)
            paths = build_paths(inp)
            pricing = price(inp, paths)
            rows = build_cashflows(inp, paths, pricing.gross_premium)
            pv_premium = sum(r.premium_pv for r in rows)
            pv_expense = sum(r.expense_pv for r in rows)
            # Equivalence principle: PV gross premiums = PV benefits + PV expenses.
            self.assertEqual(
                pricing.pv_gross_premium,
                pricing.pv_benefit + pricing.pv_expense,
            )
            self.assertLess(abs(pv_premium - pv_expense), Decimal("1e-22"))

    def test_zero_rate_zero_mortality_exact_annuity(self) -> None:
        inp = _inputs(rate="0")
        pricing = price(inp, build_paths(inp))
        # Premiums of 1 certain for 3 years, discount 1 -> factor 3.
        self.assertEqual(pricing.annuity_factor, Decimal(3))
        # Expenses: 10 + 2 + 2 = 14, annual gross premium = 14/3.
        self.assertEqual(pricing.gross_premium, Decimal(14) / Decimal(3))


class ExtremeRateTest(unittest.TestCase):
    def test_very_large_rate(self) -> None:
        inp = _inputs(rate="1000000", zero=False)
        paths = build_paths(inp)
        pricing = price(inp, paths)
        # v ~ 1e-6: end-timing year-1 flows dominate and are tiny.
        self.assertGreater(pricing.gross_premium, Decimal(0))
        self.assertLess(pricing.pv_benefit, Decimal("0.001"))

    def test_rate_near_minus_one_rejected_by_engine(self) -> None:
        from life_reserves.engine import run

        inp = _inputs(rate="-1.000000001", zero=False, term=1)
        with self.assertRaises(ValueError):
            run(inp)


class EquivalenceWithMortalityTest(unittest.TestCase):
    def test_gross_covers_net_plus_expense(self) -> None:
        inp = _inputs(zero=False)
        pricing = price(inp, build_paths(inp))
        self.assertGreaterEqual(pricing.gross_premium, pricing.net_premium)
        self.assertLess(
            abs(
                pricing.pv_gross_premium
                - pricing.pv_benefit - pricing.pv_expense
            ),
            Decimal("1e-22"),
        )


if __name__ == "__main__":
    unittest.main()
