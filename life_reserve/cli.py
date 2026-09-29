"""Command-line interface for the annual term reserve engine."""

from __future__ import annotations

import argparse
import sys
from decimal import Decimal

from .engine import rate_sensitivity, run_valuation, synthetic_table
from .errors import ValidationError
from .model import ExpenseAssumptions, MortalityTable, Policy, Timing
from .oracle import run_oracle


def _fmt(value: Decimal, places: str = "%.6f") -> str:
    return places % value


def _print_table(headers: list[str], rows: list[list[str]]) -> None:
    widths = [len(h) for h in headers]
    for row in rows:
        for idx, cell in enumerate(row):
            widths[idx] = max(widths[idx], len(cell))
    line = "  ".join(h.ljust(widths[i]) for i, h in enumerate(headers))
    print(line)
    print("  ".join("-" * widths[i] for i in range(len(headers))))
    for row in rows:
        print("  ".join(row[i].ljust(widths[i]) for i in range(len(headers))))


def _parse_qx(values: list[str]) -> dict[int, Decimal]:
    rates: dict[int, Decimal] = {}
    for item in values:
        age_text, rate_text = item.split("=", 1)
        rates[int(age_text)] = Decimal(rate_text)
    return rates


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="life-reserve",
        description="Annual term life reserve engine (stdlib + synthetic data).",
    )
    parser.add_argument("--issue-age", type=int, required=True)
    parser.add_argument("--term", type=int, required=True)
    parser.add_argument("--sum-assured", type=Decimal, default=Decimal("100000"))
    parser.add_argument("--rate", type=Decimal, default=Decimal("0.03"))
    parser.add_argument(
        "--timing",
        choices=[t.value for t in Timing],
        default=Timing.BEGINNING.value,
    )
    parser.add_argument("--expense-percent", type=Decimal, default=Decimal(0))
    parser.add_argument("--expense-per-policy", type=Decimal, default=Decimal(0))
    parser.add_argument("--initial-expense", type=Decimal, default=Decimal(0))
    parser.add_argument(
        "--qx",
        action="append",
        default=[],
        metavar="AGE=RATE",
        help="per-age mortality, repeatable; defaults to the synthetic table",
    )
    parser.add_argument(
        "--tolerance", type=Decimal, default=Decimal("0.0001")
    )
    parser.add_argument(
        "--sensitivity",
        type=Decimal,
        action="append",
        default=None,
        metavar="BUMP",
        help="rate shock, repeatable (default +-1%)",
    )
    parser.add_argument(
        "--oracle", action="store_true", help="run mechanical oracle checks"
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    if args.qx:
        table = MortalityTable(rates=_parse_qx(args.qx))
    else:
        table = synthetic_table(args.issue_age, args.term)

    policy = Policy(
        issue_age=args.issue_age,
        term=args.term,
        sum_assured=args.sum_assured,
        interest_rate=args.rate,
        timing=Timing(args.timing),
        expenses=ExpenseAssumptions(
            percent_of_premium=args.expense_percent,
            per_policy=args.expense_per_policy,
            initial_expense=args.initial_expense,
        ),
    )

    try:
        valuation = run_valuation(policy, table, args.tolerance)
    except ValidationError as exc:
        print(f"input error: {exc}", file=sys.stderr)
        return 2

    p = valuation.pricing
    print("== Pricing ==")
    print(f"net premium          : {_fmt(p.net_premium)}")
    print(f"gross premium        : {_fmt(p.gross_premium)}")
    print(f"benefit PV           : {_fmt(p.benefit_pv)}")
    print(f"expense PV           : {_fmt(p.expense_pv)}")
    print(f"net premium PV       : {_fmt(p.net_premium_pv)}")
    print(f"gross premium PV     : {_fmt(p.gross_premium_pv)}")
    print(f"premium annuity      : {_fmt(p.annuity_factor)}")

    print("\n== State / cash-flow detail (sorted by year) ==")
    cf_by_year = {row.year: row for row in valuation.cashflows}
    rows: list[list[str]] = []
    for state in sorted(valuation.states, key=lambda s: s.year):
        cf = cf_by_year[state.year]
        rows.append(
            [
                str(state.year),
                str(state.age),
                _fmt(state.in_force),
                _fmt(state.death_prob),
                _fmt(state.survive_prob),
                _fmt(cf.premium),
                _fmt(cf.expense),
                _fmt(cf.benefit),
                _fmt(cf.discount_factor),
            ]
        )
    _print_table(
        [
            "year", "age", "in_force", "death", "survive",
            "premium", "expense", "benefit", "v^t",
        ],
        rows,
    )

    print("\n== Yearly reserves ==")
    reserve_rows = [
        [
            str(row.year),
            str(row.age),
            _fmt(row.in_force),
            _fmt(row.reserve_explicit),
            _fmt(row.reserve_recursive),
        ]
        for row in sorted(valuation.reserves.rows, key=lambda r: r.year)
    ]
    _print_table(
        ["year", "age", "in_force", "explicit", "recursive"], reserve_rows
    )

    if valuation.reserves.diagnostics:
        print("\n!! reserve method disagreement (diagnostic only):", file=sys.stderr)
        for d in valuation.reserves.diagnostics:
            print(
                f"  year {d.year}: explicit={d.explicit} recursive={d.recursive}"
                f" diff={d.difference} tol={d.tolerance}",
                file=sys.stderr,
            )
        return 3

    bumps = (
        tuple(args.sensitivity)
        if args.sensitivity is not None
        else (Decimal("-0.01"), Decimal("0.01"))
    )
    print("\n== Rate sensitivity ==")
    sens_rows = [
        [
            _fmt(row["rate"]),
            _fmt(row["net_premium"]),
            _fmt(row["gross_premium"]),
            _fmt(row["benefit_pv"]),
            _fmt(row["expense_pv"]),
        ]
        for row in rate_sensitivity(policy, table, bumps, args.tolerance)
    ]
    _print_table(
        ["rate", "net_premium", "gross_premium", "benefit_pv", "expense_pv"],
        sens_rows,
    )

    if args.oracle:
        report = run_oracle(policy, table, valuation, args.tolerance)
        print("\n== Oracle ==")
        for check in report.checks:
            print(f"[{'PASS' if check.passed else 'FAIL'}] {check.name}: {check.detail}")
        if not report.passed:
            return 4

    return 0


if __name__ == "__main__":
    sys.exit(main())
