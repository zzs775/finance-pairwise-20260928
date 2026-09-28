"""固定归因路径：逐资产按 交易→价格→利息→FX 优先级重放事件时间线。"""

from __future__ import annotations

from decimal import ROUND_HALF_EVEN, Context, Decimal

from pnl_engine.decimal_utils import AMOUNT_Q, QTY_Q, qmul
from pnl_engine.errors import Failure, FailureCode, ValidationError
from pnl_engine.models import (
    AssetAttribution,
    BusinessTime,
    ComponentTerm,
    Dataset,
)

_HIGH = Context(prec=60, rounding=ROUND_HALF_EVEN)

TRADE_FLOW = "trade_flow"
TRADE_PNL = "trade_pnl"
PRICE = "price"
INTEREST = "interest"
FX = "fx"


def attribute_asset(dataset: Dataset, asset_id: str) -> AssetAttribution:
    asset = dataset.assets[asset_id]
    start_pos = dataset.start_positions.get(asset_id)
    end_pos = dataset.end_positions.get(asset_id)
    if start_pos is None or end_pos is None:
        raise ValidationError(
            [
                Failure(
                    FailureCode.MISSING_FACTOR,
                    f"start/end position required for asset {asset_id}",
                    asset_id,
                )
            ]
        )

    price_at = _level_map(dataset.prices, asset_id, "asset_id")
    accrual_at = _level_map(dataset.accruals, asset_id, "asset_id")
    fx_at = _level_map(dataset.fx, asset.currency, "currency")

    p0 = price_at[dataset.period_start].price
    a0 = accrual_at[dataset.period_start].accrued
    f0 = fx_at[dataset.period_start].fx_to_base
    p_end = price_at[dataset.period_end].price
    a_end = accrual_at[dataset.period_end].accrued
    f_end = fx_at[dataset.period_end].fx_to_base

    if a0 != start_pos.accrued:
        raise ValidationError(
            [Failure(FailureCode.MISSING_FACTOR, "start accrual tick != position snapshot", asset_id)]
        )
    end_accrual_snapshot = end_pos.accrued

    qty = start_pos.quantity
    qty_check = qty
    for txn in sorted(
        (t for t in dataset.transactions if t.asset_id == asset_id),
        key=lambda t: (t.when.iso_ts, t.when.seq, t.txn_id),
    ):
        qty_check = (qty_check + txn.delta_qty).quantize(QTY_Q)
    if qty_check != end_pos.quantity:
        raise ValidationError(
            [
                Failure(
                    FailureCode.MISSING_FACTOR,
                    "end quantity != start quantity + trade deltas",
                    asset_id,
                )
            ]
        )

    result = AssetAttribution(
        asset_id=asset_id, industry=asset.industry, currency=asset.currency
    )

    price_now = p0
    accr_now = a0
    fx_now = f0
    local_now = _local_value(qty, price_now, accr_now)
    base_now = qmul(local_now, fx_now, AMOUNT_Q)
    result.start_value_base = base_now

    instants = _collect_instants(dataset, asset_id, asset.currency)
    for when in instants:
        base_before = base_now

        def _emit(category: str, base_factor: Decimal, factor_delta: Decimal,
                  local_raw: Decimal, local_amt: Decimal, version: str) -> None:
            nonlocal base_now
            base_amt = qmul(local_amt, fx_now, AMOUNT_Q)
            base_raw = _HIGH.multiply(local_raw, fx_now)
            result.terms.append(
                ComponentTerm(
                    category=category,
                    base=base_factor,
                    factor_delta=factor_delta,
                    raw=base_raw,
                    amount=base_amt,
                    rounding=base_amt - base_raw,
                    when=when,
                    snapshot_version=version,
                )
            )
            base_now += base_amt

        for txn in sorted(
            (t for t in dataset.transactions if t.asset_id == asset_id and t.when == when),
            key=lambda t: t.txn_id,
        ):
            flow_amt = qmul(txn.delta_qty, txn.price, AMOUNT_Q)
            flow_raw = _HIGH.multiply(txn.delta_qty, txn.price)
            _emit(TRADE_FLOW, txn.delta_qty, txn.price, flow_raw, flow_amt,
                  txn.snapshot.version)
            local_now = _local_value(qty, price_now, accr_now)
            qty = (qty + txn.delta_qty).quantize(QTY_Q)
            local_after = _local_value(qty, price_now, accr_now)
            pnl_amt = local_after - local_now - flow_amt
            pnl_raw = _HIGH.multiply(txn.delta_qty, price_now - txn.price)
            _emit(TRADE_PNL, txn.delta_qty, price_now - txn.price, pnl_raw, pnl_amt,
                  txn.snapshot.version)
            local_now = local_after

        price_tick = price_at.get(when)
        if price_tick is not None:
            new_price = price_tick.price
            local_after = _local_value(qty, new_price, accr_now)
            amt = local_after - local_now
            raw = _HIGH.multiply(qty, new_price - price_now)
            _emit(PRICE, qty, new_price - price_now, raw, amt,
                  price_tick.snapshot.version)
            price_now = new_price
            local_now = local_after

        accr_tick = accrual_at.get(when)
        if accr_tick is not None:
            new_accr = accr_tick.accrued
            amt = new_accr - accr_now
            _emit(INTEREST, Decimal(1).quantize(AMOUNT_Q), amt, amt, amt,
                  accr_tick.snapshot.version)
            accr_now = new_accr
            local_now = _local_value(qty, price_now, accr_now)

        fx_tick = fx_at.get(when)
        if fx_tick is not None:
            delta_fx = fx_tick.fx_to_base - fx_now
            fx_raw = _HIGH.multiply(local_now, delta_fx)
            base_after = qmul(local_now, fx_tick.fx_to_base, AMOUNT_Q)
            fx_amt = base_after - qmul(local_now, fx_now, AMOUNT_Q)
            result.terms.append(
                ComponentTerm(
                    category=FX,
                    base=local_now,
                    factor_delta=delta_fx,
                    raw=fx_raw,
                    amount=fx_amt,
                    rounding=fx_amt - fx_raw,
                    when=when,
                    snapshot_version=fx_tick.snapshot.version,
                )
            )
            fx_now = fx_tick.fx_to_base
            base_now += fx_amt

        if base_now != qmul(local_now, fx_now, AMOUNT_Q):
            residual_step = qmul(local_now, fx_now, AMOUNT_Q) - base_now
            result.terms.append(
                ComponentTerm(
                    category="residual",
                    base=Decimal(0).quantize(AMOUNT_Q),
                    factor_delta=Decimal(0).quantize(AMOUNT_Q),
                    raw=residual_step,
                    amount=residual_step,
                        rounding=Decimal(0).quantize(AMOUNT_Q),
                        when=when,
                        snapshot_version="rounding-tail",
                    )
                )
            base_now += residual_step

    snapshot_end = qmul(
        _local_value(end_pos.quantity, p_end, end_accrual_snapshot), f_end, AMOUNT_Q
    )
    result.end_value_base = snapshot_end
    if snapshot_end != base_now:
        gap = snapshot_end - base_now
        result.snapshot_gap_base = gap

    result.residual_base = sum(
        (t.amount for t in result.terms if t.category == "residual"), Decimal(0)
    ) + result.snapshot_gap_base
    result.residual_base = result.residual_base.quantize(AMOUNT_Q)
    result.snapshot_gap_base = result.snapshot_gap_base.quantize(AMOUNT_Q)
    from pnl_engine.models import TraceItem

    for term in result.terms:
        if term.category == "residual":
            result.trace.append(
                TraceItem(
                    when=f"{term.when.iso_ts}#{term.when.seq}",
                    kind="rounding_tail",
                    amount=term.amount,
                    snapshot_version=term.snapshot_version,
                )
            )
    if result.snapshot_gap_base != 0:
        result.trace.append(
            TraceItem(
                when=f"{dataset.period_end.iso_ts}#{dataset.period_end.seq}",
                kind="snapshot_gap",
                amount=result.snapshot_gap_base,
                snapshot_version=end_pos.snapshot.version,
            )
        )
    return result


def attribute_all(dataset: Dataset) -> dict[str, AssetAttribution]:
    return {
        asset_id: attribute_asset(dataset, asset_id)
        for asset_id in sorted(
            set(dataset.start_positions) | set(dataset.end_positions)
        )
    }


def _local_value(qty: Decimal, price: Decimal, accrued: Decimal) -> Decimal:
    return qmul(qty, price, AMOUNT_Q) + accrued


def _level_map(records, key_value: str, key_attr: str) -> dict[BusinessTime, object]:
    return {
        record.when: record
        for record in records
        if getattr(record, key_attr) == key_value
    }


def _collect_instants(dataset: Dataset, asset_id: str, currency: str) -> list[BusinessTime]:
    instants = {
        tick.when
        for tick in dataset.prices
        if tick.asset_id == asset_id and tick.when != dataset.period_start
    }
    instants.update(
        tick.when
        for tick in dataset.accruals
        if tick.asset_id == asset_id and tick.when != dataset.period_start
    )
    instants.update(
        tick.when
        for tick in dataset.fx
        if tick.currency == currency and tick.when != dataset.period_start
    )
    instants.update(
        txn.when
        for txn in dataset.transactions
        if txn.asset_id == asset_id
    )
    in_window = [
        w
        for w in instants
        if (dataset.period_start.iso_ts, dataset.period_start.seq)
        < (w.iso_ts, w.seq)
        <= (dataset.period_end.iso_ts, dataset.period_end.seq)
    ]
    return sorted(in_window, key=lambda w: (w.iso_ts, w.seq))
