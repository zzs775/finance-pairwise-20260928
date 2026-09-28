import unittest
from decimal import Decimal

from chainladder.model import Triangle
from chainladder.factors import (
    FactorConfig,
    FactorMethod,
    ZeroDenominatorPolicy,
    compute_factors,
)
from chainladder.errors import ZeroDenominatorError
from tests.fixtures import SMALL_ROWS, shuffled_small_rows


def tri(rows=None):
    return Triangle.from_rows(rows or SMALL_ROWS)


class FactorTest(unittest.TestCase):
    def test_volume_weighted_manual(self):
        steps = compute_factors(tri())
        self.assertEqual([s.dev_from for s in steps], [1, 2])
        s1 = steps[0]
        self.assertEqual(s1.numerator, Decimal("330.00"))
        self.assertEqual(s1.denominator, Decimal("220.00"))
        self.assertEqual(s1.factor, Decimal("1.500000"))
        self.assertEqual(s1.used_units, 2)
        s2 = steps[1]
        self.assertEqual(s2.numerator, Decimal("175.00"))
        self.assertEqual(s2.denominator, Decimal("150.00"))
        self.assertEqual(s2.factor, Decimal("1.166667"))
        self.assertEqual(s2.units, [(0, Decimal("150.00"), Decimal("175.00"))])

    def test_simple_average_manual(self):
        cfg = FactorConfig(method=FactorMethod.SIMPLE_AVERAGE)
        steps = compute_factors(tri(), cfg)
        self.assertEqual(steps[0].factor, Decimal("1.500000"))
        self.assertEqual(steps[1].factor, Decimal("1.166667"))

    def test_simple_vs_volume_differ(self):
        rows = [
            (2020, 1, "100"), (2020, 2, "200"), (2020, 3, "210"),
            (2021, 1, "1000"), (2021, 2, "1100"),
            (2022, 1, "50"),
        ]
        t = Triangle.from_rows(rows)
        vw = compute_factors(t, FactorConfig(method=FactorMethod.VOLUME_WEIGHTED))
        sa = compute_factors(t, FactorConfig(method=FactorMethod.SIMPLE_AVERAGE))
        self.assertEqual(vw[0].factor, Decimal("1.181818"))
        self.assertEqual(sa[0].factor, Decimal("1.550000"))

    def test_fixed_factors(self):
        cfg = FactorConfig(
            method=FactorMethod.FIXED,
            fixed_factors={1: "1.5", 2: "1.2"},
        )
        steps = compute_factors(tri(), cfg)
        self.assertEqual([s.factor for s in steps],
                         [Decimal("1.500000"), Decimal("1.200000")])
        self.assertEqual(steps[0].used_units, 0)

    def test_fixed_missing_raises(self):
        cfg = FactorConfig(method=FactorMethod.FIXED, fixed_factors={1: "1.5"})
        with self.assertRaises(ZeroDenominatorError):
            compute_factors(tri(), cfg)

    def test_zero_denominator_error(self):
        rows = [
            (2020, 1, "0"), (2020, 2, "0"),
            (2021, 1, "0"),
        ]
        t = Triangle.from_rows(rows)
        with self.assertRaises(ZeroDenominatorError):
            compute_factors(t, FactorConfig())

    def test_zero_denominator_skip(self):
        rows = [
            (2020, 1, "0"), (2020, 2, "10"),
            (2021, 1, "100"), (2021, 2, "200"),
            (2022, 1, "50"),
        ]
        t = Triangle.from_rows(rows)
        cfg = FactorConfig(zero_policy=ZeroDenominatorPolicy.SKIP)
        steps = compute_factors(t, cfg)
        self.assertEqual(steps[0].used_units, 1)
        self.assertEqual(steps[0].factor, Decimal("2.000000"))

    def test_zero_denominator_all_skipped_use_one(self):
        rows = [(2020, 1, "0"), (2020, 2, "0"), (2021, 1, "0")]
        t = Triangle.from_rows(rows)
        cfg = FactorConfig(zero_policy=ZeroDenominatorPolicy.USE_ONE)
        steps = compute_factors(t, cfg)
        self.assertEqual(steps[0].factor, Decimal("1.000000"))

    def test_order_independent_factors(self):
        a = compute_factors(tri())
        b = compute_factors(Triangle.from_rows(shuffled_small_rows()))
        self.assertEqual([s.factor for s in a], [s.factor for s in b])
        self.assertEqual([s.units for s in a], [s.units for s in b])


if __name__ == "__main__":
    unittest.main()
