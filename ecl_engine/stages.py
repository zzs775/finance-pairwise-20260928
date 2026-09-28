"""Stage-dependent loss-horizon selection."""

from .validation import ValidationError
from .models import Stage

#: Stage 1 recognises losses over the next 12 periods.
STAGE1_HORIZON = 12


def loss_periods(stage: Stage, remaining_periods: int) -> range:
    """Return the 1-based period range covered by a contract's stage.

    Stage 1 uses the in-spec 12-period window (truncated at maturity);
    stages 2/3 use the full remaining lifetime.
    """
    if not isinstance(stage, Stage):
        raise ValidationError(f"unknown stage {stage!r}")
    if remaining_periods < 0:
        raise ValidationError(
            f"negative remaining_periods: {remaining_periods}"
        )
    if stage is Stage.STAGE1:
        horizon = min(STAGE1_HORIZON, remaining_periods)
    else:
        horizon = remaining_periods
    return range(1, horizon + 1)
