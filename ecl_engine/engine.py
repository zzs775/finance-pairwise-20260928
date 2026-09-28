"""Per-period ECL computation and scenario aggregation."""

from dataclasses import dataclass
from decimal import localcontext

from .errors import ValidationError
from . import curves, fixed, stages
from .fixed import Decimal, ZERO
from .models import Contract, Scenario


@dataclass(frozen=True)
class PeriodLine:
    """One fully-detailed ECL line for one period and one scenario."""

    contract_id: str
    period: int
    scenario: str
    marginal_pd: Decimal
    lgd: Decimal
    ead: Decimal
    discount_factor: Decimal
    weight: Decimal
    raw_product: Decimal
    rounded_ecl: Decimal
    rounding_adj: Decimal


@dataclass(frozen=True)
class ScenarioTotal:
    """Scenario-level aggregate."""

    scenario: str
    ecl: Decimal


@dataclass(frozen=True)
class ContractECL:
    """Detailed ECL result for one contract."""

    contract_id: str
    stage: int
    period_start: int
    period_end: int
    lines: tuple[PeriodLine, ...]
    scenario_totals: tuple[ScenarioTotal, ...]
    total_ecl: Decimal


def compute_period_lines(contract: Contract,
                         scenarios) -> tuple[PeriodLine, ...]:
    """Compute one fully-detailed ECL line per loss period per scenario."""
    start, end = stages.loss_period_range(
        contract.stage, contract.remaining_periods)
    marginals = curves.marginal_pd(contract.cumulative_pd.values)
    period_marginals = curves.slice_periods(marginals, start, end)
    lgds = curves.slice_periods(contract.lgd.values, start, end)
    eads = curves.slice_periods(contract.ead.values, start, end)
    discounts = curves.slice_periods(contract.discount.values, start, end)

    lines: list[PeriodLine] = []
    with localcontext() as context:
        context.prec = fixed.PRODUCT_PRECISION
        for offset, period in enumerate(range(start, end + 1)):
            marginal_pd = period_marginals[offset]
            lgd = lgds[offset]
            ead = eads[offset]
            discount = discounts[offset]
            if ead > fixed.MAX_AMOUNT:
                raise ValidationError(
                    f"contract {contract.contract_id} period {period}: "
                    f"EAD {ead} exceeds cap {fixed.MAX_AMOUNT} (overflow)")
            for scenario in scenarios:
                weight = scenario.weight
                raw = fixed.product(marginal_pd, lgd, ead, discount, weight)
                if not raw.is_finite() or abs(raw) > fixed.MAX_AMOUNT:
                    raise ValidationError(
                        f"contract {contract.contract_id} period {period} "
                        f"scenario {scenario.name}: ECL product overflow")
                rounded = fixed.round_line(raw)
                lines.append(PeriodLine(
                    contract_id=contract.contract_id,
                    period=period,
                    scenario=scenario.name,
                    marginal_pd=marginal_pd,
                    lgd=lgd,
                    ead=ead,
                    discount_factor=discount,
                    weight=weight,
                    raw_product=raw,
                    rounded_ecl=rounded,
                    rounding_adj=rounded - raw,
                ))
    return tuple(lines)


def compute_contract_ecl(contract: Contract,
                         scenarios) -> ContractECL:
    """Compute detailed ECL result for one contract."""
    lines = compute_period_lines(contract, scenarios)
    start, end = stages.loss_period_range(
        contract.stage, contract.remaining_periods)
    by_scenario: dict[str, Decimal] = {}
    for line in lines:
        by_scenario[line.scenario] = (
            by_scenario.get(line.scenario, ZERO) + line.rounded_ecl)
    scenario_totals = tuple(
        ScenarioTotal(scenario.name,
                      fixed.q(by_scenario.get(scenario.name, ZERO)))
        for scenario in scenarios)
    total = fixed.q(sum((line.rounded_ecl for line in lines), ZERO))
    summed = fixed.q(sum((item.ecl for item in scenario_totals), ZERO))
    if total != summed:
        raise ValidationError(
            f"contract {contract.contract_id}: ECL additivity violated: "
            f"{total} != {summed}")
    return ContractECL(
        contract_id=contract.contract_id,
        stage=contract.stage,
        period_start=start,
        period_end=end,
        lines=lines,
        scenario_totals=scenario_totals,
        total_ecl=total,
    )
