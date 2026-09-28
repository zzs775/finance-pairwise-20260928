import io
import io
import json
import tempfile
from contextlib import redirect_stderr, redirect_stdout
from datetime import date
from decimal import Decimal
from pathlib import Path
from unittest import TestCase

from dcurve.bootstrap import BondValuation, bootstrap
from dcurve.jsonio import (
    bonds_from_payload,
    book_from_payload,
    dumps,
    loads,
    result_to_payload,
)
from dcurve.pricing import bond_pv
from tests.fixtures import standard_bond, standard_book


def _shuffle_book(book):
    return type(book)(
        value_date=book.value_date,
        deposits=tuple(reversed(book.deposits)),
        fras=tuple(reversed(book.fras)),
        swaps=tuple(reversed(book.swaps)),
    )


class OrderInvarianceTest(TestCase):
    def test_nodes_diagnostics_and_json_identical_when_input_reordered(self):
        a = bootstrap(standard_book())
        b = bootstrap(_shuffle_book(standard_book()))

        self.assertEqual(
            [(n.date, n.discount_factor) for n in a.curve.nodes],
            [(n.date, n.discount_factor) for n in b.curve.nodes])
        self.assertEqual(a.curve.version_id, b.curve.version_id)
        self.assertEqual(
            [(d.id, d.maturity) for d in a.diagnostics],
            [(d.id, d.maturity) for d in b.diagnostics])

        bond = standard_bond()
        va = (BondValuation(bond.id, bond_pv(a.curve, bond)),)
        vb = (BondValuation(bond.id, bond_pv(b.curve, bond)),)
        self.assertEqual(
            dumps(result_to_payload(a, va)),
            dumps(result_to_payload(b, vb)))

    def test_version_id_is_content_based(self) -> None:
        first = standard_book().with_version()
        second = _shuffle_book(standard_book()).with_version()
        self.assertEqual(first.version_id, second.version_id)


class CanonicalJsonTest(TestCase):
    def test_roundtrip_preserves_decimal_scale(self) -> None:
        result = bootstrap(standard_book())
        payload = result_to_payload(result)
        text = dumps(payload)
        # Compact separators, no float engineering, stable key order.
        self.assertNotIn(" ", text)
        self.assertNotIn("e-", text)
        reparsed = loads(text)
        self.assertEqual(reparsed["nodes"][0]["discount_factor"],
                         payload["nodes"][0]["discount_factor"])
        book = book_from_payload({
            "value_date": "2025-01-13",
            "deposits": [
                {"id": "D3M", "start": "2025-01-13",
                 "maturity": "2025-04-14", "rate": "0.0300",
                 "day_count": "act/360"}],
        })
        self.assertEqual(str(book.deposits[0].rate), "0.0300")

    def test_bonds_payload_parses(self) -> None:
        portfolio = bonds_from_payload({
            "bonds": [{"id": "B", "flows": [["2025-04-14", "1.02"]]}]})
        self.assertEqual(portfolio.bonds[0].flows[0][1], Decimal("1.02"))


class CliTest(TestCase):
    def test_cli_success_emits_canonical_json(self) -> None:
        from dcurve.cli import run

        root = Path(__file__).resolve().parents[1]
        quotes = root / "examples" / "quotes.json"
        bonds = root / "examples" / "bonds.json"
        buffer = io.StringIO()
        with redirect_stdout(buffer):
            code = run([str(quotes), "-b", str(bonds)])
        self.assertEqual(code, 0)
        payload = json.loads(buffer.getvalue())
        self.assertEqual(len(payload["nodes"]), 3)
        self.assertEqual(payload["bond_valuations"][0]["id"], "BOND-A")

    def test_cli_failure_returns_nonzero_and_classifies(self) -> None:
        from dcurve.cli import run

        bad = {
            "value_date": "2025-01-13",
            "deposits": [
                {"id": "BAD", "start": "2025-01-13",
                 "maturity": "2025-04-14", "rate": "-500",
                 "day_count": "act/360"}],
        }
        with tempfile.NamedTemporaryFile(
                "w", suffix=".json", delete=False, encoding="utf-8") as fh:
            fh.write(dumps(bad))
            path = fh.name
        err = io.StringIO()
        with redirect_stdout(io.StringIO()), redirect_stderr(err):
            code = run([path])
        self.assertEqual(code, 2)
        self.assertIn("NoBracketError", err.getvalue())
