"""Prospective reserves: explicit state sum vs backward recursion."""

from __future__ import annotations

from decimal import Decimal

from life_reserves.models import Diagnosis, Inputs, Pricing, YearPath


def _v(inputs: Inputs) -> Decimal:
    return Decimal(1) / (Decimal(1) + inputs.interest_rate)


def reserves_state_sum(
    inputs: Inputs,
    paths: list[YearPath],
    pricing: Pricing,
) -> list[Decimal]:
    """Explicit state summation of reserves at each year boundary.

    ``reserve_k`` is the PV at boundary ``t = k`` (end of policy year ``k``,
    per policy alive there) of future benefits and expenses minus future
    gross premiums, for ``k = 0..n``.  "Future" means years ``k+1 .. n``.

    Future masses are built directly from conditional mortality given
    survival to ``k``: ``m_{k+1} = 1`` and
    ``m_j = prod_{r=k+1}^{j-1} p_r`` for later years; the conditioned death
    mass in year ``j`` is ``m_j * q_j``.  This keeps the per-alive reserve
    well-defined even when survival to boundary ``k`` has zero unconditional
    probability (e.g. certain earlier death), matching the backward
    recursion's per-policy interpretation rather than dividing aggregate
    issue-time flows by a zero mass.

    Re-indexing the year-``j`` discount factor from boundary ``k`` gives:

    * beginning timing: factor ``v**(j-k-1)``;
    * end timing: factor ``v**(j-k)``.

    The terminal reserve ``reserve_n`` is exactly zero (no future flows).
    """
    n = inputs.term
    beginning = inputs.timing.value == "beginning"
    v = _v(inputs)
    result: list[Decimal] = []

    for k in range(0, n + 1):
        total = Decimal(0)
        # Conditional mass of entering year j given alive at boundary k.
        entry_mass = Decimal(1)
        for j in range(k + 1, n + 1):
            path = paths[j - 1]
            if beginning:
                shifted = v ** (j - k - 1)
            else:
                shifted = v ** (j - k)
            death_mass = entry_mass * path.q_mortality
            entry_expense = (
                inputs.expenses.initial if j == 1 else inputs.expenses.renewal
            )
            expected_outgo = (
                death_mass * (inputs.sum_assured + inputs.expenses.claim)
                + entry_mass * entry_expense
                - entry_mass * pricing.gross_premium
            )
            total += expected_outgo * shifted
            entry_mass *= path.p_conditional
        result.append(total)
    return result


def reserves_backward(
    inputs: Inputs, paths: list[YearPath], pricing: Pricing
) -> list[Decimal]:
    """Backward reserve recursion starting from the zero terminal reserve.

    Reserves are per-policy amounts conditional on being alive at the
    boundary.  Premiums and issue/renewal expenses are paid on entry into a
    year (by the alive policyholder); death benefit, claim expense and the
    next reserve apply with the conditional probabilities ``q_k`` / ``p_k``.

    Beginning timing (flows at the year's start, ``reserve_k`` at end):

        reserve_{k-1} + P - E_k
            = q_k*(S+Ec) + v*p_k*reserve_k

    End timing (premium/expenses and death flows at the year's end,
    ``reserve_k`` also at end):

        reserve_{k-1}
            = v * (q_k*(S+Ec) + p_k*reserve_k - P + E_k)

    Starting from ``reserve_n = 0`` (contract boundary) the recursion walks
    backwards to ``reserve_0``.
    """
    n = inputs.term
    v = _v(inputs)
    beginning = inputs.timing.value == "beginning"
    reserve = [Decimal(0)] * (n + 1)

    for k in range(n, 0, -1):
        path = paths[k - 1]
        q = path.q_mortality
        p = path.p_conditional
        claim_outgo = inputs.sum_assured + inputs.expenses.claim
        entry_expense = (
            inputs.expenses.initial if k == 1 else inputs.expenses.renewal
        )
        premium = pricing.gross_premium
        # Conditional expected net outgo at the year-end boundary:
        # death claim (mass q), survivor reserve (mass p), renewal/issue
        # expense (paid on entry) minus the premium collected on entry.
        end_term = v * (
            q * claim_outgo + p * reserve[k] - premium + entry_expense
        )
        if beginning:
            # Premium, entry expense and the claim are valued at the
            # year's start; only the survivor reserve is one year away:
            # V_{k-1} = q*claim + v*p*V_k - P + E.
            reserve[k - 1] = (
                q * claim_outgo + v * p * reserve[k] - premium + entry_expense
            )
        else:
            reserve[k - 1] = end_term
    return reserve


def reconcile(
    inputs: Inputs,
    paths: list[YearPath],
    state_sum: list[Decimal],
    backward: list[Decimal],
    tolerance: Decimal,
) -> list[Diagnosis]:
    """Compare both reserve methods boundary by boundary.

    Returns one :class:`Diagnosis` per boundary whose absolute difference
    exceeds ``tolerance``.  The engine never picks one method over the other:
    any disagreement is surfaced to the caller.
    """
    diagnostics: list[Diagnosis] = []
    for k in range(inputs.term + 1):
        a = state_sum[k]
        b = backward[k]
        if abs(a - b) > tolerance:
            diagnostics.append(
                Diagnosis(
                    code="RESERVE_METHOD_MISMATCH",
                    detail=(
                        f"year boundary t={k} (age {inputs.issue_age + k}): "
                        f"state-sum reserve {a} != backward reserve {b}"
                    ),
                    expected=a,
                    actual=b,
                    tolerance=tolerance,
                )
            )
    return diagnostics
