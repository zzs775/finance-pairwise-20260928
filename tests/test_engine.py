import unittest
from decimal import Decimal

from ecl_engine.engine import price_all, price_contract
from ecl_engine.models import (
    AssumptionSet,
    Contract,
    Curve,
    ScenarioInput,
    ScenarioWeight,
    Stage,
    TermPoint,
)
from ecl_engine.scales import D


def curve(values: list[str]) -> Curve:
    return Curve(
        tuple(
            TermPoint(period, D(value))
            for period, value in enumerate(values, start=1)
        )
    )


def make_inputs(
    contract_id: str,
    pd: list[str],
    lgd: list[str],
    ead: list[str],
    df: list[str],
) -> dict:
    scenarios = ("base", "upside", "downside")
    return {
        contract_id: tuple(
            ScenarioInput(
                name=name,
                cumulative_pd=curve(pd),
                lgd=curve(lgd),
                ead=curve(ead),
                discount=curve(df),
            )
            for name in scenarios
        )
    }


def make_assumptions(inputs, weights=("0.6", "0.25", "0.15")) -> AssumptionSet:
    return AssumptionSet(
        version="v1",
        effective_date="2026-01-01",
        scenarios=tuple(
            ScenarioWeight(name, D(weight))
            for name, weight in zip(("base", "upside", "downside"), weights)
        ),
        inputs=inputs,
    )


class EngineTest(unittest.TestCase):
    def test_stage1_uses_12_periods_only(self) -> None:
        periods = 24
        contract = Contract("C1", Stage.STAGE1, periods)
        assumptions = make_assumptions(
            make_inputs(
                "C1",
                ["0.01"] * periods,
                ["0.4"] * periods,
                ["1000"] * periods,
                ["0.99"] * periods,
            )
        )
        result = price_contract(contract, assumptions)
        covered = {(line.scenario, line.period) for line in result.lines}
        self.assertEqual(len(covered), 12 * 3)
        self.assertEqual(min(p for _, p in covered), 1)
        self.assertEqual(max(p for _, p in covered), 12)

    def test_stage3_uses_full_remaining_life(self) -> None:
        contract = Contract("C2", Stage.STAGE3, 30)
        assumptions = make_assumptions(
            make_inputs(
                "C2",
                ["0.02"] * 30,
                ["0.5"] * 30,
                ["500"] * 30,
                ["0.95"] * 30,
            )
        )
        result = price_contract(contract, assumptions)
        covered = {line.period for line in result.lines}
        self.assertEqual(covered, set(range(1, 31)))

    def test_total_equals_exact_sum_of_lines(self) -> None:
        contract = Contract("C3", Stage.STAGE2, 18)
        assumptions = make_assumptions(
            make_inputs(
                "C3",
                [f"{min(0.005 * i, 0.9):.3f}" for i in range(1, 19)],
                ["0.45"] * 18,
                [str(1000 + 7 * i) for i in range(18)],
                [f"{0.99 ** (i + 1):.6f}" for i in range(18)],
            )
        )
        result = price_contract(contract, assumptions)
        lines_sum = sum((line.weighted_ecl for line in result.lines), D("0"))
        self.assertEqual(lines_sum, result.total_ecl)

    def test_multipliers_recorded_on_each_line(self) -> None:
        contract = Contract("C4", Stage.STAGE1, 1)
        assumptions = make_assumptions(
            make_inputs("C4", ["0.1"], ["0.4"], ["1000"], ["0.98"])
        )
        result = price_contract(contract, assumptions)
        base = next(line for line in result.lines if line.scenario == "base")
        self.assertEqual(base.marginal_pd, D("0.10000000"))
        self.assertEqual(base.lgd, D("0.40000000"))
        self.assertEqual(base.ead, D("1000.00"))
        self.assertEqual(base.discount_factor, D("0.98000000"))
        self.assertEqual(base.weight, D("0.60000000"))
        self.assertEqual(base.period_ecl, D("39.200000"))
        self.assertEqual(base.weighted_ecl, D("23.520000"))

    def test_reordering_contracts_does_not_change_totals(self) -> None:
        inputs = {}
        contracts = []
        for index, periods in enumerate((6, 14, 24), start=1):
            cid = f"C{index}"
            inputs.update(
                make_inputs(
                    cid,
                    ["0.01"] * periods,
                    ["0.4"] * periods,
                    ["1000"] * periods,
                    ["0.99"] * periods,
                )
            )
            contracts.append(Contract(cid, Stage(index), periods))
        assumptions = make_assumptions(inputs)
        forward = price_all(contracts, assumptions)
        reversed_order = price_all(list(reversed(contracts)), assumptions)
        self.assertEqual(
            [(c.contract_id, c.total_ecl) for c in forward],
            [(c.contract_id, c.total_ecl) for c in reversed_order],
        )
        for before, after in zip(forward, reversed_order):
            self.assertEqual(before.lines, after.lines)

    def test_zero_remaining_life_is_zero(self) -> None:
        contract = Contract("C5", Stage.STAGE1, 0)
        assumptions = make_assumptions(make_inputs("C5", [], [], [], []))
        result = price_contract(contract, assumptions)
        self.assertEqual(result.total_ecl, D("0.00"))
        self.assertEqual(result.lines, ())


if __name__ == "__main__":
    unittest.main()
