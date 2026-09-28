import unittest
from decimal import Decimal

from pnl_engine.canonical import decimal_to_text, to_canonical
from pnl_engine.decimal_utils import (
    AMOUNT_Q,
    FX_Q,
    PRICE_Q,
    QTY_Q,
    freeze_amount,
    freeze_fx,
    freeze_price,
    freeze_qty,
    is_finite_decimal,
    qmul,
)


class DecimalUtilsTest(unittest.TestCase):
    def test_scales_frozen(self):
        self.assertEqual(freeze_price("1.2345678999"), Decimal("1.23456790"))
        self.assertEqual(freeze_qty("1.2345678"), Decimal("1.234568"))
        self.assertEqual(freeze_fx("1.12345678912"), Decimal("1.1234567891"))
        self.assertEqual(freeze_amount("10.000000009"), Decimal("10.00000001"))

    def test_banker_rounding(self):
        self.assertEqual(freeze_amount("0.123456785"), Decimal("0.12345678"))
        self.assertEqual(freeze_amount("0.123456795"), Decimal("0.12345680"))

    def test_non_finite_detection(self):
        self.assertFalse(is_finite_decimal(Decimal("NaN")))
        self.assertFalse(is_finite_decimal(Decimal("sNaN")))
        self.assertFalse(is_finite_decimal(Decimal("Infinity")))
        self.assertFalse(is_finite_decimal("not-a-number"))
        self.assertTrue(is_finite_decimal(Decimal("-0")))
        self.assertTrue(is_finite_decimal("3.14"))

    def test_qmul_quantized(self):
        product = qmul(Decimal("10.234568"), Decimal("1.1234567891"), AMOUNT_Q)
        self.assertEqual(product.as_tuple().exponent, AMOUNT_Q.as_tuple().exponent)

    def test_quantum_constants(self):
        self.assertEqual(PRICE_Q, Decimal("0.00000001"))
        self.assertEqual(QTY_Q, Decimal("0.000001"))
        self.assertEqual(FX_Q, Decimal("0.0000000001"))
        self.assertEqual(AMOUNT_Q, Decimal("0.00000001"))


class CanonicalJsonTest(unittest.TestCase):
    def test_decimal_text_and_order(self):
        payload = {
            "b": Decimal("1.20"),
            "a": [Decimal("0E-8"), Decimal("-3.5"), {"z": 1, "y": 2}],
        }
        text = to_canonical(payload)
        self.assertEqual(
            text,
            '{"a":["0.00000000","-3.5",{"y":2,"z":1}],"b":"1.20"}',
        )

    def test_deterministic_across_dict_insertion(self):
        first = {"x": Decimal("1"), "y": [Decimal("2")], "z": {"k": Decimal("3")}}
        second = {"z": {"k": Decimal("3")}, "y": [Decimal("2")], "x": Decimal("1")}
        self.assertEqual(to_canonical(first), to_canonical(second))

    def test_non_finite_decimal_rejected(self):
        with self.assertRaises(ValueError):
            decimal_to_text(Decimal("NaN"))
        with self.assertRaises(ValueError):
            to_canonical({"x": Decimal("Infinity")})


if __name__ == "__main__":
    unittest.main()
