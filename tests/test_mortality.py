import unittest
from decimal import Decimal

from life_reserves.mortality import conditional_survival, validate_mortality


class MortalityValidationTest(unittest.TestCase):
    def test_returns_complete_age_window(self) -> None:
        table = {
            29: Decimal("0.005"),
            30: Decimal("0.01"),
            31: Decimal("0.02"),
            32: Decimal("0.03"),
        }
        q = validate_mortality(30, 3, table)
        self.assertEqual(q, [Decimal("0.01"), Decimal("0.02"), Decimal("0.03")])

    def test_boundary_probabilities_are_allowed(self) -> None:
        q = validate_mortality(0, 2, {0: Decimal(0), 1: Decimal(1)})
        self.assertEqual(q, [Decimal(0), Decimal(1)])
        self.assertEqual(conditional_survival(Decimal(1)), Decimal(0))

    def test_negative_probability_rejected(self) -> None:
        with self.assertRaises(ValueError):
            validate_mortality(30, 1, {30: Decimal("-0.0001")})

    def test_probability_above_one_rejected(self) -> None:
        with self.assertRaises(ValueError):
            validate_mortality(30, 1, {30: Decimal("1.0001")})

    def test_age_out_of_range_rejected(self) -> None:
        # Required attained ages 40..42; missing age 42.
        with self.assertRaises(ValueError):
            validate_mortality(40, 3, {40: Decimal("0.01"), 41: Decimal("0.01")})

    def test_non_decimal_rate_rejected(self) -> None:
        with self.assertRaises(ValueError):
            validate_mortality(40, 1, {40: 0.01})  # type: ignore[dict-item]

    def test_bad_issue_age_and_term(self) -> None:
        with self.assertRaises(ValueError):
            validate_mortality(-1, 1, {-1: Decimal("0.1")})
        with self.assertRaises(ValueError):
            validate_mortality(40, 0, {})


if __name__ == "__main__":
    unittest.main()
