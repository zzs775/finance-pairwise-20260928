"""Fixed-scale Decimal helpers."""

from decimal import localcontext
from decimal import Decimal

SCALE_DIGITS = 10
QUANTUM: Decimal = Decimal(1).scaleb(-SCALE_DIGITS)  # 1E-10
LINE_QUANTUM: Decimal = QUANTUM
ZERO: Decimal = Decimal(0).quantize(QUANTUM)
ONE: Decimal = Decimal(1).quantize(QUANTUM)
MAX_AMOUNT: Decimal = Decimal("1E18")
PRODUCT_PRECISION = 80


def q(value: Decimal) -> Decimal:
    """Quantize a Decimal to the engine's fixed scale."""
    if not isinstance(value, Decimal):
        value = Decimal(value)
    if not is_finite(value):
        from .errors import ValidationError

        raise ValidationError(f"non-finite value: {value}")
    with localcontext() as context:
        context.prec = PRODUCT_PRECISION
        return value.quantize(QUANTUM)


def is_finite(value: Decimal) -> bool:
    """Return True only for finite Decimal values."""
    return isinstance(value, Decimal) and value.is_finite()


def product(*factors: Decimal) -> Decimal:
    """Exact product of fixed-scale factors (no rounding)."""
    result = Decimal(1)
    for factor in factors:
        result *= factor
    return result


def round_line(value: Decimal) -> Decimal:
    """Round one period/scenario ECL line to the reporting scale."""
    if not is_finite(value):
        from .errors import ValidationError

        raise ValidationError(f"non-finite ECL line: {value}")
    with localcontext() as context:
        context.prec = PRODUCT_PRECISION
        return value.quantize(LINE_QUANTUM)
