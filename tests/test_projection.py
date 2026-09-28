import unittest
from decimal import Decimal

from chainladder.model import Triangle
from chainladder.factors import FactorConfig, FactorMethod
from chainladder.projection import project
from tests.fixtures import SMALL_ROWS


class ProjectionTest(unittest.TestCase):
    def setUp(self):
        self.tri = Triangle.from_rows(SMALL_ROWS)

    def test_paid_is_latest_diagonal(self):
        res = project(self.tri)
        self.assertEqual(res.origins[0].paid, Decimal("175.00"))
        self.assertEqual(res.origins[1].paid, Decimal("180.00"))
        self.assertEqual(res.origins[2].paid, Decimal("130.00"))

    def test_projection_no_tail_manual(self):
        res = project(self.tri)
        f1, f2 = Decimal("1.500000"), Decimal("1.166667")
        self.assertEqual(res.origins[0].projected, [])
        self.assertEqual(res.origins[0].reserve, Decimal("0.00"))
        p1 = res.origins[1].projected
        self.assertEqual(p1, [Decimal("210.00")])
        self.assertEqual(res.origins[1].ultimate, Decimal("210.00"))
        self.assertEqual(res.origins[1].reserve, Decimal("30.00"))
        p2 = res.origins[2].projected
        self.assertEqual(p2, [Decimal("195.00"), Decimal("227.50")])
        self.assertEqual(res.origins[2].ultimate, Decimal("227.50"))
        self.assertEqual(res.origins[2].reserve, Decimal("97.50"))

    def test_tail_factor_manual(self):
        res = project(self.tri, tail_factor="1.05")
        self.assertEqual(res.tail_factor, Decimal("1.050000"))
        self.assertEqual(res.origins[0].ultimate, Decimal("183.75"))
        self.assertEqual(res.origins[0].reserve, Decimal("8.75"))
        self.assertEqual(res.origins[1].ultimate, Decimal("220.50"))
        self.assertEqual(res.origins[2].ultimate, Decimal("238.88"))

    def test_identity_paid_plus_reserve(self):
        for tail in (None, "1.00", "1.1", "2.0"):
            res = project(self.tri, tail_factor=tail)
            for origin in res.origins.values():
                self.assertEqual(q(origin.paid + origin.reserve), origin.ultimate)
        res = project(self.tri)
        self.assertEqual(q(res.total_paid() + res.total_reserve()), res.total_ultimate())

    def test_observed_cells_not_overwritten(self):
        res = project(self.tri)
        for i, origin in res.origins.items():
            k = self.tri.observed_boundary()[i]
            self.assertEqual(self.tri.cells[(i, k)].amount, origin.paid)
        self.assertEqual(self.tri.row(0),
                         [Decimal("100.00"), Decimal("150.00"), Decimal("175.00")])

    def test_fixed_factors_projection(self):
        cfg = FactorConfig(method=FactorMethod.FIXED,
                           fixed_factors={1: "1.5", 2: "1.2"})
        res = project(self.tri, config=cfg)
        self.assertEqual(res.origins[2].projected,
                         [Decimal("195.00"), Decimal("234.00")])
        self.assertEqual(res.origins[2].ultimate, Decimal("234.00"))

    def test_tail_below_one_rejected(self):
        from chainladder.errors import ShapeError
        with self.assertRaises(ShapeError):
            project(self.tri, tail_factor="0.9")


def q(value):
    return Decimal(value).quantize(Decimal("0.01"))


if __name__ == "__main__":
    unittest.main()
