"""Curve conversions: cumulative PD to marginal PD, term alignment."""

from decimal import Decimal

from .fixed import ZERO


def marginal_pd(cumulative) -> list[Decimal]:
    """Convert non-decreasing cumulative PD series to per-period marginals."""
    result: list[Decimal] = []
    previous = ZERO
    for value in cumulative:
        result.append(value - previous)
        previous = value
    return result


def slice_periods(values, start: int, end: int) -> list[Decimal]:
    """Return values for inclusive 1-based period range [start, end]."""
    if start < 1 or end < start or end > len(values):
        from .errors import ValidationError

        raise ValidationError(
            f"period range [{start}, {end}] invalid for length {len(values)}")
    return list(values[start - 1:end])
