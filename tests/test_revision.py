import unittest
from decimal import Decimal

from pnl_engine.canonical import to_canonical
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
from pnl_engine.models import Transaction
from pnl_engine.revision import revise_transaction


def _dataset():
    a1 = asset("A1", "USD", "tech")
    a2 = asset("A2", "USD", "energy")
    return dataset(
        [a1, a2],
        [position("A1", "0", "0"), position("A2", "5", "0")],
        [position("A1", "8", "0", "s-end"), position("A2", "5", "0", "s-end")],
        [txn("OLD", "A1", T1, "8", "40")],
        [
            price("A1", T0, "40"), price("A1", T1, "40"), price("A1", T2, "42"),
            price("A2", T0, "10"), price("A2", T2, "11"),
        ],
        [
            accrual("A1", T0, "0"), accrual("A1", T2, "0"),
            accrual("A2", T0, "0"), accrual("A2", T2, "0"),
        ],
        [fx("USD", T0, "1"), fx("USD", T2, "1")],
    )


class RevisionTest(unittest.TestCase):
    def test_revised_quantity_rebuilds_end_position(self):
        data = _dataset()
        revised = Transaction(
            txn_id="OLD",
            asset_id="A1",
            when=data.transactions[0].when,
            delta_qty=Decimal("6.000000"),
            price=Decimal("39.00000000"),
            currency="USD",
            snapshot=data.transactions[0].snapshot,
        )
        outcome = revise_transaction(data, revised)
        self.assertTrue(outcome.consistent, outcome.diff)
        self.assertEqual(
            outcome.revised_dataset.end_positions["A1"].quantity,
            Decimal("6.000000"),
        )
        for asset_id in ("A1", "A2"):
            self.assertEqual(
                to_canonical(outcome.full_results[asset_id]),
                to_canonical(outcome.incremental_results[asset_id]),
            )

    def test_revision_to_other_asset_touches_both(self):
        data = _dataset()
        original = data.transactions[0]
        revised = Transaction(
            txn_id="OLD",
            asset_id="A2",
            when=original.when,
            delta_qty=Decimal("2.000000"),
            price=Decimal("10.00000000"),
            currency="USD",
            snapshot=original.snapshot,
        )
        outcome = revise_transaction(data, revised)
        self.assertEqual(outcome.affected_asset_ids, ("A1", "A2"))
        self.assertTrue(outcome.consistent, outcome.diff)
        self.assertEqual(
            outcome.revised_dataset.end_positions["A1"].quantity,
            Decimal("0.000000"),
        )
        self.assertEqual(
            outcome.revised_dataset.end_positions["A2"].quantity,
            Decimal("7.000000"),
        )


if __name__ == "__main__":
    unittest.main()
