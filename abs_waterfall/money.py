"""Fixed-scale Decimal monetary primitives.

All money lives on a 0.01 grid; rates/proportions on a 1e-10 grid.
Every arithmetic entry point rejects negatives and enforces the magnitude
cap so that overflow is a classified failure rather than a silent bigint.
"""

from collections.abc import Iterable
from decimal import ROUND_HALF_EVEN, Decimal

MONEY_SCALE: Decimal = Decimal("0.01")
RATE_SCALE: Decimal = Decimal("0.0000000001")
MAX_MONEY: Decimal = Decimal("999999999999.99")
ZERO: Decimal = Decimal("0.00")


def to_money(value: object = 0) -> Decimal:
    """Quantize *value* onto the money grid, rejecting negatives/overflow."""
    try:
        dec = Decimal(value)
    except (ArithmeticError, TypeError, ValueError) as exc:
        from .errors import NegativeCashError

        raise NegativeCashError(f"not a monetary value: {value!r}") from exc
    if not dec.is_finite() or dec < 0:
        from .errors import NegativeCashError

        raise NegativeCashError(f"non-finite or negative money: {value!r}")
    quantized = dec.quantize(MONEY_SCALE, rounding=ROUND_HALF_EVEN)
    if quantized > MAX_MONEY:
        from .errors import CashOverflowError

        raise CashOverflowError(f"money exceeds cap {MAX_MONEY}: {quantized}")
    return quantized


def to_rate(value: object) -> Decimal:
    """Quantize a rate/proportion onto the rate grid (must be >= 0)."""
    try:
        dec = Decimal(value)
    except (ArithmeticError, TypeError, ValueError) as exc:
        from .errors import NegativeCashError

        raise NegativeCashError(f"not a rate: {value!r}") from exc
    if not dec.is_finite() or dec < 0:
        from .errors import NegativeCashError

        raise NegativeCashError(f"non-finite or negative rate: {value!r}")
    return dec.quantize(RATE_SCALE, rounding=ROUND_HALF_EVEN)


def _guard_money(dec: Decimal) -> Decimal:
    if not dec.is_finite() or dec < 0:
        from .errors import NegativeCashError

        raise NegativeCashError(f"non-finite or negative money: {dec}")
    if dec > MAX_MONEY:
        from .errors import CashOverflowError

        raise CashOverflowError(f"money exceeds cap {MAX_MONEY}: {dec}")
    if dec.as_tuple().exponent < MONEY_SCALE.as_tuple().exponent:
        dec = dec.quantize(MONEY_SCALE, rounding=ROUND_HALF_EVEN)
    return dec


def checked_add(left: Decimal, right: Decimal) -> Decimal:
    return _guard_money(Decimal(left) + Decimal(right))


def checked_sub(left: Decimal, right: Decimal) -> Decimal:
    """Subtract but never go below zero (use for guarded cash movements)."""
    result = Decimal(left) - Decimal(right)
    if result < 0:
        from .errors import NegativeCashError

        raise NegativeCashError(
            f"balance would go negative: {left} - {right}"
        )
    return _guard_money(result)


def money_sum(values: Iterable[Decimal]) -> Decimal:
    total = ZERO
    for value in values:
        total = checked_add(total, value)
    return total
