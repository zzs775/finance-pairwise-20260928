"""Log-linear discount-factor interpolation between solved nodes."""

from dataclasses import dataclass
from datetime import date
from decimal import Decimal, localcontext

from .errors import (
    ExtrapolationError,
    IllegalDateError,
    NonPositiveDiscountError,
)

from .errors import (
    ExtrapolationError,
    IllegalDateError,
    NonPositiveDiscountError,
)


@dataclass(frozen=True)
class DiscountCurve:
    """Immutable node set; queries outside [first, last] are rejected."""

    valuation_date: date
    nodes: tuple[tuple[date, Decimal], ...]

    def __post_init__(self) -> None:
        if not isinstance(self.valuation_date, date):
            raise IllegalDateError("valuation date must be a date")
        if not self.nodes:
            raise IllegalDateError("curve needs at least one node")
        previous: date | None = None
        for node_date, df in self.nodes:
            if not isinstance(node_date, date):
                raise IllegalDateError("node date must be a date")
            if not isinstance(df, Decimal) or not df.is_finite():
                raise NonPositiveDiscountError(
                    f"discount factor at {node_date} must be a finite Decimal"
                )
            if df <= 0:
                raise NonPositiveDiscountError(
                    f"discount factor at {node_date} must be positive, got {df}"
                )
            if previous is not None and node_date <= previous:
                raise IllegalDateError(
                    f"node dates must be strictly increasing: "
                    f"{previous} then {node_date}"
                )
            previous = node_date
        if self.nodes[0][0] != self.valuation_date:
            raise IllegalDateError(
                "first curve node must equal the valuation date"
            )

    def discount(self, day: date) -> Decimal:
        if not isinstance(day, date):
            raise IllegalDateError("discount query must use a date")
        first_date = self.nodes[0][0]
        last_date, _ = self.nodes[-1]
        if day < first_date or day > last_date:
            raise ExtrapolationError(
                f"query date {day} outside curve range "
                f"[{first_date}, {last_date}]"
            )
        for i, (node_date, df) in enumerate(self.nodes):
            if node_date == day:
                return df
        # Binary search for the containing interval.
        lo, hi = 0, len(self.nodes) - 1
        while hi - lo > 1:
            mid = (lo + hi) // 2
            if self.nodes[mid][0] <= day:
                lo = mid
            else:
                hi = mid
        left_date, left_df = self.nodes[lo]
        right_date, right_df = self.nodes[hi]
        return _loglinear(day, left_date, left_df, right_date, right_df)


def _loglinear(
    query: date,
    left_date: date,
    left_df: Decimal,
    right_date: date,
    right_df: Decimal,
) -> Decimal:
    if left_date == query:
        return left_df
    with localcontext() as context:
        context.prec = max(context.prec, 40)
        total = Decimal((right_date - left_date).days)
        elapsed = Decimal((query - left_date).days)
        weight = elapsed / total
        log_df = left_df.ln() + weight * (right_df.ln() - left_df.ln())
        result = log_df.exp()
        if not result.is_finite() or result <= 0:
            raise NonPositiveDiscountError(
                f"interpolated discount at {query} is not positive"
            )
        return +result
