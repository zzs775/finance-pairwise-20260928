"""Direct term-expansion reference oracle for small contracts.

The reference enumerator deliberately re-implements the rule book in the
plainest possible nested loops (no engine helpers beyond parsing) so that it
can independently cross-check marginal conversion, stage windows and scenario
weighting.
"""

from .scales import (
    AMOUNT_QUANT,
    D,
    DETAIL_QUANT,
    RATE_QUANT,
    ZERO,
    quantize,
)
from .stages import STAGE1_HORIZON


def enumerate_reference(contract, assumptions) -> list[dict]:
    """Brute-force per-period enumeration of marginal PD, stage window, weights.

    Returns one dict per (scenario, period) in the same canonical order the
    engine uses (scenario declaration order, then period).
    """
    if contract.stage == 1:
        horizon = min(STAGE1_HORIZON, contract.remaining_periods)
    else:
        horizon = contract.remaining_periods

    inputs_by_name = {
        item.name: item
        for item in assumptions.inputs[contract.contract_id]
    }
    weights = {item.name: D(item.weight) for item in assumptions.scenarios}
    total_weight = sum(weights.values(), ZERO)
    residual = quantize(D(1) - total_weight, RATE_QUANT)

    rows: list[dict] = []
    first_name = assumptions.scenarios[0].name
    for scenario in assumptions.scenarios:
        name = scenario.name
        weight = quantize(weights[name], RATE_QUANT)
        if name == first_name:
            weight += residual
        scenario_input = inputs_by_name[name]
        cumulative = [
            quantize(point.value, RATE_QUANT)
            for point in scenario_input.cumulative_pd.points
        ]
        previous = ZERO
        for period in range(1, horizon + 1):
            marginal_pd = cumulative[period - 1] - previous
            previous = cumulative[period - 1]
            lgd = quantize(D(scenario_input.lgd.value_at(period)), RATE_QUANT)
            ead = quantize(D(scenario_input.ead.value_at(period)), AMOUNT_QUANT)
            discount = quantize(
                D(scenario_input.discount.value_at(period)), RATE_QUANT
            )
            period_ecl = quantize(
                marginal_pd * lgd * ead * discount, DETAIL_QUANT
            )
            weighted_ecl = quantize(weight * period_ecl, DETAIL_QUANT)
            rows.append(
                {
                    "scenario": name,
                    "period": period,
                    "marginal_pd": marginal_pd,
                    "lgd": lgd,
                    "ead": ead,
                    "discount_factor": discount,
                    "weight": weight,
                    "period_ecl": period_ecl,
                    "weighted_ecl": weighted_ecl,
                }
            )
    return rows


def verify_against_engine(contract, assumptions, priced) -> None:
    """Assert engine detail lines match enumerator multipliers row by row."""
    rows = enumerate_reference(contract, assumptions)
    lines = priced.lines
    if len(rows) != len(lines):
        raise AssertionError(
            f"row count mismatch: reference={len(rows)} engine={len(lines)}"
        )
    for row, line in zip(rows, lines):
        for key in (
            "scenario",
            "period",
            "marginal_pd",
            "lgd",
            "ead",
            "discount_factor",
            "weight",
            "period_ecl",
        ):
            if row[key] != getattr(line, key):
                raise AssertionError(
                    f"{key} mismatch at {row['scenario']}/p{row['period']}: "
                    f"reference={row[key]} engine={getattr(line, key)}"
                )
