import json
import subprocess
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
QUOTES = ROOT / "examples" / "quotes.json"


class CliTest(unittest.TestCase):
    def test_build_writes_canonical_report(self) -> None:
        result = subprocess.run(
            [sys.executable, "-m", "discurve.cli", "build", str(QUOTES)],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        report = json.loads(result.stdout)
        self.assertIn("nodes", report)
        self.assertIn("diagnostics", report)
        self.assertEqual(len(report["diagnostics"]), 5)
        for diag in report["diagnostics"]:
            self.assertLessEqual(
                abs(float(diag["residual"])),
                float(report["pricing_tolerance"]),
            )
        self.assertEqual(report["bonds"][0]["name"], "BOND-A")
        self.assertGreater(float(report["bonds"][0]["model_pv"]), 100)

    def test_build_rejects_invalid_file_with_nonzero_exit(self) -> None:
        bad = ROOT / ".cache" / "tmp" / "bad_quotes.json"
        bad.parent.mkdir(parents=True, exist_ok=True)
        bad.write_text(
            json.dumps(
                {
                    "version_id": "x",
                    "valuation_date": "not-a-date",
                    "deposits": [],
                }
            ),
            encoding="utf-8",
        )
        result = subprocess.run(
            [sys.executable, "-m", "discurve.cli", "build", str(bad)],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("date", result.stderr.lower() + result.stdout.lower())


if __name__ == "__main__":
    unittest.main()
