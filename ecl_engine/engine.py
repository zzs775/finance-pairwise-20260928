"""Per-period ECL expansion and scenario-weighted aggregation.

For every contract and scenario the engine emits one :class:`PeriodLine` per
loss period.  Each line records every multiplier (marginal PD, LGD, EAD,
discount factor), the scenario weight, both rounding adjustments and the
resulting weighted amount.  A contract total is *always* the exact sum of its
detail lines; the final line absorbs the last rounding cent.
"""

from decimal import Decimal

from .marginal import cumulative_to_marginal
from .models import (
    AssumptionSet,
    Contract,
    ContractECL,
    PeriodLine,
)
from .scales import (
    AMOUNT_QUANT,
    D,
    DETAIL_QUANT,
    RATE_QUANT,
    ZERO,
    fixed_sum,
    quantize,
)
from .stages import loss_periods
from .validation import (
    validate_cumulative_pd,
    validate_curve_periods,
    validate_nonnegative_amounts,
    validate_rate_series,
    validate_term_coverage,
    validate_weights,
)

#: Maximum accepted gap between summed weights and exactly 1.
WEIGHT_TOLERANCE = D("0.00000001")


def _scenario_inputs_for(assumptions: AssumptionSet, contract: Contract):
    try:
        inputs = assumptions.inputs[contract.contract_id]
    except KeyError as exc:
        raise KeyError(
            f"no assumption inputs for contract {contract.contract_id!r}"
        ) from exc
    weights = {weight.name: D(weight.weight) for weight in assumptions.scenarios}
    by_name = {item.name: item for item in inputs}
    if set(by_name) != set(weights):
        raise ValueError(
            f"contract {contract.contract_id!r}: scenario names do not match "
            f"weights ({sorted(by_name)} vs {sorted(weights)})"
        )
    return by_name, weights


def _validate_scenario_input(scenario, required: int) -> None:
    label = f"{scenario.name}"
    validate_curve_periods(
        scenario.cumulative_pd.points, required, label=f"{label}.pd"
    )
    validate_curve_periods(scenario.lgd.points, required, label=f"{label}.lgd")
    validate_curve_periods(scenario.ead.points, required, label=f"{label}.ead")
    validate_curve_periods(
        scenario.discount.points, required, label=f"{label}.discount"
    )
    validate_cumulative_pd(
        [point.value for point in scenario.cumulative_pd.points[:required]],
        label=f"{label}.pd",
    )
    validate_rate_series(
        [point.value for point in scenario.lgd.points[:required]],
        label=f"{label}.lgd",
    )
    validate_nonnegative_amounts(
        [point.value for point in scenario.ead.points[:required]],
        label=f"{label}.ead",
    )
    validate_rate_series(
        [point.value for point in scenario.discount.points[:required]],
        label=f"{label}.discount",
    )


def expand_period_lines(
    contract: Contract, assumptions: AssumptionSet
) -> list[PeriodLine]:
    """Enumerate every (period, scenario) multiplier line for a contract."""
    by_name, raw_weights = _scenario_inputs_for(assumptions, contract)
    ordered_names = [weight.name for weight in assumptions.scenarios]
    validate_term_coverage(
        contract.remaining_periods,
        contract.remaining_periods,
        label=f"{contract.contract_id}.term",
    )
    if contract.remaining_periods == 0:
        return []
    for name in ordered_names:
        _validate_scenario_input(by_name[name], contract.remaining_periods)
    residual = validate_weights(
        [(w.name, w.weight) for w in assumptions.scenarios],
        WEIGHT_TOLERANCE,
    )

    periods = list(loss_periods(contract.stage, contract.remaining_periods))
    lines: list[PeriodLine] = []
    # Absorb the closure residual on the first scenario deterministically.
    adjusted_weights = {
        name: quantize(raw_weights[name], RATE_QUANT) for name in ordered_names
    }
    adjusted_weights[ordered_names[0]] += residual

    for name in ordered_names:
        scenario = by_name[name]
        marginals = cumulative_to_marginal(
            [point.value for point in scenario.cumulative_pd.points]
        )
        weight = adjusted_weights[name]
        for period in periods:
            marginal_pd = marginals[period - 1]
            lgd = quantize(D(scenario.lgd.value_at(period)), RATE_QUANT)
            ead = quantize(D(scenario.ead.value_at(period)), AMOUNT_QUANT)
            discount_factor = quantize(
                D(scenario.discount.value_at(period)), RATE_QUANT
            )
            raw_period_ecl = marginal_pd * lgd * ead * discount_factor
            period_ecl = quantize(raw_period_ecl, DETAIL_QUANT)
            period_rounding = period_ecl - raw_period_ecl
            raw_weighted = weight * period_ecl
            weighted_ecl = quantize(raw_weighted, DETAIL_QUANT)
            weight_rounding = weighted_ecl - raw_weighted
            lines.append(
                PeriodLine(
                    contract_id=contract.contract_id,
                    scenario=name,
                    period=period,
                    marginal_pd=marginal_pd,
                    lgd=lgd,
                    ead=ead,
                    discount_factor=discount_factor,
                    weight=weight,
                    period_ecl=period_ecl,
                    weighted_ecl=weighted_ecl,
                    rounding_delta=quantize(
                        period_rounding + weight_rounding, DETAIL_QUANT
                    ),
                )
            )
    return lines


def price_contract(
    contract: Contract, assumptions: AssumptionSet
) -> ContractECL:
    """Compute a contract ECL whose total equals the exact sum of its lines."""
    lines = expand_period_lines(contract, assumptions)
    if not lines:
        return ContractECL(
            contract_id=contract.contract_id,
            total_ecl=ZERO,
            lines=tuple(),
        )
    subtotal = fixed_sum((line.weighted_ecl for line in lines), DETAIL_QUANT)
    total = quantize(subtotal, AMOUNT_QUANT)
    # Force exact identity total == sum(lines): the final line carries the
    # final cent adjustment so detail lines add up to the reported total.
    lines = list(lines)
    last = lines[-1]
    adjusted_last = last.weighted_ecl + (total - subtotal)
    lines[-1] = PeriodLine(
        contract_id=last.contract_id,
        scenario=last.scenario,
        period=last.period,
        marginal_pd=last.marginal_pd,
        lgd=last.lgd,
        ead=last.ead,
        discount_factor=last.discount_factor,
        weight=last.weight,
        period_ecl=last.period_ecl,
        weighted_ecl=adjusted_last,
        rounding_delta=quantize(
            last.rounding_delta + (total - subtotal), DETAIL_QUANT
        ),
    )
    return ContractECL(
        contract_id=contract.contract_id,
        total_ecl=total,
        lines=tuple(lines),
    )


def price_all(
    contracts, assumptions: AssumptionSet
) -> tuple[ContractECL, ...]:
    """Price many contracts deterministically (sorted by contract id)."""
    ordered = sorted(contracts, key=lambda c: c.contract_id)
    return tuple(price_contract(contract, assumptions) for contract in ordered)
