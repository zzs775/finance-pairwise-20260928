"""命令行入口：读取本地 CSV/JSON 三角形，输出因子、终极赔付与准备金。"""

import argparse
import json
import sys

from .errors import TriangleError
from .factors import FactorConfig, FactorMethod, ZeroDenominatorPolicy
from .io_utils import read_rows
from .model import Triangle
from .projection import project
from .serialization import result_to_plain, to_canonical_json


def _fixed_pairs(values: list[str] | None) -> dict[int, str]:
    fixed: dict[int, str] = {}
    for item in values or []:
        dev, raw = item.split("=", 1)
        fixed[int(dev)] = raw
    return fixed


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="chainladder",
        description="可修订赔付三角形链梯法引擎",
    )
    parser.add_argument("input", help="本地 CSV/JSON 三角形文件路径")
    parser.add_argument(
        "--method",
        choices=[m.value for m in FactorMethod],
        default=FactorMethod.VOLUME_WEIGHTED.value,
    )
    parser.add_argument(
        "--zero-policy",
        choices=[p.value for p in ZeroDenominatorPolicy],
        default=ZeroDenominatorPolicy.ERROR.value,
    )
    parser.add_argument("--tail", default=None, help="尾因子，如 1.05；缺省为 1")
    parser.add_argument(
        "--fixed",
        action="append",
        metavar="DEV=FACTOR",
        help="固定选择因子，可重复，如 --fixed 1=1.5 --fixed 2=1.2",
    )
    parser.add_argument(
        "--canonical",
        action="store_true",
        help="输出规范 JSON（键排序、紧凑分隔）",
    )
    parser.add_argument(
        "--indent", type=int, default=2, help="JSON 缩进（--canonical 时忽略）"
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        rows = read_rows(args.input)
        triangle = Triangle.from_rows(rows)
        config = FactorConfig(
            method=FactorMethod(args.method),
            zero_policy=ZeroDenominatorPolicy(args.zero_policy),
            fixed_factors=_fixed_pairs(args.fixed),
        )
        result = project(triangle, config, tail_factor=args.tail)
    except (TriangleError, ValueError, KeyError, OSError) as exc:
        print(f"错误: {exc}", file=sys.stderr)
        return 2

    if args.canonical:
        print(to_canonical_json(result))
    else:
        print(json.dumps(result_to_plain(result), ensure_ascii=False, indent=args.indent))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
