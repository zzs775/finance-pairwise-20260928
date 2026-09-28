import os
import tempfile
import unittest

from xbrlcheck.parseinput import parse_instance_bytes, parse_linkbase, parse_revision


INSTANCE = b"""<xbrl>
  <context id="c1"><period><instant>2025-12-31</instant></period></context>
  <context id="c2"><period><startDate>2025-01-01</startDate><endDate>2025-12-31</endDate></period></context>
  <context id="c3"><period/></context>
  <unit id="usd"><measure>iso4217:USD</measure></unit>
  <unit id="per"><divide><unitNumerator><measure>iso4217:USD</measure></unitNumerator>
    <unitDenominator><measure>shares</measure></unitDenominator></divide></unit>
  <Assets contextRef="c1" unitRef="usd" decimals="-3">1000</Assets>
</xbrl>"""


class ParseInputTest(unittest.TestCase):
    def test_instance_contexts_units_facts(self):
        instance, diagnostics = parse_instance_bytes(INSTANCE, "inst.xml")
        self.assertEqual(set(instance.contexts), {"c1", "c2", "c3"})
        self.assertEqual(instance.contexts["c1"].period_kind, "instant")
        self.assertEqual(instance.contexts["c2"].period_kind, "duration")
        self.assertEqual(instance.units["usd"].measures, ("iso4217:USD",))
        self.assertEqual(instance.units["per"].measures[0], "iso4217:USD")
        self.assertTrue(instance.units["per"].measures[1].startswith("per:"))
        fact = instance.facts[0]
        self.assertEqual((fact.concept, fact.context_ref, fact.unit_ref), ("Assets", "c1", "usd"))
        self.assertEqual((fact.value, fact.decimals), ("1000", "-3"))
        self.assertEqual(fact.location.source, "inst.xml")
        self.assertGreater(fact.location.line, 0)
        self.assertTrue(any(code == "E107" for code in (d.code for d in diagnostics)))

    def test_linkbase_concepts_and_arcs(self):
        linkbase = b"""<linkbase>
          <concept name="Assets" type="monetary"/>
          <concept name="Note" type="string"/>
          <arc role="r" from="Assets" to="Note" weight="x"/>
          <arc role="r" from="Assets" to="Assets" weight="2" priority="3"/>
        </linkbase>"""
        path = _write(linkbase)
        try:
            taxonomy, diagnostics = parse_linkbase(path)
        finally:
            os.unlink(path)
        self.assertEqual(taxonomy.concepts, {"Assets", "Note"})
        self.assertTrue(taxonomy.is_numeric("Assets"))
        self.assertFalse(taxonomy.is_numeric("Note"))
        self.assertEqual([d.code for d in diagnostics], ["E201"])
        good = taxonomy.arcs[0]
        self.assertEqual((good.weight, good.priority), (2, 3))

    def test_revision_is_facts_only(self):
        revision = b"""<revision>
          <Cash contextRef="c1" unitRef="usd" decimals="0">42</Cash>
        </revision>"""
        instance, _ = parse_revision(_write_bytes(revision, ".xml"))
        self.assertEqual(len(instance.facts), 1)
        self.assertEqual(instance.contexts, {})
        self.assertEqual(instance.units, {})


def _write(data: bytes) -> str:
    return _write_bytes(data, ".xml")


def _write_bytes(data: bytes, suffix: str) -> str:
    tmp_dir = os.path.join(os.getcwd(), ".cache", "tmp")
    os.makedirs(tmp_dir, exist_ok=True)
    handle = tempfile.NamedTemporaryFile(suffix=suffix, delete=False, dir=tmp_dir)
    handle.write(data)
    handle.close()
    return handle.name


if __name__ == "__main__":
    unittest.main()
