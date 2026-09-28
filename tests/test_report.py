import unittest

from xbrlcheck.errors import Diagnostic
from xbrlcheck.errors import Location
from xbrlcheck.report import render_report


class ReportTest(unittest.TestCase):
    def test_empty_report(self):
        report = render_report([])
        self.assertIn(b"# summary: 0 error(s), 0 warning(s), 0 info", report)
        self.assertTrue(report.endswith(b"\n"))
        self.assertNotIn(b"\r", report)

    def test_sorted_by_code_role_concept(self):
        diagnostics = [
            Diagnostic("W301", "later", Location(), role="r2", concept="Z"),
            Diagnostic("E101", "dup", Location(), role="r2", concept="A"),
            Diagnostic("W301", "early", Location(), role="r1", concept="A"),
        ]
        report = render_report(diagnostics, version="7")
        text = report.decode("utf-8")
        self.assertIn("# version: 7", text)
        positions = [
            text.index("error E101"),
            text.index("warning W301") ,
            text.rindex("warning W301"),
        ]
        self.assertEqual(positions, sorted(positions))
        first_warning = text.index("warning W301")
        second_warning = text.index("warning W301", first_warning + 1)
        self.assertIn("r1", text[first_warning:second_warning])

    def test_byte_stable_across_input_order(self):
        a = [
            Diagnostic("E101", "m1", Location("i.xml", 9, 1), concept="Z"),
            Diagnostic("E102", "m2", Location("i.xml", 2, 4), concept="A"),
        ]
        b = list(reversed(a))
        self.assertEqual(render_report(a), render_report(b))

    def test_utf8_output(self):
        report = render_report([Diagnostic("E102", "未知概念", Location("数据.xml", 3, 2))])
        self.assertIn("未知概念".encode("utf-8"), report)


if __name__ == "__main__":
    unittest.main()
