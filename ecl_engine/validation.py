"""Structural validation: PD boundaries, EAD sign, weight closure, gaps."""

from decimal import Decimal

from .scales import D, ONE, RATE_QUANT, ZERO, fixed_sum, is_finite


class ValidationError(ValueError):
    """Raised when an input contract or assumption set is classified invalid."""


def validate_cumulative_pd(values, *, label: str = "cumulative_pd") -> None:
    """Require finite values in [0, 1], non-decreasing over periods."""
    previous: Decimal | None = None
    if not values:
        raise ValidationError(f"{label}: empty term structure")
    for period, raw in enumerate(values, start=1):
        value = D(raw)
        if not is_finite(value):
            raise ValidationError(f"{label}[{period}]: non-finite value")
        if value < ZERO or value > ONE:
            raise ValidationError(
                f"{label}[{period}]: cumulative PD {value} outside [0, 1]"
            )
        if previous is not None and value < previous:
            raise ValidationError(
                f"{label}[{period}]: cumulative PD decreases {previous} -> {value}"
            )
        previous = value


def validate_nonnegative_amounts(values, *, label: str = "ead") -> None:
    """Require finite, non-negative EAD amounts."""
    if not values:
        raise ValidationError(f"{label}: empty term structure")
    for period, raw in enumerate(values, start=1):
        value = D(raw)
        if not is_finite(value):
            raise ValidationError(f"{label}[{period}]: non-finite value")
        if value < ZERO:
            raise ValidationError(f"{label}[{period}]: negative amount {value}")


def validate_weights(weights, tolerance: Decimal, *, label: str = "weights") -> Decimal:
    """Require finite, non-negative weights that close to 1.

    Returns the residual (tail difference) that must be absorbed explicitly.
    """
    if not weights:
        raise ValidationError(f"{label}: at least one scenario weight required")
    seen: set[str] = set()
    decimals: list[Decimal] = []
    for name, raw in weights:
        if name in seen:
            raise ValidationError(f"{label}: duplicate scenario name {name!r}")
        seen.add(name)
        value = D(raw)
        if not is_finite(value):
            raise ValidationError(f"{label}[{name}]: non-finite weight")
        if value < ZERO:
            raise ValidationError(f"{label}[{name}]: negative weight {value}")
        decimals.append(value)
    total = fixed_sum(decimals, RATE_QUANT)
    residual = ONE - total
    if abs(residual) > tolerance:
        raise ValidationError(
            f"{label}: weights do not close to 1 (sum={total}, "
            f"residual={residual}, tolerance={tolerance})"
        )
    return residual


def validate_term_coverage(
    length: int, required: int, *, label: str = "term"
) -> None:
    """Require contiguous term coverage; raise on gaps or shortfall."""
    if length < 0:
        raise ValidationError(f"{label}: negative length {length}")
    if length < required:
        raise ValidationError(
            f"{label}: term gap, coverage {length} < required {required}"
        )


def validate_curve_periods(points, required: int, *, label: str = "curve") -> None:
    """Require periods to be 1-based, contiguous integers covering ``required``."""
    if not points:
        raise ValidationError(f"{label}: empty curve")
    periods = [p.period for p in points]
    expected = list(range(1, len(periods) + 1))
    if periods != expected:
        raise ValidationError(
            f"{label}: periods must be 1-based and contiguous, got {periods}"
        )
    validate_term_coverage(len(points), required, label=label)


def validate_rate_series(values, *, label: str = "rate") -> None:
    """Finite, non-negative rate values (used for LGD and discount factors)."""
    if not values:
        raise ValidationError(f"{label}: empty term structure")
    for period, raw in enumerate(values, start=1):
        value = D(raw)
        if not is_finite(value):
            raise ValidationError(f"{label}[{period}]: non-finite value")
        if value < ZERO:
            raise ValidationError(f"{label}[{period}]: negative value {value}")
