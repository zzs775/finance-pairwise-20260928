from decimal import Decimal
from unittest import TestCase

from dcurve.bootstrap import bootstrap
from dcurve.constants import REPRICING_TOLERANCE
from dcurve.pricing import (
    bond_pv,
    deposit_pv,
    reference_cashflow_pv,
    swap_pv,
)
from tests.fixtures import (
    analytic_deposit_df,
    analytic_swap_nodes,
    deposit_book,
    sample_bond,
    two_period_swap_book,
)

HIGH_PRECISION = Decimal("1e-16")


def assert_decimal_close(case: TestCase, actual: Decimal,
                         expected: Decimal) -> None:
    case.assertLessEqual((actual - expected).copy_abs(), HIGH_PRECISION)


class DepositOracleTest(TestCase):
    def test_bootstrapped_df_matches_analytic_deposit(self) -> None:
        book = deposit_book()
        result = bootstrap(book)
        solved = result.curve.nodes[0].discount_factor
        expected = analytic_deposit_df()
        assert_decimal_close(self, solved, expected)

        # Independent oracle: deposit cash flows discounted at the same
        # analytic factor reproduce a zero par value.
        deposit = book.deposits[0]
        flows = ((deposit.start, Decimal(-1)),
                 (deposit.maturity,
                  Decimal(1) + deposit.rate * Decimal(91) / Decimal(360)))
        df_value_date = Decimal(1)
        oracle_value = reference_cashflow_pv(
            flows, (df_value_date, expected))
        self.assertLessEqual(oracle_value.copy_abs(), Decimal("1e-49"))
        self.assertLessEqual(
            abs(deposit_pv(result.curve, deposit)), REPRICING_TOLERANCE)


class TwoPeriodSwapOracleTest(TestCase):
    def test_nodes_match_hand_computed_par_formula(self) -> None:
        book = two_period_swap_book()
        result = bootstrap(book)
        df1, df2 = analytic_swap_nodes()
        assert_decimal_close(self, result.curve.nodes[0].discount_factor, df1)
        assert_decimal_close(self, result.curve.nodes[1].discount_factor, df2)

        swap = book.swaps[0]
        self.assertLessEqual(
            swap_pv(result.curve, swap).copy_abs(), REPRICING_TOLERANCE)

        # Hand schedule: fixed leg = coupons + terminal unit notional;
        # float leg receives 1 at maturity against 1 paid at effective.
        tau1, tau2 = Decimal(181) / 360, Decimal(184) / 360
        # Fixed leg of a receive-fixed swap: coupons only, K * annuity.
        coupon_pv = reference_cashflow_pv(
            ((result.curve.nodes[0].date, swap.fixed_rate * tau1),
             (result.curve.nodes[1].date, swap.fixed_rate * tau2)),
            (df1, df2))
        # Receive-float leg: borrow 1 at effective, repay 1 at maturity.
        float_leg_pv = reference_cashflow_pv(
            ((swap.effective, Decimal(-1)),
             (result.curve.nodes[1].date, Decimal(1))),
            (Decimal(1), df2))
        self.assertEqual(float_leg_pv, df2 - Decimal(1))
        # Par: coupon PV plus float-leg PV is zero.
        assert_decimal_close(
            self, coupon_pv + float_leg_pv, Decimal(0))

    def test_diagnostics_fields_and_order(self) -> None:
        result = bootstrap(two_period_swap_book())
        ids = [item.id for item in result.diagnostics]
        self.assertEqual(ids, ["D6M", "S1Y"])
        for item in result.diagnostics:
            self.assertEqual(item.market_target, Decimal(0))
            self.assertLessEqual(abs(item.residual), REPRICING_TOLERANCE)
            self.assertGreaterEqual(item.iterations, 0)

    def test_bond_repriced_on_calibrated_nodes(self) -> None:
        result = bootstrap(two_period_swap_book())
        df1, df2 = analytic_swap_nodes()
        bond = sample_bond()
        expected = (Decimal("0.025") * df1
                    + Decimal("1.025") * df2)
        assert_decimal_close(self, bond_pv(result.curve, bond), expected)
