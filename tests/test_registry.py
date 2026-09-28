"""Tests for the assumption registry."""

import unittest
from datetime import date
from decimal import Decimal

from ecl_engine.errors import ValidationError, VersionError
from ecl_engine.models import AssumptionSet, Scenario
from ecl_engine.registry import AssumptionRegistry


def make_assumptions(version: str, effective=date(2026, 1, 1),
                     weights=(Decimal("1"),)) -> AssumptionSet:
    return AssumptionSet(
        version, effective,
        scenarios=tuple(Scenario(f"s{i}", w)
                        for i, w in enumerate(weights)))


class RegistryTest(unittest.TestCase):
    def test_publish_and_get(self) -> None:
        registry = AssumptionRegistry()
        registry.publish(make_assumptions("v1"))
        self.assertEqual(registry.get("v1").version, "v1")

    def test_failed_publication_changes_nothing(self) -> None:
        registry = AssumptionRegistry()
        registry.publish(make_assumptions("v1"))
        bad = make_assumptions("v2", weights=(Decimal("0.5"),))
        with self.assertRaises(ValidationError):
            registry.publish(bad)
        with self.assertRaises(VersionError):
            registry.get("v2")
        self.assertEqual(registry.all_versions(), ("v1",))

    def test_version_immutable(self) -> None:
        registry = AssumptionRegistry()
        registry.publish(make_assumptions("v1"))
        with self.assertRaises(VersionError):
            registry.publish(make_assumptions("v1", date(2027, 1, 1)))

    def test_latest_by_effective_date(self) -> None:
        registry = AssumptionRegistry()
        registry.publish(make_assumptions("v1", date(2026, 1, 1)))
        registry.publish(make_assumptions("v2", date(2026, 7, 1)))
        self.assertEqual(registry.latest.version, "v2")

    def test_latest_empty(self) -> None:
        with self.assertRaises(VersionError):
            AssumptionRegistry().latest

    def test_unknown_version(self) -> None:
        with self.assertRaises(VersionError):
            AssumptionRegistry().get("nope")


if __name__ == "__main__":
    unittest.main()
