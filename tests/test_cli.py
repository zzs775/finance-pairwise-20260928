"""Tests for the command-line interface."""

import io
import json
import uuid
import shutil
import unittest
from pathlib import Path

from ecl_engine import cli

WORKSPACE = Path(__file__).resolve().parents[1]
TMP_ROOT = WORKSPACE / ".testtmp"


class TempDir:
    """Temp directory located inside the writable workspace."""

    def __init__(self) -> None:
        TMP_ROOT.mkdir(parents=True, exist_ok=True)
        self.path = TMP_ROOT / uuid.uuid4().hex
        self.path.mkdir(parents=True, exist_ok=False)

    def cleanup(self) -> None:
        shutil.rmtree(self.path, ignore_errors=True)


class CLITest(unittest.TestCase):
    def test_demo_emits_canonical_json(self) -> None:
        buffer = io.BytesIO()
        import sys

        old_stdout = sys.stdout
        wrapper = io.TextIOWrapper(buffer, encoding="utf-8")
        try:
            sys.stdout = wrapper
            code = cli.main(["demo", "--batch-size", "1"])
            wrapper.flush()
        finally:
            sys.stdout = old_stdout
            wrapper.detach()
        self.assertEqual(code, 0)
        payload = json.loads(buffer.getvalue().decode("utf-8"))
        contract_ids = [item[0] for item in payload["contracts"]]
        self.assertEqual(contract_ids, sorted(contract_ids))
        self.assertEqual(contract_ids, ["ANON-0001", "ANON-0002",
                                        "ANON-0003"])

    def test_run_from_json_file(self) -> None:
        assumptions, contracts = cli.demo_inputs()
        payload = {
            "assumptions": {
                "version": assumptions.version,
                "effective_date": assumptions.effective_date.isoformat(),
                "scenarios": [
                    {"name": s.name, "weight": str(s.weight)}
                    for s in assumptions.scenarios],
            },
            "contracts": [
                {
                    "contract_id": c.contract_id,
                    "stage": c.stage,
                    "remaining_periods": c.remaining_periods,
                    "curves": {
                        "cumulative_pd": [str(v) for v in
                                          c.cumulative_pd.values],
                        "lgd": [str(v) for v in c.lgd.values],
                        "ead": [str(v) for v in c.ead.values],
                        "discount": [str(v) for v in c.discount.values],
                    },
                }
                for c in contracts
            ],
        }
        tmp = TempDir()
        try:
            input_path = tmp.path / "input.json"
            output_path = tmp.path / "out.json"
            input_path.write_text(json.dumps(payload), encoding="utf-8")
            code = cli.main(["run", str(input_path),
                             "-o", str(output_path)])
            self.assertEqual(code, 0)
            self.assertTrue(output_path.exists())
        finally:
            tmp.cleanup()

    def test_invalid_input_returns_error_code(self) -> None:
        tmp = TempDir()
        try:
            path = tmp.path / "bad.json"
            path.write_text(
                '{"assumptions": {"version": "", "effective_date": '
                '"2026-01-01", "scenarios": []}, "contracts": []}',
                encoding="utf-8")
            self.assertEqual(cli.main(["run", str(path)]), 2)
        finally:
            tmp.cleanup()


if __name__ == "__main__":
    unittest.main()
