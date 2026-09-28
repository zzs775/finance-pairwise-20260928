"""Command line interface (standard library argparse)."""

import argparse
import json
import sys
from decimal import Decimal

from .assumptions import AssumptionRegistry
from .reference import enumerate_reference
from .synthetic import load_assumptions, load_contracts
from .valuation import ValuationService, serialize_result


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="ecl-engine",
        description="Rule-driven ECL engine on local anonymous synthetic data",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    price = sub.add_parser("price", help="run a full valuation")
    price.add_argument("--version", default="v1")
    price.add_argument("--valuation-id", default="daily-run")
    price.add_argument("--run-date", default="2026-06-30")

    revise = sub.add_parser(
        "revise", help="publish a new version and run an incremental valuation"
    )
    revise.add_argument("--new-version", default="v2")
    revise.add_argument("--valuation-id", default="daily-run")
    revise.add_argument("--run-date", default="2026-06-30")

    reference = sub.add_parser(
        "reference", help="enumerate one contract with the direct oracle"
    )
    reference.add_argument("--contract", default="ANON-002")
    reference.add_argument("--version", default="v1")
    return parser


def _print_json(payload) -> None:
    sys.stdout.write(
        json.dumps(
            payload,
            sort_keys=True,
            indent=2,
            ensure_ascii=False,
            default=lambda value: str(value) if isinstance(value, Decimal) else value,
        )
        + "\n"
    )


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        contracts = load_contracts()
        if args.command == "price":
            registry = AssumptionRegistry()
            registry.publish(load_assumptions(args.version))
            service = ValuationService(registry, run_date=args.run_date)
            result = service.run_full(args.valuation_id, contracts, args.version)
            sys.stdout.buffer.write(serialize_result(result) + b"\n")
            return 0
        if args.command == "revise":
            registry = AssumptionRegistry()
            registry.publish(load_assumptions("v1"))
            service = ValuationService(registry, run_date=args.run_date)
            service.run_full(args.valuation_id, contracts, "v1")
            registry.publish(load_assumptions(args.new_version))
            result = service.run_incremental(
                args.valuation_id, contracts, args.new_version
            )
            sys.stdout.buffer.write(serialize_result(result) + b"\n")
            return 0
        if args.command == "reference":
            assumptions = load_assumptions(args.version)
            contract = next(
                item for item in contracts if item.contract_id == args.contract
            )
            rows = enumerate_reference(contract, assumptions)
            _print_json(rows)
            return 0
    except (ValueError, KeyError) as exc:
        sys.stderr.write(f"ecl-engine: {exc}\n")
        return 2
    parser.error(f"unknown command {args.command!r}")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
