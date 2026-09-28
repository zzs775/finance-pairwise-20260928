"""历史交易修订：增量结果必须等于全量重算（骨架）。"""

from __future__ import annotations

import copy
from dataclasses import dataclass

from pnl_engine.attribution import attribute_all
from pnl_engine.canonical import to_canonical
from pnl_engine.errors import Failure, FailureCode, ValidationError
from pnl_engine.models import Dataset, Transaction
from pnl_engine.validation import validate_dataset


@dataclass(frozen=True)
class RevisionResult:
    revised_dataset: Dataset
    full_results: dict
    incremental_results: dict
    affected_asset_ids: tuple[str, ...]
    consistent: bool
    diff: tuple[str, ...]


def revise_transaction(dataset: Dataset, revised: Transaction) -> RevisionResult:
    index = next(
        (i for i, txn in enumerate(dataset.transactions) if txn.txn_id == revised.txn_id),
        None,
    )
    if index is None:
        raise ValidationError(
            [Failure(FailureCode.MISSING_FACTOR, f"unknown transaction id: {revised.txn_id}", revised.txn_id)]
        )

    old = dataset.transactions[index]
    affected = tuple(sorted({old.asset_id, revised.asset_id}))

    revised_dataset = copy.deepcopy(dataset)
    revised_dataset.transactions[index] = revised
    _rebuild_end_positions(revised_dataset, affected)

    failures = validate_dataset(revised_dataset)
    if failures:
        raise ValidationError(failures)

    full_results = attribute_all(revised_dataset)

    baseline = attribute_all(dataset)
    incremental_results = dict(baseline)
    for asset_id in affected:
        incremental_results[asset_id] = attribute_all(revised_dataset)[asset_id]

    diff = _diff_results(full_results, incremental_results, affected)
    return RevisionResult(
        revised_dataset=revised_dataset,
        full_results=full_results,
        incremental_results=incremental_results,
        affected_asset_ids=affected,
        consistent=not diff,
        diff=tuple(diff),
    )


def _rebuild_end_positions(dataset: Dataset, affected: tuple[str, ...]) -> None:
    for asset_id in affected:
        start = dataset.start_positions.get(asset_id)
        if start is None:
            continue
        from pnl_engine.decimal_utils import QTY_Q

        qty = start.quantity
        for txn in sorted(
            dataset.transactions,
            key=lambda t: (t.when.iso_ts, t.when.seq, t.txn_id),
        ):
            if txn.asset_id == asset_id:
                qty = (qty + txn.delta_qty).quantize(QTY_Q)
        end = dataset.end_positions[asset_id]
        object.__setattr__(end, "quantity", qty)


def _diff_results(full: dict, incremental: dict, affected: tuple[str, ...]) -> list[str]:
    diffs: list[str] = []
    for asset_id in affected:
        left = full[asset_id]
        right = incremental[asset_id]
        if to_canonical(left) != to_canonical(right):
            diffs.append(asset_id)
    for asset_id, result in full.items():
        if asset_id in affected:
            continue
        if to_canonical(result) != to_canonical(incremental[asset_id]):
            diffs.append(f"unaffected:{asset_id}")
    return diffs
