"""Segment-wise curve bootstrapping with bracketed bisection."""

from dataclasses import dataclass
from datetime import date
from decimal import Decimal, localcontext
from typing import Callable

from .errors import (
    DuplicateNodeError,
    ExtrapolationError,
    IterationLimitError,
    MissingTenorError,
    NonFiniteValueError,
    PricingToleranceError,
    RootBracketingError,
)
from .instruments import (
    FRA,
    Deposit,
    QuoteSnapshot,
    Swap,
    ordered_instruments,
    validate_snapshot,
)
from .interpolator import DiscountCurve
from .pricing import (
    deposit_model_pv,
    deposit_residual,
    fra_model_pv,
    fra_residual,
    swap_model_pv,
    swap_residual,
    swap_schedule,
)

PRICING_TOLERANCE = Decimal("1E-10")
MAX_BISECTION_ITERATIONS = 200
BISECTION_CONVERGENCE = Decimal("1E-20")
BRACKET_EXPANSIONS = 64
DOWN_FACTOR = Decimal("0.5")
UP_FACTOR = Decimal("1.5")


@dataclass(frozen=True)
class InstrumentDiagnostic:
    name: str
    kind: str
    node_date: date
    model_pv: Decimal
    market_target: Decimal
    residual: Decimal
    iterations: int


@dataclass(frozen=True)
class BootstrapDraft:
    curve: DiscountCurve
    diagnostics: tuple[InstrumentDiagnostic, ...]


def bootstrap(snapshot: QuoteSnapshot, calendar) -> BootstrapDraft:
    """Build the full curve or raise; never returns a partial result."""
    validate_snapshot(snapshot)

    tagged = ordered_instruments(snapshot)
    tagged_sorted = sorted(
        tagged,
        key=lambda item: instrument_node_date(item[0], item[1], calendar),
    )

    claimed: set[date] = {snapshot.valuation_date}
    for kind, instrument in tagged_sorted:
        node_day = instrument_node_date(kind, instrument, calendar)
        if node_day in claimed:
            raise DuplicateNodeError(
                f"node date {node_day} is claimed by more than one "
                f"instrument ({instrument.name!r})"
            )
        claimed.add(node_day)

    solved: list[tuple[date, Decimal]] = [
        (snapshot.valuation_date, Decimal(1))
    ]
    diagnostics: list[InstrumentDiagnostic] = []

    with localcontext() as context:
        context.prec = max(context.prec, 40)
        for kind, instrument in tagged_sorted:
            node_day = instrument_node_date(kind, instrument, calendar)
            residual_fn, model_fn = _bind(kind, instrument, calendar)
            df, iterations = _solve_node(
                node_day, solved, residual_fn
            )
            solved.append((node_day, df))
            curve_now = DiscountCurve(
                snapshot.valuation_date, tuple(solved)
            )
            residual = residual_fn(curve_now.discount)
            if abs(residual) > PRICING_TOLERANCE:
                raise PricingToleranceError(
                    f"{kind} {instrument.name!r}: residual {residual} exceeds "
                    f"tolerance {PRICING_TOLERANCE}"
                )
            diagnostics.append(
                InstrumentDiagnostic(
                    name=instrument.name,
                    kind=kind,
                    node_date=node_day,
                    model_pv=model_fn(curve_now.discount),
                    market_target=Decimal(0),
                    residual=residual,
                    iterations=iterations,
                )
            )

    curve = DiscountCurve(snapshot.valuation_date, tuple(solved))
    return BootstrapDraft(curve=curve, diagnostics=tuple(diagnostics))


def instrument_node_date(kind: str, instrument, calendar) -> date:
    """Curve node at which an instrument pins a discount factor."""
    if kind == "deposit":
        deposit: Deposit = instrument
        return deposit.maturity
    if kind == "fra":
        fra: FRA = instrument
        return fra.maturity
    if kind == "swap":
        swap: Swap = instrument
        schedule = swap_schedule(swap, calendar)
        return schedule[-1].adjusted_end
    raise TypeError(f"unknown instrument kind: {kind!r}")


def _bind(kind: str, instrument, calendar):
    if kind == "deposit":
        deposit: Deposit = instrument
        return (
            lambda discount: deposit_residual(deposit, discount),
            lambda discount: deposit_model_pv(deposit, discount),
        )
    if kind == "fra":
        fra: FRA = instrument
        return (
            lambda discount: fra_residual(fra, discount),
            lambda discount: fra_model_pv(fra, discount),
        )
    if kind == "swap":
        swap: Swap = instrument
        schedule = swap_schedule(swap, calendar)
        return (
            lambda discount: swap_residual(swap, discount, schedule),
            lambda discount: swap_model_pv(swap, discount, schedule),
        )
    raise TypeError(f"unknown instrument kind: {kind!r}")


def _solve_node(
    node_date: date,
    solved: list[tuple[date, Decimal]],
    residual_fn: Callable[[Callable[[date], Decimal]], Decimal],
) -> tuple[Decimal, int]:
    previous_date, previous_df = solved[-1]
    if node_date <= previous_date:
        raise DuplicateNodeError(
            f"node {node_date} is not strictly after {previous_date}"
        )

    def residual_at(df: Decimal) -> Decimal:
        candidate_nodes = (*solved, (node_date, df))
        curve = DiscountCurve(solved[0][0], candidate_nodes)
        try:
            value = residual_fn(curve.discount)
        except ExtrapolationError as exc:
            raise MissingTenorError(
                f"instrument at node {node_date} references a date outside "
                f"the available curve range: {exc}"
            ) from exc
        if not value.is_finite():
            raise NonFiniteValueError(
                f"residual at node {node_date}, df={df} was non-finite"
            )
        return value

    anchor = previous_df
    low = anchor
    high = anchor
    f_low = residual_at(low)
    f_high = f_low
    if f_low == 0:
        return anchor, 1

    iterations = 1
    bracket_found = False
    for _ in range(BRACKET_EXPANSIONS):
        candidate_low = low * DOWN_FACTOR
        iterations += 1
        f_candidate_low = residual_at(candidate_low)
        if f_candidate_low == 0:
            return candidate_low, iterations
        if f_candidate_low * f_low < 0:
            low, f_low = candidate_low, f_candidate_low
            bracket_found = True
            break
        low = candidate_low
        f_low = f_candidate_low

        candidate_high = high * UP_FACTOR
        iterations += 1
        f_candidate_high = residual_at(candidate_high)
        if f_candidate_high == 0:
            return candidate_high, iterations
        if f_candidate_high * f_high < 0:
            high, f_high = candidate_high, f_candidate_high
            bracket_found = True
            break
        high = candidate_high
        f_high = f_candidate_high

    if not bracket_found:
        raise RootBracketingError(
            f"could not bracket root at node {node_date} after "
            f"{iterations} bracket evaluations"
        )

    while iterations < MAX_BISECTION_ITERATIONS:
        mid = (low + high) / 2
        iterations += 1
        if mid <= 0:
            raise NonFiniteValueError(
                f"bisection at node {node_date} left the positive domain"
            )
        f_mid = residual_at(mid)
        if f_mid == 0 or (high - low) <= BISECTION_CONVERGENCE:
            return mid, iterations
        if f_low * f_mid < 0:
            high, f_high = mid, f_mid
        else:
            low, f_low = mid, f_mid
    raise IterationLimitError(
        f"bisection at node {node_date} exceeded "
        f"{MAX_BISECTION_ITERATIONS} iterations"
    )
