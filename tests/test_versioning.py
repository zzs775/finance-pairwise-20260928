import unittest
from datetime import date
from decimal import Decimal

from discurve.bootstrap import bootstrap
from discurve.calendar import HolidayCalendar
from discurve.errors import CurveError
from discurve.instruments import (
    Bond,
    BondCashFlow,
    Deposit,
    QuoteSnapshot,
    Swap,
)
from discurve.interpolator import DiscountCurve
from discurve.pricing import bond_pv
from discurve.serialization import canonical_dumps
from discurve.versioning import CurveStore, CurveVersion


CAL = HolidayCalendar("embedded", frozenset())
VAL = date(2026, 1, 1)


def make_snapshot(version_id: str, *, order: str = "normal") -> QuoteSnapshot:
    deposits = [
        Deposit("D3M", VAL, date(2026, 4, 1), Decimal("0.03")),
        Deposit("D6M", VAL, date(2026, 7, 1), Decimal("0.032")),
    ]
    swaps = [
        Swap(
            "S1Y", VAL, date(2027, 1, 1), Decimal("0.036"),
            period_months=6, day_count="30/360",
        ),
        Swap(
            "S2Y", VAL, date(2028, 1, 1), Decimal("0.038"),
            period_months=6, day_count="30/360",
        ),
    ]
    if order == "reversed":
        deposits = list(reversed(deposits))
        swaps = list(reversed(swaps))
    return QuoteSnapshot(
        version_id,
        VAL,
        deposits=tuple(deposits),
        swaps=tuple(swaps),
    )


class VersionStoreTest(unittest.TestCase):
    def test_no_current_version_raises(self) -> None:
        store = CurveStore()
        with self.assertRaises(CurveError):
            store.current()

    def test_failed_rebuild_keeps_old_version_queryable(self) -> None:
        store = CurveStore()
        draft = bootstrap(make_snapshot("v1"), CAL)
        store.publish(
            CurveVersion("c1", "v1", VAL, draft.curve)
        )
        old_df = store.discount(date(2026, 4, 1))

        bad_snapshot = QuoteSnapshot(
            "v2",
            VAL,
            deposits=(
                Deposit("DUP", VAL, date(2026, 4, 1), Decimal("0.03")),
                Deposit("DUP2", VAL, date(2026, 4, 1), Decimal("0.031")),
            ),
        )
        with self.assertRaises(CurveError):
            bootstrap(bad_snapshot, CAL)

        # Nothing was published; the old complete version stays live.
        self.assertEqual(store.version_id, "c1")
        self.assertEqual(store.discount(date(2026, 4, 1)), old_df)

    def test_successful_rebuild_atomically_replaces_version(self) -> None:
        store = CurveStore()
        draft_one = bootstrap(make_snapshot("v1"), CAL)
        store.publish(CurveVersion("c1", "v1", VAL, draft_one.curve))

        draft_two = bootstrap(make_snapshot("v2"), CAL)
        store.publish(CurveVersion("c2", "v2", VAL, draft_two.curve))
        self.assertEqual(store.version_id, "c2")
        self.assertEqual(store.current().quote_version_id, "v2")


class DeterminismTest(unittest.TestCase):
    def test_input_order_does_not_change_nodes_or_diagnostics(self) -> None:
        normal = bootstrap(make_snapshot("v", order="normal"), CAL)
        reversed_order = bootstrap(
            make_snapshot("v", order="reversed"), CAL
        )
        self.assertEqual(
            list(normal.curve.nodes), list(reversed_order.curve.nodes)
        )
        normal_names = [d.name for d in normal.diagnostics]
        reversed_names = [d.name for d in reversed_order.diagnostics]
        self.assertEqual(normal_names, reversed_names)
        self.assertEqual(
            canonical_dumps(normal), canonical_dumps(reversed_order)
        )

    def test_canonical_json_is_key_sorted(self) -> None:
        curve = DiscountCurve(
            VAL,
            (
                (VAL, Decimal(1)),
                (date(2026, 2, 1), Decimal("0.99")),
            ),
        )
        text = canonical_dumps(
            {
                "nodes": curve,
                "z": Decimal("1.50"),
            }
        )
        self.assertTrue(text.startswith('{"nodes":'))
        self.assertIn('"z":"1.5"', text)


class BondRepricingTest(unittest.TestCase):
    def test_bond_pv_is_discounted_cash_flow_sum(self) -> None:
        draft = bootstrap(make_snapshot("v"), CAL)
        bond = Bond(
            "BOND",
            (
                BondCashFlow(date(2026, 7, 1), Decimal("3")),
                BondCashFlow(date(2027, 1, 1), Decimal("103")),
            ),
        )
        present_value = bond_pv(bond, draft.curve.discount)
        nodes = dict(draft.curve.nodes)
        expected = Decimal("3") * nodes[date(2026, 7, 1)]
        expected += Decimal("103") * nodes[date(2027, 1, 1)]
        self.assertLess(abs(present_value - expected), Decimal("1E-18"))


if __name__ == "__main__":
    unittest.main()
