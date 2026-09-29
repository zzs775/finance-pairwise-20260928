"""Top-level orchestration: validate, price, reserve and reconcile."""

from __future__ import annotations

from decimal import Decimal

from life_reserves.cashflows import build_cashflows, build_paths
from life_reserves.mortality import validate_mortality
from life_reserves.models import EngineResult, Inputs, ReserveRow
from life_reserves.pricing import price
from life_reserves.reserves import (
    reconcile,
    reserves_backward,
    reserves_state_sum,
)
from life_reserves.sensitivity import sensitivity as run_sensitivity

DEFAULT_TOLERANCE = Decimal("0.000001")


def run(
    inputs: Inputs,
    rate_shocks: list[Decimal] | None = None,
    tolerance: Decimal = DEFAULT_TOLERANCE,
) -> EngineResult:
    """Run the full reserve engine.

    Steps: validate inputs and the mortality table; build probability paths;
    price net/gross premiums; build per-year cash flows; compute reserves by
    both explicit state summation and backward recursion; reconcile them
    (mismatches become :class:`Diagnosis` entries, never a silent choice);
    and re-price under the requested interest-rate shocks.
    """
    _validate_scalars(inputs)
    validate_mortality(inputs.issue_age, inputs.term, inputs.mortality)

    paths = build_paths(inputs)
    pricing = price(inputs, paths)
    cashflows = build_cashflows(inputs, paths, pricing.gross_premium)

    state_sum = reserves_state_sum(inputs, paths, pricing)
    backward = reserves_backward(inputs, paths, pricing)
    diagnostics = reconcile(
        inputs, paths, state_sum, backward, tolerance
    )

    reserves = [
        ReserveRow(
            year=k,
            age=inputs.issue_age + k,
            p_enter=Decimal(1) if k == 0 else paths[k - 1].p_survive,
            reserve_state_sum=state_sum[k],
            reserve_backward=backward[k],
        )
        for k in range(inputs.term + 1)
    ]

    sens = run_sensitivity(inputs, rate_shocks)

    return EngineResult(
        inputs=inputs,
        pricing=pricing,
        paths=paths,
        cashflows=cashflows,
        reserves=reserves,
        sensitivity=sens,
        diagnostics=diagnostics,
    )


def _validate_scalars(inputs: Inputs) -> None:
    if not isinstance(inputs.issue_age, int) or inputs.issue_age < 0:
        raise ValueError("issue_age must be a non-negative integer")
    if not isinstance(inputs.term, int) or inputs.term <= 0:
        raise ValueError("term must be a positive integer")
    if not isinstance(inputs.sum_assured, Decimal) or inputs.sum_assured < 0:
        raise ValueError("sum_assured must be a non-negative Decimal")
    if (
        not isinstance(inputs.interest_rate, Decimal)
        or inputs.interest_rate <= Decimal(-1)
    ):
        raise ValueError(
            "interest_rate must be a Decimal strictly greater than -1"
        )
    for name in ("initial", "renewal", "claim"):
        value = getattr(inputs.expenses, name)
        if not isinstance(value, Decimal) or value < 0:
            raise ValueError(f"expenses.{name} must be a non-negative Decimal")
