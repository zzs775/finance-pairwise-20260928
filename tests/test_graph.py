import unittest

from xbrlcheck.graph import build_role_graphs
from xbrlcheck.graph import canonical_min_cycles
from xbrlcheck.model import Arc
from xbrlcheck.model import Location
from xbrlcheck.model import Taxonomy


def arc(role, parent, child, weight=1, priority=0, order=1.0, line=1):
    return Arc(role, parent, child, weight, priority, order, Location("lb.xml", line, 1))


class GraphTest(unittest.TestCase):
    def test_highest_priority_wins(self):
        taxonomy = Taxonomy(
            concepts={"P", "C"},
            concept_types={"P": "monetary", "C": "monetary"},
            arcs=[arc("r", "P", "C", 1, 0, 1.0, 1), arc("r", "P", "C", 2, 5, 1.0, 2)],
        )
        graphs, diagnostics = build_role_graphs(taxonomy)
        self.assertEqual(graphs["r"].outgoing("P")[0].weight, 2)
        self.assertEqual([d.code for d in diagnostics], ["I401"])

    def test_equal_priority_conflicting_weights(self):
        taxonomy = Taxonomy(
            concepts={"P", "C"},
            concept_types={"P": "monetary", "C": "monetary"},
            arcs=[arc("r", "P", "C", 1, 0, 1.0, 1), arc("r", "P", "C", -1, 0, 2.0, 2)],
        )
        graphs, diagnostics = build_role_graphs(taxonomy)
        self.assertEqual([d.code for d in diagnostics], ["E201"])
        self.assertEqual(len(graphs["r"].outgoing("P")), 1)

    def test_self_loop(self):
        taxonomy = Taxonomy(
            concepts={"A"},
            concept_types={"A": "monetary"},
            arcs=[arc("r", "A", "A")],
        )
        graphs, _ = build_role_graphs(taxonomy)
        self.assertEqual(canonical_min_cycles(graphs["r"]), [("A",)])

    def test_canonical_minimum_cycle_rotation(self):
        taxonomy = Taxonomy(
            concepts={"A", "B", "C"},
            concept_types={n: "monetary" for n in ("A", "B", "C")},
            arcs=[
                arc("r", "B", "C"),
                arc("r", "C", "A"),
                arc("r", "A", "B"),
            ],
        )
        graphs, _ = build_role_graphs(taxonomy)
        self.assertEqual(canonical_min_cycles(graphs["r"]), [("A", "B", "C")])

    def test_two_independent_cycles(self):
        taxonomy = Taxonomy(
            concepts={"A", "B", "C", "D"},
            concept_types={n: "monetary" for n in ("A", "B", "C", "D")},
            arcs=[
                arc("r", "A", "B"), arc("r", "B", "A"),
                arc("r", "C", "D"), arc("r", "D", "C"),
            ],
        )
        graphs, _ = build_role_graphs(taxonomy)
        self.assertEqual(canonical_min_cycles(graphs["r"]), [("A", "B"), ("C", "D")])

    def test_roles_are_separate_graphs(self):
        taxonomy = Taxonomy(
            concepts={"A", "B"},
            concept_types={"A": "monetary", "B": "monetary"},
            arcs=[arc("r1", "A", "B"), arc("r2", "B", "A")],
        )
        graphs, _ = build_role_graphs(taxonomy)
        self.assertEqual(sorted(graphs), ["r1", "r2"])
        self.assertEqual(canonical_min_cycles(graphs["r1"]), [])
        self.assertEqual(canonical_min_cycles(graphs["r2"]), [])


if __name__ == "__main__":
    unittest.main()
