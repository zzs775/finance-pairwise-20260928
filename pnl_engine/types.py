"""核心数据类型（不可变、全部携带业务时刻与快照版本）。"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from enum import Enum


class ComponentKind(str, Enum):
    TRADE = "trade"
    PRICE = "price"
    INTEREST = "interest"
    FX = "fx"
    RESIDUAL = "residual"


@dataclass(frozen=True)
class SnapshotVersion:
    sequence: int
    label: str = ""


@dataclass(frozen=True)
class Stamped:
    instant: str
    version: SnapshotVersion


@dataclass(frozen=True)
class Asset:
    asset_id: str
    currency: str
    industry: str


@dataclass(frozen=True)
class Position:
    asset_id: str
    quantity: Decimal
    price: Decimal


@dataclass(frozen=True)
class Trade:
    trade_id: str
    asset_id: str
    instant: str
    quantity: Decimal
    price: Decimal
    currency: str
    version: SnapshotVersion


@dataclass(frozen=True)
class PriceTick:
    asset_id: str
    instant: str
    price: Decimal
    version: SnapshotVersion


@dataclass(frozen=True)
class AccrualTick:
    asset_id: str
    instant: str
    amount: Decimal
    currency: str
    version: SnapshotVersion


@dataclass(frozen=True)
class FxTick:
    currency: str
    instant: str
    rate: Decimal
    version: SnapshotVersion


@dataclass(frozen=True)
class InputBundle:
    base_currency: str
    start: str
    end: str
    assets: tuple[Asset, ...] = ()
    start_positions: tuple[Position, ...] = ()
    trades: tuple[Trade, ...] = ()
    prices: tuple[PriceTick, ...] = ()
    accruals: tuple[AccrualTick, ...] = ()
    fx: tuple[FxTick, ...] = ()


@dataclass(frozen=True)
class Component:
    kind: ComponentKind
    base: Decimal
    factor_delta: Decimal
    rounding: Decimal
    value: Decimal


@dataclass(frozen=True)
class AssetResult:
    asset_id: str
    currency: str
    start_value: Decimal
    end_value: Decimal
    components: tuple[Component, ...] = ()
    residual: Decimal = Decimal(0)


@dataclass(frozen=True)
class DiffItem:
    asset_id: str
    kind: str
    expected: Decimal
    actual: Decimal


@dataclass(frozen=True)
class NodeResult:
    node_id: str
    level: str
    start_value: Decimal
    end_value: Decimal
    components: tuple[Component, ...] = ()
    residual: Decimal = Decimal(0)
    children: tuple[str, ...] = ()


@dataclass(frozen=True)
class Report:
    base_currency: str
    start: str
    end: str
    assets: tuple[AssetResult, ...] = ()
    nodes: tuple[NodeResult, ...] = ()
    residual_diffs: tuple[DiffItem, ...] = ()
