"""直接重估参考器与冻结路径枚举（骨架）。"""

from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal
from itertools import permutations

from pnl_engine.decimal_utils import AMOUNT_Q, QTY_Q, qmul
from pnl_engine.models import AssetAttribution, BusinessTime, Dataset


@dataclass(frozen=True)
class PathResult:
    order: tuple[str, ...]
    amounts: dict[str, Decimal] = field(default_factory=dict)
    residual: Decimal = Decimal(0)


@dataclass(frozen=True)
class Mismatch:
    asset_id: str
    message: str


MAX_ENUMERATION_POSITIONS = 8
_LOCAL_CATEGORIES = ("trade", "price", "interest", "fx")


def direct_value(dataset: Dataset, asset_id: str, when: BusinessTime) -> Decimal:
    asset = dataset.assets[asset_id]
    price = _level_at(dataset.prices, "asset_id", asset_id, when)
    fx = _level_at(dataset.fx, "currency", asset.currency, when)
    qty = _quantity_at(dataset, asset_id, when)
    if when == dataset.period_end and asset_id in dataset.end_positions:
        accrual = dataset.end_positions[asset_id].accrued
    elif when == dataset.period_start and asset_id in dataset.start_positions:
        accrual = dataset.start_positions[asset_id].accrued
    else:
        accrual = _level_at(dataset.accruals, "asset_id", asset_id, when)
    local = qmul(qty, price, AMOUNT_Q) + accrual
    return qmul(local, fx, AMOUNT_Q)


def enumerate_frozen_paths(dataset: Dataset, asset_id: str) -> list[PathResult]:
    """枚举同一刻因子变化的全部冻结顺序（24 种以内）。

    每个顺序独立使用伸缩式（telescoping）记账重放整条路径，
    返回该顺序下各类分量总额与残差。
    """
    asset = dataset.assets[asset_id]
    instants = _instants(dataset, asset_id, asset.currency)
    results: list[PathResult] = []

    def _replay(order: tuple[str, ...]) -> PathResult:
        price = _level_at(dataset.prices, "asset_id", asset_id, dataset.period_start)
        accrual = _level_at(dataset.accruals, "asset_id", asset_id, dataset.period_start)
        fx = _level_at(dataset.fx, "currency", asset.currency, dataset.period_start)
        qty = dataset.start_positions[asset_id].quantity
        local = qmul(qty, price, AMOUNT_Q) + accrual
        base = qmul(local, fx, AMOUNT_Q)
        totals = {cat: Decimal(0).quantize(AMOUNT_Q) for cat in _LOCAL_CATEGORIES}

        for when in instants:
            pending_txns = sorted(
                [
                    t
                    for t in dataset.transactions
                    if t.asset_id == asset_id and t.when == when
                ],
                key=lambda t: t.txn_id,
            )
            new_price = _optional_level(dataset.prices, "asset_id", asset_id, when, price)
            new_accrual = _optional_level(
                dataset.accruals, "asset_id", asset_id, when, accrual
            )
            new_fx = _optional_level(dataset.fx, "currency", asset.currency, when, fx)
            for factor in order:
                if factor == "trade" and pending_txns:
                    for txn in pending_txns:
                        flow = qmul(txn.delta_qty, txn.price, AMOUNT_Q)
                        qty = (qty + txn.delta_qty).quantize(QTY_Q)
                        local_after = qmul(qty, price, AMOUNT_Q) + accrual
                        pnl_local = local_after - local - flow
                        totals["trade"] += qmul(flow, fx, AMOUNT_Q) + qmul(
                            pnl_local, fx, AMOUNT_Q
                        )
                        local = local_after
                        base = qmul(local, fx, AMOUNT_Q)
                    pending_txns = []
                elif factor == "price" and new_price != price:
                    local_after = qmul(qty, new_price, AMOUNT_Q) + accrual
                    totals["price"] += qmul(local_after - local, fx, AMOUNT_Q)
                    price = new_price
                    local = local_after
                    base = qmul(local, fx, AMOUNT_Q)
                elif factor == "interest" and new_accrual != accrual:
                    local_after = qmul(qty, price, AMOUNT_Q) + new_accrual
                    totals["interest"] += qmul(local_after - local, fx, AMOUNT_Q)
                    accrual = new_accrual
                    local = local_after
                    base = qmul(local, fx, AMOUNT_Q)
                elif factor == "fx" and new_fx != fx:
                    base_after = qmul(local, new_fx, AMOUNT_Q)
                    totals["fx"] += base_after - base
                    fx = new_fx
                    base = base_after

        end_value = direct_value(dataset, asset_id, dataset.period_end)
        start_value = direct_value(dataset, asset_id, dataset.period_start)
        residual = end_value - start_value - sum(totals.values(), Decimal(0))
        return PathResult(order=order, amounts=totals, residual=residual)

    for order in permutations(_LOCAL_CATEGORIES):
        results.append(_replay(order))
    return results


def cross_check(
    dataset: Dataset, asset_results: dict[str, AssetAttribution]
) -> list[Mismatch]:
    mismatches: list[Mismatch] = []
    if len(asset_results) > MAX_ENUMERATION_POSITIONS:
        for asset_id in sorted(asset_results):
            mismatches.extend(_check_endpoints(dataset, asset_id, asset_results[asset_id]))
        return mismatches

    for asset_id in sorted(asset_results):
        result = asset_results[asset_id]
        mismatches.extend(_check_endpoints(dataset, asset_id, result))
        paths = enumerate_frozen_paths(dataset, asset_id)
        pipeline_totals = {
            "trade": _category_sum(result, ("trade_flow", "trade_pnl")),
            "price": _category_sum(result, ("price",)),
            "interest": _category_sum(result, ("interest",)),
            "fx": _category_sum(result, ("fx",)),
        }
        canonical_priority = ("trade", "price", "interest", "fx")
        reference = next(path for path in paths if path.order == canonical_priority)
        total_change = result.end_value_base - result.start_value_base
        for path in paths:
            if sum(path.amounts.values(), Decimal(0)) + path.residual != total_change:
                mismatches.append(
                    Mismatch(
                        asset_id,
                        f"frozen path {path.order} does not close to end-start value",
                    )
                )
        for category in _LOCAL_CATEGORIES:
            if pipeline_totals[category] != reference.amounts[category]:
                mismatches.append(
                    Mismatch(
                        asset_id,
                        f"category {category}: pipeline {pipeline_totals[category]} "
                        f"!= reference {reference.amounts[category]}",
                    )
                )
        if reference.residual != result.residual_base:
            mismatches.append(
                Mismatch(
                    asset_id,
                    f"reference residual {reference.residual} != pipeline residual {result.residual_base}",
                )
            )
    return mismatches


def _check_endpoints(
    dataset: Dataset, asset_id: str, result: AssetAttribution
) -> list[Mismatch]:
    mismatches: list[Mismatch] = []
    start_ref = direct_value(dataset, asset_id, dataset.period_start)
    end_ref = direct_value(dataset, asset_id, dataset.period_end)
    if start_ref != result.start_value_base:
        mismatches.append(
            Mismatch(asset_id, f"start value {result.start_value_base} != direct {start_ref}")
        )
    if end_ref != result.end_value_base:
        mismatches.append(
            Mismatch(asset_id, f"end value {result.end_value_base} != direct {end_ref}")
        )
    closure = result.end_value_base - result.start_value_base - sum(
        (term.amount for term in result.terms), Decimal(0)
    )
    if closure != result.residual_base:
        mismatches.append(Mismatch(asset_id, f"residual mismatch: {closure} vs {result.residual_base}"))
    return mismatches


def _category_sum(result: AssetAttribution, categories: tuple[str, ...]) -> Decimal:
    return sum(
        (term.amount for term in result.terms if term.category in categories),
        Decimal(0),
    )


def _quantity_at(dataset: Dataset, asset_id: str, when: BusinessTime) -> Decimal:
    qty = dataset.start_positions[asset_id].quantity
    for txn in sorted(dataset.transactions, key=lambda t: (t.when.iso_ts, t.when.seq, t.txn_id)):
        if txn.asset_id != asset_id:
            continue
        if (txn.when.iso_ts, txn.when.seq) <= (when.iso_ts, when.seq):
            qty = (qty + txn.delta_qty).quantize(QTY_Q)
    return qty


def _level_at(records, attr: str, key: str, when: BusinessTime) -> Decimal:
    matches = [r for r in records if getattr(r, attr) == key and r.when == when]
    if not matches:
        raise KeyError(f"factor at {when.iso_ts} for {attr}={key}")
    return getattr(matches[0], _value_field(records))


def _optional_level(records, attr: str, key: str, when: BusinessTime, default: Decimal) -> Decimal:
    matches = [r for r in records if getattr(r, attr) == key and r.when == when]
    if not matches:
        return default
    return getattr(matches[0], _value_field(records))


def _value_field(records) -> str:
    first = records[0]
    name = type(first).__name__
    return {
        "PriceTick": "price",
        "AccrualTick": "accrued",
        "FXTick": "fx_to_base",
    }[name]


def _instants(dataset: Dataset, asset_id: str, currency: str) -> list[BusinessTime]:
    instants = set()
    instants.update(t.when for t in dataset.transactions if t.asset_id == asset_id)
    instants.update(t.when for t in dataset.prices if t.asset_id == asset_id)
    instants.update(t.when for t in dataset.accruals if t.asset_id == asset_id)
    instants.update(t.when for t in dataset.fx if t.currency == currency)
    bounded = [
        w
        for w in instants
        if (dataset.period_start.iso_ts, dataset.period_start.seq)
        < (w.iso_ts, w.seq)
        <= (dataset.period_end.iso_ts, dataset.period_end.seq)
    ]
    return sorted(bounded, key=lambda w: (w.iso_ts, w.seq))
