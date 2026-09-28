import unittest

from tests.test_assumptions import registry_with_v1
from tests.test_engine import make_assumptions, make_inputs
from ecl_engine.assumptions import AssumptionRegistry
from ecl_engine.models import Contract, Stage
from ecl_engine.validation import ValidationError
from ecl_engine.valuation import ValuationService, serialize_result


class ValuationServiceTest(unittest.TestCase):
    def _setup(self):
        registry, contracts = registry_with_v1(periods=12)
        service = ValuationService(registry, run_date="2026-06-30")
        full = service.run_full("run-1", contracts, "v1")
        return registry, contracts, service, full

    def test_full_run_binds_version(self) -> None:
        _, _, service, full = self._setup()
        self.assertEqual(full.version, "v1")
        self.assertEqual(full.run_date, "2026-06-30")

    def test_incremental_matches_full_byte_for_byte(self) -> None:
        registry, contracts, service, _ = self._setup()
        v2_inputs = dict(registry.get("v1").inputs)
        v2_inputs["C2"] = make_inputs(
            "C2",
            ["0.05"] * 12,
            ["0.4"] * 12,
            ["1000"] * 12,
            ["0.99"] * 12,
        )["C2"]
        v2 = make_assumptions(v2_inputs)
        object.__setattr__(v2, "version", "v2")
        registry.publish(v2)

        incremental = service.run_incremental("run-1", contracts, "v2")
        service2 = ValuationService(registry, run_date="2026-06-30")
        full = service2.run_full("run-1", contracts, "v2")
        self.assertEqual(serialize_result(incremental), serialize_result(full))
        # Unchanged contract result object is reused verbatim.
        prior = service.results["run-1"]
        c1_before = next(c for c in prior.contracts if c.contract_id == "C1")
        c1_after = next(c for c in incremental.contracts if c.contract_id == "C1")
        self.assertIs(c1_before, c1_after)

    def test_input_reordering_identical_bytes(self) -> None:
        registry, contracts, service, full = self._setup()
        reordered = service.run_full("run-1", list(reversed(contracts)), "v1")
        self.assertEqual(serialize_result(reordered), serialize_result(full))

    def test_failed_run_keeps_published_result(self) -> None:
        _, _, service, full = self._setup()
        with self.assertRaises(Exception):
            service.run_full("run-1", [], "version-does-not-exist")
        self.assertIs(service.results["run-1"], full)

    def test_incremental_requires_prior_run(self) -> None:
        registry, contracts, _, _ = self._setup()
        service = ValuationService(registry)
        with self.assertRaises(ValidationError):
            service.run_incremental("missing", contracts, "v1")


if __name__ == "__main__":
    unittest.main()
