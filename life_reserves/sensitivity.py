"""Interest-rate sensitivity via full re-pricing under additive shocks."""

from __future__ import annotations

from dataclasses import replace
from decimal import Decimal

from life_reserves.cashflows import build_paths
from life_reserves.models import Inputs, SensitivityRow
from life_reserves.pricing import price

DEFAULT_SHOCKS: tuple[Decimal, ...] = (
    Decimal("-0.02"),
    Decimal("0"),
    Decimal("0.02"),
)


def sensitivity(
    inputs: Inputs, shocks: list[Decimal] | None = None
) -> list[SensitivityRow]:
    """Re-price the contract under each additive interest-rate shock.

    The shocked rate is ``interest_rate + shock`` and must stay strictly
    greater than ``-1`` (a non-positive discount factor is not defined).
    Result rows are returned stably ordered by interest rate, so reordering
    the requested shocks never changes the output order.
    """
    chosen = list(DEFAULT_SHOCKS if shocks is None else shocks)
    chosen.sort()
    rows: list[SensitivityRow] = []
    for shock in chosen:
        rate = inputs.interest_rate + shock
        if rate <= Decimal(-1):
            raise ValueError(
                f"shocked interest rate {rate} must be strictly greater than -1"
            )
        shocked_inputs = replace(inputs, interest_rate=rate)
        paths = build_paths(shocked_inputs)
        pricing = price(shocked_inputs, paths)
        rows.append(
            SensitivityRow(
                interest_rate=rate,
                net_premium=pricing.net_premium,
                gross_premium=pricing.gross_premium,
                pv_benefit=pricing.pv_benefit,
                pv_expense=pricing.pv_expense,
            )
        )
    return rows
