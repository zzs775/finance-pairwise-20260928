"""Command-line entry point: run a JSON fixture and print a canonical report."""

import argparse
import json
import sys
from decimal import Decimal
from pathlib import Path

from .engine import run
from .errors import WaterfallError
from .models import (
    Fee,
    PoolPeriod,
    PrincipalMode,
    PriorityProfile,
    ReserveSpec,
    Tranche,
    Trigger,
    WaterfallConfig,
)
from .money import to_money, to_rate
from .oracle import assert_cash_closure, assert_loss_closure
from .report import to_canonical_json


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="abs-waterfall",
        description="Run an ABS cash-flow waterfall from a JSON fixture.",
    )
    parser.add_argument("fixture", type=Path, help="path to the JSON fixture")
    parser.add_argument(
        "--check-oracle",
        action="store_true",
        help="assert per-cent cash and loss closure via the oracle",
    )
    parser.add_argument(
        "--indent",
        type=int,
        default=None,
        help="pretty-print JSON with this indent",
    )
    return parser


def _config_from_dict(payload: dict) -> WaterfallConfig:
    tranches = tuple(
        Tranche(
            id=item["id"],
            seniority=int(item["seniority"]),
            initial_balance=to_money(item["initial_balance"]),
            coupon_rate=to_rate(item["coupon_rate"]),
        )
        for item in payload["tranches"]
    )
    fees = tuple(
        Fee(
            id=item["id"],
            rate=to_rate(item.get("rate", 0)),
            fixed_amount=to_money(item.get("fixed_amount", 0)),
        )
        for item in payload.get("fees", [])
    )
    reserve_payload = payload.get("reserve", {})
    reserve = ReserveSpec(
        target_rate=to_rate(reserve_payload.get("target_rate", 0)),
        min_balance=to_money(reserve_payload.get("min_balance", 0)),
        releasable=bool(reserve_payload.get("releasable", True)),
    )
    profiles = tuple(
        PriorityProfile(
            id=item["id"],
            principal_mode=PrincipalMode(item["principal_mode"]),
            interest_order=tuple(item["interest_order"]),
            principal_order=tuple(item["principal_order"]),
        )
        for item in payload["profiles"]
    )
    triggers = tuple(
        Trigger(
            id=item["id"],
            indicator=item["indicator"],
            operator=item["operator"],
            threshold=Decimal(str(item["threshold"])),
            profile_id=item["profile_id"],
        )
        for item in payload.get("triggers", [])
    )
    return WaterfallConfig(
        tranches=tranches,
        fees=fees,
        reserve=reserve,
        triggers=triggers,
        profiles=profiles,
        default_profile_id=payload["default_profile_id"],
        allow_interest_principal_switch=bool(
            payload.get("allow_interest_principal_switch", True)
        ),
    )


def _periods_from_list(items: list[dict]) -> list[PoolPeriod]:
    return [
        PoolPeriod(
            index=int(item["index"]),
            beginning_pool_balance=to_money(item["beginning_pool_balance"]),
            interest_collections=to_money(item["interest_collections"]),
            principal_collections=to_money(item["principal_collections"]),
            realized_losses=to_money(item["realized_losses"]),
            indicators={
                name: Decimal(str(value))
                for name, value in item.get("indicators", {}).items()
            },
        )
        for item in items
    ]


def load_fixture(path: Path) -> tuple[WaterfallConfig, list[PoolPeriod]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return _config_from_dict(payload), _periods_from_list(payload["periods"])


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        config, periods = load_fixture(args.fixture)
        result = run(config, periods)
        if args.check_oracle:
            for period_result, period in zip(result.periods, periods):
                assert_cash_closure(config, period, period_result)
                assert_loss_closure(config, period, period_result)
    except (OSError, KeyError, ValueError, WaterfallError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    if args.indent is not None:
        print(json.dumps(json.loads(to_canonical_json(result)), indent=args.indent))
    else:
        print(to_canonical_json(result))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
