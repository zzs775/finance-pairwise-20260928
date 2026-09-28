"""命令行入口。"""

from __future__ import annotations

import argparse
import sys

from .canonical import to_canonical_json
from .errors import PnlError
from .loader import load_bundle
from .revision import build_report, incremental_report, revised_bundle


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="pnl-engine",
        description="组合 PnL 解释引擎（仅标准库）",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    run = sub.add_parser("run", help="对夹具做全量归因并输出规范 JSON")
    run.add_argument("bundle", help="合成夹具 JSON 路径")
    run.add_argument(
        "--allow-residual",
        action="store_true",
        help="允许非零 residual（默认题内控制夹具必须为零）",
    )

    revise = sub.add_parser(
        "revise", help="用修订交易包重算并与全量结果核对"
    )
    revise.add_argument("bundle", help="原始夹具 JSON 路径")
    revise.add_argument("revisions", help="修订交易 JSON 路径（trades 数组）")

    return parser


def _cmd_run(path: str, *, allow_residual: bool) -> int:
    bundle = load_bundle(path)
    report = build_report(bundle, strict=not allow_residual)
    print(to_canonical_json(report))
    return 0


def _cmd_revise(bundle_path: str, revisions_path: str) -> int:
    from .loader import load_trades

    bundle = load_bundle(bundle_path)
    revised_trades = load_trades(revisions_path)
    old_report = build_report(bundle)
    new_bundle = revised_bundle(bundle, revised_trades)
    full_report = build_report(new_bundle)
    incremental = incremental_report(old_report, bundle, new_bundle)
    if to_canonical_json(incremental) != to_canonical_json(full_report):
        print("incremental report differs from full recompute", file=sys.stderr)
        return 2
    print(to_canonical_json(full_report))
    return 0


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.command == "run":
            return _cmd_run(args.bundle, allow_residual=args.allow_residual)
        if args.command == "revise":
            return _cmd_revise(args.bundle, args.revisions)
    except (OSError, KeyError, ValueError, PnlError) as exc:
        category = getattr(exc, "category", exc.__class__.__name__)
        print(f"{category}: {exc}", file=sys.stderr)
        return 1
    return 0
