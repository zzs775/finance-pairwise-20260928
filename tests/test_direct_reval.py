import unittest
from decimal import Decimal

from pnl_engine.attribution import attribute_all
from pnl_engine.direct_reval import (
    MAX_ENUMERATION_POSITIONS,
    cross_check,
    direct_value,
    enumerate_frozen_paths,
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


def _complex_dataset():
    a1 = asset("A1", "EUR", "tech")
    a2 = asset("A2", "USD", "energy")
    return dataset(
        [a1, a2],
        [position("A1", "10", "0"), position("A2", "0", "0")],
        [position("A1", "13", "0.30", "s-end"), position("A2", "4", "0", "s-end")],
        [
            txn("T1", "A1", T1, "3", "102.50", "EUR"),
            txn("T2", "A2", T1, "4", "7.10", "USD"),
        ],
        [
            price("A1", T0, "100"), price("A1", T1, "103"), price("A1", T2, "104"),
            price("A2", T0, "7"), price("A2", T1, "7.10"), price("A2", T2, "7.50"),
        ],
        [
            accrual("A1", T0, "0"), accrual("A1", T1, "0.10"), accrual("A1", T2, "0.30"),
            accrual("A2", T0, "0"), accrual("A2", T2, "0"),
        ],
        [
            fx("EUR", T0, "1.10"), fx("EUR", T1, "1.12"), fx("EUR", T2, "1.15"),
            fx("USD", T0, "1"), fx("USD", T2, "1"),
        ],
    )


class DirectRevalTest(unittest.TestCase):
    def test_direct_value_endpoints(self):
        data = _complex_dataset()
        self.assertEqual(direct_value(data, "A1", data.period_start), Decimal("1100.00000000"))
        self.assertEqual(direct_value(data, "A2", data.period_end), Decimal("30.00000000"))

    def test_all_24_frozen_orderings_close(self):
        data = _complex_dataset()
        for asset_id in ("A1", "A2"):
            paths = enumerate_frozen_paths(data, asset_id)
            self.assertEqual(len(paths), 24)
            results = attribute_all(data)
            total_change = (
                results[asset_id].end_value_base - results[asset_id].start_value_base
            )
            for path in paths:
                self.assertEqual(
                    sum(path.amounts.values(), Decimal(0)) + path.residual,
                    total_change,
                    path.order,
                )

    def test_canonical_order_matches_pipeline(self):
        data = _complex_dataset()
        results = attribute_all(data)
        self.assertEqual(cross_check(data, results), [])

    def test_enumeration_limit_is_eight(self):
        self.assertEqual(MAX_ENUMERATION_POSITIONS, 8)


if __name__ == "__main__":
    unittest.main()
