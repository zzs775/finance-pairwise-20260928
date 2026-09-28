"""Multi-period engine with continuous state carry-forward."""

from .errors import ValidationError
from .models import EngineResult, PoolPeriod, WaterfallConfig
from .waterfall import run_period, validate_config


def run(
    config: WaterfallConfig,
    periods: list[PoolPeriod],
) -> EngineResult:
    """Run periods sequentially; each period starts from the prior ending."""
    validate_config(config)
    ordered = sorted(periods, key=lambda period: period.index)
    if [period.index for period in ordered] != list(range(len(ordered))):
        raise ValidationError(
            "period indices must be contiguous starting at 0 "
            f"(got {[period.index for period in ordered]})"
        )
    results = []
    prior = None
    for period in ordered:
        result = run_period(config, period, prior)
        results.append(result)
        prior = result.ending_state
    return EngineResult(periods=results)
