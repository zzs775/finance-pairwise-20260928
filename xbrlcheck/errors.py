"""Stable diagnostic error codes and exception types."""

from __future__ import annotations

from dataclasses import dataclass

# Stable error / warning codes.
E_XML_LIMIT = "E001"  # resource limit exceeded while parsing XML
E_XML_UNSAFE = "E002"  # DOCTYPE / external entity rejected
E_XML_SYNTAX = "E003"  # malformed XML
E_DUP_FACT = "E101"  # duplicate fact (same concept/context/unit)
E_UNKNOWN_CONCEPT = "E102"  # fact or arc references an unknown concept
E_MISSING_CONTEXT = "E103"  # contextRef does not resolve
E_MISSING_UNIT = "E104"  # unitRef does not resolve
E_UNIT_CONFLICT = "E105"  # unit usage conflict (numeric/non-numeric)
E_BAD_DECIMAL = "E106"  # illegal decimals attribute / non-numeric fact value
E_BAD_PERIOD = "E107"  # malformed context period
E_ARC_CONFLICT = "E201"  # same from/to arcs at equal priority with different weight
E_CROSS_CONTEXT = "E202"  # summation mixes facts from different contexts
E_CROSS_UNIT = "E203"  # summation mixes facts with different units
E_CYCLE = "E204"  # cycle detected in a role calculation graph
W_INCONSISTENT = "W301"  # parent reported interval disjoint from children interval
I_LOW_PRIORITY_ARC = "I401"  # duplicate lower-priority arc ignored
I_REVISION_REJECTED = "I402"  # incremental revision rejected, current version retained


@dataclass(frozen=True)
class Location:
    """Source location of a diagnostic."""

    source: str = ""
    line: int = 0
    column: int = 0

    def render(self) -> str:  # pragma: no cover - implemented later
        if not self.source:
            return "<input>"
        if self.line:
            return f"{self.source}:{self.line}:{self.column}"
        return self.source


@dataclass(frozen=True)
class Diagnostic:
    """One stable, reproducible validation finding."""

    code: str
    message: str
    location: Location = Location()
    role: str = ""
    concept: str = ""
    context: str = ""
    unit: str = ""

    def sort_key(self) -> tuple:
        severity_rank = {"error": 0, "warning": 1, "info": 2}[self.severity]
        return (
            severity_rank,
            self.code,
            self.role,
            self.concept,
            self.context,
            self.unit,
            self.location.source,
            self.location.line,
            self.location.column,
            self.message,
        )

    @property
    def severity(self) -> str:
        if self.code.startswith("W"):
            return "warning"
        if self.code.startswith("I"):
            return "info"
        return "error"

    def render(self) -> str:
        parts = []
        if self.role:
            parts.append(f"role={self.role}")
        if self.concept:
            parts.append(f"concept={self.concept}")
        if self.context:
            parts.append(f"context={self.context}")
        if self.unit:
            parts.append(f"unit={self.unit}")
        suffix = f" [{', '.join(parts)}]" if parts else ""
        message_lines = self.message.splitlines()
        if message_lines:
            message_lines[0] = message_lines[0] + suffix
        message = "\n".join(message_lines)
        return f"{self.severity} {self.code} {self.location.render()}: {message}"


class XBRLCheckError(Exception):
    """Fatal error carrying a stable code."""

    def __init__(self, code: str, message: str, location: Location | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.location = location or Location()
