from __future__ import annotations

import unittest
from decimal import Decimal

from pnl_engine.attribution import attribute_asset
from pnl_engine.reference import (
    MAX_ENUMERATED_POSITIONS,
    cross_check,
    direct_revalue,
    enumerate_paths,
)
from pnl_engine.errors import ReferenceMismatchError
from pnl_engine.types import (
    AccrualTick,
    Asset,
    FxTick,
    InputBundle,
    Position,
    PriceTick,
    SnapshotVersion,
    Trade,
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
            Asset("U1", "USD", "FI"),
        ),
        start_positions=(
            Position("A1", Decimal("2"), Decimal("100")),
            Position("U1", Decimal("1"), Decimal("50")),
        ),
        trades=(
            Trade(
                "T1",
                "U1",
                "2026-01-01T12:00:00",
                Decimal("1"),
                Decimal("52"),
                "USD",
                V1,
            ),
        ),
        prices=(
            PriceTick("A1", "2026-01-02T09:00:00", Decimal("105"), V1),
            PriceTick("U1", "2026-01-01T12:00:00", Decimal("52"), V1),
            PriceTick("U1", "2026-01-02T09:00:00", Decimal("55"), V1),
        ),
        accruals=(
            AccrualTick(
                "U1", "2026-01-02T00:00:00", Decimal("1"), "USD", V1
            ),
        ),
        fx=(
            FxTick("USD", "2026-01-01T09:00:00", Decimal("7"), V1),
            FxTick("USD", "2026-01-02T09:00:00", Decimal("8"), V1),
        ),
    )


class DirectRevalueTest(unittest.TestCase):
    def test_boundary_values(self) -> None:
        ref = direct_revalue(bundle())
        self.assertEqual(ref["A1"], {"start": Decimal("200.00"), "end": Decimal("210.00")})
        # U1: 期末 2*55 + 应计 2 = 112 USD * 8 = 896 CNY
        self.assertEqual(ref["U1"]["start"], Decimal("350.00"))
        self.assertEqual(ref["U1"]["end"], Decimal("896.00"))


class CrossCheckTest(unittest.TestCase):
    def test_pipeline_matches_reference(self) -> None:
        b = bundle()
        validate_bundle(b)
        results = [attribute_asset(asset, b) for asset in b.assets]
        cross_check(b, results)


class PathEnumerationTest(unittest.TestCase):
    def test_frozen_priority_order(self) -> None:
        paths = dict(enumerate_paths(bundle()))
        u1_events = paths["U1"]
        kinds = [event[3] for event in u1_events]
        self.assertEqual(
            kinds, ["fx", "trade", "price", "interest", "price", "fx"]
        )

    def test_enumeration_limit(self) -> None:
        b = bundle()
        assets = b.assets + tuple(
            Asset(f"X{i}", "CNY", "IND") for i in range(MAX_ENUMERATED_POSITIONS)
        )
        positions = b.start_positions + tuple(
            Position(f"X{i}", Decimal("1"), Decimal("1"))
            for i in range(MAX_ENUMERATED_POSITIONS)
        )
        prices = b.prices + tuple(
            PriceTick(f"X{i}", "2026-01-02T09:00:00", Decimal("1"), V1)
            for i in range(MAX_ENUMERATED_POSITIONS)
        )
        oversized = InputBundle(
            base_currency=b.base_currency,
            start=b.start,
            end=b.end,
            assets=assets,
            start_positions=positions,
            trades=b.trades,
            prices=prices,
            accruals=b.accruals,
            fx=b.fx,
        )
        with self.assertRaises(ReferenceMismatchError):
            enumerate_paths(oversized)


if __name__ == "__main__":
    unittest.main()
