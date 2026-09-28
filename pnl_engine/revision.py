"""历史交易修订：增量结果必须等于全量重算。"""

from __future__ import annotations

from .aggregate import roll_up
from .attribution import attribute_asset
from .reference import cross_check
from .residual import assert_closure
from .types import InputBundle, Report
from .validation import validate_bundle


def build_report(bundle: InputBundle, *, strict: bool = True) -> Report:
    """全量重算流水线：校验 -> 逐资产归因 -> 参考核对 -> 层级汇总 -> 闭合。"""
    validate_bundle(bundle)
    asset_results = [attribute_asset(asset, bundle) for asset in bundle.assets]
    cross_check(bundle, asset_results)
    residual_diffs = assert_closure(asset_results, strict=strict)
    report = roll_up(bundle, asset_results)
    return Report(
        base_currency=report.base_currency,
        start=report.start,
        end=report.end,
        assets=report.assets,
        nodes=report.nodes,
        residual_diffs=residual_diffs,
    )


def revised_bundle(
    bundle: InputBundle, revised_trades: tuple
) -> InputBundle:
    """按 trade_id 替换/新增历史交易，输出规范排序的新快照包。"""
    revisions = {trade.trade_id: trade for trade in revised_trades}
    merged: dict[str, object] = {}
    for trade in bundle.trades:
        merged[trade.trade_id] = trade
    merged.update(revisions)
    ordered = tuple(
        sorted(
            merged.values(),
            key=lambda trade: (
                trade.instant,
                trade.version.sequence,
                trade.trade_id,
            ),
        )
    )
    return InputBundle(
        base_currency=bundle.base_currency,
        start=bundle.start,
        end=bundle.end,
        assets=bundle.assets,
        start_positions=bundle.start_positions,
        trades=ordered,
        prices=bundle.prices,
        accruals=bundle.accruals,
        fx=bundle.fx,
    )


def incremental_report(
    old: Report, old_bundle: InputBundle, new_bundle: InputBundle
) -> Report:
    """历史修订后的增量结果。

    任何被修订交易触及的资产都重算；其余资产沿用旧结果。父层始终由
    （新旧混合后的）子层重新相加，因此结果与全量重算逐位一致。
    """
    validate_bundle(new_bundle)
    affected = {trade.asset_id for trade in new_bundle.trades}
    old_trade_ids = {trade.trade_id for trade in old_bundle.trades}
    new_trade_ids = {trade.trade_id for trade in new_bundle.trades}
    for trade_id in old_trade_ids - new_trade_ids:
        for trade in old_bundle.trades:
            if trade.trade_id == trade_id:
                affected.add(trade.asset_id)

    results_by_id = {asset.asset_id: asset for asset in old.assets}
    for asset in new_bundle.assets:
        if asset.asset_id in affected:
            results_by_id[asset.asset_id] = attribute_asset(asset, new_bundle)

    results = [results_by_id[asset.asset_id] for asset in new_bundle.assets]
    cross_check(new_bundle, results)
    residual_diffs = assert_closure(results, strict=True)
    report = roll_up(new_bundle, results)
    return Report(
        base_currency=report.base_currency,
        start=report.start,
        end=report.end,
        assets=report.assets,
        nodes=report.nodes,
        residual_diffs=residual_diffs,
    )
