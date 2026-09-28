import unittest
from decimal import Decimal

from chainladder.model import Triangle
from chainladder.development import (
    cumulative_to_incremental,
    incremental_to_cumulative,
    triangle_cumulative_to_incremental,
    triangle_incremental_to_cumulative,
)
from chainladder.errors import NegativePaymentError
from tests.fixtures import SMALL_ROWS


class DevelopmentTest(unittest.TestCase):
    def test_cumulative_to_incremental_row(self):
        cum = [Decimal("100.00"), Decimal("150.00"), Decimal("175.00")]
        inc = cumulative_to_incremental(cum)
        self.assertEqual(inc, [Decimal("100.00"), Decimal("50.00"), Decimal("25.00")])

    def test_roundtrip_single_row(self):
        cum = [Decimal("100.10"), Decimal("150.35"), Decimal("175.35"), Decimal("175.35")]
        self.assertEqual(incremental_to_cumulative(cumulative_to_incremental(cum)), cum)
        inc = [Decimal("100.10"), Decimal("50.25"), Decimal("25.00"), Decimal("0.00")]
        self.assertEqual(cumulative_to_incremental(incremental_to_cumulative(inc)), inc)

    def test_negative_incremental_rejected(self):
        with self.assertRaises(NegativePaymentError):
            cumulative_to_incremental([Decimal("100"), Decimal("90")])
        with self.assertRaises(NegativePaymentError):
            incremental_to_cumulative([Decimal("10"), Decimal("-1")])

    def test_triangle_roundtrip(self):
        tri = Triangle.from_rows(SMALL_ROWS)
        cum_matrix = {i: tri.row(i) for i in range(tri.n)}
        inc_matrix = triangle_cumulative_to_incremental(cum_matrix)
        self.assertEqual(inc_matrix[0],
                         [Decimal("100.00"), Decimal("50.00"), Decimal("25.00")])
        self.assertEqual(inc_matrix[1], [Decimal("120.00"), Decimal("60.00")])
        self.assertEqual(inc_matrix[2], [Decimal("130.00")])
        back = triangle_incremental_to_cumulative(inc_matrix)
        self.assertEqual(back, cum_matrix)

    def test_manual_incremental_sums(self):
        inc = {0: [Decimal("100"), Decimal("50"), Decimal("25")],
               1: [Decimal("120"), Decimal("60")],
               2: [Decimal("130")]}
        cum = triangle_incremental_to_cumulative(inc)
        self.assertEqual(cum[0], [Decimal("100.00"), Decimal("150.00"), Decimal("175.00")])
        self.assertEqual(cum[1][-1], Decimal("180.00"))


if __name__ == "__main__":
    unittest.main()
