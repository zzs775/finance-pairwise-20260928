"""Tests for deterministic canonical batching."""

import unittest
from datetime import date
from decimal import Decimal

from ecl_engine import batch
from ecl_engine.models import AssumptionSet, Contract, Curve, Scenario


def make_contract(contract_id: str, periods: int) -> Contract:
    pd_values = [Decimal(i + 1) / Decimal(100) for i in range(periods)]
    one = [Decimal("1")] * periods
    return Contract(
        contract_id, 2, periods, Curve(pd_values), Curve(one),
        Curve(one), Curve(one))


ASSUMPTIONS = AssumptionSet(
    "v1", date(2026, 1, 1),
    scenarios=(Scenario("base", Decimal("0.6")),
               Scenario("down", Decimal("0.4"))))


class BatchEquivalenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.contracts = [make_contract("C", 3), make_contract("A", 2),
                          make_contract("B", 5), make_contract("D", 1)]

    def test_incremental_equals_full_byte_for_byte(self) -> None:
        full = batch.run_full(self.contracts, ASSUMPTIONS)
        for size in (1, 2, 3, 4, 10):
            incremental = batch.run_batches(
                self.contracts, ASSUMPTIONS, batch_size=size)
            self.assertEqual(incremental, full, msg=f"batch_size={size}")

    def test_input_reorder_does_not_change_bytes(self) -> None:
        ordered = batch.run_full(self.contracts, ASSUMPTIONS)
        shuffled = batch.run_full(list(reversed(self.contracts)),
                                  ASSUMPTIONS)
        self.assertEqual(shuffled, ordered)
        self.assertEqual(
            batch.run_batches(list(reversed(self.contracts)), ASSUMPTIONS),
            ordered)

    def test_serialization_is_utf8_json(self) -> None:
        raw = batch.run_full(self.contracts, ASSUMPTIONS)
        self.assertTrue(raw.endswith(b"\n"))
        text = raw.decode("utf-8")
        self.assertIn('"contracts"', text)
        self.assertIn("A", text)  # sorted first

    def test_invalid_batch_size(self) -> None:
        with self.assertRaises(ValueError):
            batch.run_batches(self.contracts, ASSUMPTIONS, batch_size=0)


if __name__ == "__main__":
    unittest.main()
