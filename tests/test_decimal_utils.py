import unittest
from decimal import Decimal, InvalidOperation

from chainladder.decimal_utils import D, q2, qf, money, factor, ZERO, ONE


class DecimalUtilsTest(unittest.TestCase):
    def test_D_strings_ints_decimal(self):
        self.assertEqual(D(" 100.50 "), Decimal("100.50"))
        self.assertEqual(D(100), Decimal(100))
        self.assertEqual(D(Decimal("1.2")), Decimal("1.2"))

    def test_D_rejects_float_and_bool(self):
        with self.assertRaises(TypeError):
            D(1.5)
        with self.assertRaises(TypeError):
            D(True)

    def test_D_rejects_garbage(self):
        with self.assertRaises(InvalidOperation):
            D("abc")

    def test_q2_half_up(self):
        self.assertEqual(q2(Decimal("1.005")), Decimal("1.01"))
        self.assertEqual(q2(Decimal("1.004")), Decimal("1.00"))
        self.assertEqual(str(q2(Decimal("5"))), "5.00")

    def test_qf_half_up(self):
        self.assertEqual(qf(Decimal("1.5")), Decimal("1.500000"))
        self.assertEqual(qf(Decimal("1.2345678")), Decimal("1.234568"))

    def test_money_and_factor(self):
        self.assertEqual(money("123.456"), Decimal("123.46"))
        self.assertEqual(factor("1.2"), Decimal("1.200000"))

    def test_constants(self):
        self.assertEqual(ZERO, Decimal(0))
        self.assertEqual(ONE, Decimal(1))


if __name__ == "__main__":
    unittest.main()
