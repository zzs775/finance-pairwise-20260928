import unittest

from ecl_engine.models import Stage
from ecl_engine.stages import STAGE1_HORIZON, loss_periods
from ecl_engine.validation import ValidationError


class StagesTest(unittest.TestCase):
    def test_stage1_window(self) -> None:
        self.assertEqual(list(loss_periods(Stage.STAGE1, 24)), list(range(1, 13)))
        self.assertEqual(STAGE1_HORIZON, 12)

    def test_stage1_truncated_at_maturity(self) -> None:
        self.assertEqual(list(loss_periods(Stage.STAGE1, 7)), [1, 2, 3, 4, 5, 6, 7])
        self.assertEqual(list(loss_periods(Stage.STAGE1, 0)), [])

    def test_stage2_and_stage3_lifetime(self) -> None:
        self.assertEqual(
            list(loss_periods(Stage.STAGE2, 30)), list(range(1, 31))
        )
        self.assertEqual(
            list(loss_periods(Stage.STAGE3, 30)), list(range(1, 31))
        )

    def test_negative_remaining_fails(self) -> None:
        with self.assertRaises(ValidationError):
            loss_periods(Stage.STAGE2, -1)

    def test_window_is_contiguous_without_gap_or_duplicate(self) -> None:
        for stage in Stage:
            periods = list(loss_periods(stage, 20))
            self.assertEqual(periods, list(range(1, len(periods) + 1)))
            self.assertEqual(len(periods), len(set(periods)))


if __name__ == "__main__":
    unittest.main()
