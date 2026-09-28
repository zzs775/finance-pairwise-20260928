"""Immutable discount curve with log-linear interpolation."""

from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from .constants import ONE, ZERO
from .errors import (
    DuplicateNodeError,
    ExtrapolationError,
    IllegalDateError,
    NonPositiveDiscountFactorError,
)

Date = date


@dataclass(frozen=True, slots=True)
class CurveNode:
    date: Date
    discount_factor: Decimal


@dataclass(frozen=True, slots=True)
class DiscountCurve:
    value_date: Date
    nodes: tuple[CurveNode, ...]
    version_id: str

    @classmethod
    def create(cls, value_date: Date, nodes: tuple[CurveNode, ...],
               version_id: str) -> "DiscountCurve":
        if not isinstance(value_date, Date):
            raise IllegalDateError("value_date must be a date")
        if not nodes:
            raise NonPositiveDiscountFactorError(
                "curve needs at least one calibrated node")
        previous_date = value_date
        for index, node in enumerate(nodes):
            if not isinstance(node.date, Date):
                raise IllegalDateError(f"node {index} date must be a date")
            df = node.discount_factor
            if not isinstance(df, Decimal) or not df.is_finite():
                raise NonPositiveDiscountFactorError(
                    f"node {node.date.isoformat()} discount factor is not finite")
            if df <= ZERO:
                raise NonPositiveDiscountFactorError(
                    f"node {node.date.isoformat()} has non-positive discount "
                    f"factor {df}")
            if node.date <= previous_date:
                if index > 0 and node.date == nodes[index - 1].date:
                    raise DuplicateNodeError(
                        f"duplicate curve node at {node.date.isoformat()}")
                raise IllegalDateError(
                    f"node {node.date.isoformat()} is not strictly after the "
                    "value date or previous node")
            previous_date = node.date
        if not version_id:
            raise IllegalDateError("curve must carry a quote version id")
        return cls(value_date=value_date, nodes=tuple(nodes),
                   version_id=version_id)

    def covered(self, day: Date) -> bool:
        if not isinstance(day, Date):
            raise IllegalDateError(f"expected a date, got {day!r}")
        return self.value_date <= day <= self.nodes[-1].date

    def discount_factor(self, day: Date) -> Decimal:
        if not isinstance(day, Date):
            raise IllegalDateError(f"expected a date, got {day!r}")
        if day == self.value_date:
            return ONE
        nodes = self.nodes
        if day < self.value_date or day > nodes[-1].date:
            raise ExtrapolationError(
                f"discount query at {day.isoformat()} lies outside calibrated "
                f"range [{self.value_date.isoformat()}, "
                f"{nodes[-1].date.isoformat()}]")
        left = 0
        right = len(nodes) - 1
        while left <= right:
            mid = (left + right) // 2
            if nodes[mid].date == day:
                return nodes[mid].discount_factor
            if nodes[mid].date < day:
                left = mid + 1
            else:
                right = mid - 1
        # left is the first node strictly after day.
        later = nodes[left]
        earlier = nodes[left - 1]
        width = Decimal((later.date - earlier.date).days)
        position = Decimal((day - earlier.date).days) / width
        log_df = ((ONE - position) * earlier.discount_factor.ln()
                  + position * later.discount_factor.ln())
        return log_df.exp()
