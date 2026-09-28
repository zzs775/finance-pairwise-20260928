import itertools
import unittest

from xbrlcheck.graph import resolve_arcs
from xbrlcheck.model import Arc
from xbrlcheck.model import Context
from xbrlcheck.model import Fact
from xbrlcheck.model import Instance
from xbrlcheck.model import Taxonomy
from xbrlcheck.model import Unit
from xbrlcheck.oracle import cross_check
from xbrlcheck.oracle import hand_computed_fixtures
from xbrlcheck.oracle import oracle_check
from xbrlcheck.validate import validate


class OracleFixturesTest(unittest.TestCase):
    def test_all_hand_computed_fixtures(self):
        for name, (taxonomy, instance, expected) in hand_computed_fixtures().items():
            findings = oracle_check(taxonomy, instance)
            got = [
                (
                    f.code,
                    f.role,
                    f.concept,
                    f.context,
                    f.unit,
                    f.parent_interval,
                    f.children_interval,
                    f.minimum_difference,
                )
                for f in findings
            ]
            want = [
                (
                    f.code,
                    f.role,
                    f.concept,
                    f.context,
                    f.unit,
                    f.parent_interval,
                    f.children_interval,
                    f.minimum_difference,
                )
                for f in expected
            ]
            self.assertEqual(got, want, name)

    def test_oracle_agrees_with_production_on_fixtures(self):
        for name, (taxonomy, instance, _) in hand_computed_fixtures().items():
            disagreements = cross_check(taxonomy, instance, validate(taxonomy, instance))
            self.assertEqual(disagreements, [], name)


class EnumeratedSmallGraphsTest(unittest.TestCase):
    """Deterministically enumerate small parent/child graphs and value choices."""

    def _taxonomy(self, weight):
        taxonomy = Taxonomy(
            concepts={"P", "C"},
            concept_types={"P": "monetary", "C": "monetary"},
            arcs=[Arc("r", "P", "C", weight, 0, 1.0)],
        )
        return taxonomy

    def test_enumerated_two_node_graphs(self):
        context = Context("c", "instant", instant="2025-12-31")
        unit = Unit("u", ("iso4217:USD",))
        values = [0, 1, 5, 10]
        for weight in (-1, 1, 2):
            taxonomy = self._taxonomy(weight)
            for parent_value, child_value in itertools.product(values, values):
                instance = Instance(
                    contexts={"c": context},
                    units={"u": unit},
                    facts=[
                        Fact("P", "c", "u", "INF", str(parent_value)),
                        Fact("C", "c", "u", "INF", str(child_value)),
                    ],
                )
                oracle_findings = oracle_check(taxonomy, instance)
                production = validate(taxonomy, instance)
                disagreements = cross_check(taxonomy, instance, production)
                self.assertEqual(disagreements, [], (weight, parent_value, child_value))
                expected_gap = parent_value != child_value * weight
                has_w301 = any(f.code == "W301" for f in oracle_findings)
                self.assertEqual(expected_gap, has_w301, (weight, parent_value, child_value))

    def test_duplicate_arc_resolution_is_deterministic(self):
        taxonomy = Taxonomy(
            concepts={"P", "C"},
            concept_types={"P": "monetary", "C": "monetary"},
            arcs=[
                Arc("r", "P", "C", 1, 0, 1.0),
                Arc("r", "P", "C", 1, 0, 1.0),
            ],
        )
        resolutions_a, _ = resolve_arcs(taxonomy)
        reversed_taxonomy = Taxonomy(
            concepts=taxonomy.concepts,
            concept_types=taxonomy.concept_types,
            arcs=list(reversed(taxonomy.arcs)),
        )
        resolutions_b, _ = resolve_arcs(reversed_taxonomy)
        self.assertEqual(
            [(r.winner.weight, r.winner.priority) for r in resolutions_a],
            [(r.winner.weight, r.winner.priority) for r in resolutions_b],
        )


if __name__ == "__main__":
    unittest.main()
