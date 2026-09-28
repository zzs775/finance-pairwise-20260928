"""资产 -> 行业 -> 组合 的严格子层相加汇总。"""

from __future__ import annotations

from decimal import Decimal

from .decimalx import MONEY_QUANT, fsum
from .types import (
    AssetResult,
    Component,
    ComponentKind,
    InputBundle,
    NodeResult,
    Report,
)


def _merge_components(
    components: list[Component],
) -> tuple[Component, ...]:
    """按分类合并分量；父层数值只能由子层相加产生。"""
    merged: dict[ComponentKind, list[Component]] = {}
    for component in components:
        merged.setdefault(component.kind, []).append(component)
    result: list[Component] = []
    for kind in sorted(merged, key=lambda item: item.value):
        group = merged[kind]
        base = fsum((component.base for component in group), MONEY_QUANT)
        factor_delta = fsum(
            (component.factor_delta for component in group), MONEY_QUANT
        )
        value = fsum((component.value for component in group), MONEY_QUANT)
        rounding = fsum(
            (component.rounding for component in group), MONEY_QUANT
        )
        result.append(
            Component(
                kind=kind,
                base=base,
                factor_delta=factor_delta,
                rounding=rounding,
                value=value,
            )
        )
    return tuple(result)


def _node(
    node_id: str,
    level: str,
    children_results: list,
    child_ids: list[str],
) -> NodeResult:
    components: list[Component] = []
    for child in children_results:
        components.extend(child.components)
    return NodeResult(
        node_id=node_id,
        level=level,
        start_value=fsum(
            (child.start_value for child in children_results), MONEY_QUANT
        ),
        end_value=fsum(
            (child.end_value for child in children_results), MONEY_QUANT
        ),
        components=_merge_components(components),
        residual=fsum(
            (child.residual for child in children_results), MONEY_QUANT
        ),
        children=tuple(sorted(child_ids)),
    )


def roll_up(bundle: InputBundle, assets: list[AssetResult]) -> Report:
    """产出层级报告；结果顺序与输入顺序、字典遍历无关。"""
    ordered_assets = sorted(assets, key=lambda item: item.asset_id)
    assets_by_id = {asset.asset_id: asset for asset in bundle.assets}

    industry_members: dict[str, list[AssetResult]] = {}
    for result in ordered_assets:
        industry = assets_by_id[result.asset_id].industry
        industry_members.setdefault(industry, []).append(result)

    nodes: list[NodeResult] = []
    industry_nodes: dict[str, NodeResult] = {}
    for industry in sorted(industry_members):
        members = industry_members[industry]
        node = _node(
            node_id=industry,
            level="industry",
            children_results=members,
            child_ids=[member.asset_id for member in members],
        )
        industry_nodes[industry] = node
        nodes.append(node)

    portfolio = _node(
        node_id="PORTFOLIO",
        level="portfolio",
        children_results=[
            industry_nodes[industry] for industry in sorted(industry_nodes)
        ],
        child_ids=sorted(industry_nodes),
    )
    nodes.append(portfolio)

    return Report(
        base_currency=bundle.base_currency,
        start=bundle.start,
        end=bundle.end,
        assets=tuple(ordered_assets),
        nodes=tuple(nodes),
    )


def sum_components(components: list[Component]) -> Decimal:
    """分量值之和（便于外部做层级核对）。"""
    return fsum((component.value for component in components), MONEY_QUANT)
