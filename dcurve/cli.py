"""Command-line entry point: ``python -m dcurve``."""

import argparse
import sys

from .bootstrap import BondValuation, CurveHolder
from .errors import CurveError
from .jsonio import (
    book_from_payload,
    bonds_from_payload,
    dumps,
    loads,
    result_to_payload,
)
from .pricing import bond_pv


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="dcurve",
        description="Bootstrap a single-currency discount curve and reprice "
                    "bond cash flows (standard library only).")
    parser.add_argument(
        "quotes",
        help="path to the JSON quote book (use '-' for standard input)")
    parser.add_argument(
        "-b", "--bonds",
        help="optional JSON file with bond cash flows to reprice")
    parser.add_argument(
        "-o", "--output",
        help="write canonical JSON here (default: standard output)")
    parser.add_argument(
        "--check-only", action="store_true",
        help="validate inputs and print diagnostics, but do not publish")
    return parser


def run(argv: list[str] | None = None, holder: CurveHolder | None = None,
        stdin: str | None = None) -> int:
    parser = _build_parser()
    args = parser.parse_args(argv)

    try:
        raw = sys.stdin.read() if args.quotes == "-" \
            else _read_file(args.quotes)
        payload = loads(raw)
        book = book_from_payload(payload)
        portfolio = bonds_from_payload(loads(_read_file(args.bonds))) \
            if args.bonds else None

        if holder is None:
            holder = CurveHolder()
        if args.check_only:
            from .bootstrap import bootstrap
            result = bootstrap(book)
        else:
            result = holder.publish(book)

        valuations = tuple(
            BondValuation(bond.id, bond_pv(result.curve, bond))
            for bond in (portfolio.bonds if portfolio else ()))
        output = dumps(result_to_payload(result, valuations))
        if args.output:
            with open(args.output, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(output + "\n")
        else:
            sys.stdout.write(output + "\n")
        return 0
    except CurveError as exc:
        print(f"dcurve: error: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 2
    except (OSError, ValueError) as exc:
        print(f"dcurve: error: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 2


def _read_file(path: str) -> str:
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def main(argv: list[str] | None = None) -> int:
    return run(argv=argv)
