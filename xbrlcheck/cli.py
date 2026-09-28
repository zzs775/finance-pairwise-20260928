"""Command line interface."""

from __future__ import annotations

import sys
import argparse

from .errors import XBRLCheckError
from .limits import Limits
from .parseinput import parse_instance, parse_linkbase, parse_revision
from .report import render_report
from .validate import validate
from .versioning import VersionedStore


def _add_limit_args(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--max-depth", type=int, default=64)
    parser.add_argument("--max-elements", type=int, default=100_000)
    parser.add_argument("--max-text-length", type=int, default=1_000_000)
    parser.add_argument("--max-document-bytes", type=int, default=20_000_000)


def _limits(args: argparse.Namespace) -> Limits:
    return Limits(
        max_depth=args.max_depth,
        max_elements=args.max_elements,
        max_text_length=args.max_text_length,
        max_document_bytes=args.max_document_bytes,
    )


def _cmd_validate(args: argparse.Namespace) -> int:
    limits = _limits(args)
    taxonomy, linkbase_diagnostics = parse_linkbase(args.linkbase, limits)
    instance, instance_diagnostics = parse_instance(args.instance, limits)
    diagnostics = [*linkbase_diagnostics, *instance_diagnostics, *validate(taxonomy, instance)]
    report = render_report(diagnostics, version=args.version)
    _emit(args, report)
    has_error = any(diagnostic.severity == "error" for diagnostic in diagnostics)
    return 2 if has_error else 0


def _cmd_revise(args: argparse.Namespace) -> int:
    limits = _limits(args)
    taxonomy, linkbase_diagnostics = parse_linkbase(args.linkbase, limits)
    instance, instance_diagnostics = parse_instance(args.instance, limits)
    revision, revision_diagnostics = parse_revision(args.revision, limits)
    store = VersionedStore(taxonomy, instance, version=args.base_version)
    result = store.apply_revision(revision)
    diagnostics = [*linkbase_diagnostics, *instance_diagnostics, *revision_diagnostics, *result.diagnostics]
    report = render_report(diagnostics, version=result.version)
    _emit(args, report)
    if not result.accepted:
        sys.stderr.write(
            f"revision rejected (version {result.version} retained): {result.reason}\n"
        )
        return 3
    if not result.byte_identical_rebuild:
        sys.stderr.write("incremental report differs from full rebuild\n")
        return 4
    has_error = any(diagnostic.severity == "error" for diagnostic in result.diagnostics)
    return 2 if has_error else 0


def _emit(args: argparse.Namespace, report: bytes) -> None:
    if args.output and args.output != "-":
        with open(args.output, "wb") as handle:
            handle.write(report)
    else:
        sys.stdout.buffer.write(report)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="xbrlcheck", description="Local XBRL calculation linkbase validator")
    subparsers = parser.add_subparsers(dest="command", required=True)

    validate_parser = subparsers.add_parser("validate", help="validate an instance against a linkbase")
    validate_parser.add_argument("linkbase")
    validate_parser.add_argument("instance")
    validate_parser.add_argument("--version", default="1")
    validate_parser.add_argument("-o", "--output", default="-")
    _add_limit_args(validate_parser)
    validate_parser.set_defaults(func=_cmd_validate)

    revise_parser = subparsers.add_parser("revise", help="apply an incremental fact revision")
    revise_parser.add_argument("linkbase")
    revise_parser.add_argument("instance")
    revise_parser.add_argument("revision")
    revise_parser.add_argument("--base-version", default="1")
    revise_parser.add_argument("-o", "--output", default="-")
    _add_limit_args(revise_parser)
    revise_parser.set_defaults(func=_cmd_revise)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except XBRLCheckError as exc:
        sys.stderr.write(f"{exc.code} {exc.location.render()}: {exc.message}\n")
        return 5
    except FileNotFoundError as exc:
        sys.stderr.write(f"E000 input file not found: {exc.filename}\n")
        return 6


if __name__ == "__main__":
    sys.exit(main())
