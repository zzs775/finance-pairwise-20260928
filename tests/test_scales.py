import unittest
from decimal import Decimal

from ecl_engine.scales import (
    AMOUNT_QUANT,
    RATE_QUANT,
    D,
    fixed_sum,
    is_finite,
    quantize,
)


class ScalesTest(unittest.TestCase):
    def test_parse_decimal_string(self) -> None:
        self.assertEqual(D("0.1"), Decimal("0.1"))

    def test_reject_float_and_bool(self) -> None:
        with self.assertRaises(TypeError):
            D(0.1)
        with self.assertRaises(TypeError):
            D(True)

    def test_half_up_quantization(self) -> None:
        self.assertEqual(quantize(D("0.005"), AMOUNT_QUANT), D("0.01"))
        self.assertEqual(quantize(D("0.004"), AMOUNT_QUANT), D("0.00"))
        self.assertEqual(quantize(D("1e-9"), RATE_QUANT), D("0E-8"))

    def test_finite_check(self) -> None:
        self.assertTrue(is_finite(D("0")))

    def test_fixed_sum(self) -> None:
        self.assertEqual(fixed_sum(["0.1", "0.2"], AMOUNT_QUANT), D("0.30"))
        self.assertEqual(
            fixed_sum(["0.005", "0.005"], AMOUNT_QUANT), D("0.01")
        )


if __name__ == "__main__":
    unittest.main()
