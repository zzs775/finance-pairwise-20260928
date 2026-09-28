"""Canonical byte-stable report rendering."""

from __future__ import annotations

from .errors import Diagnostic


def render_report(diagnostics: list[Diagnostic], *, version: str = "") -> bytes:
    ordered = sorted(diagnostics, key=lambda diagnostic: diagnostic.sort_key())
    lines: list[str] = []
    lines.append("# xbrlcheck report")
    if version:
        lines.append(f"# version: {version}")
    counts = {"error": 0, "warning": 0, "info": 0}
    for diagnostic in ordered:
        counts[diagnostic.severity] += 1
    lines.append(
        f"# summary: {counts['error']} error(s), {counts['warning']} warning(s), {counts['info']} info"
    )
    lines.append("")
    for diagnostic in ordered:
        lines.append(diagnostic.render())
    lines.append("")
    # Canonical bytes: UTF-8, LF only, trailing newline.
    return ("\n".join(lines)).encode("utf-8").replace(b"\r\n", b"\n")
