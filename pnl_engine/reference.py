"""直接重估参考器与冻结归因路径枚举（最多 8 个头寸）。"""

from __future__ import annotations

from decimal import Decimal

from .decimalx import FX_QUANT, MONEY_QUANT, PRICE_QUANT, QTY_QUANT, freeze, fmul
from .errors import MissingFactorError, ReferenceMismatchError
from .types import AssetResult, InputBundle

MAX_ENUMERATED_POSITIONS = 8


def _latest_up_to(ticks, instant: str):
    chosen = None
    for tick in sorted(ticks, key=lambda item: item.instant):
        if tick.instant <= instant:
            chosen = tick
    return chosen


def _quantity_after(bundle: InputBundle, asset_id: str, instant: str) -> Decimal:
    quantity = Decimal(0)
    for position in bundle.start_positions:
        if position.asset_id == asset_id:
            quantity = freeze(position.quantity, QTY_QUANT)
    for trade in sorted(bundle.trades, key=lambda item: (item.instant, item.version.sequence, item.trade_id)):
        if trade.asset_id == asset_id and trade.instant <= instant:
            quantity = freeze(quantity + trade.quantity, QTY_QUANT)
    return quantity


def _accrual_up_to(bundle: InputBundle, asset_id: str, instant: str) -> Decimal:
    total = Decimal(0)
    for tick in sorted(
        bundle.accruals, key=lambda item: (item.instant, item.version.sequence)
    ):
        if tick.asset_id == asset_id and tick.instant <= instant:
            held = _quantity_after(bundle, asset_id, tick.instant)
            total += fmul(held, freeze(tick.amount, PRICE_QUANT), MONEY_QUANT)
    return total


def direct_revalue(bundle: InputBundle) -> dict[str, object]:
    """独立路径：直接在起止边界用最新价格/汇率重估每个资产。

    不经过事件流，只回答两个问题：期初组合值多少（本币）、期末值多少（本币）。
    """
    reference: dict[str, object] = {}
    for asset in bundle.assets:
        start_q = _quantity_after(bundle, asset.asset_id, bundle.start)
        end_q = _quantity_after(bundle, asset.asset_id, bundle.end)

        start_price: Decimal | None = None
        for position in bundle.start_positions:
            if position.asset_id == asset.asset_id:
                start_price = freeze(position.price, PRICE_QUANT)
        end_tick = _latest_up_to(
            tuple(t for t in bundle.prices if t.asset_id == asset.asset_id),
            bundle.end,
        )
        if end_q != 0 and end_tick is None:
            raise MissingFactorError(
                f"reference: asset {asset.asset_id} missing closing price"
            )
        end_price = freeze(end_tick.price, PRICE_QUANT) if end_tick else Decimal(0)

        if asset.currency == bundle.base_currency:
            start_fx = end_fx = Decimal(1)
        else:
            fx_ticks = tuple(t for t in bundle.fx if t.currency == asset.currency)
            start_fx_tick = _latest_up_to(fx_ticks, bundle.start)
            end_fx_tick = _latest_up_to(fx_ticks, bundle.end)
            if start_fx_tick is None or end_fx_tick is None:
                raise MissingFactorError(
                    f"reference: asset {asset.asset_id} missing fx"
                )
            start_fx = freeze(start_fx_tick.rate, FX_QUANT)
            end_fx = freeze(end_fx_tick.rate, FX_QUANT)

        start_local = (
            fmul(start_q, start_price, MONEY_QUANT)
            if start_price is not None
            else Decimal(0)
        )
        end_local = fmul(end_q, end_price, MONEY_QUANT) + _accrual_up_to(
            bundle, asset.asset_id, bundle.end
        )
        reference[asset.asset_id] = {
            "start": fmul(start_local, start_fx, MONEY_QUANT),
            "end": fmul(end_local, end_fx, MONEY_QUANT),
        }
    return reference


def enumerate_paths(bundle: InputBundle) -> tuple:
    """枚举每个资产被冻结的归因路径（事件序列的规范表示）。

    持仓数超过 MAX_ENUMERATED_POSITIONS 时拒绝，确保路径可全量核对。
    """
    held = {
        position.asset_id
        for position in bundle.start_positions
    } | {trade.asset_id for trade in bundle.trades}
    if len(held) > MAX_ENUMERATED_POSITIONS:
        raise ReferenceMismatchError(
            f"enumeration limit: {len(held)} positions > {MAX_ENUMERATED_POSITIONS}"
        )
    paths = []
    for asset_id in sorted(held):
        events = []
        for trade in bundle.trades:
            if trade.asset_id == asset_id:
                events.append(
                    (
                        trade.instant,
                        1,
                        trade.version.sequence,
                        "trade",
                        str(trade.quantity),
                        str(trade.price),
                    )
                )
        for tick in bundle.prices:
            if tick.asset_id == asset_id:
                events.append(
                    (tick.instant, 2, tick.version.sequence, "price", str(tick.price))
                )
        for tick in bundle.accruals:
            if tick.asset_id == asset_id:
                events.append(
                    (tick.instant, 3, tick.version.sequence, "interest", str(tick.amount))
                )
        for tick in bundle.fx:
            currency = next(
                a.currency for a in bundle.assets if a.asset_id == asset_id
            )
            if tick.currency == currency:
                events.append(
                    (tick.instant, 4, tick.version.sequence, "fx", str(tick.rate))
                )
        paths.append((asset_id, tuple(sorted(events))))
    return tuple(paths)


def cross_check(bundle: InputBundle, results: list[AssetResult]) -> None:
    """用直接重估器逐项核对快速流水线的起止价值。"""
    reference = direct_revalue(bundle)
    paths = enumerate_paths(bundle)
    enumerated = {asset_id for asset_id, _ in paths}
    for result in results:
        expected = reference[result.asset_id]
        if result.asset_id not in enumerated:
            raise ReferenceMismatchError(
                f"asset {result.asset_id} absent from enumerated paths"
            )
        if result.start_value != expected["start"]:
            raise ReferenceMismatchError(
                f"asset {result.asset_id}: start {result.start_value} != "
                f"reference {expected['start']}"
            )
        if result.end_value != expected["end"]:
            raise ReferenceMismatchError(
                f"asset {result.asset_id}: end {result.end_value} != "
                f"reference {expected['end']}"
            )
