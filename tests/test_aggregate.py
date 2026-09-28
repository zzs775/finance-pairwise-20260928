from __future__ import annotations

import random
import unittest
from decimal import Decimal

from pnl_engine.aggregate import roll_up, sum_components
from pnl_engine.attribution import attribute_asset
from pnl_engine.types import (
    Asset,
    FxTick,
    InputBundle,
    Position,
    PriceTick,
    SnapshotVersion,
)
from pnl_engine.validation import validate_bundle

V1 = SnapshotVersion(1, "snap-1")


def bundle() -> InputBundle:
    return InputBundle(
        base_currency="CNY",
        start="2026-01-01T09:00:00",
        end="2026-01-02T09:00:00",
        assets=(
            Asset("A1", "CNY", "TECH"),
            Asset("A2", "CNY", "TECH"),
            Asset("B1", "USD", "FI"),
        ),
        start_positions=(
            Position("A1", Decimal("1"), Decimal("100")),
            Position("A2", Decimal("2"), Decimal("50")),
            Position("B1", Decimal("3"), Decimal("10")),
        ),
        trades=(),
        prices=(
            PriceTick("A1", "2026-01-02T09:00:00", Decimal("110"), V1),
            PriceTick("A2", "2026-01-02T09:00:00", Decimal("48"), V1),
            PriceTick("B1", "2026-01-02T09:00:00", Decimal("12"), V1),
        ),
        accruals=(),
        fx=(
            FxTick("USD", "2026-01-01T09:00:00", Decimal("7"), V1),
            FxTick("USD", "2026-01-02T09:00:00", Decimal("7"), V1),
        ),
    )


def _run(bundle: InputBundle):
    return [
        attribute_asset(asset, bundle) for asset in bundle.assets
    ]


class RollUpTest(unittest.TestCase):
    def setUp(self) -> None:
        validate_bundle(bundle())
        self.report = roll_up(bundle(), _run(bundle()))

    def test_industry_equals_asset_sum(self) -> None:
        report = self.report
        tech = next(node for node in report.nodes if node.node_id == "TECH")
        a1 = next(asset for asset in report.assets if asset.asset_id == "A1")
        a2 = next(asset for asset in report.assets if asset.asset_id == "A2")
        self.assertEqual(tech.start_value, a1.start_value + a2.start_value)
        self.assertEqual(tech.end_value, a1.end_value + a2.end_value)
        self.assertEqual(tech.residual, a1.residual + a2.residual)
        self.assertEqual(
            sum_components(tech.components),
            sum_components(a1.components) + sum_components(a2.components),
        )

    def test_portfolio_equals_industry_sum(self) -> None:
        report = self.report
        portfolio = next(
            node for node in report.nodes if node.node_id == "PORTFOLIO"
        )
        industries = [
            node for node in report.nodes if node.level == "industry"
        ]
        self.assertEqual(
            portfolio.start_value,
            sum((node.start_value for node in industries), Decimal(0)),
        )
        self.assertEqual(
            portfolio.end_value,
            sum((node.end_value for node in industries), Decimal(0)),
        )
        self.assertEqual(
            portfolio.residual,
            sum((node.residual for node in industries), Decimal(0)),
        )
        self.assertEqual(
            tuple(portfolio.children),
            tuple(sorted(node.node_id for node in industries)),
        )

    def test_input_order_does_not_matter(self) -> None:
        base = bundle()
        shuffled_assets = list(base.assets)
        rng = random.Random(7)
        rng.shuffle(shuffled_assets)
        shuffled_bundle = InputBundle(
            base_currency=base.base_currency,
            start=base.start,
            end=base.end,
            assets=tuple(shuffled_assets),
            start_positions=base.start_positions,
            trades=base.trades,
            prices=base.prices,
            accruals=base.accruals,
            fx=base.fx,
        )
        shuffled_results = _run(shuffled_bundle)
        rng.shuffle(shuffled_results)
        shuffled_report = roll_up(shuffled_bundle, shuffled_results)
        self.assertEqual(
            [asset.asset_id for asset in shuffled_report.assets],
            [asset.asset_id for asset in self.report.assets],
        )
        self.assertEqual(
            [node.node_id for node in shuffled_report.nodes],
            [node.node_id for node in self.report.nodes],
        )
        self.assertEqual(
            [(c.kind, c.value) for node in shuffled_report.nodes for c in node.components],
            [(c.kind, c.value) for node in self.report.nodes for c in node.components],
        )


if __name__ == "__main__":
    unittest.main()
