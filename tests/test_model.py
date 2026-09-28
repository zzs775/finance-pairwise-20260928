import unittest
from decimal import Decimal

from chainladder.model import Triangle
from chainladder.errors import (
    DuplicateCellError,
    NegativePaymentError,
    InternalHoleError,
    IllegalVersionError,
    ShapeError,
)
from tests.fixtures import SMALL_ROWS, shuffled_small_rows


class ModelTest(unittest.TestCase):
    def test_build_small_triangle(self):
        tri = Triangle.from_rows(SMALL_ROWS)
        self.assertEqual(tri.n, 3)
        self.assertEqual(tri.origin_labels, [2020, 2021, 2022])
        diag = tri.latest_diagonal()
        self.assertEqual(diag, {0: Decimal("175.00"), 1: Decimal("180.00"), 2: Decimal("130.00")})

    def test_order_and_whitespace_independent(self):
        a = Triangle.from_rows(SMALL_ROWS)
        b = Triangle.from_rows(shuffled_small_rows())
        self.assertEqual(a.cells, b.cells)
        self.assertEqual(a.latest_diagonal(), b.latest_diagonal())

    def test_row_values_quantized(self):
        tri = Triangle.from_rows(SMALL_ROWS)
        self.assertEqual(tri.row(0), [Decimal("100.00"), Decimal("150.00"), Decimal("175.00")])

    def test_boundary_shape(self):
        tri = Triangle.from_rows(SMALL_ROWS)
        self.assertEqual(tri.observed_boundary(), {0: 3, 1: 2, 2: 1})

    def test_duplicate_cell(self):
        rows = SMALL_ROWS + [(2020, 1, "999")]
        with self.assertRaises(DuplicateCellError):
            Triangle.from_rows(rows)

    def test_negative_payment(self):
        rows = [(2020, 1, "-1.00"), (2021, 1, "5")]
        with self.assertRaises(NegativePaymentError):
            Triangle.from_rows(rows)

    def test_non_monotone_cumulative(self):
        rows = [(2020, 1, "100"), (2020, 2, "90"), (2021, 1, "5")]
        with self.assertRaises(NegativePaymentError):
            Triangle.from_rows(rows)

    def test_internal_hole(self):
        rows = [
            (2020, 1, "100"),
            (2020, 3, "150"),
            (2021, 1, "50"),
            (2021, 2, "70"),
            (2022, 1, "20"),
        ]
        with self.assertRaises(InternalHoleError):
            Triangle.from_rows(rows)

    def test_out_of_shape(self):
        rows = [
            (2020, 1, "100"),
            (2020, 2, "150"),
            (2020, 3, "160"),
            (2020, 4, "165"),
            (2021, 1, "50"),
            (2021, 2, "60"),
            (2022, 1, "20"),
        ]
        with self.assertRaises(ShapeError):
            Triangle.from_rows(rows)

    def test_sparse_boundary_allowed(self):
        rows = [
            (2020, 1, "100"),
            (2020, 2, "150"),
            (2021, 1, "120"),
            (2022, 1, "130"),
        ]
        tri = Triangle.from_rows(rows)
        self.assertEqual(tri.observed_boundary(), {0: 2, 1: 1, 2: 1})

    def test_illegal_version(self):
        rows = [(2020, 1, "100", 0), (2021, 1, "5")]
        with self.assertRaises(IllegalVersionError):
            Triangle.from_rows(rows)

    def test_zero_allowed(self):
        tri = Triangle.from_rows([(2020, 1, "0.00"), (2021, 1, "0.00")])
        self.assertEqual(tri.latest_diagonal()[0], Decimal("0.00"))


if __name__ == "__main__":
    unittest.main()
