"""输入校验：缺失因子、重复交易、币种不符、时间倒置、非有限值。"""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal

from .decimalx import is_finite
from .errors import (
    CurrencyMismatchError,
    DuplicateTradeError,
    MissingFactorError,
    NonFiniteValueError,
    TimeInversionError,
)
from .types import InputBundle


def _parse_instant(value: str, *, field: str) -> datetime:
    try:
        return datetime.fromisoformat(value)
    except (TypeError, ValueError) as exc:
        raise TimeInversionError(f"{field}: unparsable instant {value!r}") from exc


def _require_finite(value, *, where: str) -> None:
    if not is_finite(value):
        raise NonFiniteValueError(f"{where}: non-finite value {value!r}")


def _check_window(bundle: InputBundle) -> tuple[datetime, datetime]:
    start = _parse_instant(bundle.start, field="bundle.start")
    end = _parse_instant(bundle.end, field="bundle.end")
    if start > end:
        raise TimeInversionError(f"window inverted: {bundle.start} > {bundle.end}")
    return start, end


def _check_instant(instant: str, start, end, *, where: str) -> datetime:
    moment = _parse_instant(instant, field=where)
    if moment < start or moment > end:
        raise TimeInversionError(f"{where}: {instant} outside [{start}, {end}]")
    return moment


def _check_assets(bundle: InputBundle):
    if not bundle.base_currency:
        raise MissingFactorError("base_currency is empty")
    assets = {}
    for asset in bundle.assets:
        if not asset.asset_id:
            raise MissingFactorError("asset with empty asset_id")
        if asset.asset_id in assets:
            raise DuplicateTradeError(
                f"duplicate asset_id: {asset.asset_id}"
            )
        if not asset.currency:
            raise MissingFactorError(f"asset {asset.asset_id}: empty currency")
        if not asset.industry:
            raise MissingFactorError(f"asset {asset.asset_id}: empty industry")
        assets[asset.asset_id] = asset
    return assets


def _check_positions(bundle: InputBundle, assets, start, end) -> None:
    seen: set[str] = set()
    for position in bundle.start_positions:
        if position.asset_id not in assets:
            raise MissingFactorError(
                f"position for unknown asset {position.asset_id}"
            )
        if position.asset_id in seen:
            raise TimeInversionError(
                f"duplicate start position for {position.asset_id}"
            )
        seen.add(position.asset_id)
        _require_finite(position.quantity, where=f"position {position.asset_id}.quantity")
        _require_finite(position.price, where=f"position {position.asset_id}.price")
        if position.price < 0:
            raise MissingFactorError(
                f"position {position.asset_id}: negative price {position.price}"
            )


def _check_trades(bundle: InputBundle, assets, start, end) -> None:
    seen_ids: set[str] = set()
    for trade in bundle.trades:
        if trade.trade_id in seen_ids:
            raise DuplicateTradeError(f"duplicate trade_id: {trade.trade_id}")
        seen_ids.add(trade.trade_id)
        asset = assets.get(trade.asset_id)
        if asset is None:
            raise MissingFactorError(
                f"trade {trade.trade_id}: unknown asset {trade.asset_id}"
            )
        if trade.currency != asset.currency:
            raise CurrencyMismatchError(
                f"trade {trade.trade_id}: currency {trade.currency} != "
                f"asset currency {asset.currency}"
            )
        _check_instant(trade.instant, start, end, where=f"trade {trade.trade_id}")
        _require_finite(trade.quantity, where=f"trade {trade.trade_id}.quantity")
        _require_finite(trade.price, where=f"trade {trade.trade_id}.price")
        if trade.quantity == 0:
            raise MissingFactorError(f"trade {trade.trade_id}: zero quantity")
        if trade.price < 0:
            raise MissingFactorError(
                f"trade {trade.trade_id}: negative price {trade.price}"
            )
        if trade.version.sequence < 0:
            raise TimeInversionError(
                f"trade {trade.trade_id}: negative version sequence"
            )


def _check_prices(bundle: InputBundle, assets, start, end) -> None:
    latest: dict[str, datetime] = {}
    for tick in bundle.prices:
        asset = assets.get(tick.asset_id)
        if asset is None:
            raise MissingFactorError(
                f"price tick: unknown asset {tick.asset_id}"
            )
        moment = _check_instant(
            tick.instant, start, end, where=f"price {tick.asset_id}"
        )
        _require_finite(tick.price, where=f"price {tick.asset_id}@{tick.instant}")
        if tick.price < 0:
            raise MissingFactorError(
                f"price {tick.asset_id}@{tick.instant}: negative price"
            )
        previous = latest.get(tick.asset_id)
        if previous is not None and moment <= previous:
            raise TimeInversionError(
                f"price series for {tick.asset_id}: {tick.instant} not after {previous}"
            )
        latest[tick.asset_id] = moment


def _check_accruals(bundle: InputBundle, assets, start, end) -> None:
    for tick in bundle.accruals:
        asset = assets.get(tick.asset_id)
        if asset is None:
            raise MissingFactorError(
                f"accrual tick: unknown asset {tick.asset_id}"
            )
        if tick.currency != asset.currency:
            raise CurrencyMismatchError(
                f"accrual {tick.asset_id}@{tick.instant}: currency {tick.currency} "
                f"!= asset currency {asset.currency}"
            )
        _check_instant(
            tick.instant, start, end, where=f"accrual {tick.asset_id}"
        )
        _require_finite(tick.amount, where=f"accrual {tick.asset_id}@{tick.instant}")


def _check_fx(bundle: InputBundle, assets, start, end) -> None:
    latest: dict[str, datetime] = {}
    for tick in bundle.fx:
        if not tick.currency:
            raise MissingFactorError("fx tick with empty currency")
        moment = _check_instant(tick.instant, start, end, where=f"fx {tick.currency}")
        _require_finite(tick.rate, where=f"fx {tick.currency}@{tick.instant}")
        if tick.rate <= 0:
            raise MissingFactorError(
                f"fx {tick.currency}@{tick.instant}: rate must be positive, got {tick.rate}"
            )
        if tick.currency == bundle.base_currency and tick.rate != Decimal(1):
            raise CurrencyMismatchError(
                f"fx for base currency {tick.currency} must be 1, got {tick.rate}"
            )
        previous = latest.get(tick.currency)
        if previous is not None and moment <= previous:
            raise TimeInversionError(
                f"fx series for {tick.currency}: {tick.instant} not after {previous}"
            )
        latest[tick.currency] = moment


def _held_currencies(bundle: InputBundle, assets) -> set[str]:
    currencies: set[str] = set()
    for position in bundle.start_positions:
        currencies.add(assets[position.asset_id].currency)
    for trade in bundle.trades:
        currencies.add(assets[trade.asset_id].currency)
    return currencies


def _check_factor_coverage(bundle: InputBundle, assets) -> None:
    held = {
        position.asset_id
        for position in bundle.start_positions
    } | {trade.asset_id for trade in bundle.trades}
    end_prices = {tick.asset_id for tick in bundle.prices if tick.instant == bundle.end}
    for asset_id in held:
        if asset_id not in end_prices:
            raise MissingFactorError(
                f"asset {asset_id}: missing closing price at {bundle.end}"
            )

    fx_end = {tick.currency for tick in bundle.fx if tick.instant == bundle.end}
    fx_start = {tick.currency for tick in bundle.fx if tick.instant == bundle.start}
    for currency in _held_currencies(bundle, assets):
        if currency == bundle.base_currency:
            continue
        if currency not in fx_start:
            raise MissingFactorError(
                f"currency {currency}: missing opening fx at {bundle.start}"
            )
        if currency not in fx_end:
            raise MissingFactorError(
                f"currency {currency}: missing closing fx at {bundle.end}"
            )


def validate_bundle(bundle: InputBundle) -> None:
    start, end = _check_window(bundle)
    assets = _check_assets(bundle)
    _check_positions(bundle, assets, start, end)
    _check_trades(bundle, assets, start, end)
    _check_prices(bundle, assets, start, end)
    _check_accruals(bundle, assets, start, end)
    _check_fx(bundle, assets, start, end)
    _check_factor_coverage(bundle, assets)
