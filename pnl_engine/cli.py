"""命令行入口。"""

from __future__ import annotations

import argparse
import json
import sys
from decimal import Decimal

from pnl_engine.aggregation import aggregate
from pnl_engine.attribution import attribute_all
from pnl_engine.canonical import to_canonical
from pnl_engine.decimal_utils import (
    freeze_amount,
    freeze_fx,
    freeze_price,
    freeze_qty,
)
from pnl_engine.direct_reval import cross_check
from pnl_engine.errors import ValidationError
from pnl_engine.models import (
    AccrualTick,
    Asset,
    BusinessTime,
    Dataset,
    FXTick,
    Position,
    PriceTick,
    Snapshot,
    Transaction,
)
from pnl_engine.validation import validate_dataset


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="组合 PnL 解释引擎")
    parser.add_argument("input", nargs="?", help="输入夹具 JSON 路径（默认 stdin）")
    parser.add_argument("-o", "--output", help="输出路径（默认 stdout）")
    parser.add_argument(
        "--require-zero-residual",
        action="store_true",
        help="控制夹具模式：residual 必须为零",
    )
    args = parser.parse_args(argv)

    raw_text = _read_text(args.input)
    try:
        payload = json.loads(raw_text, parse_float=Decimal)
    except json.JSONDecodeError as exc:
        print(f"invalid json: {exc}", file=sys.stderr)
        return 2

    try:
        dataset = parse_dataset(payload)
    except ValueError as exc:
        from pnl_engine.errors import Failure, FailureCode

        raise ValidationError(
            [Failure(FailureCode.NON_FINITE_VALUE, str(exc))]
        ) from exc
    try:
        failures = validate_dataset(dataset)
        if failures:
            raise ValidationError(failures)
        asset_results = attribute_all(dataset)
        nodes = aggregate(dataset, asset_results)
        mismatches = cross_check(dataset, asset_results)
    except ValidationError as exc:
        print(to_canonical({"status": "failed", "failures": exc.failures}), file=sys.stderr)
        return 1

    residuals = [
        {"asset_id": asset_id, "residual": result.residual_base}
        for asset_id, result in sorted(asset_results.items())
        if result.residual_base != 0
    ]
    portfolio_residual = nodes["portfolio"].residual_base
    if args.require_zero_residual and portfolio_residual != 0:
        text = to_canonical(
            {
                "status": "residual_nonzero",
                "portfolio_residual": portfolio_residual,
                "minimal_diff_set": _minimal_trace(asset_results),
            }
        )
        print(text, file=sys.stderr)
        return 3

    output = {
        "status": "ok" if not mismatches else "cross_check_failed",
        "assets": {
            asset_id: {
                "start_value_base": result.start_value_base,
                "end_value_base": result.end_value_base,
                "residual_base": result.residual_base,
                "terms": result.terms,
            }
            for asset_id, result in sorted(asset_results.items())
        },
        "nodes": {node_id: node for node_id, node in sorted(nodes.items())},
        "residuals": residuals,
        "mismatches": [
            {"asset_id": m.asset_id, "message": m.message} for m in mismatches
        ],
    }
    text = to_canonical(output)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as handle:
            handle.write(text)
    else:
        print(text)
    return 0 if output["status"] == "ok" else 4


def parse_dataset(payload: dict) -> Dataset:
    period_start = _btime(payload["period_start"])
    period_end = _btime(payload["period_end"])

    assets = {
        item["asset_id"]: Asset(
            asset_id=item["asset_id"],
            currency=item["currency"],
            industry=item["industry"],
        )
        for item in payload.get("assets", [])
    }

    def _position(table: dict) -> dict[str, Position]:
        return {
            asset_id: Position(
                asset_id=asset_id,
                quantity=freeze_qty(item["quantity"]),
                accrued=freeze_amount(item["accrued"]),
                snapshot=_snapshot(item["snapshot"]),
            )
            for asset_id, item in table.items()
        }

    transactions = [
        Transaction(
            txn_id=item["txn_id"],
            asset_id=item["asset_id"],
            when=_btime(item["when"]),
            delta_qty=freeze_qty(item["delta_qty"]),
            price=freeze_price(item["price"]),
            currency=item["currency"],
            snapshot=_snapshot(item["snapshot"]),
        )
        for item in payload.get("transactions", [])
    ]
    prices = [
        PriceTick(
            asset_id=item["asset_id"],
            when=_btime(item["when"]),
            price=freeze_price(item["price"]),
            snapshot=_snapshot(item["snapshot"]),
        )
        for item in payload.get("prices", [])
    ]
    accruals = [
        AccrualTick(
            asset_id=item["asset_id"],
            when=_btime(item["when"]),
            accrued=freeze_amount(item["accrued"]),
            snapshot=_snapshot(item["snapshot"]),
        )
        for item in payload.get("accruals", [])
    ]
    fx = [
        FXTick(
            currency=item["currency"],
            when=_btime(item["when"]),
            fx_to_base=freeze_fx(item["fx_to_base"]),
            snapshot=_snapshot(item["snapshot"]),
        )
        for item in payload.get("fx", [])
    ]

    return Dataset(
        base_currency=payload["base_currency"],
        period_start=period_start,
        period_end=period_end,
        assets=assets,
        start_positions=_position(payload.get("start_positions", {})),
        end_positions=_position(payload.get("end_positions", {})),
        transactions=transactions,
        prices=prices,
        accruals=accruals,
        fx=fx,
    )


def _btime(value) -> BusinessTime:
    if isinstance(value, str):
        return BusinessTime(iso_ts=value)
    return BusinessTime(iso_ts=value["iso_ts"], seq=int(value.get("seq", 0)))


def _snapshot(value) -> Snapshot:
    return Snapshot(version=value["version"], asof=_btime(value["asof"]))


def _read_text(path: str | None) -> str:
    if not path or path == "-":
        return sys.stdin.read()
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def _minimal_trace(asset_results: dict) -> list[dict]:
    trace: list[dict] = []
    for asset_id in sorted(asset_results):
        result = asset_results[asset_id]
        if result.residual_base == 0:
            continue
        trace.append(
            {
                "asset_id": asset_id,
                "residual_base": result.residual_base,
                "instants": [
                    {
                        "when": item.when,
                        "kind": item.kind,
                        "residual": item.amount,
                        "snapshot_version": item.snapshot_version,
                    }
                    for item in result.trace
                ],
            }
        )
    return trace


if __name__ == "__main__":
    sys.exit(main())
