import io
import json
import os
import shutil
import unittest
from contextlib import redirect_stdout, redirect_stderr

from chainladder.cli import main

CSV_TEXT = (
    "origin,dev,amount\n"
    "2020,1,100.00\n2020,2,150.00\n2020,3,175.00\n"
    "2021,1,120.00\n2021,2,180.00\n2022,1,130.00\n"
)

JSON_TEXT = json.dumps([
    {"origin": 2020, "dev": 1, "amount": "100.00"},
    {"origin": 2020, "dev": 2, "amount": "150.00"},
    {"origin": 2020, "dev": 3, "amount": "175.00"},
    {"origin": 2021, "dev": 1, "amount": "120.00"},
    {"origin": 2021, "dev": 2, "amount": "180.00"},
    {"origin": 2022, "dev": 1, "amount": "130.00"},
])


class CliTest(unittest.TestCase):
    def setUp(self):
        self.tmpdir = os.path.join(os.path.dirname(__file__), "_cli_tmp")
        os.makedirs(self.tmpdir, exist_ok=True)
        self.csv_path = os.path.join(self.tmpdir, "triangle.csv")
        self.json_path = os.path.join(self.tmpdir, "triangle.json")
        with open(self.csv_path, "w", encoding="utf-8") as f:
            f.write(CSV_TEXT)
        with open(self.json_path, "w", encoding="utf-8") as f:
            f.write(JSON_TEXT)

    def tearDown(self):
        shutil.rmtree(self.tmpdir, ignore_errors=True)

    def run_cli(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = main(list(args))
        return code, out.getvalue(), err.getvalue()

    def test_csv_default_volume_weighted(self):
        code, out, err = self.run_cli(self.csv_path)
        self.assertEqual(code, 0, err)
        payload = json.loads(out)
        self.assertEqual(payload["factors"][0]["factor"], "1.500000")
        self.assertEqual(payload["origins"]["2022"]["ultimate"], "227.50")
        self.assertEqual(payload["total_reserve"], "127.50")

    def test_json_input_same_result(self):
        _, out_csv, _ = self.run_cli(self.csv_path, "--canonical")
        _, out_json, _ = self.run_cli(self.json_path, "--canonical")
        self.assertEqual(out_csv, out_json)

    def test_simple_average_and_tail(self):
        code, out, err = self.run_cli(
            self.csv_path, "--method", "simple_average", "--tail", "1.05"
        )
        self.assertEqual(code, 0, err)
        payload = json.loads(out)
        self.assertEqual(payload["tail_factor"], "1.050000")
        self.assertEqual(payload["origins"]["2020"]["ultimate"], "183.75")

    def test_fixed_factors(self):
        code, out, err = self.run_cli(
            self.csv_path, "--method", "fixed",
            "--fixed", "1=1.5", "--fixed", "2=1.2",
        )
        self.assertEqual(code, 0, err)
        payload = json.loads(out)
        self.assertEqual(payload["factors"][1]["factor"], "1.200000")
        self.assertEqual(payload["origins"]["2022"]["ultimate"], "234.00")

    def test_zero_denominator_error_exit_code(self):
        path = os.path.join(self.tmpdir, "zero.csv")
        with open(path, "w", encoding="utf-8") as f:
            f.write("2020,1,0\n2020,2,0\n2021,1,0\n")
        code, _, err = self.run_cli(path)
        self.assertEqual(code, 2)
        self.assertIn("分母为零", err)

    def test_zero_denominator_skip_policy(self):
        path = os.path.join(self.tmpdir, "zero2.csv")
        with open(path, "w", encoding="utf-8") as f:
            f.write(
                "2020,1,0\n2020,2,10\n"
                "2021,1,100\n2021,2,200\n2022,1,50\n"
            )
        code, out, err = self.run_cli(path, "--zero-policy", "skip")
        self.assertEqual(code, 0, err)
        payload = json.loads(out)
        self.assertEqual(payload["factors"][0]["factor"], "2.000000")


if __name__ == "__main__":
    unittest.main()
