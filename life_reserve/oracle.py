"""Mechanical oracle: closure, recursion and probability-mass identities."""

from dataclasses import dataclass
from decimal import Decimal

from .model import MortalityTable, ONE, Policy, ZERO
from .engine import run_valuation


def _check(name: str, passed: bool, detail: str) -> "OracleCheck":
    return OracleCheck(name=name, passed=bool(passed), detail=detail)


def _within(a: Decimal, b: Decimal, tol: Decimal) -> bool:
    return abs(a - b) <= tol


@dataclass(frozen=True)
class OracleCheck:
    name: str
    passed: bool
    detail: str


@dataclass(frozen=True)
class OracleReport:
    passed: bool
    checks: list[OracleCheck]


def run_oracle(
    policy: Policy,
    table: MortalityTable,
    valuation: object,
    tolerance: Decimal,
) -> OracleReport:
    """Run the four mechanical identities and report, never raise on failure.

    Checks
    ------
    1. Zero mortality  : with all q_x = 0, death-benefit PV is exactly 0.
    2. PV closure      : gross-premium PV = benefit PV + expense PV.
    3. Recursion       : R_t = q_t*S + e_t - P + v*p_t*R_{t+1} for every
                         year, and the terminal reserve R_{n+1} = 0.
    4. Probability mass: death_t + survive_t == in_force_t for every year.
    """
    checks: list[OracleCheck] = []
    tol = Decimal(tolerance)

    # --- 1. zero mortality oracle on a freshly built all-zero table ---
    zero_rates = {
        age: ZERO
        for age in range(policy.issue_age, policy.issue_age + policy.term)
    }
    zero_table = MortalityTable(rates=zero_rates)
    zero_val = run_valuation(policy, zero_table, tol)
    checks.append(
        _check(
            "zero_mortality_benefit_pv",
            zero_val.pricing.benefit_pv == ZERO,
            f"benefit_pv at q=0 is {zero_val.pricing.benefit_pv}",
        )
    )

    # --- 2. present-value closure (zero table: premium == expenses) ---
    zero_gap = (
        zero_val.pricing.gross_premium_pv
        - zero_val.pricing.benefit_pv
        - zero_val.pricing.expense_pv
    )
    checks.append(
        _check(
            "zero_mortality_premium_expense_closure",
            _within(zero_gap, ZERO, tol),
            f"premium_pv - benefit_pv - expense_pv = {zero_gap} (tol {tol})",
        )
    )
    # same closure on the real table
    real_gap = (
        valuation.pricing.gross_premium_pv
        - valuation.pricing.benefit_pv
        - valuation.pricing.expense_pv
    )
    checks.append(
        _check(
            "premium_benefit_expense_closure",
            _within(real_gap, ZERO, tol),
            f"premium_pv - benefit_pv - expense_pv = {real_gap} (tol {tol})",
        )
    )

    # --- 3. yearly recursion equation, using the provided valuation ---
    rows = valuation.reserves.rows
    row_by_year = {row.year: row for row in rows}
    states = valuation.states
    exp = policy.expenses
    premium = valuation.pricing.gross_premium
    v_disc = ONE / (ONE + policy.interest_rate)
    recursion_ok = True
    recursion_detail = "all years satisfy R_t = qS + e - P + v*p*R_{t+1}"
    for state in states:
        r_t = row_by_year[state.year].reserve_recursive
        r_next = row_by_year[state.year + 1].reserve_recursive
        per_life_expense = (
            exp.percent_of_premium * premium
            + exp.per_policy
            + (exp.initial_expense if state.year == 1 else ZERO)
        )
        expected = (
            state.q * policy.sum_assured
            + per_life_expense
            - premium
            + v_disc * state.p * r_next
        )
        if not _within(r_t, expected, tol):
            recursion_ok = False
            recursion_detail = (
                f"year {state.year}: R_t={r_t} vs expected {expected}"
            )
            break
    checks.append(_check("yearly_recursion", recursion_ok, recursion_detail))

    terminal = row_by_year[policy.term + 1]
    checks.append(
        _check(
            "terminal_reserve_zero",
            terminal.reserve_explicit == ZERO
            and terminal.reserve_recursive == ZERO,
            f"R_{{n+1}} explicit={terminal.reserve_explicit} "
            f"recursive={terminal.reserve_recursive}",
        )
    )

    # --- 4. probability mass conservation ---
    mass_ok = True
    mass_detail = "death_t + survive_t == in_force_t for all years"
    for state in states:
        if state.death_prob + state.survive_prob != state.in_force:
            mass_ok = False
            mass_detail = (
                f"year {state.year}: {state.death_prob} + {state.survive_prob}"
                f" != {state.in_force}"
            )
            break
    # the final survivor mass plus all deaths must total one
    total_deaths = sum((s.death_prob for s in states), ZERO)
    final_survivors = states[-1].survive_prob
    checks.append(
        _check(
            "probability_mass",
            mass_ok and _within(total_deaths + final_survivors, ONE, tol),
            mass_detail
            + f"; total={total_deaths + final_survivors}",
        )
    )

    return OracleReport(
        passed=all(check.passed for check in checks), checks=checks
    )
