from __future__ import annotations

import unittest
from decimal import Decimal

from pnl_engine.attribution import attribute_asset
from pnl_engine.decimalx import MONEY_QUANT, fmul
from pnl_engine.errors import NonFiniteValueError
from pnl_engine.revision import build_report
from pnl_engine.types import (
    Asset,
    InputBundle,
    Position,
    PriceTick,
    SnapshotVersion,
)

V1 = SnapshotVersion(1, "snap-1")


class OverflowTest(unittest.TestCase):
    def test_huge_positions_still_close_exactly(self) -> None:
        big = Decimal("1000000000000")
        bundle = InputBundle(
            base_currency="CNY",
            start="2026-01-01T09:00:00",
            end="2026-01-02T09:00:00",
            assets=(Asset("HUGE", "CNY", "IND"),),
            start_positions=(Position("HUGE", big, Decimal("999999.99")),),
            trades=(),
            prices=(
                PriceTick(
                    "HUGE",
                    "2026-01-02T09:00:00",
                    Decimal("1000000.01"),
                    V1,
                ),
            ),
            accruals=(),
            fx=(),
        )
        report = build_report(bundle)
        asset = report.assets[0]
        self.assertEqual(asset.residual, Decimal("0.00"))
        expected_end = fmul(big, Decimal("1000000.01"), MONEY_QUANT)
        self.assertEqual(asset.end_value, expected_end)

    def test_infinity_caught_in_attribution_path(self) -> None:
        asset = Asset("INF", "CNY", "IND")
        bundle = InputBundle(
            base_currency="CNY",
            start="2026-01-01T09:00:00",
            end="2026-01-02T09:00:00",
            assets=(asset,),
            start_positions=(Position("INF", Decimal("1"), Decimal("1")),),
            trades=(),
            prices=(
                PriceTick(
                    "INF",
                    "2026-01-02T09:00:00",
                    Decimal("Infinity"),
                    V1,
                ),
            ),
            accruals=(),
            fx=(),
        )
        with self.assertRaises(NonFiniteValueError):
            attribute_asset(asset, bundle)


if __name__ == "__main__":
    unittest.main()
