from __future__ import annotations

import unittest
from decimal import Decimal

from pnl_engine.attribution import attribute_asset
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


def _closure(result) -> bool:
    total = result.start_value
    for component in result.components:
        total += component.value
    total += result.residual
    return total == result.end_value


def _by_kind(result, kind: str):
    return [c for c in result.components if c.kind.value == kind]


class LocalBuySellTest(unittest.TestCase):
    def setUp(self) -> None:
        self.bundle = InputBundle(
            base_currency="CNY",
            start="2026-01-01T09:00:00",
            end="2026-01-02T09:00:00",
            assets=(Asset("A", "CNY", "IND"),),
            start_positions=(
                Position("A", Decimal("10"), Decimal("100")),
            ),
            trades=(
                Trade(
                    "T1",
                    "A",
                    "2026-01-01T12:00:00",
                    Decimal("5"),
                    Decimal("102"),
                    "CNY",
                    V1,
                ),
                Trade(
                    "T2",
                    "A",
                    "2026-01-01T15:00:00",
                    Decimal("-3"),
                    Decimal("104"),
                    "CNY",
                    V1,
                ),
            ),
            prices=(
                PriceTick("A", "2026-01-01T12:00:00", Decimal("102"), V1),
                PriceTick("A", "2026-01-01T15:00:00", Decimal("104"), V1),
                PriceTick("A", "2026-01-02T09:00:00", Decimal("108"), V1),
            ),
            accruals=(),
            fx=(),
        )

    def test_closes_exactly(self) -> None:
        validate_bundle(self.bundle)
        result = attribute_asset(self.bundle.assets[0], self.bundle)
        self.assertTrue(_closure(result))
        self.assertEqual(result.residual, Decimal("0.00"))

    def test_trade_flow_and_price_split(self) -> None:
        result = attribute_asset(self.bundle.assets[0], self.bundle)
        trades = _by_kind(result, "trade")
        # T1 名义 +5*102，滑点 -5*2；T2 卖出 -3*104，滑点 -3*(-2)=+6
        trade_values = sorted(component.value for component in trades)
        self.assertEqual(
            sum(trade_values),
            Decimal("510")
            - Decimal("10")
            - Decimal("312")
            + Decimal("6"),
        )
        prices = _by_kind(result, "price")
        # 100->102 基数 15，102->104 基数 12，104->108 基数 12
        self.assertEqual(
            [component.base for component in prices],
            [
                Decimal("15.00000000"),
                Decimal("12.00000000"),
                Decimal("12.00000000"),
            ],
        )


class ReversalPositionTest(unittest.TestCase):
    def test_sell_then_buy_back_flat_then_long(self) -> None:
        bundle = InputBundle(
            base_currency="CNY",
            start="2026-01-01T09:00:00",
            end="2026-01-02T09:00:00",
            assets=(Asset("A", "CNY", "IND"),),
            start_positions=(
                Position("A", Decimal("4"), Decimal("50")),
            ),
            trades=(
                Trade(
                    "T1",
                    "A",
                    "2026-01-01T10:00:00",
                    Decimal("-10"),
                    Decimal("50"),
                    "CNY",
                    V1,
                ),
                Trade(
                    "T2",
                    "A",
                    "2026-01-01T14:00:00",
                    Decimal("12"),
                    Decimal("55"),
                    "CNY",
                    V1,
                ),
            ),
            prices=(
                PriceTick("A", "2026-01-01T10:00:00", Decimal("50"), V1),
                PriceTick("A", "2026-01-01T14:00:00", Decimal("55"), V1),
                PriceTick("A", "2026-01-02T09:00:00", Decimal("60"), V1),
            ),
            accruals=(),
            fx=(),
        )
        validate_bundle(bundle)
        result = attribute_asset(bundle.assets[0], bundle)
        self.assertTrue(_closure(result))
        self.assertEqual(result.residual, Decimal("0.00"))
        # 期末持仓：4 - 10 + 12 = 6，盯市 60
        self.assertEqual(result.end_value, Decimal("360.00"))


class InterestBoundaryTest(unittest.TestCase):
    def test_zero_position_no_interest(self) -> None:
        bundle = InputBundle(
            base_currency="CNY",
            start="2026-01-01T09:00:00",
            end="2026-01-02T09:00:00",
            assets=(Asset("A", "CNY", "IND"),),
            start_positions=(),
            trades=(
                Trade(
                    "T1",
                    "A",
                    "2026-01-01T15:00:00",
                    Decimal("2"),
                    Decimal("10"),
                    "CNY",
                    V1,
                ),
            ),
            prices=(
                PriceTick("A", "2026-01-01T15:00:00", Decimal("10"), V1),
                PriceTick("A", "2026-01-02T09:00:00", Decimal("10"), V1),
            ),
            accruals=(
                AccrualTick(
                    "A", "2026-01-01T12:00:00", Decimal("1"), "CNY", V1
                ),
            ),
            fx=(),
        )
        validate_bundle(bundle)
        result = attribute_asset(bundle.assets[0], bundle)
        self.assertEqual(_by_kind(result, "interest"), [])
        self.assertTrue(_closure(result))

    def test_negative_accrual_on_short(self) -> None:
        bundle = InputBundle(
            base_currency="CNY",
            start="2026-01-01T09:00:00",
            end="2026-01-02T09:00:00",
            assets=(Asset("A", "CNY", "IND"),),
            start_positions=(
                Position("A", Decimal("-5"), Decimal("20")),
            ),
            trades=(),
            prices=(
                PriceTick("A", "2026-01-02T09:00:00", Decimal("22"), V1),
            ),
            accruals=(
                AccrualTick(
                    "A", "2026-01-02T00:00:00", Decimal("0.4"), "CNY", V1
                ),
            ),
            fx=(),
        )
        validate_bundle(bundle)
        result = attribute_asset(bundle.assets[0], bundle)
        interest = _by_kind(result, "interest")
        self.assertEqual(len(interest), 1)
        self.assertEqual(interest[0].value, Decimal("-2.00"))
        self.assertTrue(_closure(result))


class FxDirectionTest(unittest.TestCase):
    def _bundle(self, rates: tuple[str, ...]) -> InputBundle:
        instants = (
            "2026-01-01T09:00:00",
            "2026-01-01T12:00:00",
            "2026-01-02T09:00:00",
        )
        return InputBundle(
            base_currency="CNY",
            start=instants[0],
            end=instants[2],
            assets=(Asset("U", "USD", "IND"),),
            start_positions=(
                Position("U", Decimal("1"), Decimal("100")),
            ),
            trades=(),
            prices=(
                PriceTick("U", instants[2], Decimal("100"), V1),
            ),
            accruals=(),
            fx=tuple(
                FxTick("USD", instant, Decimal(rate), V1)
                for instant, rate in zip(instants, rates)
            ),
        )

    def test_fx_up(self) -> None:
        bundle = self._bundle(("7", "7.5", "8"))
        validate_bundle(bundle)
        result = attribute_asset(bundle.assets[0], bundle)
        fx = _by_kind(result, "fx")
        self.assertEqual(sum(c.value for c in fx), Decimal("100.00"))
        self.assertEqual(result.end_value, Decimal("800.00"))
        self.assertTrue(_closure(result))
        self.assertEqual(result.residual, Decimal("0.00"))

    def test_fx_down(self) -> None:
        bundle = self._bundle(("8", "7.5", "7"))
        validate_bundle(bundle)
        result = attribute_asset(bundle.assets[0], bundle)
        fx = _by_kind(result, "fx")
        self.assertEqual(sum(c.value for c in fx), Decimal("-100.00"))
        self.assertEqual(result.end_value, Decimal("700.00"))
        self.assertTrue(_closure(result))


if __name__ == "__main__":
    unittest.main()
