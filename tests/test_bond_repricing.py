from datetime import date
from decimal import Decimal
from unittest import TestCase

from dcurve.bootstrap import bootstrap
from dcurve.errors import IllegalDateError, MissingTenorError, QuoteValidationError
from dcurve.instruments import BondCashflows
from dcurve.pricing import bond_pv, reference_cashflow_pv
from tests.fixtures import analytic_swap_nodes, two_period_swap_book


class BondRepricingTest(TestCase):
    def setUp(self) -> None:
        self.result = bootstrap(two_period_swap_book())
        self.curve = self.result.curve

    def test_node_date_bond_is_discounted_sum(self) -> None:
        df1, df2 = analytic_swap_nodes()
        bond = BondCashflows("B", (
            (date(2025, 7, 15), Decimal("0.025")),
            (date(2026, 1, 15), Decimal("1.025")),
        ))
        expected = reference_cashflow_pv(
            bond.flows, (df1, df2))
        self.assertLessEqual(
            (bond_pv(self.curve, bond) - expected).copy_abs(),
            Decimal("1e-16"))

    def test_interpolation_date_uses_loglinear_curve(self) -> None:
        bond = BondCashflows("MID", (
            (date(2025, 10, 15), Decimal("1")),
        ))
        df_mid = self.curve.discount_factor(date(2025, 10, 15))
        self.assertEqual(bond_pv(self.curve, bond), df_mid)
        self.assertTrue(df_mid < self.curve.nodes[0].discount_factor)
        self.assertTrue(df_mid > self.curve.nodes[1].discount_factor)

    def test_cash_flow_beyond_last_node_rejected(self) -> None:
        bond = BondCashflows("LONG", ((date(2026, 6, 15), Decimal("1")),))
        with self.assertRaises(MissingTenorError):
            bond_pv(self.curve, bond)

    def test_cash_flow_before_value_date_rejected(self) -> None:
        bond = BondCashflows("OLD", ((date(2024, 12, 31), Decimal("1")),))
        with self.assertRaises(IllegalDateError):
            bond_pv(self.curve, bond)

    def test_unsorted_bond_flows_rejected_at_construction(self) -> None:
        with self.assertRaises(QuoteValidationError):
            BondCashflows("UNSORTED", (
                (date(2026, 1, 15), Decimal("1")),
                (date(2025, 7, 15), Decimal("1")),
            ))
