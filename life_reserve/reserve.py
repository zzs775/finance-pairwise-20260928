"""Yearly reserves: explicit state summation vs backward recursion."""

from dataclasses import dataclass
from decimal import Decimal

from .model import ONE, Policy, ZERO
from .cashflows import discount_factor
from .probability import YearState


@dataclass(frozen=True)
class ReserveRow:
    """Reserves at the start of one policy year (per in-force life)."""

    year: int
    age: int
    in_force: Decimal
    reserve_explicit: Decimal
    reserve_recursive: Decimal


@dataclass(frozen=True)
class ReserveDiagnostic:
    """Diagnostic returned when the two methods disagree beyond tolerance."""

    year: int
    explicit: Decimal
    recursive: Decimal
    difference: Decimal
    tolerance: Decimal


@dataclass(frozen=True)
class ReserveResult:
    rows: list[ReserveRow]
    diagnostics: list[ReserveDiagnostic]


def compute_reserves(
    policy: Policy,
    states: list[YearState],
    gross_premium: Decimal,
    tolerance: Decimal,
) -> ReserveResult:
    """Yearly gross-premium reserves by two independent methods.

    Both methods value per life still in force at the start of year t.

    All three cash-flow legs sit at the same yearly node, ordered
    premium -> expense -> death benefit; consecutive nodes are one year
    apart. R_t is the reserve per in-force life at the node of year t.

    Method A - explicit state summation (prospective), cohort level:
        C_t = in_force_t * (q_t*S + e_t - P) + v * C_{t+1},
        R_t = C_t / in_force_t.

    Method B - backward recursion, per in-force life:
        R_{n+1} = 0 at the contract boundary,
        R_t     = q_t*S + e_t - P + v * p_t * R_{t+1}.
    BEGINNING places the node at t-1, END at t; the recursion is identical,
    only the physical time of the node changes.

    Mismatches beyond ``tolerance`` are returned as diagnostics; neither
    method is selected over the other.
    """
    n = len(states)
    rows: list[ReserveRow] = []
    diagnostics: list[ReserveDiagnostic] = []

    # --- Method A: prospective explicit summation, built from the end ---
    explicit: dict[int, Decimal] = {}
    cohort_value = ZERO
    v = ONE / (ONE + policy.interest_rate)
    exp = policy.expenses
    for idx in range(n - 1, -1, -1):
        state = states[idx]
        per_life_expense = (
            exp.percent_of_premium * gross_premium
            + exp.per_policy
            + (exp.initial_expense if state.year == 1 else ZERO)
        )
        year_value = (
            state.in_force
            * (state.q * policy.sum_assured + per_life_expense - gross_premium)
        )
        cohort_value = year_value + v * cohort_value
        explicit[state.year] = (
            cohort_value / state.in_force
            if state.in_force != ZERO
            else ZERO
        )

    # --- Method B: backward recursion, terminal reserve zero ---
    recursive: dict[int, Decimal] = {}
    next_reserve = ZERO  # R_{n+1} = 0 at the contract boundary
    for idx in range(n - 1, -1, -1):
        state = states[idx]
        per_life_expense = (
            exp.percent_of_premium * gross_premium
            + exp.per_policy
            + (exp.initial_expense if state.year == 1 else ZERO)
        )
        reserve = (
            state.q * policy.sum_assured
            + per_life_expense
            - gross_premium
            + v * state.p * next_reserve
        )
        recursive[state.year] = reserve
        next_reserve = reserve

    terminal_in_force = states[-1].survive_prob if states else ZERO
    for state in states:
        a = explicit[state.year]
        b = recursive[state.year]
        difference = abs(a - b)
        if difference > tolerance:
            diagnostics.append(
                ReserveDiagnostic(
                    year=state.year,
                    explicit=a,
                    recursive=b,
                    difference=difference,
                    tolerance=tolerance,
                )
            )
        rows.append(
            ReserveRow(
                year=state.year,
                age=state.age,
                in_force=state.in_force,
                reserve_explicit=a,
                reserve_recursive=b,
            )
        )

    # Contract boundary: R_{n+1} = 0 by definition for both methods.
    rows.append(
        ReserveRow(
            year=n + 1,
            age=policy.issue_age + n,
            in_force=terminal_in_force,
            reserve_explicit=ZERO,
            reserve_recursive=ZERO,
        )
    )

    return ReserveResult(rows=rows, diagnostics=diagnostics)
