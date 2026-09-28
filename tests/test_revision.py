import unittest
from decimal import Decimal

from chainladder.model import Triangle, Cell
from chainladder.factors import FactorConfig, FactorMethod
from chainladder.projection import project
from chainladder.revision import revise, incremental_recompute, full_recompute
from chainladder.serialization import to_canonical_json
from chainladder.errors import IllegalVersionError
from tests.fixtures import SMALL_ROWS


class RevisionTest(unittest.TestCase):
    def setUp(self):
        self.tri = Triangle.from_rows(SMALL_ROWS)

    def test_version_must_increase(self):
        with self.assertRaises(IllegalVersionError):
            revise(self.tri, Cell(origin=2021, dev=2, amount="180", version=1))
        with self.assertRaises(IllegalVersionError):
            revise(self.tri, Cell(origin=2021, dev=2, amount="200", version=0))

    def test_unknown_cell_cannot_revise(self):
        with self.assertRaises(KeyError):
            revise(self.tri, Cell(origin=2022, dev=2, amount="200", version=2))

    def test_revision_affected_devs(self):
        affected = revise(self.tri, Cell(origin=2022, dev=1, amount="140", version=2))
        self.assertEqual(affected, [])
        affected = revise(self.tri, Cell(origin=2020, dev=2, amount="160", version=3))
        self.assertEqual(affected, [1, 2])

    def test_revision_breaks_monotonicity_rejected(self):
        revise(self.tri, Cell(origin=2020, dev=2, amount="160", version=2))
        from chainladder.errors import NegativePaymentError
        with self.assertRaises(NegativePaymentError):
            revise(self.tri, Cell(origin=2020, dev=1, amount="170", version=3))
        self.assertEqual(self.tri.get(0, 1).amount, Decimal("100.00"))
        self.assertEqual(self.tri.get(0, 2).amount, Decimal("160.00"))

    def test_history_retained(self):
        revise(self.tri, Cell(origin=2021, dev=1, amount="130", version=2))
        key = (1, 1)
        versions = [c.version for c in self.tri.history[key]]
        amounts = [c.amount for c in self.tri.history[key]]
        self.assertEqual(versions, [1, 2])
        self.assertEqual(amounts, [Decimal("120.00"), Decimal("130.00")])

    def test_incremental_equals_full_recompute(self):
        cfg = FactorConfig(method=FactorMethod.VOLUME_WEIGHTED)
        previous = project(self.tri, cfg, tail_factor="1.05")
        affected = revise(self.tri, Cell(origin=2021, dev=2, amount="192", version=2))
        inc = incremental_recompute(self.tri, previous, affected)
        full = full_recompute(self.tri, cfg, "1.05")
        self.assertEqual(
            [(s.dev_from, s.numerator, s.denominator, s.factor) for s in inc.factors],
            [(s.dev_from, s.numerator, s.denominator, s.factor) for s in full.factors],
        )
        for i in range(self.tri.n):
            self.assertEqual(inc.origins[i], full.origins[i])
        self.assertEqual(to_canonical_json(inc), to_canonical_json(full))

    def test_unaffected_factor_steps_reused(self):
        cfg = FactorConfig(method=FactorMethod.VOLUME_WEIGHTED)
        previous = project(self.tri, cfg, tail_factor="1.05")
        affected = revise(self.tri, Cell(origin=2020, dev=2, amount="160", version=2))
        inc = incremental_recompute(self.tri, previous, affected)
        self.assertIn(1, affected)
        old_by_dev = {s.dev_from: s for s in previous.factors}
        new_by_dev = {s.dev_from: s for s in inc.factors}
        self.assertIsNot(new_by_dev[1], old_by_dev[1])
        self.assertIsNot(new_by_dev[2], old_by_dev[2])
        affected2 = revise(self.tri, Cell(origin=2021, dev=1, amount="125", version=3))
        inc2 = incremental_recompute(self.tri, inc, affected2)
        self.assertEqual(affected2, [1])
        by2 = {s.dev_from: s for s in inc2.factors}
        self.assertIsNot(by2[1], new_by_dev[1])
        self.assertIs(by2[2], new_by_dev[2])
        full = full_recompute(self.tri, cfg, "1.05")
        self.assertEqual(to_canonical_json(inc2), to_canonical_json(full))

    def test_revision_propagates_to_later_projections(self):
        cfg = FactorConfig(method=FactorMethod.VOLUME_WEIGHTED)
        revise(self.tri, Cell(origin=2021, dev=2, amount="192", version=2))
        res = full_recompute(self.tri, cfg)
        s1 = res.factors[0]
        self.assertEqual(s1.numerator, Decimal("342.00"))
        self.assertEqual(s1.denominator, Decimal("220.00"))
        self.assertEqual(s1.factor, Decimal("1.554545"))

    def test_no_affected_devs_returns_previous(self):
        previous = project(self.tri)
        out = incremental_recompute(self.tri, previous, [])
        self.assertEqual(to_canonical_json(out), to_canonical_json(previous))

    def test_revision_under_simple_average(self):
        cfg = FactorConfig(method=FactorMethod.SIMPLE_AVERAGE)
        previous = project(self.tri, cfg)
        affected = revise(self.tri, Cell(origin=2020, dev=1, amount="110", version=2))
        inc = incremental_recompute(self.tri, previous, affected)
        full = full_recompute(self.tri, cfg)
        self.assertEqual(to_canonical_json(inc), to_canonical_json(full))


if __name__ == "__main__":
    unittest.main()
