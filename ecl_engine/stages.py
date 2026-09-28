"""Stage period ranges and stage-migration handling."""

from .errors import ValidationError

STAGE1_WINDOW = 12


def loss_period_range(stage: int, remaining_periods: int) -> tuple[int, int]:
    """Return inclusive 1-based period range over which losses are taken."""
    if remaining_periods <= 0:
        raise ValidationError(
            f"remaining_periods must be positive, got {remaining_periods}")
    if stage == 1:
        end = min(STAGE1_WINDOW, remaining_periods)
        return 1, end
    if stage in (2, 3):
        return 1, remaining_periods
    raise ValidationError(f"unknown stage: {stage}")


def migrated_period_ranges(previous_stage: int, current_stage: int,
                           remaining_periods: int) -> list[tuple[int, int]]:
    """Partition periods across a stage migration with no gap or overlap.

    The contract is observed in ``previous_stage`` for its current valuation
    and in ``current_stage`` for the revised valuation.  Each stage's range is
    computed against the same remaining term; the union of periods covers
    1..remaining_periods exactly once when the ranges differ, and the wider
    range dominates.
    """
    old_start, old_end = loss_period_range(previous_stage, remaining_periods)
    new_start, new_end = loss_period_range(current_stage, remaining_periods)
    if (old_start, old_end) == (new_start, new_end):
        return [(old_start, old_end)]
    far_end = max(old_end, new_end)
    near_end = min(old_end, new_end)
    ranges = [(1, near_end)]
    if far_end > near_end:
        ranges.append((near_end + 1, far_end))
    return ranges
