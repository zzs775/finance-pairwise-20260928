from __future__ import annotations

import unittest
from decimal import Decimal

from pnl_engine.errors import ClosureError
from pnl_engine.residual import assert_closure, check_closure, minimal_diffs
from pnl_engine.types import AssetResult, Component, ComponentKind


def _comp(kind: ComponentKind, value: str) -> Component:
    return Component(
        kind=kind,
        base=Decimal(0),
        factor_delta=Decimal(0),
        rounding=Decimal(0),
        value=Decimal(value),
    )


class ClosureTest(unittest.TestCase):
    def test_balanced_result(self) -> None:
        result = AssetResult(
            asset_id="A",
            currency="CNY",
            start_value=Decimal("100.00"),
            end_value=Decimal("110.00"),
            components=(_comp(ComponentKind.PRICE, "10.00"),),
        )
        self.assertEqual(check_closure(result), Decimal("0.00"))
        self.assertEqual(minimal_diffs([result]), ())

    def test_residual_absorbs_tail(self) -> None:
        result = AssetResult(
            asset_id="A",
            currency="CNY",
            start_value=Decimal("100.00"),
            end_value=Decimal("110.01"),
            components=(_comp(ComponentKind.PRICE, "10.00"),),
            residual=Decimal("0.01"),
        )
        self.assertEqual(check_closure(result), Decimal("0.00"))
        diffs = minimal_diffs([result])
        self.assertEqual(len(diffs), 1)
        self.assertEqual(diffs[0].actual, Decimal("0.01"))

    def test_unexplained_gap_reported(self) -> None:
        result = AssetResult(
            asset_id="A",
            currency="CNY",
            start_value=Decimal("100.00"),
            end_value=Decimal("110.00"),
            components=(_comp(ComponentKind.PRICE, "9.00"),),
        )
        self.assertEqual(check_closure(result), Decimal("1.00"))
        diffs = minimal_diffs([result])
        self.assertEqual(diffs[0].actual, Decimal("1.00"))

    def test_strict_mode_raises(self) -> None:
        result = AssetResult(
            asset_id="A",
            currency="CNY",
            start_value=Decimal("0"),
            end_value=Decimal("1"),
        )
        with self.assertRaises(ClosureError):
            assert_closure([result], strict=True)

    def test_diffs_sorted_by_asset(self) -> None:
        bad = lambda aid: AssetResult(
            asset_id=aid,
            currency="CNY",
            start_value=Decimal("0"),
            end_value=Decimal("1"),
        )
        diffs = minimal_diffs([bad("Z"), bad("A"), bad("M")])
        self.assertEqual([d.asset_id for d in diffs], ["A", "M", "Z"])


if __name__ == "__main__":
    unittest.main()
