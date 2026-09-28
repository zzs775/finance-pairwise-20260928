"""核心记录类型（骨架：仅类型定义）。"""

from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal
from enum import IntEnum


class EventPriority(IntEnum):
    """同刻事件固定优先级：交易最先，然后价格、利息、FX。"""

    TRADE = 0
    PRICE = 1
    INTEREST = 2
    FX = 3


@dataclass(frozen=True)
class BusinessTime:
    iso_ts: str
    seq: int = 0


@dataclass(frozen=True)
class Snapshot:
    version: str
    asof: BusinessTime


@dataclass(frozen=True)
class Asset:
    asset_id: str
    currency: str
    industry: str


@dataclass(frozen=True)
class Position:
    asset_id: str
    quantity: Decimal
    accrued: Decimal
    snapshot: Snapshot


@dataclass(frozen=True)
class Transaction:
    txn_id: str
    asset_id: str
    when: BusinessTime
    delta_qty: Decimal
    price: Decimal
    currency: str
    snapshot: Snapshot


@dataclass(frozen=True)
class PriceTick:
    asset_id: str
    when: BusinessTime
    price: Decimal
    snapshot: Snapshot


@dataclass(frozen=True)
class AccrualTick:
    asset_id: str
    when: BusinessTime
    accrued: Decimal
    snapshot: Snapshot


@dataclass(frozen=True)
class FXTick:
    currency: str
    when: BusinessTime
    fx_to_base: Decimal
    snapshot: Snapshot


@dataclass
class Dataset:
    base_currency: str
    period_start: BusinessTime
    period_end: BusinessTime
    assets: dict[str, Asset] = field(default_factory=dict)
    start_positions: dict[str, Position] = field(default_factory=dict)
    end_positions: dict[str, Position] = field(default_factory=dict)
    transactions: list[Transaction] = field(default_factory=list)
    prices: list[PriceTick] = field(default_factory=list)
    accruals: list[AccrualTick] = field(default_factory=list)
    fx: list[FXTick] = field(default_factory=list)


@dataclass(frozen=True)
class ComponentTerm:
    category: str
    base: Decimal
    factor_delta: Decimal
    raw: Decimal
    amount: Decimal
    rounding: Decimal
    when: BusinessTime
    snapshot_version: str


@dataclass
class AssetAttribution:
    asset_id: str
    industry: str
    currency: str
    start_value_base: Decimal = Decimal(0)
    end_value_base: Decimal = Decimal(0)
    terms: list[ComponentTerm] = field(default_factory=list)
    residual_base: Decimal = Decimal(0)
    snapshot_gap_base: Decimal = Decimal(0)
    trace: list["TraceItem"] = field(default_factory=list)


@dataclass(frozen=True)
class TraceItem:
    when: str
    kind: str
    amount: Decimal
    snapshot_version: str


@dataclass
class NodeSummary:
    node_id: str
    level: str
    start_value_base: Decimal = Decimal(0)
    end_value_base: Decimal = Decimal(0)
    trade_base: Decimal = Decimal(0)
    price_base: Decimal = Decimal(0)
    interest_base: Decimal = Decimal(0)
    fx_base: Decimal = Decimal(0)
    rounding_base: Decimal = Decimal(0)
    residual_base: Decimal = Decimal(0)
