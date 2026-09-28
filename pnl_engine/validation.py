"""输入校验：分类失败（骨架）。"""

from __future__ import annotations

from decimal import Decimal

from pnl_engine.decimal_utils import is_finite_decimal
from pnl_engine.errors import Failure, FailureCode
from pnl_engine.models import Dataset


def _f(code: FailureCode, message: str, record_id: str | None = None) -> Failure:
    return Failure(code=code, message=message, record_id=record_id)


def validate_dataset(dataset: Dataset) -> list[Failure]:
    failures: list[Failure] = []

    failures.extend(_validate_finite_values(dataset))
    failures.extend(_validate_currencies(dataset))
    failures.extend(_validate_duplicates(dataset))
    failures.extend(_validate_time_order(dataset))
    failures.extend(_validate_required_factors(dataset))

    return sorted(failures, key=lambda f: (f.code.value, f.record_id or "", f.message))


def _validate_finite_values(dataset: Dataset) -> list[Failure]:
    out: list[Failure] = []
    numeric_fields = {
        "start_positions": ("quantity", "accrued"),
        "end_positions": ("quantity", "accrued"),
        "transactions": ("delta_qty", "price"),
        "prices": ("price",),
        "accruals": ("accrued",),
        "fx": ("fx_to_base",),
    }
    for attr_name, fields in numeric_fields.items():
        records = getattr(dataset, attr_name).values() if isinstance(
            getattr(dataset, attr_name), dict
        ) else getattr(dataset, attr_name)
        for record in records:
            rid = getattr(record, "txn_id", getattr(record, "asset_id", None))
            for field_name in fields:
                value = getattr(record, field_name)
                if not is_finite_decimal(value):
                    out.append(
                        _f(
                            FailureCode.NON_FINITE_VALUE,
                            f"{attr_name}.{field_name} is not a finite decimal: {value!r}",
                            rid,
                        )
                    )
    return out


def _validate_currencies(dataset: Dataset) -> list[Failure]:
    out: list[Failure] = []
    for txn in dataset.transactions:
        asset = dataset.assets.get(txn.asset_id)
        if asset is not None and txn.currency != asset.currency:
            out.append(
                _f(
                    FailureCode.CURRENCY_MISMATCH,
                    f"txn {txn.txn_id} currency {txn.currency} != asset {txn.asset_id} "
                    f"currency {asset.currency}",
                    txn.txn_id,
                )
            )
    for tick in dataset.fx:
        if tick.currency == dataset.base_currency and tick.fx_to_base != Decimal(1):
            out.append(
                _f(
                    FailureCode.CURRENCY_MISMATCH,
                    f"FX for base currency must be 1, got {tick.fx_to_base}",
                    tick.currency,
                )
            )
    return out


def _validate_duplicates(dataset: Dataset) -> list[Failure]:
    out: list[Failure] = []
    seen_txns: set[str] = set()
    for txn in dataset.transactions:
        if txn.txn_id in seen_txns:
            out.append(
                _f(
                    FailureCode.DUPLICATE_TRANSACTION,
                    f"duplicate transaction id: {txn.txn_id}",
                    txn.txn_id,
                )
            )
        seen_txns.add(txn.txn_id)
    out.extend(_check_duplicate_ticks(dataset.prices, "price", "asset_id"))
    out.extend(_check_duplicate_ticks(dataset.accruals, "accrual", "asset_id"))
    out.extend(_check_duplicate_ticks(dataset.fx, "fx", "currency"))
    return out


def _check_duplicate_ticks(records, label: str, id_attr: str) -> list[Failure]:
    out: list[Failure] = []
    seen: set[tuple[str, str, int]] = set()
    for record in records:
        key = (getattr(record, id_attr), record.when.iso_ts, record.when.seq)
        if key in seen:
            out.append(
                _f(
                    FailureCode.DUPLICATE_TRANSACTION,
                    f"duplicate {label} tick at same instant for {id_attr}={key[0]}",
                    getattr(record, id_attr),
                )
            )
        seen.add(key)
    return out


def _validate_time_order(dataset: Dataset) -> list[Failure]:
    out: list[Failure] = []
    bounds_ok = _strictly_before(dataset.period_start, dataset.period_end)
    if not bounds_ok:
        out.append(
            _f(
                FailureCode.TIME_INVERSION,
                "period_start must be strictly before period_end",
                None,
            )
        )

    def _within(record, when):
        rid = getattr(record, "txn_id", getattr(record, "asset_id", getattr(record, "currency", None)))
        if _strictly_before(when, dataset.period_start) or _strictly_before(
            dataset.period_end, when
        ):
            out.append(
                _f(
                    FailureCode.TIME_INVERSION,
                    f"event time {when.iso_ts}#{when.seq} outside period",
                    rid,
                )
            )

    for txn in dataset.transactions:
        _within(txn, txn.when)
    for tick in dataset.prices:
        _within(tick, tick.when)
    for tick in dataset.accruals:
        _within(tick, tick.when)
    for tick in dataset.fx:
        _within(tick, tick.when)

    out.extend(_check_monotone(dataset.prices, "price", "asset_id"))
    out.extend(_check_monotone(dataset.accruals, "accrual", "asset_id"))
    out.extend(_check_monotone(dataset.fx, "fx", "currency"))
    return out


def _check_monotone(records, label: str, id_attr: str) -> list[Failure]:
    out: list[Failure] = []
    grouped: dict[str, list] = {}
    for record in records:
        grouped.setdefault(getattr(record, id_attr), []).append(record)
    for key, items in grouped.items():
        ordered = sorted(items, key=lambda r: (r.when.iso_ts, r.when.seq))
        for prev, curr in zip(ordered, ordered[1:]):
            if not _strictly_before(prev.when, curr.when):
                out.append(
                    _f(
                        FailureCode.TIME_INVERSION,
                        f"{label} ticks for {id_attr}={key} not strictly ordered",
                        key,
                    )
                )
    return out


def _strictly_before(left, right) -> bool:
    return (left.iso_ts, left.seq) < (right.iso_ts, right.seq)


def _validate_required_factors(dataset: Dataset) -> list[Failure]:
    out: list[Failure] = []
    asset_ids = set(dataset.start_positions) | set(dataset.end_positions) | {
        txn.asset_id for txn in dataset.transactions
    }
    for asset_id in sorted(asset_ids):
        asset = dataset.assets.get(asset_id)
        if asset is None:
            out.append(_f(FailureCode.MISSING_FACTOR, f"asset master missing: {asset_id}", asset_id))
            continue
        for table, attr, id_attr in (
            (dataset.prices, "price", "asset_id"),
            (dataset.accruals, "accrual", "asset_id"),
        ):
            ticks = [t for t in table if getattr(t, id_attr) == asset_id]
            if not ticks:
                out.append(
                    _f(FailureCode.MISSING_FACTOR, f"{attr} factor missing for asset {asset_id}", asset_id)
                )
                continue
            if not any(t.when == dataset.period_start for t in ticks):
                out.append(
                    _f(FailureCode.MISSING_FACTOR, f"{attr} factor missing at period start for {asset_id}", asset_id)
                )
            if not any(t.when == dataset.period_end for t in ticks):
                out.append(
                    _f(FailureCode.MISSING_FACTOR, f"{attr} factor missing at period end for {asset_id}", asset_id)
                )
        fx_ticks = [t for t in dataset.fx if t.currency == asset.currency]
        for bound_name, bound in (("start", dataset.period_start), ("end", dataset.period_end)):
            if not any(t.when == bound for t in fx_ticks):
                out.append(
                    _f(
                        FailureCode.MISSING_FACTOR,
                        f"FX factor missing at period {bound_name} for currency {asset.currency}",
                        asset_id,
                    )
                )
    return out
