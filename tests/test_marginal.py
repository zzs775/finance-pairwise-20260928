import unittest
from decimal import Decimal

from ecl_engine.marginal import cumulative_to_marginal
from ecl_engine.scales import D


class MarginalTest(unittest.TestCase):
    def test_basic_differences(self) -> None:
        self.assertEqual(
            cumulative_to_marginal(["0.05", "0.12", "0.20"]),
            [D("0.05000000"), D("0.07000000"), D("0.08000000")],
        )

    def test_zero_first_period(self) -> None:
        marginals = cumulative_to_marginal(["0", "0", "0.03"])
        self.assertEqual(marginals, [D("0"), D("0"), D("0.03000000")])

    def test_telescoping_sum_equals_last_cumulative(self) -> None:
        cumulative = ["0.1", "0.100000004", "0.2", "1"]
        marginals = cumulative_to_marginal(cumulative)
        self.assertEqual(sum(marginals, D("0")), D("1.00000000"))

    def test_flat_region(self) -> None:
        marginals = cumulative_to_marginal(["0.5", "0.5", "0.5"])
        self.assertEqual(marginals, [D("0.50000000"), D("0"), D("0")])


if __name__ == "__main__":
    unittest.main()
