import unittest

from xbrlcheck.model import Arc
from xbrlcheck.model import Context
from xbrlcheck.model import Fact
from xbrlcheck.model import Instance
from xbrlcheck.model import Taxonomy
from xbrlcheck.model import Unit
from xbrlcheck.parseinput import parse_instance_bytes
from xbrlcheck.validate import validate


def monetary_taxonomy(names, arcs=None):
    return Taxonomy(
        concepts=set(names),
        concept_types={name: "monetary" for name in names},
        arcs=arcs or [],
    )


CONTEXT_C = Context("c", "instant", instant="2025-12-31")
CONTEXT_D = Context("d", "duration", start="2025-01-01", end="2025-12-31")
USD = Unit("usd", ("iso4217:USD",))


class ValidateStructureTest(unittest.TestCase):
    def _run(self, facts, taxonomy):
        instance = Instance(contexts={"c": CONTEXT_C}, units={"usd": USD}, facts=facts)
        return validate(taxonomy, instance)

    def test_duplicate_fact(self):
        tax = monetary_taxonomy(["A"])
        facts = [Fact("A", "c", "usd", "INF", "1"), Fact("A", "c", "usd", "INF", "1")]
        self.assertIn("E101", [d.code for d in self._run(facts, tax)])

    def test_unknown_concept(self):
        facts = [Fact("Mystery", "c", "usd", "INF", "1")]
        self.assertIn("E102", [d.code for d in self._run(facts, monetary_taxonomy(["A"]))])

    def test_missing_context_and_unit(self):
        facts = [Fact("A", "nope", "usd", "INF", "1")]
        self.assertIn("E103", [d.code for d in self._run(facts, monetary_taxonomy(["A"]))])
        facts = [Fact("A", "c", "nounit", "INF", "1")]
        self.assertIn("E104", [d.code for d in self._run(facts, monetary_taxonomy(["A"]))])

    def test_unit_conflict_non_numeric(self):
        tax = Taxonomy(concepts={"Note"}, concept_types={"Note": "string"})
        facts = [Fact("Note", "c", "usd", "", "hello")]
        self.assertIn("E105", [d.code for d in self._run(facts, tax)])

    def test_illegal_decimal(self):
        facts = [Fact("A", "c", "usd", "bad", "1")]
        self.assertIn("E106", [d.code for d in self._run(facts, monetary_taxonomy(["A"]))])

    def test_unknown_arc_concept_and_non_numeric_arc(self):
        tax = Taxonomy(
            concepts={"A", "N"},
            concept_types={"A": "monetary", "N": "string"},
            arcs=[
                Arc("r", "A", "Ghost", 1, 0, 1.0),
                Arc("r", "A", "N", 1, 0, 2.0),
            ],
        )
        codes = [d.code for d in validate(tax, Instance(contexts={"c": CONTEXT_C}, units={"usd": USD}))]
        self.assertIn("E102", codes)
        self.assertIn("E201", codes)

    def test_bad_period_emits_e107(self):
        xml = b"""<xbrl>
          <context id="bad"><period><startDate>2025-01-01</startDate></period></context>
          <unit id="usd"><measure>iso4217:USD</measure></unit>
          <Assets contextRef="bad" unitRef="usd" decimals="INF">1</Assets>
        </xbrl>"""
        from xbrlcheck.parseinput import parse_instance_bytes

        instance, parse_diagnostics = parse_instance_bytes(xml)
        diagnostics = parse_diagnostics + validate(monetary_taxonomy(["Assets"]), instance)
        self.assertIn("E107", [d.code for d in diagnostics])

    def test_self_loop_emits_e204_and_skips_calculation(self):
        tax = monetary_taxonomy(["A"])
        tax.arcs = [Arc("r", "A", "A", 1, 0, 1.0)]
        instance = Instance(
            contexts={"c": CONTEXT_C},
            units={"usd": USD},
            facts=[Fact("A", "c", "usd", "INF", "1")],
        )
        diagnostics = validate(tax, instance)
        self.assertIn("E204", [d.code for d in diagnostics])
        self.assertNotIn("W301", [d.code for d in diagnostics])
        cycle = next(d for d in diagnostics if d.code == "E204")
        self.assertIn("A -> A", cycle.message)
        self.assertIn("@", cycle.message)


class CalculateTest(unittest.TestCase):
    def _tax(self):
        tax = monetary_taxonomy(["Assets", "Cash", "AR"])
        tax.arcs = [
            Arc("bs", "Assets", "Cash", 1, 0, 1.0),
            Arc("bs", "Assets", "AR", 1, 0, 2.0),
        ]
        return tax

    def test_consistent_sum_no_warning(self):
        instance = Instance(
            contexts={"c": CONTEXT_C},
            units={"usd": USD},
            facts=[
                Fact("Assets", "c", "usd", "INF", "1000"),
                Fact("Cash", "c", "usd", "INF", "600"),
                Fact("AR", "c", "usd", "INF", "400"),
            ],
        )
        self.assertEqual(validate(self._tax(), instance), [])

    def test_warning_lists_contributors_intervals_and_gap(self):
        instance = Instance(
            contexts={"c": CONTEXT_C},
            units={"usd": USD},
            facts=[
                Fact("Assets", "c", "usd", "INF", "1000"),
                Fact("Cash", "c", "usd", "INF", "600"),
                Fact("AR", "c", "usd", "0", "300"),
            ],
        )
        warnings_ = [d for d in validate(self._tax(), instance) if d.code == "W301"]
        self.assertEqual(len(warnings_), 1)
        message = warnings_[0].message
        self.assertIn("contributor Cash weight +1 interval [600, 600]", message)
        self.assertIn("contributor AR weight +1 interval [299.5, 300.5]", message)
        self.assertIn("minimum difference 99.5", message)
        self.assertEqual(warnings_[0].role, "bs")
        self.assertEqual(warnings_[0].concept, "Assets")

    def test_each_fact_belongs_to_exactly_its_context(self):
        instance = Instance(
            contexts={"c": CONTEXT_C, "d": CONTEXT_D},
            units={"usd": USD},
            facts=[
                Fact("Assets", "c", "usd", "INF", "100"),
                Fact("Cash", "c", "usd", "INF", "100"),
                Fact("Assets", "d", "usd", "INF", "200"),
                Fact("Cash", "d", "usd", "INF", "999"),
            ],
        )
        warnings_ = [d for d in validate(self._tax(), instance) if d.code == "W301"]
        self.assertEqual(len(warnings_), 1)
        self.assertEqual(warnings_[0].context, "d")
        self.assertIn("[200, 200]", warnings_[0].message)
        self.assertIn("[999, 999]", warnings_[0].message)

    def test_multi_units_do_not_mix(self):
        eur = Unit("eur", ("iso4217:EUR",))
        instance = Instance(
            contexts={"c": CONTEXT_C},
            units={"usd": USD, "eur": eur},
            facts=[
                Fact("Assets", "c", "usd", "INF", "100"),
                Fact("Cash", "c", "eur", "INF", "100"),
            ],
        )
        codes = [(d.code, d.unit) for d in validate(self._tax(), instance)]
        self.assertIn(("E203", "usd"), codes)
        self.assertNotIn("W301", [code for code, _ in codes])

    def test_xml_element_order_does_not_change_results(self):
        xml_a = b"""<xbrl>
          <context id="c"><period><instant>2025-12-31</instant></period></context>
          <unit id="usd"><measure>iso4217:USD</measure></unit>
          <Assets contextRef="c" unitRef="usd" decimals="INF">1000</Assets>
          <Cash contextRef="c" unitRef="usd" decimals="INF">600</Cash>
          <AR contextRef="c" unitRef="usd" decimals="INF">300</AR>
        </xbrl>"""
        xml_b = b"""<xbrl>
          <AR contextRef="c" unitRef="usd" decimals="INF">300</AR>
          <unit id="usd"><measure>iso4217:USD</measure></unit>
          <Cash contextRef="c" unitRef="usd" decimals="INF">600</Cash>
          <context id="c"><period><instant>2025-12-31</instant></period></context>
          <Assets contextRef="c" unitRef="usd" decimals="INF">1000</Assets>
        </xbrl>"""
        inst_a, _ = parse_instance_bytes(xml_a, "a.xml")
        inst_b, _ = parse_instance_bytes(xml_b, "b.xml")
        tax = self._tax()
        report_a = [(d.code, d.role, d.concept, d.context, d.unit, d.message)
                    for d in validate(tax, inst_a)]
        report_b = [(d.code, d.role, d.concept, d.context, d.unit, d.message)
                    for d in validate(tax, inst_b)]
        self.assertEqual(report_a, report_b)

    def test_report_sorted_by_role_then_concept(self):
        tax = monetary_taxonomy(["B", "A", "Bb", "Aa"])
        tax.arcs = [
            Arc("r2", "B", "Bb", 1, 0, 1.0),
            Arc("r1", "A", "Aa", 1, 0, 1.0),
        ]
        instance = Instance(
            contexts={"c": CONTEXT_C},
            units={"usd": USD},
            facts=[
                Fact("B", "c", "usd", "INF", "1"), Fact("Bb", "c", "usd", "INF", "9"),
                Fact("A", "c", "usd", "INF", "1"), Fact("Aa", "c", "usd", "INF", "9"),
            ],
        )
        warnings_ = [d for d in validate(tax, instance) if d.code == "W301"]
        keys = [(d.role, d.concept) for d in warnings_]
        self.assertEqual(keys, sorted(keys))
        self.assertEqual(keys, [("r1", "A"), ("r2", "B")])


if __name__ == "__main__":
    unittest.main()
