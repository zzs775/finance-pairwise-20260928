"""Local anonymous synthetic contract / assumption fixtures.

All values are deterministic, anonymous, synthetic-only data generated from
integer arithmetic (no float, no external files, no network).
"""

from dataclasses import replace

from .models import (
    AssumptionSet,
    Contract,
    Curve,
    ScenarioInput,
    ScenarioWeight,
    Stage,
    TermPoint,
)
from .scales import D

SCENARIO_NAMES = ("base", "upside", "downside")


def _curve(values) -> Curve:
    return Curve(
        tuple(
            TermPoint(period, D(str(value)))
            for period, value in enumerate(values, start=1)
        )
    )


def _default_curves(periods: int, contract_seed: int, scenario: str):
    """Deterministic synthetic PD/LGD/EAD/discount term structures."""
    scenario_factor = {"base": 1, "upside": 0, "downside": 2}[scenario]
    cumulative = []
    for period in range(1, periods + 1):
        raw = period * (contract_seed + scenario_factor)
        value = min(raw, 100)
        cumulative.append(f"{value // 100}.{value % 100:02d}")
    lgd = [f"0.{(30 + 5 * scenario_factor + contract_seed) % 60:02d}"] * periods
    ead = [str(1000 * (1 + contract_seed) + 10 * period) for period in range(periods)]
    discount = [f"0.{99 - scenario_factor - (period // 10):02d}" for period in range(1, periods + 1)]
    return cumulative, lgd, ead, discount


def _make_scenario_inputs(contract_seed: int, periods: int):
    return tuple(
        ScenarioInput(
            name=name,
            cumulative_pd=_curve(cumulative),
            lgd=_curve(lgd),
            ead=_curve(ead),
            discount=_curve(discount),
        )
        for name in SCENARIO_NAMES
        for cumulative, lgd, ead, discount in [
            _default_curves(periods, contract_seed, name)
        ]
    )


def load_contracts() -> list[Contract]:
    """Return deterministic anonymous synthetic contracts.

    Covers: stage 1 long horizon (truncation), stage 1 under 12 periods,
    stage 2, stage 3, a long lifetime contract and a matured contract.
    """
    return [
        Contract("ANON-001", Stage.STAGE1, 24),
        Contract("ANON-002", Stage.STAGE1, 7),
        Contract("ANON-003", Stage.STAGE2, 36),
        Contract("ANON-004", Stage.STAGE3, 18),
        Contract("ANON-005", Stage.STAGE2, 60),
        Contract("ANON-006", Stage.STAGE1, 0),
    ]


def _build_inputs(contracts) -> dict:
    inputs = {}
    for index, contract in enumerate(contracts, start=1):
        inputs[contract.contract_id] = _make_scenario_inputs(
            index, contract.remaining_periods
        )
    return inputs


def load_assumptions(version: str = "v1") -> AssumptionSet:
    """Return a deterministic synthetic assumption set.

    ``v1`` closes exactly; ``v2`` revises PD/LGD/weights (weights carry a
    1e-8 tail residual absorbed by the first scenario).
    """
    contracts = load_contracts()
    inputs = _build_inputs(contracts)
    if version == "v1":
        weights = (
            ScenarioWeight("base", D("0.60000000")),
            ScenarioWeight("upside", D("0.25000000")),
            ScenarioWeight("downside", D("0.15000000")),
        )
        return AssumptionSet(
            version="v1",
            effective_date="2026-01-01",
            scenarios=weights,
            inputs=inputs,
        )
    if version == "v2":
        revised = dict(inputs)
        # PD revision for ANON-003: double the cumulative slope (bounded at 1).
        contract = next(c for c in contracts if c.contract_id == "ANON-003")
        old_inputs = revised["ANON-003"]
        new_scenarios = []
        for scenario in old_inputs:
            points = tuple(
                replace(
                    point,
                    value=D(str(min(point.value * 2, D("1")))),
                )
                for point in scenario.cumulative_pd.points
            )
            new_scenarios.append(replace(scenario, cumulative_pd=Curve(points)))
        revised["ANON-003"] = tuple(new_scenarios)
        # LGD revision for ANON-004.
        old_inputs = revised["ANON-004"]
        revised["ANON-004"] = tuple(
            replace(
                scenario,
                lgd=_curve(["0.55"] * 18),
            )
            for scenario in old_inputs
        )
        weights = (
            ScenarioWeight("base", D("0.59999999")),
            ScenarioWeight("upside", D("0.25000000")),
            ScenarioWeight("downside", D("0.15000000")),
        )
        return AssumptionSet(
            version="v2",
            effective_date="2026-07-01",
            scenarios=weights,
            inputs=revised,
        )
    raise KeyError(f"unknown synthetic assumption version {version!r}")
