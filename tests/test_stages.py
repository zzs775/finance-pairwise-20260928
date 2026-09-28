"""Tests for stage period ranges and migration partitioning."""

import unittest

from ecl_engine.errors import ValidationError
from ecl_engine import stages


class LossRangeTest(unittest.TestCase):
    def test_stage1_within_window(self) -> None:
        self.assertEqual(stages.loss_period_range(1, 5), (1, 5))

    def test_stage1_truncated_to_window(self) -> None:
        self.assertEqual(stages.loss_period_range(1, 36), (1, 12))

    def test_stage2_and_3_full_term(self) -> None:
        self.assertEqual(stages.loss_period_range(2, 36), (1, 36))
        self.assertEqual(stages.loss_period_range(3, 7), (1, 7))

    def test_invalid_inputs(self) -> None:
        with self.assertRaises(ValidationError):
            stages.loss_period_range(1, 0)
        with self.assertRaises(ValidationError):
            stages.loss_period_range(4, 12)


class MigrationPartitionTest(unittest.TestCase):
    def test_same_stage_single_range(self) -> None:
        self.assertEqual(stages.migrated_period_ranges(2, 2, 36),
                         [(1, 36)])

    def test_stage1_to_stage2_partitions_without_gap(self) -> None:
        ranges = stages.migrated_period_ranges(1, 2, 36)
        self.assertEqual(ranges, [(1, 12), (13, 36)])
        covered = [p for start, end in ranges
                   for p in range(start, end + 1)]
        self.assertEqual(covered, list(range(1, 37)))

    def test_short_term_migration_is_single_range(self) -> None:
        self.assertEqual(stages.migrated_period_ranges(1, 2, 8),
                         [(1, 8)])


if __name__ == "__main__":
    unittest.main()
