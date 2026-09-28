import unittest
from decimal import Decimal

from pnl_engine.attribution import (
    FX,
    INTEREST,
    PRICE,
    TRADE_FLOW,
    TRADE_PNL,
    attribute_all,
)
from pnl_engine.fixtures import (
    T0,
    T1,
    T2,
    accrual,
    asset,
    dataset,
    fx,
    position,
    price,
    txn,
)


def _terms(result, category):
    return [term for term in result.terms if term.category == category]


class AttributionTest(unittest.TestCase):
    def _assert_closes(self, data):
        results = attribute_all(data)
        for result in results.values():
            attributed = sum((t.amount for t in result.terms), Decimal(0))
            self.assertEqual(
                result.start_value_base + attributed,
                result.end_value_base,
            )
            self.assertEqual(
                result.residual_base,
                result.end_value_base - result.start_value_base - attributed,
            )
        return results

    def test_buy_hold_sell_across_period_boundary(self):
        item = asset("A1", "USD", "eq")
        data = dataset(
            [item],
            [position("A1", "0", "0")],
            [position("A1", "0", "0", "s-end")],
            [
                txn("BUY", "A1", T1, "10", "100.00"),
                txn("SELL", "A1", T2, "-10", "105.00"),
            ],
            [price("A1", T0, "100"), price("A1", T1, "100"), price("A1", T2, "105")],
            [accrual("A1", T0, "0"), accrual("A1", T1, "0"), accrual("A1", T2, "0")],
        )
        result = self._assert_closes(data)["A1"]
        trade = sum(t.amount for t in _terms(result, TRADE_PNL))
        flow = sum(t.amount for t in _terms(result, TRADE_FLOW))
        self.assertEqual(flow, Decimal("-50.00000000"))
        self.assertEqual(trade, Decimal("50.00000000"))
        self.assertEqual(result.start_value_base, Decimal("0.00000000"))
        self.assertEqual(result.end_value_base, Decimal("0.00000000"))
        self.assertEqual(result.residual_base, Decimal("0.00000000"))

    def test_short_position_reverse_sign(self):
        item = asset("A1", "USD", "eq")
        data = dataset(
            [item],
            [position("A1", "0", "0")],
            [position("A1", "-5", "0", "s-end")],
            [txn("SHORT", "A1", T1, "-5", "100.00")],
            [price("A1", T0, "100"), price("A1", T1, "100"), price("A1", T2, "98")],
            [accrual("A1", T0, "0"), accrual("A1", T2, "0")],
        )
        result = self._assert_closes(data)["A1"]
        price_pnl = sum(t.amount for t in _terms(result, PRICE))
        self.assertEqual(price_pnl, Decimal("10.00000000"))
        self.assertEqual(result.end_value_base, Decimal("-490.00000000"))

    def test_interest_boundary_negative_and_zero(self):
        item = asset("A1", "USD", "bond")
        data = dataset(
            [item],
            [position("A1", "2", "0")],
            [position("A1", "2", "-0.25", "s-end")],
            [],
            [price("A1", T0, "100"), price("A1", T2, "100")],
            [
                accrual("A1", T0, "0"),
                accrual("A1", T1, "0.10"),
                accrual("A1", T2, "-0.25"),
            ],
        )
        result = self._assert_closes(data)["A1"]
        interest = sum(t.amount for t in _terms(result, INTEREST))
        self.assertEqual(interest, Decimal("-0.25000000"))

    def test_fx_depreciation_negative_for_long(self):
        item = asset("A1", "EUR", "eq")
        data = dataset(
            [item],
            [position("A1", "10", "0")],
            [position("A1", "10", "0", "s-end")],
            [],
            [price("A1", T0, "100"), price("A1", T2, "100")],
            [accrual("A1", T0, "0"), accrual("A1", T2, "0")],
            [fx("EUR", T0, "1.10"), fx("EUR", T2, "1.00")],
        )
        result = self._assert_closes(data)["A1"]
        fx_pnl = sum(t.amount for t in _terms(result, FX))
        self.assertEqual(fx_pnl, Decimal("-100.00000000"))
        self.assertEqual(result.end_value_base, Decimal("1000.00000000"))

    def test_fx_appreciation_positive(self):
        item = asset("A1", "EUR", "eq")
        data = dataset(
            [item],
            [position("A1", "10", "0")],
            [position("A1", "10", "0", "s-end")],
            [],
            [price("A1", T0, "100"), price("A1", T2, "100")],
            [accrual("A1", T0, "0"), accrual("A1", T2, "0")],
            [fx("EUR", T0, "1.00"), fx("EUR", T2, "1.05")],
        )
        result = self._assert_closes(data)["A1"]
        self.assertEqual(sum(t.amount for t in _terms(result, FX)), Decimal("50.00000000"))

    def test_same_instant_trade_before_price_priority(self):
        item = asset("A1", "USD", "eq")
        data = dataset(
            [item],
            [position("A1", "0", "0")],
            [position("A1", "10", "0", "s-end")],
            [txn("BUY", "A1", T1, "10", "99.00")],
            [price("A1", T0, "100"), price("A1", T1, "102"), price("A1", T2, "102")],
            [accrual("A1", T0, "0"), accrual("A1", T2, "0")],
        )
        result = self._assert_closes(data)["A1"]
        when_terms = [t for t in result.terms if t.when.iso_ts == T1]
        categories = [t.category for t in when_terms]
        self.assertEqual(
            categories.index(TRADE_PNL) < categories.index(PRICE),
            True,
        )
        trade_pnl = next(t.amount for t in when_terms if t.category == TRADE_PNL)
        self.assertEqual(trade_pnl, Decimal("10.00000000"))
        price_pnl = next(t.amount for t in when_terms if t.category == PRICE)
        self.assertEqual(price_pnl, Decimal("20.00000000"))

    def test_terms_carry_base_factor_delta_rounding_and_snapshot(self):
        item = asset("A1", "EUR", "eq")
        data = dataset(
            [item],
            [position("A1", "3", "0")],
            [position("A1", "3", "0", "s-end")],
            [],
            [price("A1", T0, "100"), price("A1", T2, "101")],
            [accrual("A1", T0, "0"), accrual("A1", T2, "0")],
            [fx("EUR", T0, "1.00"), fx("EUR", T2, "1.00")],
        )
        result = attribute_all(data)["A1"]
        for term in result.terms:
            self.assertTrue(term.snapshot_version)
            self.assertEqual(term.amount - term.raw, term.rounding)
            self.assertIsNotNone(term.when)


if __name__ == "__main__":
    unittest.main()
