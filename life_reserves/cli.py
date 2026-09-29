"""Command line interface."""

from __future__ import annotations

import argparse
import json
import sys
from decimal import Decimal

from life_reserves.engine import DEFAULT_TOLERANCE, run
from life_reserves.models import Expenses, Inputs, Timing


def _synthetic_mortality(issue_age: int, term: int) -> dict[int, Decimal]:
    """Deterministic synthetic Gompertz mortality (stdlib ``math`` only).

    ``mu(x) = B*c^x`` with ``B = 0.0001`` and ``c = 1.08``;
    ``q_x = 1 - exp(-mu(x))``, rounded to 12 decimal places.  This is
    fabricated data for demonstration, not a real industry table.
    """
    import math

    table: dict[int, Decimal] = {}
    for age in range(issue_age, issue_age + term + 1):
        mu = 0.0001 * (1.08 ** age)
        q = 1.0 - math.exp(-mu)
        table[age] = Decimal(str(round(q, 12)))
    return table


def _parse_config(raw: dict) -> Inputs:
    mortality = {int(age): Decimal(str(q)) for age, q in raw["mortality"].items()}
    expense_raw = raw.get("expenses", {})
    expenses = Expenses(
        initial=Decimal(str(expense_raw.get("initial", "0"))),
        renewal=Decimal(str(expense_raw.get("renewal", "0"))),
        claim=Decimal(str(expense_raw.get("claim", "0"))),
    )
    timing = Timing(raw.get("timing", "end"))
    return Inputs(
        issue_age=int(raw["issue_age"]),
        term=int(raw["term"]),
        sum_assured=Decimal(str(raw["sum_assured"])),
        timing=timing,
        interest_rate=Decimal(str(raw["interest_rate"])),
        mortality=mortality,
        expenses=expenses,
    )


def _decimal_default(value: Decimal) -> str:
    return str(value)


def _result_to_json(result) -> dict:
    return {
        "pricing": {
            "net_premium": result.pricing.net_premium,
            "gross_premium": result.pricing.gross_premium,
            "pv_benefit": result.pricing.pv_benefit,
            "pv_expense": result.pricing.pv_expense,
            "pv_net_premium": result.pricing.pv_net_premium,
            "pv_gross_premium": result.pricing.pv_gross_premium,
            "annuity_factor": result.pricing.annuity_factor,
        },
        "paths": [
            {
                "year": p.year,
                "age": p.age,
                "q": p.q_mortality,
                "p_conditional": p.p_conditional,
                "p_enter": p.p_enter,
                "p_death": p.p_death,
                "p_survive": p.p_survive,
            }
            for p in result.paths
        ],
        "cashflows": [
            {
                "year": c.year,
                "age": c.age,
                "premium_expected": c.premium_expected,
                "expense_expected": c.expense_expected,
                "benefit_expected": c.benefit_expected,
                "premium_pv": c.premium_pv,
                "expense_pv": c.expense_pv,
                "benefit_pv": c.benefit_pv,
            }
            for c in result.cashflows
        ],
        "reserves": [
            {
                "year": r.year,
                "age": r.age,
                "p_enter": r.p_enter,
                "reserve_state_sum": r.reserve_state_sum,
                "reserve_backward": r.reserve_backward,
            }
            for r in result.reserves
        ],
        "sensitivity": [
            {
                "interest_rate": s.interest_rate,
                "net_premium": s.net_premium,
                "gross_premium": s.gross_premium,
                "pv_benefit": s.pv_benefit,
                "pv_expense": s.pv_expense,
            }
            for s in result.sensitivity
        ],
        "diagnostics": [
            {
                "code": d.code,
                "detail": d.detail,
                "expected": str(d.expected),
                "actual": str(d.actual),
                "tolerance": str(d.tolerance),
            }
            for d in result.diagnostics
        ],
    }


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="life-reserves",
        description="Annual term life reserve engine (stdlib + Decimal).",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    run_p = sub.add_parser("run", help="read a JSON contract spec and run the engine")
    run_p.add_argument("--input", required=True, help="path to JSON contract spec")
    run_p.add_argument("--out", help="write JSON output here instead of stdout")
    run_p.add_argument(
        "--tolerance",
        default=str(DEFAULT_TOLERANCE),
        help="reserve reconciliation tolerance (default 1e-6)",
    )

    demo = sub.add_parser("demo", help="run a built-in synthetic-data example")
    demo.add_argument("--issue-age", type=int, default=40)
    demo.add_argument("--term", type=int, default=10)
    demo.add_argument("--sum-assured", default="100000")
    demo.add_argument("--rate", default="0.03")
    demo.add_argument("--timing", choices=("beginning", "end"), default="end")
    demo.add_argument("--out")

    return parser


def main(argv: list[str] | None = None) -> int:
    """Entry point."""
    parser = _build_parser()
    args = parser.parse_args(argv)

    if args.command == "run":
        with open(args.input, "r", encoding="utf-8") as fh:
            raw = json.load(fh)
        inputs = _parse_config(raw)
        shocks = (
            [Decimal(str(s)) for s in raw["rate_shocks"]]
            if "rate_shocks" in raw
            else None
        )
        tolerance = Decimal(args.tolerance)
    else:
        inputs = Inputs(
            issue_age=args.issue_age,
            term=args.term,
            sum_assured=Decimal(args.sum_assured),
            timing=Timing(args.timing),
            interest_rate=Decimal(args.rate),
            mortality=_synthetic_mortality(args.issue_age, args.term),
            expenses=Expenses(
                initial=Decimal("200"),
                renewal=Decimal("20"),
                claim=Decimal("100"),
            ),
        )
        shocks = None
        tolerance = DEFAULT_TOLERANCE

    result = run(inputs, rate_shocks=shocks, tolerance=tolerance)
    payload = json.dumps(
        _result_to_json(result),
        default=_decimal_default,
        indent=2,
        sort_keys=False,
        ensure_ascii=False,
    )
    if args.out:
        with open(args.out, "w", encoding="utf-8") as fh:
            fh.write(payload + "\n")
    else:
        print(payload)

    return 1 if result.diagnostics else 0


if __name__ == "__main__":
    sys.exit(main())
