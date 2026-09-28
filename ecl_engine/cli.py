"""Command-line interface (stdlib only)."""

import argparse
import json
import os
import sys
from datetime import date
from decimal import Decimal

from . import batch
from . import validation
from .errors import ECLError
from .models import AssumptionSet, Contract, Curve, Scenario


def _decimals(values) -> list[Decimal]:
    return [Decimal(str(value)) for value in values]


def demo_inputs() -> tuple[AssumptionSet, list[Contract]]:
    """Return built-in anonymized synthetic contracts and assumptions."""
    assumptions = AssumptionSet(
        "demo-v1", date(2026, 1, 31),
        scenarios=(Scenario("base", Decimal("0.60")),
                   Scenario("adverse", Decimal("0.40"))))

    def make(contract_id: str, stage: int, cumulative_pd, lgd, ead,
             discount) -> Contract:
        return Contract(
            contract_id, stage, len(cumulative_pd),
            Curve(_decimals(cumulative_pd)),
            Curve(_decimals(lgd)),
            Curve(_decimals(ead)),
            Curve(_decimals(discount)))

    contracts = [
        make("ANON-0001", 1,
             ["0.01", "0.025", "0.04"],
             ["0.45", "0.45", "0.45"],
             ["100000", "98000", "96000"],
             ["0.995", "0.985", "0.970"]),
        make("ANON-0002", 2,
             ["0.03", "0.07", "0.12", "0.18"],
             ["0.50", "0.50", "0.50", "0.50"],
             ["250000", "245000", "240000", "235000"],
             ["0.990", "0.975", "0.955", "0.930"]),
        make("ANON-0003", 3,
             ["0.80", "0.85"],
             ["0.60", "0.60"],
             ["75000", "70000"],
             ["0.980", "0.950"]),
    ]
    return assumptions, contracts


def load_inputs(path: str) -> tuple[AssumptionSet, list[Contract]]:
    """Load assumptions and contracts from a JSON document."""
    with open(path, "r", encoding="utf-8") as handle:
        payload = json.load(handle, parse_float=Decimal, parse_int=Decimal)
    version = payload["assumptions"]["version"]
    effective = date.fromisoformat(payload["assumptions"]["effective_date"])
    scenarios = tuple(
        Scenario(item["name"], Decimal(str(item["weight"])))
        for item in payload["assumptions"]["scenarios"])
    assumptions = AssumptionSet(version, effective, scenarios=scenarios)
    contracts = []
    for item in payload["contracts"]:
        curves = item["curves"]
        contracts.append(Contract(
            item["contract_id"], int(item["stage"]),
            int(item["remaining_periods"]),
            Curve(_decimals(curves["cumulative_pd"])),
            Curve(_decimals(curves["lgd"])),
            Curve(_decimals(curves["ead"])),
            Curve(_decimals(curves["discount"]))))
    return assumptions, contracts


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m ecl_engine.cli",
        description="Rule-driven expected credit loss engine")
    sub = parser.add_subparsers(dest="command", required=True)

    demo = sub.add_parser("demo", help="run built-in synthetic dataset")
    demo.add_argument("-o", "--output", help="write canonical JSON here")
    demo.add_argument("--batch-size", type=int, default=2)

    run = sub.add_parser("run", help="run contracts from a JSON file")
    run.add_argument("path", help="path to the JSON input file")
    run.add_argument("-o", "--output", help="write canonical JSON here")
    run.add_argument("--batch-size", type=int, default=2)
    return parser


def main(argv=None) -> int:
    """CLI entry point."""
    args = build_parser().parse_args(argv)
    try:
        if args.command == "demo":
            assumptions, contracts = demo_inputs()
        else:
            assumptions, contracts = load_inputs(args.path)
        validation.validate_assumptions(assumptions)
        full = batch.run_full(contracts, assumptions)
        incremental = batch.run_batches(
            contracts, assumptions, batch_size=args.batch_size)
        if full != incremental:
            print("internal error: batch mismatch", file=sys.stderr)
            return 1
        if args.output:
            parent = os.path.dirname(os.path.abspath(args.output))
            os.makedirs(parent, exist_ok=True)
            with open(args.output, "wb") as handle:
                handle.write(full)
        else:
            sys.stdout.buffer.write(full)
        return 0
    except (ECLError, ValueError, KeyError, TypeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
