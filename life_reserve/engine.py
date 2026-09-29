"""Top-level valuation, rate sensitivity and synthetic data."""

from dataclasses import dataclass
from dataclasses import replace
from decimal import Decimal

from .cashflows import YearCashFlow, build_cashflows
from .model import MortalityTable, ONE, Policy, ZERO
from .pricing import PricingResult, price
from .probability import YearState, project_probabilities
from .reserve import ReserveResult, compute_reserves


@dataclass(frozen=True)
class ValuationResult:
    """Full valuation output bundle."""

    policy: Policy
    states: list[YearState]
    cashflows: list[YearCashFlow]
    pricing: PricingResult
    reserves: ReserveResult


def run_valuation(
    policy: Policy, table: MortalityTable, tolerance: Decimal = Decimal("0.01")
) -> ValuationResult:
    states = project_probabilities(policy, table)
    pricing_result = price(policy, states)
    cashflow_rows = build_cashflows(policy, states, pricing_result.gross_premium)
    reserve_result = compute_reserves(
        policy, states, pricing_result.gross_premium, tolerance
    )
    return ValuationResult(
        policy=policy,
        states=states,
        cashflows=cashflow_rows,
        pricing=pricing_result,
        reserves=reserve_result,
    )


def rate_sensitivity(
    policy: Policy,
    table: MortalityTable,
    bumps: tuple[Decimal, ...] = (Decimal("-0.01"), Decimal("0.01")),
    tolerance: Decimal = Decimal("0.01"),
) -> list[dict]:
    """Re-price at the base rate and at each deterministic rate shock.

    Rows are returned sorted by rate ascending so the output is stable.
    """
    rates = {policy.interest_rate}
    for bump in bumps:
        rates.add(policy.interest_rate + bump)

    rows: list[dict] = []
    for rate in sorted(rates):
        shocked = replace(policy, interest_rate=rate)
        valuation = run_valuation(shocked, table, tolerance)
        rows.append(
            {
                "rate": rate,
                "net_premium": valuation.pricing.net_premium,
                "gross_premium": valuation.pricing.gross_premium,
                "benefit_pv": valuation.pricing.benefit_pv,
                "expense_pv": valuation.pricing.expense_pv,
            }
        )
    return rows


def synthetic_table(issue_age: int, term: int) -> MortalityTable:
    """Deterministic Gompertz-style synthetic q_x over the covered ages.

    q_x = A + B * C**x, capped just below 1. Purely synthetic constants,
    no external data. Output is independent of dict insertion order.
    """
    if issue_age < 0 or term < 1:
        raise ValueError("issue_age must be >= 0 and term >= 1")
    a = Decimal("0.0001")
    b = Decimal("0.00002")
    c = Decimal("1.09")
    cap = ONE - Decimal("0.000001")
    rates: dict[int, Decimal] = {}
    for age in range(issue_age, issue_age + term):
        q = a + b * (c ** age)
        rates[age] = min(q, cap)
    return MortalityTable(rates=rates)
