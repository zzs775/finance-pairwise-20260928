import io
import json
import unittest
from contextlib import redirect_stdout
from decimal import Decimal

from life_reserves import cli
from life_reserves.engine import run
from life_reserves.models import Expenses, Inputs, Timing


def _inputs(mortality=None, timing=Timing.END, rate="0.03", term=4):
    if mortality is None:
        mortality = {
            30: Decimal("0.002"),
            31: Decimal("0.003"),
            32: Decimal("0.004"),
            33: Decimal("0.005"),
        }
    return Inputs(
        issue_age=30,
        term=term,
        sum_assured=Decimal("100000"),
        timing=timing,
        interest_rate=Decimal(rate),
        mortality=mortality,
        expenses=Expenses(
            initial=Decimal("100"), renewal=Decimal("10"), claim=Decimal("50")
        ),
    )


class EngineIntegrationTest(unittest.TestCase):
    def test_end_to_end_no_diagnostics(self) -> None:
        result = run(_inputs())
        self.assertEqual(result.diagnostics, [])
        # Tables are sorted by year regardless of input dict ordering.
        for table in (result.paths, result.cashflows, result.reserves):
            years = [row.year for row in table]
            self.assertEqual(years, sorted(years))

    def test_input_reordering_does_not_change_output(self) -> None:
        ordered = _inputs()
        shuffled = dict(reversed(list(ordered.mortality.items())))
        a = run(ordered)
        b = run(_inputs(mortality=shuffled))
        for pa, pb in zip(a.paths, b.paths):
            self.assertEqual(pa.p_death, pb.p_death)
            self.assertEqual(pa.p_enter, pb.p_enter)
        self.assertEqual(a.pricing.gross_premium, b.pricing.gross_premium)

    def test_sensitivity_is_stably_sorted_and_monotone(self) -> None:
        result = run(
            _inputs(),
            rate_shocks=[Decimal("0.02"), Decimal("-0.02"), Decimal("0")],
        )
        rates = [row.interest_rate for row in result.sensitivity]
        self.assertEqual(rates, sorted(rates))
        gross = [row.gross_premium for row in result.sensitivity]
        # Higher discount rate -> lower required premium.
        self.assertGreater(gross[0], gross[2])

    def test_age_out_of_range_surfaces_value_error(self) -> None:
        inp = _inputs(mortality={30: Decimal("0.002"), 31: Decimal("0.003")})
        with self.assertRaises(ValueError):
            run(inp)

    def test_probability_out_of_range_surfaces_value_error(self) -> None:
        bad = {
            30: Decimal("0.002"),
            31: Decimal("0.003"),
            32: Decimal("1.2"),
            33: Decimal("0.005"),
        }
        with self.assertRaises(ValueError):
            run(_inputs(mortality=bad))

    def test_extreme_interest_rate_runs(self) -> None:
        result = run(_inputs(rate="999"))
        self.assertEqual(result.diagnostics, [])
        self.assertGreater(result.pricing.gross_premium, Decimal(0))

    def test_engine_surfaces_diagnostics_under_ultra_tight_tolerance(self) -> None:
        # Tighter than Decimal working precision: rounding noise must surface
        # as a diagnostic rather than the engine silently picking a method.
        result = run(_inputs(rate="0.03", term=3), tolerance=Decimal("1e-40"))
        self.assertTrue(result.diagnostics)
        self.assertEqual(
            result.diagnostics[0].code, "RESERVE_METHOD_MISMATCH"
        )

    def test_timing_switch_discounts_all_pvs_but_keeps_premium(self) -> None:
        end = run(_inputs(timing=Timing.END))
        beginning = run(_inputs(timing=Timing.BEGINNING))
        for pe, pb in zip(end.paths, beginning.paths):
            self.assertEqual(pe.p_death, pb.p_death)
        # Uniform one-year shift: end-timing PVs are beginning PVs times v;
        # the premium solving the equivalence equation is therefore unchanged.
        v = Decimal(1) / Decimal("1.03")
        self.assertLess(
            abs(end.pricing.pv_benefit - beginning.pricing.pv_benefit * v),
            Decimal("1e-20"),
        )
        self.assertEqual(
            end.pricing.gross_premium, beginning.pricing.gross_premium
        )


class CliSmokeTest(unittest.TestCase):
    def test_demo_command_emits_json(self) -> None:
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = cli.main(
                ["demo", "--issue-age", "40", "--term", "3", "--rate", "0.03"]
            )
        self.assertEqual(code, 0)
        payload = json.loads(buf.getvalue())
        self.assertIn("pricing", payload)
        self.assertEqual(len(payload["paths"]), 3)
        self.assertEqual(payload["reserves"][-1]["reserve_backward"], "0")

    def test_run_command_with_example_contract(self) -> None:
        buf = io.StringIO()
        with redirect_stdout(buf):
            code = cli.main(["run", "--input", "examples/contract.json"])
        self.assertEqual(code, 0)
        payload = json.loads(buf.getvalue())
        self.assertEqual(len(payload["paths"]), 10)
        self.assertEqual(payload["diagnostics"], [])


if __name__ == "__main__":
    unittest.main()
