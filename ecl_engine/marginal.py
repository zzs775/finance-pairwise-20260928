"""Conversion of cumulative PD term structures into per-period marginal PD."""

from decimal import Decimal

from .scales import D, RATE_QUANT, ZERO, quantize


def cumulative_to_marginal(cumulative_pd) -> list[Decimal]:
    """Convert quantised cumulative PDs into per-period marginal PDs.

    ``m_1 = q(c_1)`` and ``m_t = q(c_t) - q(c_{t-1})`` so the marginals are
    non-negative (cumulative PD is non-decreasing) and telescope exactly:
    ``sum(m_1..m_t) = q(c_t)`` with no rounding leak.
    """
    quantised = [quantize(D(value), RATE_QUANT) for value in cumulative_pd]
    marginals: list[Decimal] = []
    previous = ZERO
    for value in quantised:
        marginal = value - previous
        if marginal < ZERO:
            marginal = ZERO
        marginals.append(marginal)
        previous = value
    # Keep the telescoping identity after clamping.
    target = quantised[-1] if quantised else ZERO
    drift = target - sum(marginals, ZERO)
    if drift != ZERO:
        marginals[-1] = marginals[-1] + drift
    return marginals
