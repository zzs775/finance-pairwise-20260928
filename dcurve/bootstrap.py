"""Segment-wise curve bootstrapping with bracketed bisection.

Instruments are sorted by maturity (stable on id).  At each step every
instrument ending on the maturity is priced against a trial curve whose
final node discount factor is the unknown; the residual is zero at par.
A failed bracket / iteration / non-finite evaluation aborts the whole
build, so a partial curve is never returned.
"""

from dataclasses import dataclass
from decimal import Decimal

from .constants import (
    BISECTION_WIDTH_TOLERANCE,
    BRACKET_LOG_HALF_WIDTH,
    MAX_BISECTION_ITERATIONS,
    ONE,
    REPRICING_TOLERANCE,
    SOLVER_RESIDUAL_TOLERANCE,
    TWO,
    ZERO,
)
from .curve import CurveNode, DiscountCurve
from .errors import (
    DuplicateNodeError,
    IllegalDateError,
    IterationLimitError,
    NoBracketError,
    NonFiniteValueError,
    NonPositiveDiscountFactorError,
    RepricingToleranceError,
)
from .instruments import (
    DepositQuote,
    FRAQuote,
    QuoteBook,
)
from .pricing import deposit_pv, fra_pv, swap_pv


@dataclass(frozen=True, slots=True)
class Diagnostic:
    id: str
    kind: str
    maturity: str
    model_pv: Decimal
    market_target: Decimal
    residual: Decimal
    iterations: int


@dataclass(frozen=True, slots=True)
class BootstrapResult:
    curve: DiscountCurve
    diagnostics: tuple[Diagnostic, ...]


@dataclass(frozen=True, slots=True)
class BondValuation:
    id: str
    present_value: Decimal


def _instrument_kind(quote) -> str:
    if isinstance(quote, DepositQuote):
        return "deposit"
    if isinstance(quote, FRAQuote):
        return "fra"
    return "swap"


def _ordered_quotes(book: QuoteBook):
    quotes = list(book.all_quotes())
    quotes.sort(key=lambda q: (q.maturity_date, q.id))
    return quotes


def _price(quote, curve: DiscountCurve) -> Decimal:
    if isinstance(quote, DepositQuote):
        return deposit_pv(curve, quote)
    if isinstance(quote, FRAQuote):
        return fra_pv(curve, quote)
    return swap_pv(curve, quote)


def _checked(value: Decimal, label: str, maturity: str) -> Decimal:
    if not isinstance(value, Decimal) or not value.is_finite():
        raise NonFiniteValueError(
            f"non-finite {label} while solving node {maturity}: {value!r}")
    return value


def bisect_node(residual_fn, anchor_df: Decimal, maturity: str,
                instrument_ids: str):
    """Bracket then bisect f(x) = 0 with x = ln(discount factor).

    Returns (discount factor, iterations).  Raises a classified error if
    no bracket exists, the iteration cap is hit, or a midpoint is non-finite.
    """
    center = anchor_df.ln()
    lo = center - BRACKET_LOG_HALF_WIDTH
    hi = center + BRACKET_LOG_HALF_WIDTH

    def df_at(x: Decimal) -> Decimal:
        return x.exp()

    f_lo = _checked(residual_fn(df_at(lo)), "residual", maturity)
    f_hi = _checked(residual_fn(df_at(hi)), "residual", maturity)
    if f_lo == ZERO:
        return df_at(lo), 0
    if f_hi == ZERO:
        return df_at(hi), 0
    if f_lo * f_hi > ZERO:
        raise NoBracketError(
            f"no sign bracket around node {maturity} for {instrument_ids}; "
            f"f(low)={f_lo}, f(high)={f_hi}")

    for iteration in range(1, MAX_BISECTION_ITERATIONS + 1):
        mid = (lo + hi) / TWO
        candidate = df_at(mid)
        f_mid = residual_fn(candidate)
        _checked(f_mid, "midpoint residual", maturity)
        if candidate <= ZERO:
            raise NonPositiveDiscountFactorError(
                f"non-positive candidate discount factor at {maturity}")
        if f_mid == ZERO or f_mid.copy_abs() <= SOLVER_RESIDUAL_TOLERANCE:
            return candidate, iteration
        if (hi - lo).copy_abs() < BISECTION_WIDTH_TOLERANCE:
            return candidate, iteration
        if f_lo * f_mid < ZERO:
            hi, f_hi = mid, f_mid
        else:
            lo, f_lo = mid, f_mid
    raise IterationLimitError(
        f"bisection exceeded {MAX_BISECTION_ITERATIONS} iterations at "
        f"{maturity} for {instrument_ids}")


def bootstrap(book: QuoteBook) -> BootstrapResult:
    """Build a complete curve or raise; never publishes partial nodes."""
    versioned = book.with_version()
    versioned.validate()
    quotes = _ordered_quotes(versioned)
    seen_nodes: set = set()
    for quote in quotes:
        key = (type(quote), quote.maturity_date)
        if key in seen_nodes:
            raise DuplicateNodeError(
                f"duplicate {_instrument_kind(quote)} node at "
                f"{quote.maturity_date.isoformat()} ({quote.id})")
        seen_nodes.add(key)

    nodes: list[CurveNode] = []
    diagnostics: list[Diagnostic] = []
    index = 0
    while index < len(quotes):
        maturity = quotes[index].maturity_date
        group = []
        while index < len(quotes) \
                and quotes[index].maturity_date == maturity:
            group.append(quotes[index])
            index += 1

        if nodes and maturity <= nodes[-1].date:
            raise DuplicateNodeError(
                f"duplicate node date {maturity.isoformat()}")
        if not nodes and maturity <= versioned.value_date:
            raise IllegalDateError(
                f"first maturity {maturity.isoformat()} is not after value "
                f"date {versioned.value_date.isoformat()}")

        def residual(candidate_df: Decimal) -> Decimal:
            trial_nodes = tuple(nodes) + (CurveNode(maturity, candidate_df),)
            trial = DiscountCurve.create(
                versioned.value_date, trial_nodes, versioned.version_id)
            total = ZERO
            for quote in group:
                total += _price(quote, trial) / quote.notional
            return total

        anchor_df = nodes[-1].discount_factor if nodes else ONE
        ids = ", ".join(q.id for q in group)
        solved_df, iterations = bisect_node(
            residual, anchor_df, maturity.isoformat(), ids)

        final_nodes = tuple(nodes) + (CurveNode(maturity, solved_df),)
        curve = DiscountCurve.create(
            versioned.value_date, final_nodes, versioned.version_id)
        for quote in group:
            model_pv = _price(quote, curve)
            if not model_pv.is_finite():
                raise NonFiniteValueError(
                    f"{quote.id}: non-finite repricing value {model_pv!r}")
            unit_error = model_pv / quote.notional
            if unit_error.copy_abs() > REPRICING_TOLERANCE:
                raise RepricingToleranceError(
                    f"{quote.id}: unit-notional repricing residual "
                    f"{unit_error} exceeds tolerance {REPRICING_TOLERANCE}")
            diagnostics.append(Diagnostic(
                id=quote.id,
                kind=_instrument_kind(quote),
                maturity=maturity.isoformat(),
                model_pv=model_pv,
                market_target=ZERO,
                residual=model_pv,
                iterations=iterations,
            ))
        nodes.append(CurveNode(maturity, solved_df))

    result = BootstrapResult(
        curve=DiscountCurve.create(
            versioned.value_date, tuple(nodes), versioned.version_id),
        diagnostics=tuple(diagnostics),
    )
    return result


class CurveHolder:
    """Holds the published curve; replacement is atomic."""

    def __init__(self) -> None:
        object.__setattr__(self, "_curve", None)

    @property
    def current(self) -> DiscountCurve | None:
        return getattr(self, "_curve", None)

    def publish(self, book: QuoteBook) -> BootstrapResult:
        """Bootstrap ``book``; only swap the held curve on full success."""
        result = bootstrap(book)
        object.__setattr__(self, "_curve", result.curve)
        return result
