import unittest
from decimal import Decimal

from pnl_engine.aggregation import aggregate
from pnl_engine.attribution import attribute_all
from pnl_engine.direct_reval import cross_check
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
from pnl_engine.validation import validate_dataset


class SmokeTest(unittest.TestCase):
    def test_end_to_end_pipeline(self):
        a1 = asset("A1", "EUR", "tech")
        data = dataset(
            [a1],
            [position("A1", "10", "0")],
            [position("A1", "12", "0.20", "s-end")],
            [txn("T1", "A1", T1, "2", "102", "EUR")],
            [
                price("A1", T0, "100"),
                price("A1", T1, "103"),
                price("A1", T2, "104"),
            ],
            [
                accrual("A1", T0, "0"),
                accrual("A1", T1, "0.10"),
                accrual("A1", T2, "0.20"),
            ],
            [fx("EUR", T0, "1.10"), fx("EUR", T1, "1.12"), fx("EUR", T2, "1.15")],
        )
        self.assertEqual(validate_dataset(data), [])
        results = attribute_all(data)
        self.assertEqual(cross_check(data, results), [])
        nodes = aggregate(data, results)
        self.assertEqual(nodes["portfolio"].residual_base, Decimal("0.00000000"))


if __name__ == "__main__":
    unittest.main()
