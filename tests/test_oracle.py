import unittest
from decimal import Decimal

from chainladder.model import Triangle
from chainladder.factors import FactorConfig, FactorMethod
from chainladder.projection import project
from chainladder.oracle import oracle_factors, oracle_project
from tests.fixtures import SMALL_ROWS


def matrix_from(tri):
    boundary = tri.observed_boundary()
    max_dev = max(boundary.values())
    matrix = []
    for i in range(tri.n):
        row = []
        for dev in range(1, max_dev + 1):
            cell = tri.cells.get((i, dev))
            row.append(cell.amount if cell is not None else None)
        while row and row[-1] is None:
            row.pop()
        matrix.append(row)
    return matrix


class OracleTest(unittest.TestCase):
    def test_oracle_factors_manual(self):
        matrix = [
            [Decimal("100"), Decimal("150"), Decimal("175")],
            [Decimal("120"), Decimal("180")],
            [Decimal("130")],
        ]
        out = oracle_factors(matrix)
        self.assertEqual(out[0][0], Decimal("330.00"))
        self.assertEqual(out[0][1], Decimal("220.00"))
        self.assertEqual(out[0][2], Decimal("1.500000"))
        self.assertEqual(out[1][2], Decimal("1.166667"))

    def test_oracle_matches_engine_factors(self):
        tri = Triangle.from_rows(SMALL_ROWS)
        matrix = matrix_from(tri)
        oracle = oracle_factors(matrix)
        engine = compute_vw(tri)
        self.assertEqual(len(oracle), len(engine))
        for (num, den, fac), step in zip(oracle, engine):
            self.assertEqual(num, step.numerator)
            self.assertEqual(den, step.denominator)
            self.assertEqual(fac, step.factor)

    def test_oracle_project_manual(self):
        matrix = [
            [Decimal("100"), Decimal("150"), Decimal("175")],
            [Decimal("120"), Decimal("180")],
            [Decimal("130")],
        ]
        factors = [Decimal("1.500000"), Decimal("1.166667")]
        out = oracle_project(matrix, factors, Decimal("1.000000"))
        self.assertEqual(out[0]["ultimate"], Decimal("175.00"))
        self.assertEqual(out[1]["ultimate"], Decimal("210.00"))
        self.assertEqual(out[2]["ultimate"], Decimal("227.50"))
        for row in out:
            self.assertEqual(row["paid"] + row["reserve"], row["ultimate"])

    def test_oracle_matches_engine_projection(self):
        tri = Triangle.from_rows(SMALL_ROWS)
        res = project(tri, FactorConfig(method=FactorMethod.VOLUME_WEIGHTED), "1.0")
        matrix = matrix_from(tri)
        factors = [s.factor for s in res.factors]
        oracle = oracle_project(matrix, factors, res.tail_factor)
        for i, row in enumerate(oracle):
            engine = res.origins[i]
            self.assertEqual(row["ultimate"], engine.ultimate)
            self.assertEqual(row["reserve"], engine.reserve)
            self.assertEqual(row["projected"], engine.projected)


def compute_vw(tri):
    from chainladder.factors import compute_factors
    return compute_factors(tri, FactorConfig(method=FactorMethod.VOLUME_WEIGHTED))


if __name__ == "__main__":
    unittest.main()
