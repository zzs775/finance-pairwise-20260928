"""Frozen-indicator trigger evaluation.

Triggers only *read* the indicator snapshot frozen for the period; they
never mutate the pool. Indicator definitions are explicit: every trigger
must name an indicator present in the period payload, otherwise the run
fails at validation/evaluation time with TriggerConfigurationError.
"""

from collections.abc import Iterable, Mapping
from decimal import Decimal

from .errors import TriggerConfigurationError, TriggerEvaluationError
from .models import Trigger

OPERATORS: tuple[str, ...] = (">=", "<=", ">", "<", "==")
_COMPARISONS = {
    ">=": lambda a, b: a >= b,
    "<=": lambda a, b: a <= b,
    ">": lambda a, b: a > b,
    "<": lambda a, b: a < b,
    "==": lambda a, b: a == b,
}


def validate_triggers(
    triggers: Iterable[Trigger],
    profile_ids: set[str],
    indicator_names: set[str] | None = None,
) -> None:
    seen: set[str] = set()
    for trigger in triggers:
        if not trigger.id:
            raise TriggerConfigurationError("trigger id must be non-empty")
        if trigger.id in seen:
            raise TriggerConfigurationError(
                f"duplicate trigger id: {trigger.id}"
            )
        seen.add(trigger.id)
        if not trigger.indicator:
            raise TriggerConfigurationError(
                f"trigger {trigger.id!r} is missing indicator name"
            )
        if trigger.operator not in _COMPARISONS:
            raise TriggerConfigurationError(
                f"trigger {trigger.id!r} has unknown operator "
                f"{trigger.operator!r}; expected one of {OPERATORS}"
            )
        if not trigger.threshold.is_finite():
            raise TriggerConfigurationError(
                f"trigger {trigger.id!r} threshold must be finite"
            )
        if trigger.profile_id not in profile_ids:
            raise TriggerConfigurationError(
                f"trigger {trigger.id!r} references unknown profile "
                f"{trigger.profile_id!r}"
            )
        if indicator_names is not None and trigger.indicator not in indicator_names:
            raise TriggerConfigurationError(
                f"trigger {trigger.id!r} references indicator "
                f"{trigger.indicator!r} absent from period indicators "
                f"{sorted(indicator_names)}"
            )


def evaluate_triggers(
    triggers: Iterable[Trigger],
    indicators: Mapping[str, Decimal],
) -> list[Trigger]:
    """Return triggers that fire, in their declared configuration order.

    The first firing trigger selects the profile; later firings are recorded
    in the audit but cannot override the earlier one.
    """
    fired: list[Trigger] = []
    for trigger in triggers:
        if trigger.indicator not in indicators:
            raise TriggerEvaluationError(
                f"trigger {trigger.id!r} indicator {trigger.indicator!r} "
                "missing from frozen period indicators"
            )
        observed = indicators[trigger.indicator]
        if not observed.is_finite():
            raise TriggerEvaluationError(
                f"indicator {trigger.indicator!r} is not finite"
            )
        try:
            if _COMPARISONS[trigger.operator](observed, trigger.threshold):
                fired.append(trigger)
        except ArithmeticError as exc:
            raise TriggerEvaluationError(
                f"trigger {trigger.id!r} comparison failed: {exc}"
            ) from exc
    return fired
