"""资产/行业/组合层级汇总（骨架）。"""

from __future__ import annotations

from decimal import Decimal

from pnl_engine.decimal_utils import AMOUNT_Q
from pnl_engine.attribution import (
    FX,
    INTEREST,
    PRICE,
    TRADE_FLOW,
    TRADE_PNL,
)
from pnl_engine.models import AssetAttribution, Dataset, NodeSummary

_ZERO = Decimal(0)


def aggregate(
    dataset: Dataset, asset_results: dict[str, AssetAttribution]
) -> dict[str, NodeSummary]:
    nodes: dict[str, NodeSummary] = {}
    industries: dict[str, list[str]] = {}

    for asset_id in sorted(asset_results):
        result = asset_results[asset_id]
        node_id = f"asset:{asset_id}"
        nodes[node_id] = _summarize_one(node_id, "asset", result)
        industries.setdefault(result.industry, []).append(node_id)

    for industry in sorted(industries):
        node_id = f"industry:{industry}"
        nodes[node_id] = _sum_children(
            node_id, "industry", [nodes[child] for child in industries[industry]]
        )

    industry_nodes = [nodes[f"industry:{name}"] for name in sorted(industries)]
    nodes["portfolio"] = _sum_children("portfolio", "portfolio", industry_nodes)
    return nodes


def _summarize_one(node_id: str, level: str, result: AssetAttribution) -> NodeSummary:
    summary = NodeSummary(node_id=node_id, level=level)
    summary.start_value_base = result.start_value_base
    summary.end_value_base = result.end_value_base
    for term in result.terms:
        if term.category in (TRADE_FLOW, TRADE_PNL):
            summary.trade_base += term.amount
        elif term.category == PRICE:
            summary.price_base += term.amount
        elif term.category == INTEREST:
            summary.interest_base += term.amount
        elif term.category == FX:
            summary.fx_base += term.amount
        elif term.category == "residual":
            summary.residual_base += term.amount
        summary.rounding_base += term.rounding
    summary.residual_base += result.snapshot_gap_base
    for name in (
        "start_value_base", "end_value_base", "trade_base", "price_base",
        "interest_base", "fx_base", "residual_base",
    ):
        setattr(summary, name, getattr(summary, name).quantize(AMOUNT_Q))
    summary.rounding_base = summary.rounding_base.quantize(Decimal("1e-28"))
    return summary


def _sum_children(node_id: str, level: str, children: list[NodeSummary]) -> NodeSummary:
    summary = NodeSummary(node_id=node_id, level=level)
    for child in children:
        summary.start_value_base += child.start_value_base
        summary.end_value_base += child.end_value_base
        summary.trade_base += child.trade_base
        summary.price_base += child.price_base
        summary.interest_base += child.interest_base
        summary.fx_base += child.fx_base
        summary.rounding_base += child.rounding_base
        summary.residual_base += child.residual_base
    for name in (
        "start_value_base", "end_value_base", "trade_base", "price_base",
        "interest_base", "fx_base", "residual_base",
    ):
        setattr(summary, name, getattr(summary, name).quantize(AMOUNT_Q))
    summary.rounding_base = summary.rounding_base.quantize(Decimal("1e-28"))
    return summary
