"""Direct term-expansion reference enumerator for small contracts."""

from .models import Contract
from .fixed import Decimal, ZERO, product, round_line
from . import stages


def enumerate_reference(contract: Contract, scenarios) -> list[dict]:
    """Brute-force per-period enumeration of marginal PD, range, weighting.

    The enumeration is intentionally written as a flat, independent loop so
    tests can cross-check the streaming engine against explicit per-period
    facts: marginal probability, stage window coverage and scenario weights.
    """
    cumulative = list(contract.cumulative_pd.values)
    start, end = stages.loss_period_range(
        contract.stage, contract.remaining_periods)

    expected_periods = list(range(1, end + 1))
    assert expected_periods[0] == start

    records: list[dict] = []
    previous = ZERO
    for period in range(1, contract.remaining_periods + 1):
        marginal = cumulative[period - 1] - previous
        previous = cumulative[period - 1]
        in_loss_window = start <= period <= end
        for scenario in scenarios:
            if in_loss_window:
                lgd = contract.lgd.at(period)
                ead = contract.ead.at(period)
                discount = contract.discount.at(period)
                raw = product(marginal, lgd, ead, discount, scenario.weight)
                ecl = round_line(raw)
            else:
                raw = ZERO
                ecl = ZERO
            records.append({
                "contract_id": contract.contract_id,
                "period": period,
                "scenario": scenario.name,
                "marginal_pd": marginal,
                "in_loss_window": in_loss_window,
                "raw_product": raw,
                "ecl": ecl,
            })
    return records


def reference_total(contract: Contract, scenarios) -> Decimal:
    """Sum the enumerated ECL lines (exact add of rounded lines)."""
    records = enumerate_reference(contract, scenarios)
    return sum((record["ecl"] for record in records), ZERO)
