import unittest
from decimal import Decimal

from pnl_engine.errors import FailureCode
from pnl_engine.fixtures import (
    T0,
    T2,
    accrual,
    asset,
    dataset,
    fx,
    position,
    price,
    txn,
)
from pnl_engine.models import BusinessTime, Position, PriceTick, Transaction
from pnl_engine.validation import validate_dataset


def _base_dataset():
    item = asset("A1", "USD", "tech")
    return dataset(
        [item],
        [position("A1", "10", "0.00")],
        [position("A1", "15", "0.10", "s-end")],
        [txn("T1", "A1", "2026-01-02T00:00:00Z", "5", "51.00", "USD")],
        [price("A1", T0, "50"), price("A1", "2026-01-02T00:00:00Z", "51"), price("A1", T2, "52")],
        [accrual("A1", T0, "0.00"), accrual("A1", T2, "0.10")],
        [fx("USD", T0, "1"), fx("USD", T2, "1")],
    )


class ValidationTest(unittest.TestCase):
    def test_clean_fixture_passes(self):
        self.assertEqual(validate_dataset(_base_dataset()), [])

    def test_missing_factor(self):
        data = _base_dataset()
        data.prices = [p for p in data.prices if p.when.iso_ts != T2]
        codes = {f.code for f in validate_dataset(data)}
        self.assertIn(FailureCode.MISSING_FACTOR, codes)

    def test_unknown_asset_in_transaction(self):
        data = _base_dataset()
        data.transactions.append(txn("T2", "GHOST", "2026-01-02T01:00:00Z", "1", "1"))
        codes = {f.code for f in validate_dataset(data)}
        self.assertIn(FailureCode.MISSING_FACTOR, codes)

    def test_duplicate_transaction(self):
        data = _base_dataset()
        data.transactions.append(data.transactions[0])
        codes = {f.code for f in validate_dataset(data)}
        self.assertIn(FailureCode.DUPLICATE_TRANSACTION, codes)

    def test_duplicate_same_instant_tick(self):
        data = _base_dataset()
        data.prices.append(price("A1", T2, "53"))
        codes = {f.code for f in validate_dataset(data)}
        self.assertIn(FailureCode.DUPLICATE_TRANSACTION, codes)

    def test_currency_mismatch(self):
        data = _base_dataset()
        original = data.transactions[0]
        data.transactions[0] = Transaction(
            txn_id=original.txn_id,
            asset_id=original.asset_id,
            when=original.when,
            delta_qty=original.delta_qty,
            price=original.price,
            currency="EUR",
            snapshot=original.snapshot,
        )
        codes = {f.code for f in validate_dataset(data)}
        self.assertIn(FailureCode.CURRENCY_MISMATCH, codes)

    def test_base_currency_fx_must_be_one(self):
        data = _base_dataset()
        data.fx = [fx("USD", T0, "1"), fx("USD", T2, "1.01")]
        codes = {f.code for f in validate_dataset(data)}
        self.assertIn(FailureCode.CURRENCY_MISMATCH, codes)

    def test_time_inversion_outside_period(self):
        data = _base_dataset()
        data.transactions.append(
            txn("T9", "A1", "2025-12-31T00:00:00Z", "1", "50", "USD")
        )
        codes = {f.code for f in validate_dataset(data)}
        self.assertIn(FailureCode.TIME_INVERSION, codes)

    def test_non_finite_value(self):
        data = _base_dataset()
        bad = Position(
            asset_id="A1",
            quantity=Decimal("NaN"),
            accrued=Decimal("0.00"),
            snapshot=data.start_positions["A1"].snapshot,
        )
        data.start_positions["A1"] = bad
        codes = {f.code for f in validate_dataset(data)}
        self.assertIn(FailureCode.NON_FINITE_VALUE, codes)

    def test_non_finite_string_in_price(self):
        data = _base_dataset()
        original = data.prices[0]
        data.prices[0] = PriceTick(
            asset_id="A1",
            when=BusinessTime(T0),
            price=Decimal("Infinity"),
            snapshot=original.snapshot,
        )
        codes = {f.code for f in validate_dataset(data)}
        self.assertIn(FailureCode.NON_FINITE_VALUE, codes)


if __name__ == "__main__":
    unittest.main()
