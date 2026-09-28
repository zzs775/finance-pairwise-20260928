import json
import subprocess
import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class CliTest(unittest.TestCase):
    def test_fixture_runs_with_oracle_check(self) -> None:
        completed = subprocess.run(
            [
                sys.executable,
                "-m",
                "abs_waterfall.cli",
                str(ROOT / "fixtures" / "multi_period.json"),
                "--check-oracle",
            ],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        payload = json.loads(completed.stdout)
        self.assertEqual(len(payload["periods"]), 3)
        self.assertEqual(
            payload["periods"][1]["fired_triggers"], ["dlq_ge_50pct"]
        )


if __name__ == "__main__":
    unittest.main()
