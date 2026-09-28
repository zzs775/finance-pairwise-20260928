from __future__ import annotations

import unittest
from dataclasses import replace
from decimal import Decimal

from pnl_engine.revision import (
    build_report,
    incremental_report,
    revised_bundle,
)
from pnl_engine.types import (
    Asset,
    InputBundle,
    Position,
    PriceTick,
    SnapshotVersion,
    Trade,
)
from pnl_engine.validation import validate_bundle

V1 = SnapshotVersion(1, "snap-1")
V2 = SnapshotVersion(2, "snap-2")


def bundle() -> InputBundle:
    return InputBundle(
        base_currency="CNY",
        start="2026-01-01T09:00:00",
        end="2026-01-02T09:00:00",
        assets=(
            Asset("A1", "CNY", "TECH"),
            Asset("A2", "CNY", "TECH"),
        ),
        start_positions=(
            Position("A1", Decimal("10"), Decimal("100")),
            Position("A2", Decimal("5"), Decimal("20")),
        ),
        trades=(
            Trade(
                "T1",
                "A1",
                "2026-01-01T12:00:00",
                Decimal("2"),
                Decimal("101"),
                "CNY",
                V1,
            ),
        ),
        prices=(
            PriceTick("A1", "2026-01-01T12:00:00", Decimal("101"), V1),
            PriceTick("A1", "2026-01-02T09:00:00", Decimal("105"), V2),
            PriceTick("A2", "2026-01-02T09:00:00", Decimal("21"), V2),
        ),
        accruals=(),
        fx=(),
    )


class RevisionEquivalenceTest(unittest.TestCase):
    def test_revised_price_matches_full_recompute(self) -> None:
        old_bundle = bundle()
        old_report = build_report(old_bundle)
        new_bundle = revised_bundle(
            old_bundle,
            (
                replace(
                    old_bundle.trades[0],
                    price=Decimal("99"),
                    version=V2,
                ),
            ),
        )
        self.assertNotEqual(old_bundle.trades[0].price, Decimal("99"))
        validate_bundle(new_bundle)
        full = build_report(new_bundle)
        incremental = incremental_report(old_report, old_bundle, new_bundle)
        self.assertEqual(incremental, full)

    def test_unaffected_asset_preserved(self) -> None:
        old_bundle = bundle()
        old_report = build_report(old_bundle)
        new_bundle = revised_bundle(
            old_bundle,
            (
                replace(
                    old_bundle.trades[0],
                    quantity=Decimal("3"),
                    version=V2,
                ),
            ),
        )
        incremental = incremental_report(old_report, old_bundle, new_bundle)
        a2_old = next(a for a in old_report.assets if a.asset_id == "A2")
        a2_new = next(a for a in incremental.assets if a.asset_id == "A2")
        self.assertEqual(a2_old, a2_new)

    def test_new_trade_then_full_equivalence(self) -> None:
        old_bundle = bundle()
        old_report = build_report(old_bundle)
        new_trade = Trade(
            "T9",
            "A2",
            "2026-01-01T15:00:00",
            Decimal("1"),
            Decimal("20"),
            "CNY",
            V2,
        )
        new_bundle = revised_bundle(old_bundle, (new_trade,))
        full = build_report(new_bundle)
        incremental = incremental_report(old_report, old_bundle, new_bundle)
        self.assertEqual(incremental, full)

    def test_controlled_fixtures_have_zero_residual(self) -> None:
        report = build_report(bundle())
        self.assertEqual(report.residual_diffs, ())
        for asset in report.assets:
            self.assertEqual(asset.residual, Decimal("0.00"))


if __name__ == "__main__":
    unittest.main()
