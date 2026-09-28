import unittest
from decimal import Decimal

from ecl_engine.assumptions import AssumptionRegistry
from ecl_engine.engine import price_all
from ecl_engine.models import Stage
from ecl_engine.synthetic import load_assumptions, load_contracts
class SyntheticFixturesTest(unittest.TestCase):
    def test_fixtures_cover_all_stages(self) -> None:
        contracts = load_contracts()
        self.assertEqual({c.stage for c in contracts}, set(Stage))

    def test_fixtures_price_end_to_end(self) -> None:
        contracts = load_contracts()
        assumptions = load_assumptions("v1")
        results = price_all(contracts, assumptions)
        self.assertEqual(len(results), len(contracts))
        for priced in results:
            lines_sum = sum(
                (line.weighted_ecl for line in priced.lines),
                Decimal(0),
            )
            self.assertEqual(lines_sum, priced.total_ecl)

    def test_v2_revision_identifies_affected_contracts(self) -> None:
        contracts = load_contracts()
        registry = AssumptionRegistry()
        registry.publish(load_assumptions("v1"))
        registry.publish(load_assumptions("v2"))
        affected = registry.affected_contracts("v1", "v2", contracts)
        self.assertIn("ANON-003", affected)
        self.assertIn("ANON-004", affected)

    def test_unknown_version(self) -> None:
        with self.assertRaises(KeyError):
            load_assumptions("v9")


if __name__ == "__main__":
    unittest.main()
