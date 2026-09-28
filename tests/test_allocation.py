import unittest
from decimal import Decimal

from abs_waterfall.allocation import (
    largest_remainder,
    pro_rata_pay,
    sequential_pay,
)
from abs_waterfall.errors import AllocationError


class AllocationTest(unittest.TestCase):
    def test_largest_remainder_tie_breaks_by_id(self) -> None:
        result = largest_remainder(
            10, {"c": Decimal(1), "a": Decimal(1), "b": Decimal(1)}
        )
        self.assertEqual(result, {"a": 4, "b": 3, "c": 3})

    def test_largest_remainder_input_order_independent(self) -> None:
        w1 = {"z": Decimal(3), "a": Decimal(1), "m": Decimal(2)}
        w2 = {"m": Decimal(2), "z": Decimal(3), "a": Decimal(1)}
        self.assertEqual(
            largest_remainder(11, w1), largest_remainder(11, w2)
        )

    def test_sequential_pay(self) -> None:
        paid, remaining, leftover = sequential_pay(
            Decimal("10.00"),
            ["A", "B", "C"],
            {"A": Decimal("6"), "B": Decimal("6"), "C": Decimal("6")},
        )
        self.assertEqual(paid, {"A": Decimal("6.00"),
                                "B": Decimal("4.00"),
                                "C": Decimal("0.00")})
        self.assertEqual(remaining["B"], Decimal("2.00"))
        self.assertEqual(remaining["C"], Decimal("6.00"))
        self.assertEqual(leftover, Decimal("0.00"))

    def test_pro_rata_conserves_cash(self) -> None:
        paid, leftover = pro_rata_pay(
            Decimal("10.00"),
            {"A": Decimal(1), "B": Decimal(1), "C": Decimal(1)},
        )
        self.assertEqual(sum(paid.values(), Decimal(0)) + leftover,
                         Decimal("10.00"))

    def test_pro_rata_cap_redistributes(self) -> None:
        paid, leftover = pro_rata_pay(
            Decimal("10.00"),
            {"A": Decimal(1), "B": Decimal(1)},
            cap={"A": Decimal("3"), "B": Decimal("100")},
        )
        self.assertEqual(paid["A"], Decimal("3.00"))
        self.assertEqual(paid["B"], Decimal("7.00"))
        self.assertEqual(leftover, Decimal("0.00"))

    def test_pro_rata_all_capped_leaves_leftover(self) -> None:
        paid, leftover = pro_rata_pay(
            Decimal("10.00"),
            {"A": Decimal(1), "B": Decimal(1)},
            cap={"A": Decimal("3"), "B": Decimal("3")},
        )
        self.assertEqual(leftover, Decimal("4.00"))

    def test_zero_weight_fails(self) -> None:
        with self.assertRaises(AllocationError):
            largest_remainder(5, {"A": Decimal(0)})


if __name__ == "__main__":
    unittest.main()
