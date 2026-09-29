"""Per-year cash flows with an explicit ordering of premium/expense/benefit."""

from dataclasses import dataclass
from decimal import Decimal

from .model import ONE, Policy, Timing, ZERO
from .probability import YearState


@dataclass(frozen=True)
class YearCashFlow:
    """Expected cash flows and discount factor for one policy year."""

    year: int
    age: int
    premium: Decimal
    expense: Decimal
    benefit: Decimal
    in_force: Decimal
    deaths: Decimal
    discount_factor: Decimal

    @property
    def net_outgo(self) -> Decimal:
        """Expected net cash outgo at the year node: benefit + expense - premium."""
        return self.benefit + self.expense - self.premium


def discount_factor(policy: Policy, year: int) -> Decimal:
    """v ** exponent: beginning -> t-1, end -> t, where v = 1/(1+i)."""
    v = ONE / (ONE + policy.interest_rate)
    exponent = year - 1 if policy.timing is Timing.BEGINNING else year
    return v ** exponent


def build_cashflows(
    policy: Policy, states: list[YearState], gross_premium: Decimal
) -> list[YearCashFlow]:
    """Build expected cash flows for each policy year.

    Ordering at the year node (explicit, identical for every year):
      1. gross premium is collected from every life entering the year;
      2. expenses are deducted (premium-proportional, per-policy, initial);
      3. the death benefit is settled for the deaths occurring in the year.
    All three legs sit at the same node; Timing selects the discount exponent.
    """
    if gross_premium < ZERO:
        raise ValueError("gross_premium must be non-negative")

    exp = policy.expenses
    rows: list[YearCashFlow] = []
    for state in states:
        premium = state.in_force * gross_premium
        per_life_expense = (
            exp.percent_of_premium * gross_premium
            + exp.per_policy
            + (exp.initial_expense if state.year == 1 else ZERO)
        )
        expense = state.in_force * per_life_expense
        benefit = state.death_prob * policy.sum_assured
        rows.append(
            YearCashFlow(
                year=state.year,
                age=state.age,
                premium=premium,
                expense=expense,
                benefit=benefit,
                in_force=state.in_force,
                deaths=state.death_prob,
                discount_factor=discount_factor(policy, state.year),
            )
        )
    return rows
