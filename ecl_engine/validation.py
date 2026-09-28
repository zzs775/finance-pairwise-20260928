"""Validation rules for curves, weights and contract inputs."""

from decimal import Decimal

from .errors import ValidationError
from .fixed import ZERO, ONE, q, is_finite
from .models import Contract, AssumptionSet


def _require_length(values, length: int, name: str) -> None:
    if len(values) != length:
        raise ValidationError(
            f"{name} length {len(values)} does not match required "
            f"term length {length} (term gap)")


def _check_finite(values, name: str) -> None:
    for index, value in enumerate(values, start=1):
        if not is_finite(value):
            raise ValidationError(f"{name}[{index}] is not finite: {value}")


def validate_cumulative_pd(values) -> None:
    """0 <= pd <= 1, monotonic non-decreasing, finite; else ValidationError."""
    _check_finite(values, "cumulative_pd")
    previous = ZERO
    for index, value in enumerate(values, start=1):
        if value < ZERO or value > ONE:
            raise ValidationError(
                f"cumulative_pd[{index}]={value} outside [0, 1]")
        if value < previous:
            raise ValidationError(
                f"cumulative_pd[{index}]={value} declines from {previous}")
        previous = value


def validate_nonneg_curve(values, name: str) -> None:
    """All entries finite and non-negative."""
    _check_finite(values, name)
    for index, value in enumerate(values, start=1):
        if value < ZERO:
            raise ValidationError(f"{name}[{index}] is negative: {value}")


def validate_unit_interval_curve(values, name: str) -> None:
    """All entries finite and within [0, 1]."""
    _check_finite(values, name)
    for index, value in enumerate(values, start=1):
        if value < ZERO or value > ONE:
            raise ValidationError(
                f"{name}[{index}]={value} outside [0, 1]")


def validate_weights(scenarios) -> Decimal:
    """Weights finite, non-negative and sum exactly to 1."""
    if not scenarios:
        raise ValidationError("at least one scenario is required")
    names: set[str] = set()
    total = ZERO
    for scenario in scenarios:
        if scenario.name in names:
            raise ValidationError(f"duplicate scenario name: {scenario.name}")
        names.add(scenario.name)
        weight = scenario.weight
        if not is_finite(weight):
            raise ValidationError(
                f"weight of scenario {scenario.name} is not finite")
        if weight < ZERO:
            raise ValidationError(
                f"weight of scenario {scenario.name} is negative: {weight}")
        total += weight
    if total != ONE:
        raise ValidationError(
            f"scenario weights do not close to 1: {total}")
    return total


def validate_contract(contract: Contract) -> None:
    """Validate stage, period coverage and all curves of a contract."""
    if contract.stage not in (1, 2, 3):
        raise ValidationError(
            f"contract {contract.contract_id}: stage must be 1, 2 or 3, "
            f"got {contract.stage}")
    periods = contract.remaining_periods
    if not isinstance(periods, int) or periods <= 0:
        raise ValidationError(
            f"contract {contract.contract_id}: remaining_periods must be a "
            f"positive integer, got {periods}")
    curves = (
        ("cumulative_pd", contract.cumulative_pd),
        ("lgd", contract.lgd),
        ("ead", contract.ead),
        ("discount", contract.discount),
    )
    for name, curve in curves:
        _require_length(curve.values, periods, name)
    validate_cumulative_pd(contract.cumulative_pd.values)
    validate_unit_interval_curve(contract.lgd.values, "lgd")
    validate_nonneg_curve(contract.ead.values, "ead")
    validate_unit_interval_curve(contract.discount.values, "discount")


def validate_assumptions(assumptions: AssumptionSet) -> None:
    """Validate an assumption set before publication."""
    if not assumptions.version:
        raise ValidationError("assumption version must be non-empty")
    validate_weights(assumptions.scenarios)
    for contract_id, by_name in assumptions.curves.items():
        for name, curve in by_name.items():
            label = f"{assumptions.version}:{contract_id}.{name}"
            if name == "cumulative_pd":
                validate_cumulative_pd(curve.values)
            elif name in ("lgd", "discount"):
                validate_unit_interval_curve(curve.values, label)
            elif name == "ead":
                validate_nonneg_curve(curve.values, label)
            else:
                _check_finite(curve.values, label)
