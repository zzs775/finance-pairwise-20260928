import unittest
from decimal import Decimal

from abs_waterfall.errors import CashOverflowError, NegativeCashError
from abs_waterfall.money import (
    checked_add,
    checked_sub,
    money_sum,
    to_money,
    to_rate,
)


class MoneyTest(unittest.TestCase):
    def test_fixed_scale_rounding(self) -> None:
        self.assertEqual(to_money("1.235"), Decimal("1.24"))
        self.assertEqual(to_money("0.001"), Decimal("0.00"))

    def test_rate_scale(self) -> None:
        self.assertEqual(to_rate("0.05"), Decimal("0.0500000000"))

    def test_negative_rejected(self) -> None:
        with self.assertRaises(NegativeCashError):
            to_money("-0.01")
        with self.assertRaises(NegativeCashError):
            checked_sub(Decimal("1.00"), Decimal("2.00"))

    def test_overflow_rejected(self) -> None:
        with self.assertRaises(CashOverflowError):
            to_money("9999999999999")
        with self.assertRaises(CashOverflowError):
            checked_add(Decimal("999999999999.99"), Decimal("0.01"))

    def test_money_sum(self) -> None:
        self.assertEqual(
            money_sum([Decimal("1.50"), Decimal("2.25")]), Decimal("3.75")
        )


if __name__ == "__main__":
    unittest.main()
