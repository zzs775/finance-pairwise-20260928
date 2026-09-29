"""Probability path and expected cash-flow construction."""

from __future__ import annotations

from decimal import Decimal

from life_reserves.mortality import conditional_survival
from life_reserves.models import CashFlowRow, Inputs, YearPath


def build_paths(inputs: Inputs) -> list[YearPath]:
    """Derive yearly death/survival probabilities from conditional survival.

    For policy year ``k`` (1-indexed) with attained age ``x = issue_age+k-1``:

    * ``p_enter_1 = 1`` and ``p_enter_k = prod_{j<k} (1 - q_j)``;
    * ``p_death_k = p_enter_k * q_k``;
    * ``p_survive_k = p_enter_k * (1 - q_k)``;
    * therefore ``p_death_k + p_survive_k = p_enter_k``.
    """
    rates = inputs.mortality
    paths: list[YearPath] = []
    p_enter = Decimal(1)
    for k in range(1, inputs.term + 1):
        age = inputs.issue_age + k - 1
        q = rates[age]
        p = conditional_survival(q)
        p_death = p_enter * q
        p_survive = p_enter * p
        paths.append(
            YearPath(
                year=k,
                age=age,
                q_mortality=q,
                p_conditional=p,
                p_enter=p_enter,
                p_death=p_death,
                p_survive=p_survive,
            )
        )
        p_enter = p_survive
    return paths


def build_cashflows(
    inputs: Inputs, paths: list[YearPath], gross_premium: Decimal
) -> list[CashFlowRow]:
    """Build expected cash flows and their present values.

    Within each policy year the ordering is: (1) the policyholder enters the
    year alive, (2) the annual gross premium is collected, (3) issue/renewal
    expenses are incurred, and (4) mortality is observed and the death
    benefit plus claim expense are paid for deaths during the year.

    With ``Timing.BEGINNING`` all year-``k`` flows are valued at ``t = k-1``;
    with ``Timing.END`` they are valued at ``t = k``.  Let ``v = 1/(1+i)``.
    """
    v = Decimal(1) / (Decimal(1) + inputs.interest_rate)
    rows: list[CashFlowRow] = []
    for path in paths:
        k = path.year
        t = k - 1 if inputs.timing.value == "beginning" else k
        discount = v ** t

        premium_expected = gross_premium * path.p_enter
        per_policy_expense = (
            inputs.expenses.initial if k == 1 else inputs.expenses.renewal
        )
        expense_expected = (
            per_policy_expense * path.p_enter
            + inputs.expenses.claim * path.p_death
        )
        benefit_expected = inputs.sum_assured * path.p_death

        rows.append(
            CashFlowRow(
                year=k,
                age=path.age,
                premium_expected=premium_expected,
                expense_expected=expense_expected,
                benefit_expected=benefit_expected,
                premium_pv=premium_expected * discount,
                expense_pv=expense_expected * discount,
                benefit_pv=benefit_expected * discount,
            )
        )
    return rows
