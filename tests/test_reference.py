import unittest

from tests.test_engine import make_assumptions, make_inputs
from ecl_engine.engine import price_contract
from ecl_engine.models import Contract, Stage
from ecl_engine.reference import enumerate_reference, verify_against_engine
from ecl_engine.scales import D


class ReferenceTest(unittest.TestCase):
    def test_reference_marginal_and_stage_window(self) -> None:
        contract = Contract("C1", Stage.STAGE1, 24)
        cumulative = ["0.05", "0.05", "0.15"] + ["0.15"] * 21
        assumptions = make_assumptions(
            make_inputs(
                "C1",
                cumulative,
                ["0.4"] * 24,
                ["1000"] * 24,
                ["0.99"] * 24,
            )
        )
        rows = enumerate_reference(contract, assumptions)
        base_rows = [r for r in rows if r["scenario"] == "base"]
        self.assertEqual([r["period"] for r in base_rows], list(range(1, 13)))
        self.assertEqual(base_rows[0]["marginal_pd"], D("0.05000000"))
        self.assertEqual(base_rows[1]["marginal_pd"], D("0.00000000"))
        self.assertEqual(base_rows[2]["marginal_pd"], D("0.10000000"))

    def test_reference_weights_close_to_one(self) -> None:
        contract = Contract("C1", Stage.STAGE2, 3)
        assumptions = make_assumptions(
            make_inputs(
                "C1",
                ["0.1", "0.2", "0.3"],
                ["0.5"] * 3,
                ["100"] * 3,
                ["0.9"] * 3,
            )
        )
        rows = enumerate_reference(contract, assumptions)
        first_period = [r for r in rows if r["period"] == 1]
        self.assertEqual(
            sum((r["weight"] for r in first_period), D("0")), D("1.00000000")
        )

    def test_verify_against_engine(self) -> None:
        contract = Contract("C1", Stage.STAGE3, 10)
        assumptions = make_assumptions(
            make_inputs(
                "C1",
                [f"{min(0.01 * i, 0.9):.2f}" for i in range(1, 11)],
                ["0.35"] * 10,
                [str(500 + 3 * i) for i in range(10)],
                ["0.97"] * 10,
            )
        )
        priced = price_contract(contract, assumptions)
        verify_against_engine(contract, assumptions, priced)


if __name__ == "__main__":
    unittest.main()
