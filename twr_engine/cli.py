"""Command line interface.

Examples::

    python -m twr_engine.cli sample
    python -m twr_engine.cli sample --query 2026-01-06:2026-01-08
"""

from __future__ import annotations

import argparse
from datetime import date

from .data import make_sample_input
from .engine import run
from .jsonio import dumps
from .oracle import recompute_composite, verify_account
from .query import query_return, query_status


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="twr_engine",
        description="Time-weighted return engine on synthetic local data.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    sample = subparsers.add_parser(
        "sample", help="run the deterministic synthetic dataset"
    )
    sample.add_argument(
        "--query",
        metavar="YYYY-MM-DD:YYYY-MM-DD",
        help="also query the linked return over an account range",
    )
    sample.add_argument(
        "--account",
        default=None,
        help="account id used by --query (defaults to the first account)",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.command == "sample":
        return _run_sample(args)
    parser.error(f"unknown command {args.command!r}")
    return 2


def _run_sample(args: argparse.Namespace) -> int:
    output = run(make_sample_input())

    for result in output.accounts.values():
        if not verify_account(result):
            raise AssertionError(
                f"account oracle check failed for {result.account_id}"
            )

    oracle_composites = recompute_composite(output.normalized)
    payload = {
        "accounts": {
            account_id: result
            for account_id, result in sorted(output.accounts.items())
        },
        "composites": {
            composite_id: result
            for composite_id, result in sorted(output.composites.items())
        },
        "oracle": oracle_composites,
    }

    if args.query:
        start_text, end_text = args.query.split(":", 1)
        start_day = date.fromisoformat(start_text)
        end_day = date.fromisoformat(end_text)
        account_id = args.account or next(iter(sorted(output.accounts)))
        periods = output.accounts[account_id].sub_periods
        payload["query"] = {
            "account_id": account_id,
            "start_day": start_day,
            "end_day": end_day,
            "status": query_status(periods, start_day, end_day),
            "twr": query_return(periods, start_day, end_day),
        }

    print(dumps(payload))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
