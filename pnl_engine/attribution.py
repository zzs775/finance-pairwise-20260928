"""逐资产固定路径归因：交易 -> 价格 -> 利息 -> FX。"""

from __future__ import annotations

from decimal import Decimal

from .decimalx import (
    FX_QUANT,
    MONEY_QUANT,
    PRICE_QUANT,
    QTY_QUANT,
    CALC_CONTEXT,
    freeze,
    fmul,
    fsum,
)
from .types import (
    Asset,
    AssetResult,
    Component,
    ComponentKind,
    InputBundle,
)

ROUND_QUANT = Decimal("0.00000001")

PRIORITY_TRADE = 1
PRIORITY_PRICE = 2
PRIORITY_ACCRUAL = 3
PRIORITY_FX = 4


def _component(kind, base: Decimal, factor_delta: Decimal, fx: Decimal) -> Component:
    """以 base*factor_delta*fx 构造分量，舍入项单独留痕。"""
    exact = CALC_CONTEXT.create_decimal(base) * CALC_CONTEXT.create_decimal(
        factor_delta
    ) * CALC_CONTEXT.create_decimal(fx)
    value = freeze(exact, MONEY_QUANT)
    rounding = freeze(value - exact, ROUND_QUANT)
    return Component(
        kind=kind,
        base=freeze(base, QTY_QUANT) if kind != ComponentKind.FX else freeze(base, MONEY_QUANT),
        factor_delta=freeze(factor_delta, PRICE_QUANT)
        if kind != ComponentKind.FX
        else freeze(factor_delta, FX_QUANT),
        rounding=rounding,
        value=value,
    )


def _opening_state(asset: Asset, bundle: InputBundle):
    quantity = Decimal(0)
    price: Decimal | None = None
    for position in bundle.start_positions:
        if position.asset_id == asset.asset_id:
            quantity = freeze(position.quantity, QTY_QUANT)
            price = freeze(position.price, PRICE_QUANT)
            break
    fx_rate = Decimal(1) if asset.currency == bundle.base_currency else None
    for tick in bundle.fx:
        if tick.currency == asset.currency and tick.instant == bundle.start:
            fx_rate = freeze(tick.rate, FX_QUANT)
            break
    return quantity, price, fx_rate


def _timeline(asset: Asset, bundle: InputBundle):
    events: list[tuple[str, int, int, str, object]] = []
    for trade in bundle.trades:
        if trade.asset_id == asset.asset_id:
            events.append(
                (
                    trade.instant,
                    PRIORITY_TRADE,
                    trade.version.sequence,
                    trade.trade_id,
                    trade,
                )
            )
    for tick in bundle.prices:
        if tick.asset_id == asset.asset_id:
            events.append(
                (tick.instant, PRIORITY_PRICE, tick.version.sequence, tick.asset_id, tick)
            )
    for tick in bundle.accruals:
        if tick.asset_id == asset.asset_id:
            events.append(
                (tick.instant, PRIORITY_ACCRUAL, tick.version.sequence, "", tick)
            )
    for tick in bundle.fx:
        if tick.currency == asset.currency:
            events.append(
                (tick.instant, PRIORITY_FX, tick.version.sequence, tick.currency, tick)
            )
    events.sort(key=lambda item: (item[0], item[1], item[2], item[3]))
    return events


def attribute_asset(asset: Asset, bundle: InputBundle) -> AssetResult:
    """对单资产按固定事件路径归因，返回带残差的结果。

    本币口径下分量依次为：交易（名义成交 + 相对最新盯市的滑点，均计入 trade）、
    价格（持仓量 * 价格差）、利息（持仓量 * 单位应计）。FX 分量在汇率跳动时刻
    对当时本币价值（含应计现金）计提。所有本币分量在产生时刻按当时汇率换算。
    """
    quantity, price, fx_rate = _opening_state(asset, bundle)
    accrued = Decimal(0)
    components: list[Component] = []

    if fx_rate is None:
        fx_rate = Decimal(1)

    start_local = (
        fmul(quantity, price, MONEY_QUANT) if price is not None else Decimal(0)
    )
    start_value = fmul(start_local, fx_rate, MONEY_QUANT)

    for _, _, _, _, event in _timeline(asset, bundle):
        kind_name = type(event).__name__
        if kind_name == "Trade":
            deal_price = freeze(event.price, PRICE_QUANT)
            delta_q = freeze(event.quantity, QTY_QUANT)
            if price is None and quantity == 0:
                price = deal_price
            components.append(
                _component(
                    ComponentKind.TRADE, delta_q, deal_price, fx_rate
                )
            )
            if price is not None and deal_price != price:
                components.append(
                    _component(
                        ComponentKind.TRADE,
                        delta_q,
                        price - deal_price,
                        fx_rate,
                    )
                )
            quantity = freeze(quantity + delta_q, QTY_QUANT)
            if quantity == 0:
                price = None
        elif kind_name == "PriceTick":
            new_price = freeze(event.price, PRICE_QUANT)
            if price is not None and new_price != price:
                components.append(
                    _component(
                        ComponentKind.PRICE,
                        quantity,
                        new_price - price,
                        fx_rate,
                    )
                )
            price = new_price
        elif kind_name == "AccrualTick":
            per_unit = freeze(event.amount, PRICE_QUANT)
            if quantity == 0:
                continue
            local_amount = fmul(quantity, per_unit, MONEY_QUANT)
            accrued += local_amount
            components.append(
                _component(
                    ComponentKind.INTEREST, quantity, per_unit, fx_rate
                )
            )
        elif kind_name == "FxTick":
            new_rate = freeze(event.rate, FX_QUANT)
            local_value = (
                fmul(quantity, price, MONEY_QUANT) if price is not None else Decimal(0)
            ) + accrued
            if new_rate != fx_rate:
                components.append(
                    _component(
                        ComponentKind.FX,
                        local_value,
                        new_rate - fx_rate,
                        Decimal(1),
                    )
                )
            fx_rate = new_rate

    if price is None:
        price = Decimal(0)
    end_local = fmul(quantity, price, MONEY_QUANT) + accrued
    end_value = fmul(end_local, fx_rate, MONEY_QUANT)

    component_sum = fsum(
        (component.value for component in components), MONEY_QUANT
    )
    residual = fsum([end_value - start_value - component_sum], MONEY_QUANT)

    return AssetResult(
        asset_id=asset.asset_id,
        currency=asset.currency,
        start_value=start_value,
        end_value=end_value,
        components=tuple(components),
        residual=residual,
    )
