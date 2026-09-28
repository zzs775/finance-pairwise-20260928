import io
import os
import shutil
import tempfile
import uuid
import unittest
from contextlib import redirect_stderr, redirect_stdout

from xbrlcheck.cli import main


LINKBASE = """<linkbase>
  <concept name="Assets" type="monetary"/>
  <concept name="Cash" type="monetary"/>
  <arc role="bs" from="Assets" to="Cash" weight="1" priority="0"/>
</linkbase>"""

INSTANCE = """<xbrl>
  <context id="c"><period><instant>2025-12-31</instant></period></context>
  <unit id="usd"><measure>iso4217:USD</measure></unit>
  <Assets contextRef="c" unitRef="usd" decimals="INF">100</Assets>
  <Cash contextRef="c" unitRef="usd" decimals="INF">100</Cash>
</xbrl>"""

BAD_REVISION = """<revision>
  <Cash contextRef="c" unitRef="ghost" decimals="INF">1</Cash>
</revision>"""


class CliTest(unittest.TestCase):
    def setUp(self):
        base = os.path.join(os.getcwd(), ".cache", "tmp")
        self.tmp = os.path.join(base, "cli-" + uuid.uuid4().hex)
        os.makedirs(self.tmp, mode=0o777, exist_ok=False)
        self.linkbase = os.path.join(self.tmp, "linkbase.xml")
        self.instance = os.path.join(self.tmp, "instance.xml")
        self.revision = os.path.join(self.tmp, "revision.xml")
        for path, content in (
            (self.linkbase, LINKBASE),
            (self.instance, INSTANCE),
            (self.revision, BAD_REVISION),
        ):
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(content)

    def _run(self, *args):
        stdout = io.BytesIO()
        stderr = io.StringIO()
        import sys

        old_stdout = sys.stdout
        sys.stdout = type("Out", (), {"buffer": stdout})()
        try:
            with redirect_stderr(stderr):
                code = main(list(args))
        finally:
            sys.stdout = old_stdout
        return code, stdout.getvalue(), stderr.getvalue()

    def test_validate_clean_exit_zero(self):
        code, output, _ = self._run("validate", self.linkbase, self.instance)
        self.assertEqual(code, 0)
        self.assertIn(b"0 error(s)", output)

    def test_validate_error_exit_two(self):
        with open(self.instance, "r", encoding="utf-8") as handle:
            content = handle.read()
        content = content.replace(
            "</xbrl>",
            '<Mystery contextRef="c" unitRef="usd" decimals="INF">1</Mystery></xbrl>',
        )
        with open(self.instance, "w", encoding="utf-8") as handle:
            handle.write(content)
        code, output, _ = self._run("validate", self.linkbase, self.instance)
        self.assertEqual(code, 2)
        self.assertIn(b"E102", output)

    def test_revision_rejected_exit_three(self):
        code, output, stderr = self._run(
            "revise", self.linkbase, self.instance, self.revision
        )
        self.assertEqual(code, 3)
        self.assertIn("rejected", stderr)

    def test_output_to_file(self):
        out = os.path.join(self.tmp, "report.txt")
        code, _, _ = self._run("validate", self.linkbase, self.instance, "-o", out)
        self.assertEqual(code, 0)
        with open(out, "rb") as handle:
            self.assertTrue(handle.read().startswith(b"# xbrlcheck report"))

    def test_missing_file_exit_six(self):
        code, _, _ = self._run("validate", "nope.xml", self.instance)
        self.assertEqual(code, 6)

    def test_unsafe_xml_exit_five(self):
        evil = os.path.join(self.tmp, "evil.xml")
        with open(evil, "w", encoding="utf-8") as handle:
            handle.write('<!DOCTYPE x [<!ENTITY a "x">]><x/>')
        code, _, stderr = self._run("validate", evil, self.instance)
        self.assertEqual(code, 5)
        self.assertIn("E002", stderr)

    def test_resource_limit_flag(self):
        code, _, stderr = self._run(
            "validate", self.linkbase, self.instance, "--max-elements", "1"
        )
        self.assertEqual(code, 5)
        self.assertIn("E001", stderr)


if __name__ == "__main__":
    unittest.main()
    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)
