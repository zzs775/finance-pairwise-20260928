import unittest
from decimal import Decimal

from chainladder.model import Triangle, Cell
from chainladder.factors import FactorConfig, FactorMethod, ZeroDenominatorPolicy
from chainladder.projection import project
from chainladder.projection import project_parallel
from chainladder.revision import revise, incremental_recompute, full_recompute
from chainladder.development import (
    cumulative_to_incremental,
    incremental_to_cumulative,
)
from chainladder.serialization import to_canonical_json
from chainladder.errors import (
    DuplicateCellError,
    InternalHoleError,
)


class DeterminismTest(unittest.TestCase):
    def test_duplicate_diagnostic_order_independent(self):
        base = [
            (2020, 1, "100"), (2020, 2, "150"),
            (2021, 1, "120"),
        ]
        dup_a = base + [(2020, 1, "1"), (2021, 1, "2")]
        dup_b = list(reversed(base)) + [(2021, 1, "2"), (2020, 1, "1")]
        with self.assertRaises(DuplicateCellError) as cm_a:
            Triangle.from_rows(dup_a)
        with self.assertRaises(DuplicateCellError) as cm_b:
            Triangle.from_rows(dup_b)
        self.assertEqual(str(cm_a.exception), str(cm_b.exception))

    def test_hole_diagnostic_before_negative_by_position(self):
        rows = [
            (2020, 1, "100"), (2020, 3, "90"),
            (2021, 1, "50"), (2021, 2, "60"),
            (2022, 1, "10"),
        ]
        with self.assertRaises(InternalHoleError):
            Triangle.from_rows(rows)


class SparseBoundaryTest(unittest.TestCase):
    def test_sparse_boundary_factors_only_observed(self):
        rows = [
            (2020, 1, "100"), (2020, 2, "150"),
            (2021, 1, "120"),
            (2022, 1, "130"),
        ]
        tri = Triangle.from_rows(rows)
        res = project(tri)
        self.assertEqual(len(res.factors), 1)
        self.assertEqual(res.factors[0].used_units, 1)
        self.assertEqual(res.factors[0].factor, Decimal("1.500000"))
        self.assertEqual(res.origins[0].reserve, Decimal("0.00"))

    def test_single_cell_triangle(self):
        tri = Triangle.from_rows([(2030, 1, "42.00")])
        res = project(tri, tail_factor="1.1")
        self.assertEqual(res.factors, [])
        self.assertEqual(res.origins[0].ultimate, Decimal("46.20"))
        self.assertEqual(res.origins[0].reserve, Decimal("4.20"))

    def test_skip_with_single_nonzero_unit(self):
        rows = [
            (2020, 1, "0"), (2020, 2, "0"),
            (2021, 1, "80"), (2021, 2, "120"),
            (2022, 1, "10"),
        ]
        cfg = FactorConfig(zero_policy=ZeroDenominatorPolicy.SKIP)
        res = project(Triangle.from_rows(rows), cfg)
        self.assertEqual(res.factors[0].factor, Decimal("1.500000"))


class RoundTripEdgeTest(unittest.TestCase):
    def test_all_zero_row_roundtrip(self):
        row = [Decimal("0.00"), Decimal("0.00"), Decimal("0.00")]
        inc = cumulative_to_incremental(row)
        self.assertEqual(inc, [Decimal("0.00"), Decimal("0.00"), Decimal("0.00")])
        self.assertEqual(incremental_to_cumulative(inc), row)

    def test_large_amounts_no_float(self):
        big = Decimal("999999999999999999.99")
        tri = Triangle.from_rows([
            (2020, 1, str(big)), (2020, 2, str(big * 2)),
            (2021, 1, str(big)),
        ])
        res = project(tri)
        self.assertEqual(res.factors[0].factor, Decimal("2.000000"))
        self.assertEqual(res.origins[1].ultimate, big * 2)

    def test_rounding_half_up_path(self):
        tri = Triangle.from_rows([
            (2020, 1, "100"), (2020, 2, "155"), (2020, 3, "160"),
            (2021, 1, "100"), (2021, 2, "145"),
            (2022, 1, "30"),
        ])
        res = project(tri)
        self.assertEqual(res.factors[0].factor, Decimal("1.500000"))


class RevisionChainTest(unittest.TestCase):
    def _rows(self):
        return [
            (2020, 1, "100"), (2020, 2, "150"), (2020, 3, "175"),
            (2021, 1, "120"), (2021, 2, "180"),
            (2022, 1, "130"),
        ]

    def test_chain_of_revisions_all_methods(self):
        for method in FactorMethod:
            if method is FactorMethod.FIXED:
                cfg = FactorConfig(method=method, fixed_factors={1: "1.45", 2: "1.1"})
            else:
                cfg = FactorConfig(method=method)
            tri = Triangle.from_rows(self._rows())
            previous = project(tri, cfg, "1.03")
            for cell in (
                Cell(2022, 1, "140", 2),
                Cell(2021, 2, "186", 2),
                Cell(2020, 2, "152", 2),
            ):
                affected = revise(tri, cell)
                previous = incremental_recompute(tri, previous, affected, cfg, "1.03")
            full = full_recompute(tri, cfg, "1.03")
            self.assertEqual(to_canonical_json(previous), to_canonical_json(full))


class ParallelDeterminismTest(unittest.TestCase):
    def test_parallel_equals_serial_all_methods(self):
        rows = [
            (2020, 1, "100"), (2020, 2, "150"), (2020, 3, "175"),
            (2021, 1, "120"), (2021, 2, "180"),
            (2022, 1, "130"),
        ]
        for method in (FactorMethod.VOLUME_WEIGHTED, FactorMethod.SIMPLE_AVERAGE):
            cfg = FactorConfig(method=method)
            serial = project(Triangle.from_rows(rows), cfg, "1.05")
            parallel = project_parallel(Triangle.from_rows(rows), cfg, "1.05")
            self.assertEqual(
                to_canonical_json(parallel), to_canonical_json(serial)
            )

    def test_overflow_sized_projection(self):
        huge = Decimal("1" + "0" * 20)
        rows = [
            (2020, 1, huge), (2020, 2, huge * 2),
            (2021, 1, huge),
        ]
        res = project(Triangle.from_rows(rows))
        expected = q2(huge * Decimal("2.000000"))
        self.assertEqual(res.origins[1].ultimate, expected)
        self.assertEqual(res.origins[1].reserve, expected - huge)

    def test_very_large_amounts_exact(self):
        huge = Decimal("9" * 40)
        doubled = q2(huge * 2)
        rows = [
            (2020, 1, huge), (2020, 2, doubled),
            (2021, 1, huge), (2021, 2, doubled),
            (2022, 1, huge),
        ]
        res = project(Triangle.from_rows(rows))
        self.assertEqual(res.factors[0].factor, Decimal("2.000000"))
        self.assertEqual(res.origins[2].ultimate, doubled)
        self.assertEqual(
            res.origins[2].reserve, doubled - q2(huge)
        )
        self.assertEqual(len(res.origins[2].ultimate.as_tuple().digits), 43)


def q2(value):
    from chainladder.decimal_utils import q2 as quantize
    return quantize(value)


if __name__ == "__main__":
    unittest.main()
