import unittest

from xbrlcheck.model import Arc
from xbrlcheck.model import Context
from xbrlcheck.model import Fact
from xbrlcheck.model import Instance
from xbrlcheck.model import Taxonomy
from xbrlcheck.model import Unit
from xbrlcheck.versioning import VersionedStore


def build_store():
    taxonomy = Taxonomy(
        concepts={"Assets", "Cash", "AR"},
        concept_types={"Assets": "monetary", "Cash": "monetary", "AR": "monetary"},
        arcs=[
            Arc("bs", "Assets", "Cash", 1, 0, 1.0),
            Arc("bs", "Assets", "AR", 1, 0, 2.0),
        ],
    )
    context = Context("c", "instant", instant="2025-12-31")
    unit = Unit("usd", ("iso4217:USD",))
    instance = Instance(
        contexts={"c": context},
        units={"usd": unit},
        facts=[
            Fact("Assets", "c", "usd", "INF", "1000"),
            Fact("Cash", "c", "usd", "INF", "600"),
            Fact("AR", "c", "usd", "INF", "400"),
        ],
    )
    return taxonomy, VersionedStore(taxonomy, instance, version="1")


class VersioningTest(unittest.TestCase):
    def test_incremental_equals_full_rebuild_byte_for_byte(self):
        _, store = build_store()
        revision = Instance(facts=[Fact("Cash", "c", "usd", "INF", "700")])
        result = store.apply_revision(revision)
        self.assertTrue(result.accepted)
        self.assertTrue(result.byte_identical_rebuild)
        self.assertEqual(result.version, "2")
        self.assertEqual(result.report, store.rebuild_report())

    def test_affected_nodes_propagate_reverse_dependencies(self):
        _, store = build_store()
        revision = Instance(facts=[Fact("AR", "c", "usd", "INF", "1")])
        result = store.apply_revision(revision)
        self.assertEqual(set(result.affected_concepts), {"AR", "Assets"})

    def test_rejected_revision_keeps_current_version(self):
        _, store = build_store()
        before_report = store.last_report
        before_version = store.version
        bad = Instance(facts=[Fact("Cash", "c", "missing-unit", "INF", "1")])
        result = store.apply_revision(bad)
        self.assertFalse(result.accepted)
        self.assertEqual(store.version, before_version)
        self.assertEqual(store.last_report, before_report)
        self.assertEqual(result.version, before_version)

    def test_upsert_and_history(self):
        _, store = build_store()
        first = store.apply_revision(Instance(facts=[Fact("Cash", "c", "usd", "INF", "650")]))
        second = store.apply_revision(Instance(facts=[Fact("AR", "c", "usd", "INF", "350")]))
        self.assertTrue(first.accepted and second.accepted)
        self.assertEqual([v for v, _ in store.history], ["1", "2", "3"])
        values = {f.concept: f.value for f in store.instance.facts}
        self.assertEqual(values["Cash"], "650")
        self.assertEqual(values["AR"], "350")
        self.assertEqual(len(store.instance.facts), 3)

    def test_rebuild_after_many_revisions_stable(self):
        _, store = build_store()
        for value in range(600, 610):
            result = store.apply_revision(
                Instance(facts=[Fact("Cash", "c", "usd", "INF", str(value))])
            )
            self.assertTrue(result.byte_identical_rebuild)
        self.assertEqual(store.version, "11")

    def test_linkbase_version_rejected_when_unknown_concept_introduced(self):
        _, store = build_store()
        bad_taxonomy = Taxonomy(
            concepts={"Assets"},
            concept_types={"Assets": "monetary"},
            arcs=[Arc("bs", "Assets", "Vanished", 1, 0, 1.0)],
        )
        before = store.last_report
        result = store.load_linkbase_version(bad_taxonomy, "2")
        self.assertFalse(result.accepted)
        self.assertEqual(store.version, "1")
        self.assertEqual(store.last_report, before)

    def test_linkbase_version_accepted(self):
        _, store = build_store()
        new_taxonomy = Taxonomy(
            concepts={"Assets", "Cash", "AR"},
            concept_types={"Assets": "monetary", "Cash": "monetary", "AR": "monetary"},
            arcs=[
                Arc("bs", "Assets", "Cash", 1, 0, 1.0),
                Arc("bs", "Assets", "AR", 1, 0, 2.0),
            ],
        )
        result = store.load_linkbase_version(new_taxonomy, "2")
        self.assertTrue(result.accepted)
        self.assertEqual(store.version, "2")


if __name__ == "__main__":
    unittest.main()
