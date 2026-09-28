import io
import json
import os
import sys
import unittest
import uuid
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

from pnl_engine.cli import main

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "examples" / "control_portfolio.json"
TMP_ROOT = ROOT / ".cache" / "tmp" / "cli-tests"


class _TempDir:
    def __init__(self):
        TMP_ROOT.mkdir(parents=True, exist_ok=True)
        self.path = TMP_ROOT / f"case-{uuid.uuid4().hex}"
        self.path.mkdir(parents=True, exist_ok=False)

    def cleanup(self):
        import shutil

        shutil.rmtree(self.path, ignore_errors=True)

    def __enter__(self):
        return self.path

    def __exit__(self, *exc):
        self.cleanup()


class CliTest(unittest.TestCase):
    def test_control_fixture_ok_and_zero_residual(self):
        out = io.StringIO()
        with redirect_stdout(out):
            code = main([str(FIXTURE), "--require-zero-residual"])
        self.assertEqual(code, 0)
        payload = json.loads(out.getvalue())
        self.assertEqual(payload["status"], "ok")
        self.assertEqual(payload["residuals"], [])
        self.assertEqual(payload["nodes"]["portfolio"]["residual_base"], "0.00000000")

    def test_canonical_output_deterministic_across_runs(self):
        captured = []
        for _ in range(3):
            out = io.StringIO()
            with redirect_stdout(out):
                code = main([str(FIXTURE)])
            self.assertEqual(code, 0)
            captured.append(out.getvalue())
        self.assertEqual(len(set(captured)), 1)

    def test_output_file_argument(self):
        with _TempDir() as tmp:
            target = os.path.join(tmp, "result.json")
            code = main([str(FIXTURE), "-o", target])
            self.assertEqual(code, 0)
            with open(target, encoding="utf-8") as handle:
                payload = json.load(handle)
            self.assertEqual(payload["status"], "ok")

    def test_invalid_json_exit_code(self):
        with _TempDir() as tmp:
            bad = Path(tmp) / "bad.json"
            bad.write_text("{not json", encoding="utf-8")
            err = io.StringIO()
            with redirect_stderr(err):
                code = main([str(bad)])
            self.assertEqual(code, 2)

    def test_validation_failure_is_classified(self):
        payload = json.loads(FIXTURE.read_text(encoding="utf-8"))
        payload["transactions"].append(dict(payload["transactions"][0]))
        with _TempDir() as tmp:
            bad = Path(tmp) / "dup.json"
            bad.write_text(json.dumps(payload), encoding="utf-8")
            err = io.StringIO()
            with redirect_stderr(err):
                code = main([str(bad)])
            self.assertEqual(code, 1)
            reported = json.loads(err.getvalue())
            self.assertEqual(reported["status"], "failed")
            codes = {f["code"] for f in reported["failures"]}
            self.assertIn("duplicate_transaction", codes)

    def test_nonzero_residual_reports_minimal_diff_set(self):
        from pnl_engine.fixtures import (
            T0, T2, accrual, asset, dataset, fx, position, price,
        )
        from pnl_engine.aggregation import aggregate
        from pnl_engine.attribution import attribute_all
        from pnl_engine.models import Position

        item = asset("X1", "EUR", "tech")
        data = dataset(
            [item],
            [position("X1", "10", "0")],
            [position("X1", "10", "0.05", "s-end")],
            [],
            [price("X1", T0, "100"), price("X1", T2, "100")],
            [accrual("X1", T0, "0"), accrual("X1", T2, "0.05")],
            [fx("EUR", T0, "1.10"), fx("EUR", T2, "1.10")],
        )
        end = data.end_positions["X1"]
        data.end_positions["X1"] = Position(
            asset_id="X1",
            quantity=end.quantity,
            accrued=__import__("decimal").Decimal("0.06000000"),
            snapshot=end.snapshot,
        )
        results = attribute_all(data)
        nodes = aggregate(data, results)
        residual = nodes["portfolio"].residual_base
        self.assertEqual(residual, __import__("decimal").Decimal("0.01100000"))
        portfolio = nodes["portfolio"]
        explained = (
            portfolio.trade_base
            + portfolio.price_base
            + portfolio.interest_base
            + portfolio.fx_base
        )
        self.assertEqual(explained, __import__("decimal").Decimal("0.05500000"))
        self.assertTrue(results["X1"].trace)
        self.assertEqual(results["X1"].trace[-1].kind, "snapshot_gap")
        self.assertEqual(results["X1"].trace[-1].amount, residual)

        payload = json.loads(FIXTURE.read_text(encoding="utf-8"))
        end_a1 = payload["end_positions"]["A1"]
        end_a1["accrued"] = "0.31000000"
        with _TempDir() as tmp:
            bad = Path(tmp) / "residual.json"
            bad.write_text(json.dumps(payload), encoding="utf-8")
            err = io.StringIO()
            with redirect_stderr(err):
                code = main([str(bad), "--require-zero-residual"])
            self.assertEqual(code, 3)
            reported = json.loads(err.getvalue())
            self.assertEqual(reported["status"], "residual_nonzero")
            self.assertEqual(
                reported["minimal_diff_set"][0]["asset_id"], "A1"
            )


if __name__ == "__main__":
    unittest.main()
