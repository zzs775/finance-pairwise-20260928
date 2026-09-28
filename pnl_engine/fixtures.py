"""本地合成数据夹具构造器（无任何外部数据源）。"""

from __future__ import annotations

from decimal import Decimal

from pnl_engine.decimal_utils import (
    freeze_amount,
    freeze_fx,
    freeze_price,
    freeze_qty,
)
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

T0 = "2026-01-01T00:00:00Z"
T1 = "2026-01-02T00:00:00Z"
T2 = "2026-01-03T00:00:00Z"


def btime(iso_ts: str, seq: int = 0) -> BusinessTime:
    return BusinessTime(iso_ts=iso_ts, seq=seq)


def snap(version: str, iso_ts: str = T0) -> Snapshot:
    return Snapshot(version=version, asof=btime(iso_ts))


def asset(asset_id: str, currency: str = "USD", industry: str = "equity") -> Asset:
    return Asset(asset_id=asset_id, currency=currency, industry=industry)


def position(asset_id: str, quantity: str, accrued: str, version: str = "s-start") -> Position:
    return Position(
        asset_id=asset_id,
        quantity=freeze_qty(quantity),
        accrued=freeze_amount(accrued),
        snapshot=snap(version),
    )


def txn(
    txn_id: str,
    asset_id: str,
    when: str,
    delta_qty: str,
    price: str,
    currency: str = "USD",
    seq: int = 0,
    version: str = "s-txn",
) -> Transaction:
    return Transaction(
        txn_id=txn_id,
        asset_id=asset_id,
        when=btime(when, seq),
        delta_qty=freeze_qty(delta_qty),
        price=freeze_price(price),
        currency=currency,
        snapshot=snap(version, when),
    )


def price(asset_id: str, when: str, value: str, seq: int = 0, version: str = "s-price") -> PriceTick:
    return PriceTick(
        asset_id=asset_id,
        when=btime(when, seq),
        price=freeze_price(value),
        snapshot=snap(version, when),
    )


def accrual(asset_id: str, when: str, value: str, seq: int = 0, version: str = "s-accr") -> AccrualTick:
    return AccrualTick(
        asset_id=asset_id,
        when=btime(when, seq),
        accrued=freeze_amount(value),
        snapshot=snap(version, when),
    )


def fx(currency: str, when: str, value: str, seq: int = 0, version: str = "s-fx") -> FXTick:
    return FXTick(
        currency=currency,
        when=btime(when, seq),
        fx_to_base=freeze_fx(value),
        snapshot=snap(version, when),
    )


def dataset(
    assets: list[Asset],
    start: list[Position],
    end: list[Position],
    transactions: list[Transaction] | None = None,
    prices: list[PriceTick] | None = None,
    accruals: list[AccrualTick] | None = None,
    fx_ticks: list[FXTick] | None = None,
    base_currency: str = "USD",
) -> Dataset:
    return Dataset(
        base_currency=base_currency,
        period_start=btime(T0),
        period_end=btime(T2),
        assets={item.asset_id: item for item in assets},
        start_positions={item.asset_id: item for item in start},
        end_positions={item.asset_id: item for item in end},
        transactions=transactions or [],
        prices=prices or [],
        accruals=accruals or [],
        fx=fx_ticks or _default_fx({item.currency for item in assets}, base_currency),
    )


def _default_fx(currencies: set[str], base_currency: str) -> list[FXTick]:
    ticks: list[FXTick] = []
    for currency in sorted(currencies):
        value = "1" if currency == base_currency else "1.0000000000"
        ticks.append(fx(currency, T0, value))
        ticks.append(fx(currency, T2, value))
    return ticks


def cash_account(
    asset_id: str = "USD_CASH",
    start_qty: str = "0",
    end_qty: str = "0",
) -> tuple[Asset, Position, Position]:
    item = asset(asset_id, currency="USD", industry="cash")
    return item, position(asset_id, start_qty, "0"), position(asset_id, end_qty, "0", "s-end")
