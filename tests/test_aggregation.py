import random
import unittest
from decimal import Decimal

from pnl_engine.aggregation import aggregate
from pnl_engine.attribution import attribute_all
from pnl_engine.canonical import to_canonical
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


def _two_industry_dataset():
    a1 = asset("A1", "USD", "tech")
    a2 = asset("A2", "USD", "tech")
    a3 = asset("A3", "EUR", "energy")
    common_fx = [fx("USD", T0, "1"), fx("USD", T2, "1"),
                 fx("EUR", T0, "1"), fx("EUR", T2, "1.04")]
    data = dataset(
        [a1, a2, a3],
        [position("A1", "10", "0"), position("A2", "0", "0"), position("A3", "4", "0")],
        [position("A1", "10", "0.20", "s-end"),
         position("A2", "7", "0", "s-end"),
         position("A3", "4", "0.10", "s-end")],
        [txn("T1", "A2", "2026-01-02T00:00:00Z", "7", "20")],
        [
            price("A1", T0, "100"), price("A1", T2, "101"),
            price("A2", T0, "20"), price("A2", T2, "21"),
            price("A3", T0, "50"), price("A3", T2, "50"),
        ],
        [
            accrual("A1", T0, "0"), accrual("A1", T2, "0.20"),
            accrual("A2", T0, "0"), accrual("A2", T2, "0"),
            accrual("A3", T0, "0"), accrual("A3", T2, "0.10"),
        ],
        common_fx,
    )
    return data


class AggregationTest(unittest.TestCase):
    def test_parent_is_exact_sum_of_children(self):
        data = _two_industry_dataset()
        results = attribute_all(data)
        nodes = aggregate(data, results)

        tech_assets = [nodes["asset:A1"], nodes["asset:A2"]]
        tech = nodes["industry:tech"]
        for field_name in (
            "start_value_base", "end_value_base", "trade_base",
            "price_base", "interest_base", "fx_base",
            "rounding_base", "residual_base",
        ):
            self.assertEqual(
                getattr(tech, field_name),
                sum((getattr(n, field_name) for n in tech_assets), Decimal(0)),
                field_name,
            )

        portfolio = nodes["portfolio"]
        industries = [nodes["industry:tech"], nodes["industry:energy"]]
        for field_name in (
            "start_value_base", "end_value_base", "trade_base",
            "price_base", "interest_base", "fx_base",
            "rounding_base", "residual_base",
        ):
            self.assertEqual(
                getattr(portfolio, field_name),
                sum((getattr(n, field_name) for n in industries), Decimal(0)),
            )

    def test_portfolio_closes_to_zero_residual(self):
        data = _two_industry_dataset()
        nodes = aggregate(data, attribute_all(data))
        p = nodes["portfolio"]
        self.assertEqual(p.residual_base, Decimal("0.00000000"))
        self.assertEqual(
            p.end_value_base - p.start_value_base,
            p.trade_base + p.price_base + p.interest_base + p.fx_base + p.residual_base,
        )

    def test_deterministic_under_record_shuffling(self):
        data = _two_industry_dataset()
        reference = to_canonical(aggregate(data, attribute_all(data)))

        rng = random.Random(4242)
        for _ in range(5):
            shuffled = _two_industry_dataset()
            rng.shuffle(shuffled.transactions)
            rng.shuffle(shuffled.prices)
            rng.shuffle(shuffled.accruals)
            rng.shuffle(shuffled.fx)
            self.assertEqual(
                to_canonical(aggregate(shuffled, attribute_all(shuffled))),
                reference,
            )


if __name__ == "__main__":
    unittest.main()
