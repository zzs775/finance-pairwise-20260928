"""本地合成 JSON 夹具加载器。

输入示例见 fixtures/sample_bundle.json。所有金额/价格/数量/汇率均以
字符串形式提供，避免 JSON 浮点进入 Decimal 管线。
"""

from __future__ import annotations

import json
from decimal import Decimal

from .errors import NonFiniteValueError
from .types import (
    AccrualTick,
    Asset,
    FxTick,
    InputBundle,
    Position,
    PriceTick,
    SnapshotVersion,
    Trade,
)


def _decimal(value, *, field: str) -> Decimal:
    if isinstance(value, bool):
        raise NonFiniteValueError(f"{field}: boolean is not numeric")
    try:
        return Decimal(str(value))
    except Exception as exc:
        raise NonFiniteValueError(f"{field}: cannot parse {value!r}") from exc


def _version(raw) -> SnapshotVersion:
    raw = raw or {}
    return SnapshotVersion(
        sequence=int(raw.get("sequence", 0)),
        label=str(raw.get("label", "")),
    )


def bundle_from_dict(raw: dict) -> InputBundle:
    assets = tuple(
        Asset(item["asset_id"], item["currency"], item["industry"])
        for item in raw.get("assets", [])
    )
    positions = tuple(
        Position(
            item["asset_id"],
            _decimal(item["quantity"], field="position.quantity"),
            _decimal(item["price"], field="position.price"),
        )
        for item in raw.get("start_positions", [])
    )
    trades = tuple(
        Trade(
            item["trade_id"],
            item["asset_id"],
            item["instant"],
            _decimal(item["quantity"], field=f"trade {item.get('trade_id')}.quantity"),
            _decimal(item["price"], field=f"trade {item.get('trade_id')}.price"),
            item["currency"],
            _version(item.get("version")),
        )
        for item in raw.get("trades", [])
    )
    prices = tuple(
        PriceTick(
            item["asset_id"],
            item["instant"],
            _decimal(item["price"], field="price.price"),
            _version(item.get("version")),
        )
        for item in raw.get("prices", [])
    )
    accruals = tuple(
        AccrualTick(
            item["asset_id"],
            item["instant"],
            _decimal(item["amount"], field="accrual.amount"),
            item["currency"],
            _version(item.get("version")),
        )
        for item in raw.get("accruals", [])
    )
    fx = tuple(
        FxTick(
            item["currency"],
            item["instant"],
            _decimal(item["rate"], field="fx.rate"),
            _version(item.get("version")),
        )
        for item in raw.get("fx", [])
    )
    return InputBundle(
        base_currency=raw["base_currency"],
        start=raw["start"],
        end=raw["end"],
        assets=assets,
        start_positions=positions,
        trades=trades,
        prices=prices,
        accruals=accruals,
        fx=fx,
    )


def load_bundle(path: str) -> InputBundle:
    with open(path, "r", encoding="utf-8") as handle:
        return bundle_from_dict(json.load(handle))


def trades_from_dict(raw) -> tuple[Trade, ...]:
    """解析修订交易包：接受 [trade,...] 或 {"trades": [...]}。"""
    items = raw.get("trades", []) if isinstance(raw, dict) else raw
    return tuple(
        Trade(
            item["trade_id"],
            item["asset_id"],
            item["instant"],
            _decimal(item["quantity"], field=f"trade {item.get('trade_id')}.quantity"),
            _decimal(item["price"], field=f"trade {item.get('trade_id')}.price"),
            item["currency"],
            _version(item.get("version")),
        )
        for item in items
    )


def load_trades(path: str) -> tuple[Trade, ...]:
    with open(path, "r", encoding="utf-8") as handle:
        return trades_from_dict(json.load(handle))
