import unittest
from decimal import Decimal, localcontext

from pnl_engine.attribution import attribute_all
from pnl_engine.decimal_utils import AMOUNT_Q, freeze_amount
from pnl_engine.direct_reval import cross_check
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
)
from pnl_engine.validation import validate_dataset


class OverflowTest(unittest.TestCase):
    def test_huge_values_do_not_silently_overflow(self):
        item = asset("BIG", "USD", "eq")
        data = dataset(
            [item],
            [position("BIG", "999999999999999999", "0")],
            [position("BIG", "999999999999999999", "0", "s-end")],
            [],
            [
                price("BIG", T0, "999999999999999999.00000000"),
                price("BIG", T2, "999999999999999999.00000000"),
            ],
            [accrual("BIG", T0, "0"), accrual("BIG", T2, "0")],
            [fx("USD", T0, "1"), fx("USD", T2, "1")],
        )
        self.assertEqual(validate_dataset(data), [])
        result = attribute_all(data)["BIG"]
        self.assertEqual(
            result.start_value_base,
            Decimal("999999999999999998000000000000000000.00000000"),
        )
        self.assertTrue(result.start_value_base.is_finite())
        self.assertEqual(result.residual_base, Decimal("0.00000000"))

    def test_overflow_in_multiplication_raises_not_inf(self):
        from pnl_engine.decimal_utils import freeze

        with self.assertRaises(Exception):
            freeze(Decimal("1E9999999"), AMOUNT_Q)

    def test_non_finite_inputs_never_become_attributes(self):
        item = asset("BAD", "USD", "eq")
        data = dataset(
            [item],
            [position("BAD", "1", "0")],
            [position("BAD", "1", "0", "s-end")],
            [],
            [price("BAD", T0, "1"), price("BAD", T2, "1")],
            [accrual("BAD", T0, "0"), accrual("BAD", T2, "0")],
            [fx("USD", T0, "1"), fx("USD", T2, "1")],
        )
        data.end_positions["BAD"] = type(data.end_positions["BAD"])(
            asset_id="BAD",
            quantity=Decimal("NaN"),
            accrued=Decimal("0"),
            snapshot=data.end_positions["BAD"].snapshot,
        )
        codes = {f.code for f in validate_dataset(data)}
        self.assertIn(FailureCode.NON_FINITE_VALUE, codes)


class EnumerationLimitTest(unittest.TestCase):
    def test_nine_positions_skips_permutation_enumeration(self):
        assets = []
        starts = []
        ends = []
        prices_list = []
        accruals_list = []
        for i in range(9):
            aid = f"A{i}"
            assets.append(asset(aid, "USD", "eq"))
            starts.append(position(aid, "1", "0"))
            ends.append(position(aid, "1", "0", "s-end"))
            prices_list.extend([price(aid, T0, "10"), price(aid, T2, "10")])
            accruals_list.extend([accrual(aid, T0, "0"), accrual(aid, T2, "0")])
        data = dataset(
            assets, starts, ends, [], prices_list, accruals_list,
            [fx("USD", T0, "1"), fx("USD", T2, "1")],
        )
        results = attribute_all(data)
        self.assertEqual(cross_check(data, results), [])


if __name__ == "__main__":
    unittest.main()
