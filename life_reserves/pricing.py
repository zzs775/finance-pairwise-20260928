"""Net / gross premium pricing under the equivalence principle."""

from __future__ import annotations

from decimal import Decimal

from life_reserves.models import Inputs, Pricing, YearPath


def _discount_for_year(inputs: Inputs, year: int) -> Decimal:
    """Discount factor for a cash flow of ``year`` under the chosen timing."""
    v = Decimal(1) / (Decimal(1) + inputs.interest_rate)
    t = year - 1 if inputs.timing.value == "beginning" else year
    return v ** t


def price(inputs: Inputs, paths: list[YearPath]) -> Pricing:
    """Compute present values, net premium and expense-loaded gross premium.

    With ``v = 1/(1+i)`` and ``d_k = v**t_k`` where ``t_k`` is ``k-1`` for
    beginning timing and ``k`` for end timing:

    * annuity factor ``a = sum_k p_enter_k * d_k`` (PV of 1 paid on survival
      into each year, at the premium timing);
    * benefit PV ``A = sum_k p_death_k * S * d_k``;
    * expense PV ``E = E0*d_1 + sum_{k>1} p_enter_k*Er*d_k
      + sum_k p_death_k*Ec*d_k``;
    * net premium ``Pn = A / a`` (equivalence with benefits alone);
    * gross premium ``Pg = (A + E) / a`` (equivalence with benefits+expenses).
    """
    pv_benefit = Decimal(0)
    premium_factor = Decimal(0)
    pv_initial_expense = Decimal(0)
    pv_renewal_expense = Decimal(0)
    pv_claim_expense = Decimal(0)

    for path in paths:
        d = _discount_for_year(inputs, path.year)
        premium_factor += path.p_enter * d
        pv_benefit += inputs.sum_assured * path.p_death * d
        pv_claim_expense += inputs.expenses.claim * path.p_death * d
        if path.year == 1:
            # Issue expense is certain: paid once at contract issue.
            pv_initial_expense += inputs.expenses.initial * d
        else:
            pv_renewal_expense += inputs.expenses.renewal * path.p_enter * d

    if premium_factor == 0:
        raise ValueError("annuity factor is zero; premiums cannot be determined")

    pv_expense = pv_initial_expense + pv_renewal_expense + pv_claim_expense
    net_premium = pv_benefit / premium_factor
    gross_premium = (pv_benefit + pv_expense) / premium_factor

    return Pricing(
        net_premium=net_premium,
        gross_premium=gross_premium,
        pv_benefit=pv_benefit,
        pv_expense=pv_expense,
        pv_net_premium=net_premium * premium_factor,
        pv_gross_premium=gross_premium * premium_factor,
        annuity_factor=premium_factor,
    )
