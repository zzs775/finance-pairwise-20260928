from __future__ import annotations

import unittest

from pnl_engine.canonical import to_canonical_json
from pnl_engine.revision import build_report
from pnl_engine.types import InputBundle

from tests.test_aggregate import bundle as multi_bundle


class CanonicalJsonTest(unittest.TestCase):
    def _local_bundle(self):
        base = multi_bundle()
        local_assets = tuple(
            asset for asset in base.assets if asset.currency == base.base_currency
        )
        ids = {asset.asset_id for asset in local_assets}
        return InputBundle(
            base_currency=base.base_currency,
            start=base.start,
            end=base.end,
            assets=local_assets,
            start_positions=tuple(
                p for p in base.start_positions if p.asset_id in ids
            ),
            trades=base.trades,
            prices=tuple(p for p in base.prices if p.asset_id in ids),
            accruals=base.accruals,
            fx=(),
        )

    def test_deterministic_under_input_permutation(self) -> None:
        base = self._local_bundle()
        report_a = build_report(base)

        shuffled = InputBundle(
            base_currency=base.base_currency,
            start=base.start,
            end=base.end,
            assets=tuple(reversed(base.assets)),
            start_positions=tuple(reversed(base.start_positions)),
            trades=base.trades,
            prices=tuple(reversed(base.prices)),
            accruals=base.accruals,
            fx=tuple(reversed(base.fx)),
        )
        report_b = build_report(shuffled)
        self.assertEqual(to_canonical_json(report_a), to_canonical_json(report_b))

    def test_key_order_is_canonical(self) -> None:
        report = build_report(self._local_bundle())
        text = to_canonical_json(report)
        self.assertIn('"assets"', text)
        self.assertLess(
            text.index('"assets"'),
            text.index('"base_currency"'),
        )


if __name__ == "__main__":
    unittest.main()
