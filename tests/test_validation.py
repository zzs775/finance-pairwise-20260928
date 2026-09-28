from __future__ import annotations

import unittest
from dataclasses import replace
from decimal import Decimal

from pnl_engine.errors import (
    CurrencyMismatchError,
    DuplicateTradeError,
    MissingFactorError,
    NonFiniteValueError,
    TimeInversionError,
)
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


def valid_bundle() -> InputBundle:
    asset = Asset("AAPL", "USD", "TECH")
    bond = Asset("BOND", "EUR", "FI")
    return InputBundle(
        base_currency="CNY",
        start="2026-01-01T09:00:00",
        end="2026-01-02T09:00:00",
        assets=(asset, bond),
        start_positions=(Position("AAPL", Decimal("10"), Decimal("100")),),
        trades=(
            Trade(
                "T1",
                "BOND",
                "2026-01-01T12:00:00",
                Decimal("5"),
                Decimal("90"),
                "EUR",
                V1,
            ),
        ),
        prices=(
            PriceTick("AAPL", "2026-01-02T09:00:00", Decimal("105"), V1),
            PriceTick("BOND", "2026-01-02T09:00:00", Decimal("92"), V1),
        ),
        accruals=(
            AccrualTick(
                "BOND",
                "2026-01-02T00:00:00",
                Decimal("0.50"),
                "EUR",
                V1,
            ),
        ),
        fx=(
            FxTick("EUR", "2026-01-01T09:00:00", Decimal("7.80"), V1),
            FxTick("EUR", "2026-01-02T09:00:00", Decimal("7.90"), V1),
            FxTick("USD", "2026-01-01T09:00:00", Decimal("7.10"), V1),
            FxTick("USD", "2026-01-02T09:00:00", Decimal("7.15"), V1),
        ),
    )


class ValidationHappyPathTest(unittest.TestCase):
    def test_valid_bundle_passes(self) -> None:
        validate_bundle(valid_bundle())


class MissingFactorTest(unittest.TestCase):
    def test_missing_closing_price(self) -> None:
        bundle = valid_bundle()
        broken = replace(
            bundle,
            prices=tuple(p for p in bundle.prices if p.asset_id != "BOND"),
        )
        with self.assertRaises(MissingFactorError):
            validate_bundle(broken)

    def test_missing_fx(self) -> None:
        bundle = valid_bundle()
        broken = replace(
            bundle, fx=tuple(t for t in bundle.fx if not (t.currency == "USD" and t.instant == bundle.end))
        )
        with self.assertRaises(MissingFactorError):
            validate_bundle(broken)

    def test_unknown_asset_trade(self) -> None:
        bundle = valid_bundle()
        broken = replace(
            bundle,
            trades=(replace(bundle.trades[0], asset_id="NOPE", currency="EUR"),),
        )
        with self.assertRaises(MissingFactorError):
            validate_bundle(broken)


class DuplicateTradeTest(unittest.TestCase):
    def test_duplicate_trade_id(self) -> None:
        bundle = valid_bundle()
        dup = replace(bundle.trades[0], asset_id="AAPL", currency="USD")
        with self.assertRaises(DuplicateTradeError):
            validate_bundle(replace(bundle, trades=bundle.trades + (dup,)))


class CurrencyMismatchTest(unittest.TestCase):
    def test_trade_currency_mismatch(self) -> None:
        bundle = valid_bundle()
        broken = replace(
            bundle, trades=(replace(bundle.trades[0], currency="USD"),)
        )
        with self.assertRaises(CurrencyMismatchError):
            validate_bundle(broken)

    def test_base_fx_must_be_one(self) -> None:
        bundle = valid_bundle()
        broken = replace(
            bundle,
            fx=bundle.fx
            + (FxTick("CNY", "2026-01-01T09:00:00", Decimal("1.2"), V1),),
        )
        with self.assertRaises(CurrencyMismatchError):
            validate_bundle(broken)


class TimeInversionTest(unittest.TestCase):
    def test_window_inverted(self) -> None:
        bundle = valid_bundle()
        with self.assertRaises(TimeInversionError):
            validate_bundle(replace(bundle, start=bundle.end, end=bundle.start))

    def test_price_series_inverted(self) -> None:
        bundle = valid_bundle()
        broken = replace(
            bundle,
            prices=bundle.prices
            + (
                PriceTick(
                    "AAPL", "2026-01-02T08:00:00", Decimal("99"), V1
                ),
            ),
        )
        with self.assertRaises(TimeInversionError):
            validate_bundle(broken)

    def test_trade_outside_window(self) -> None:
        bundle = valid_bundle()
        broken = replace(
            bundle,
            trades=(
                replace(bundle.trades[0], instant="2025-12-31T00:00:00"),
            ),
        )
        with self.assertRaises(TimeInversionError):
            validate_bundle(broken)

    def test_unparsable_instant(self) -> None:
        bundle = valid_bundle()
        with self.assertRaises(TimeInversionError):
            validate_bundle(replace(bundle, start="not-a-time"))


class NonFiniteTest(unittest.TestCase):
    def test_nan_price_rejected(self) -> None:
        bundle = valid_bundle()
        broken = replace(
            bundle,
            prices=tuple(
                replace(p, price=Decimal("NaN"))
                if p.asset_id == "AAPL" and p.instant == bundle.end
                else p
                for p in bundle.prices
            ),
        )
        with self.assertRaises(NonFiniteValueError):
            validate_bundle(broken)

    def test_zero_fx_rejected(self) -> None:
        bundle = valid_bundle()
        broken = replace(
            bundle,
            fx=tuple(
                replace(t, rate=Decimal("0"))
                if t.currency == "EUR" and t.instant == bundle.start
                else t
                for t in bundle.fx
            ),
        )
        with self.assertRaises(MissingFactorError):
            validate_bundle(broken)


if __name__ == "__main__":
    unittest.main()
