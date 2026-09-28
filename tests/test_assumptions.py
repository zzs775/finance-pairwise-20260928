import unittest

from tests.test_engine import curve, make_assumptions, make_inputs
from ecl_engine.assumptions import AssumptionRegistry
from ecl_engine.models import Contract, ScenarioWeight, Stage
from ecl_engine.scales import D
from ecl_engine.validation import ValidationError


def registry_with_v1(
    periods: int = 12, weights=("0.6", "0.25", "0.15")
) -> tuple[AssumptionRegistry, list[Contract]]:
    registry = AssumptionRegistry()
    inputs = {}
    contracts = []
    for cid in ("C1", "C2"):
        inputs.update(
            make_inputs(
                cid,
                ["0.01"] * periods,
                ["0.4"] * periods,
                ["1000"] * periods,
                ["0.99"] * periods,
            )
        )
        contracts.append(Contract(cid, Stage.STAGE2, periods))
    registry.publish(make_assumptions(inputs, weights))
    return registry, contracts


class AssumptionRegistryTest(unittest.TestCase):
    def test_publish_and_bind(self) -> None:
        registry, _ = registry_with_v1()
        self.assertEqual(registry.versions, ("v1",))
        self.assertEqual(registry.bind("v1").version, "v1")
        self.assertEqual(registry.bind().version, "v1")

    def test_duplicate_version_rejected(self) -> None:
        registry, _ = registry_with_v1()
        with self.assertRaises(ValidationError):
            registry.publish(registry.get("v1"))

    def test_failed_version_does_not_publish(self) -> None:
        registry, _ = registry_with_v1()
        bad = make_assumptions({}, weights=("0.6", "0.3", "0.05"))
        with self.assertRaises(ValidationError):
            registry.publish(bad)
        self.assertEqual(registry.versions, ("v1",))
        self.assertEqual(registry.bind().version, "v1")

    def test_affected_contracts_pd_revision(self) -> None:
        registry, contracts = registry_with_v1()
        v2_inputs = dict(registry.get("v1").inputs)
        v2_inputs["C1"] = make_inputs(
            "C1",
            ["0.02"] * 12,
            ["0.4"] * 12,
            ["1000"] * 12,
            ["0.99"] * 12,
        )["C1"]
        v2 = make_assumptions(v2_inputs)
        object.__setattr__(v2, "version", "v2")
        registry.publish(v2)
        affected = registry.affected_contracts("v1", "v2", contracts)
        self.assertEqual(affected, ("C1",))

    def test_weight_revision_affects_all(self) -> None:
        registry, contracts = registry_with_v1()
        v2 = make_assumptions(
            dict(registry.get("v1").inputs),
            weights=("0.5", "0.3", "0.2"),
        )
        object.__setattr__(v2, "version", "v2")
        registry.publish(v2)
        affected = registry.affected_contracts("v1", "v2", contracts)
        self.assertEqual(affected, ("C1", "C2"))


if __name__ == "__main__":
    unittest.main()
