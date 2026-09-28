"""Decimal allowed-interval derivation from fact values and decimals."""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal

from .errors import Diagnostic
from .model import Fact

INF = Decimal("Infinity")


def parse_decimals(decimals: str) -> int | None:
    """Return the integer decimals exponent; None means decimals absent."""
    if decimals is None or decimals == "":
        return None
    text = decimals.strip()
    if text.upper() == "INF":
        return None  # exact: zero-width interval
    try:
        value = int(text)
    except ValueError:
        raise ValueError(f"decimals must be an integer or INF, got {decimals!r}")
    return value


@dataclass(frozen=True)
class Interval:
    low: Decimal
    high: Decimal

    def __post_init__(self) -> None:
        if self.high < self.low:
            raise ValueError("inverted interval")

    @property
    def width(self) -> Decimal:
        return self.high - self.low

    def intersects(self, other: "Interval") -> bool:
        return self.low <= other.high and other.low <= self.high

    def gap_to(self, other: "Interval") -> Decimal:
        """Smallest absolute move that makes the two closed intervals touch."""
        if self.intersects(other):
            return Decimal(0)
        if self.high < other.low:
            return other.low - self.high
        return self.low - other.high

    def scale(self, weight: int) -> "Interval":
        if weight >= 0:
            return Interval(self.low * weight, self.high * weight)
        return Interval(self.high * weight, self.low * weight)

    def add(self, other: "Interval") -> "Interval":
        return Interval(self.low + other.low, self.high + other.high)

    def render(self) -> str:
        return f"[{_fmt(self.low)}, {_fmt(self.high)}]"


def _fmt(value: Decimal) -> str:
    if value == INF:
        return "Inf"
    if value == -INF:
        return "-Inf"
    return format(value, "f")


def sum_intervals(intervals: list[tuple[int, Interval]]) -> Interval:
    total = Interval(Decimal(0), Decimal(0))
    for weight, interval in intervals:
        total = total.add(interval.scale(weight))
    return total


def fact_interval(fact: Fact) -> Interval:
    """Derive the rounding-allowed interval without rounding the reported value.

    decimals = n  means the value is rounded to 10^-n, so the true value lies in
    [v - 0.5*10^-n, v + 0.5*10^-n]. decimals="INF" (or absent) means exact.
    """
    value = Decimal(fact.value)
    exponent = parse_decimals(fact.decimals)
    if exponent is None:
        return Interval(value, value)
    half_step = Decimal(1).scaleb(-exponent) / 2
    return Interval(value - half_step, value + half_step)


def validate_numeric_fact(fact: Fact) -> list[Diagnostic]:
    """E106 checks: value must parse as Decimal and decimals must be legal."""
    diagnostics: list[Diagnostic] = []
    if fact.decimals.strip() == "":
        diagnostics.append(Diagnostic("E106", "numeric fact is missing decimals attribute", fact.location))
    try:
        parse_decimals(fact.decimals)
    except ValueError as exc:
        diagnostics.append(Diagnostic("E106", str(exc), fact.location))
    try:
        Decimal(fact.value)
    except Exception:
        diagnostics.append(
            Diagnostic("E106", f"fact value is not a valid decimal: {fact.value!r}", fact.location)
        )
    return diagnostics


def safe_fact_interval(fact: Fact) -> Interval | None:
    try:
        return fact_interval(fact)
    except Exception:
        return None
