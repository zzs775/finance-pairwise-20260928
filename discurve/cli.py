"""Command-line entry point: build a curve and print diagnostics."""

import argparse
import json
import sys
from datetime import date
from decimal import Decimal

from .bootstrap import PRICING_TOLERANCE, bootstrap
from .calendar import HolidayCalendar
from .errors import CurveError
from .instruments import (
    Bond,
    BondCashFlow,
    Deposit,
    FRA,
    QuoteSnapshot,
    Swap,
)
from .serialization import canonical_dumps
from .versioning import CurveStore, CurveVersion


def _parse_decimal(value, label: str) -> Decimal:
    try:
        result = Decimal(str(value))
    except Exception as exc:
        raise CurveError(f"{label} must parse as a decimal: {value!r}") from exc
    if not result.is_finite():
        raise CurveError(f"{label} must be finite")
    return result


def _parse_date(value: str) -> date:
    try:
        return date.fromisoformat(value)
    except (TypeError, ValueError) as exc:
        raise CurveError(f"invalid ISO date {value!r}") from exc


def parse_snapshot(payload: dict) -> QuoteSnapshot:
    deposits = tuple(
        Deposit(
            name=item["name"],
            start=_parse_date(item["start"]),
            maturity=_parse_date(item["maturity"]),
            rate=_parse_decimal(item["rate"], "rate"),
            day_count=item.get("day_count", "ACT/360"),
            notional=_parse_decimal(item.get("notional", "1"), "notional"),
        )
        for item in payload.get("deposits", [])
    )
    fras = tuple(
        FRA(
            name=item["name"],
            start=_parse_date(item["start"]),
            maturity=_parse_date(item["maturity"]),
            rate=_parse_decimal(item["rate"], "rate"),
            day_count=item.get("day_count", "ACT/360"),
            notional=_parse_decimal(item.get("notional", "1"), "notional"),
        )
        for item in payload.get("fras", [])
    )
    swaps = tuple(
        Swap(
            name=item["name"],
            effective=_parse_date(item["effective"]),
            maturity=_parse_date(item["maturity"]),
            fixed_rate=_parse_decimal(item["fixed_rate"], "fixed_rate"),
            period_months=int(item.get("period_months", 6)),
            day_count=item.get("day_count", "30/360"),
            business_day_convention=item.get(
                "business_day_convention", "modified_following"
            ),
            stub=item.get("stub", "short_front"),
            notional=_parse_decimal(item.get("notional", "1"), "notional"),
        )
        for item in payload.get("swaps", [])
    )
    bonds = tuple(
        Bond(
            name=item["name"],
            cash_flows=tuple(
                BondCashFlow(
                    payment_date=_parse_date(flow["payment_date"]),
                    amount=_parse_decimal(flow["amount"], "amount"),
                )
                for flow in item["cash_flows"]
            ),
        )
        for item in payload.get("bonds", [])
    )
    return QuoteSnapshot(
        version_id=str(payload["version_id"]),
        valuation_date=_parse_date(payload["valuation_date"]),
        deposits=deposits,
        fras=fras,
        swaps=swaps,
        bonds=bonds,
    )


def calendar_from_payload(payload: dict) -> HolidayCalendar:
    calendar_payload = payload.get("calendar", {})
    return HolidayCalendar(
        name=str(calendar_payload.get("name", "embedded")),
        holidays=frozenset(
            _parse_date(day) for day in calendar_payload.get("holidays", [])
        ),
    )


def build_report(snapshot: QuoteSnapshot, calendar: HolidayCalendar) -> dict:
    store = CurveStore()
    draft = bootstrap(snapshot, calendar)
    store.publish(
        CurveVersion(
            version_id="curve-" + snapshot.version_id,
            quote_version_id=snapshot.version_id,
            valuation_date=snapshot.valuation_date,
            curve=draft.curve,
        )
    )

    from .pricing import bond_pv

    bond_results = [
        {
            "name": bond.name,
            "model_pv": bond_pv(bond, store.discount),
        }
        for bond in snapshot.bonds
    ]
    return {
        "quote_version_id": snapshot.version_id,
        "curve_version_id": store.version_id,
        "valuation_date": snapshot.valuation_date,
        "pricing_tolerance": PRICING_TOLERANCE,
        "nodes": [
            {"date": day, "discount_factor": df}
            for day, df in draft.curve.nodes
        ],
        "diagnostics": [
            {
                "name": diag.name,
                "kind": diag.kind,
                "node_date": diag.node_date,
                "model_pv": diag.model_pv,
                "market_target": diag.market_target,
                "residual": diag.residual,
                "iterations": diag.iterations,
            }
            for diag in draft.diagnostics
        ],
        "bonds": bond_results,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="discurve",
        description="Bootstrap a single-currency discount curve.",
    )
    sub = parser.add_subparsers(dest="command", required=True)
    build = sub.add_parser("build", help="build a curve from a quote JSON file")
    build.add_argument("quotes", help="path to the quote JSON file")
    build.add_argument(
        "-o", "--output", help="write canonical JSON report here instead of stdout"
    )
    args = parser.parse_args(argv)

    if args.command == "build":
        try:
            with open(args.quotes, "r", encoding="utf-8") as handle:
                payload = json.load(handle)
            snapshot = parse_snapshot(payload)
            calendar = calendar_from_payload(payload)
            report = build_report(snapshot, calendar)
            text = canonical_dumps(report)
        except (OSError, json.JSONDecodeError, KeyError, TypeError) as exc:
            parser.error(f"invalid quote file: {exc}")
        except CurveError as exc:
            print(f"error: {exc}", file=sys.stderr)
            return 1
        if args.output:
            with open(args.output, "w", encoding="utf-8") as handle:
                handle.write(text + "\n")
        else:
            sys.stdout.write(text + "\n")
        return 0
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
