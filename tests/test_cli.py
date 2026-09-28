from __future__ import annotations

import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

from pnl_engine.cli import main

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SAMPLE = os.path.join(ROOT, "fixtures", "sample_bundle.json")
REVISIONS = os.path.join(ROOT, "fixtures", "revisions_sample.json")


class CliTest(unittest.TestCase):
    def test_run_emits_canonical_json(self) -> None:
        out = io.StringIO()
        with redirect_stdout(out):
            code = main(["run", SAMPLE])
        self.assertEqual(code, 0)
        report = json.loads(out.getvalue())
        self.assertEqual(report["base_currency"], "CNY")
        portfolio = next(
            node for node in report["nodes"] if node["node_id"] == "PORTFOLIO"
        )
        self.assertEqual(portfolio["residual"], "0.00")

    def test_revise_matches_full_recompute(self) -> None:
        out = io.StringIO()
        with redirect_stdout(out):
            code = main(["revise", SAMPLE, REVISIONS])
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out.getvalue())["residual_diffs"], [])

    def test_validation_failure_exit_code(self) -> None:
        bad = {
            "base_currency": "CNY",
            "start": "2026-01-02T09:00:00",
            "end": "2026-01-01T09:00:00",
            "assets": [],
        }
        with tempfile.NamedTemporaryFile(
            "w", suffix=".json", delete=False, encoding="utf-8"
        ) as handle:
            json.dump(bad, handle)
            path = handle.name
        try:
            err = io.StringIO()
            with redirect_stderr(err):
                code = main(["run", path])
            self.assertEqual(code, 1)
            self.assertIn("time_inversion", err.getvalue())
        finally:
            os.unlink(path)


if __name__ == "__main__":
    unittest.main()
