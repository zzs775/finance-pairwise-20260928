"""Local synthetic data generation (standard library only).

The sample is deliberately emitted in shuffled order with exact duplicate
records so callers can demonstrate idempotent normalization.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal

from .models import (
    Account,
    CashFlow,
    CashFlowTiming,
    EngineInput,
    Membership,
    Valuation,
)


def make_sample_input() -> EngineInput:
    """Build a deterministic synthetic dataset covering the documented cases.

    Accounts:
      - ``alpha``: gains, an opening subscription and a closing full
        redemption to zero.
      - ``beta``: a loss that drives capital negative after a close flow.
      - ``gamma``: joins the composite halfway through.
    """
    d1 = date(2026, 1, 5)
    d2 = date(2026, 1, 6)
    d3 = date(2026, 1, 7)
    d4 = date(2026, 1, 8)

    accounts = [
        Account("alpha"),
        Account("beta"),
        Account("gamma"),
        Account("delta"),
    ]

    valuations = [
        Valuation("alpha", d1, Decimal("100.00"), Decimal("102.00")),
        Valuation("alpha", d2, Decimal("102.00"), Decimal("210.00")),
        Valuation("alpha", d3, Decimal("210.00"), Decimal("220.00")),
        Valuation("alpha", d4, Decimal("220.00"), Decimal("0.00")),
        Valuation("beta", d1, Decimal("80.00"), Decimal("78.00")),
        Valuation("beta", d2, Decimal("78.00"), Decimal("20.00")),
        Valuation("beta", d3, Decimal("20.00"), Decimal("0.00")),
        Valuation("beta", d4, Decimal("0.00"), Decimal("-5.00")),
        Valuation("gamma", d1, Decimal("50.00"), Decimal("51.00")),
        Valuation("gamma", d2, Decimal("51.00"), Decimal("53.00")),
        Valuation("gamma", d3, Decimal("53.00"), Decimal("52.00")),
        Valuation("gamma", d4, Decimal("52.00"), Decimal("54.00")),
        Valuation("delta", d1, Decimal("10.00"), Decimal("10.00")),
        Valuation("delta", d2, Decimal("10.00"), Decimal("-5.00")),
        Valuation("delta", d3, Decimal("-5.00"), Decimal("-8.00")),
        Valuation("delta", d4, Decimal("-8.00"), Decimal("-8.00")),
    ]

    cash_flows = [
        CashFlow("alpha", d2, Decimal("100.00"), CashFlowTiming.OPEN),
        CashFlow("alpha", d4, Decimal("-220.00"), CashFlowTiming.CLOSE),
        CashFlow("beta", d2, Decimal("-60.00"), CashFlowTiming.CLOSE),
        CashFlow("delta", d2, Decimal("-15.00"), CashFlowTiming.CLOSE),
    ]

    memberships = [
        Membership("balanced", "alpha", d1, d4),
        Membership("balanced", "beta", d1, d4),
        Membership("balanced", "gamma", d3, d4),
    ]

    shuffled = list(reversed(valuations))
    duplicate_valuations = [valuations[0], valuations[0]]
    return EngineInput(
        valuations=shuffled + duplicate_valuations,
        cash_flows=list(reversed(cash_flows)) + [cash_flows[0]],
        accounts=list(reversed(accounts)),
        memberships=list(reversed(memberships)),
    )
