import unittest
from decimal import Decimal

from ecl_engine.models import Curve, Stage, TermPoint


class ModelsTest(unittest.TestCase):
    def test_curve_lookup(self) -> None:
        curve = Curve((TermPoint(1, Decimal("0.1")), TermPoint(2, Decimal("0.2"))))
        self.assertEqual(curve.value_at(2), Decimal("0.2"))
        self.assertEqual(curve.max_period, 2)
        with self.assertRaises(KeyError):
            curve.value_at(3)

    def test_stage_values(self) -> None:
        self.assertEqual(int(Stage.STAGE1), 1)
        self.assertEqual(int(Stage.STAGE3), 3)


if __name__ == "__main__":
    unittest.main()
