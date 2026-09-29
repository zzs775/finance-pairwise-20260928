"""Net premium, gross premium and present values."""

from dataclasses import dataclass
from decimal import Decimal

from .model import ONE, Policy, ZERO
from .cashflows import build_cashflows, discount_factor
from .probability import YearState


@dataclass(frozen=True)
class PricingResult:
    """Pricing outputs under the equivalence principle."""

    net_premium: Decimal
    gross_premium: Decimal
    benefit_pv: Decimal
    expense_pv: Decimal
    net_premium_pv: Decimal
    gross_premium_pv: Decimal
    annuity_factor: Decimal


def price(policy: Policy, states: list[YearState]) -> PricingResult:
    """Equivalence-principle pricing.

    Net premium :  P_net * ddot_a = PV(death benefit)
    Gross premium:  P_gross * ddot_a * (1 - e)
                       = PV(benefit) + PV(per-policy + initial expenses)
    where ddot_a = sum_t v^exponent * (t-1 p_x) is the premium annuity factor
    and e is the premium-proportional expense fraction.
    """
    annuity_factor = ZERO
    benefit_pv = ZERO
    per_life_expense_pv = ZERO
    for state in states:
        v_t = discount_factor(policy, state.year)
        annuity_factor += v_t * state.in_force
        benefit_pv += v_t * state.death_prob * policy.sum_assured
        per_life_expense = policy.expenses.per_policy + (
            policy.expenses.initial_expense if state.year == 1 else ZERO
        )
        per_life_expense_pv += v_t * state.in_force * per_life_expense

    if annuity_factor <= ZERO:
        raise ValueError("premium annuity factor is non-positive; cannot price")

    net_premium = benefit_pv / annuity_factor

    e = policy.expenses.percent_of_premium
    gross_base = annuity_factor * (ONE - e)
    if gross_base <= ZERO:
        raise ValueError(
            "premium-expense loading leaves no positive premium-paying base"
        )
    gross_premium = (benefit_pv + per_life_expense_pv) / gross_base

    gross_cashflows = build_cashflows(policy, states, gross_premium)
    gross_premium_pv = ZERO
    expense_pv = ZERO
    for row in gross_cashflows:
        v_t = row.discount_factor
        gross_premium_pv += v_t * row.premium
        expense_pv += v_t * row.expense
    net_premium_pv = net_premium * annuity_factor

    return PricingResult(
        net_premium=net_premium,
        gross_premium=gross_premium,
        benefit_pv=benefit_pv,
        expense_pv=expense_pv,
        net_premium_pv=net_premium_pv,
        gross_premium_pv=gross_premium_pv,
        annuity_factor=annuity_factor,
    )
