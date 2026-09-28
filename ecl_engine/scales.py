"""Fixed Decimal scales and low-level numeric helpers.

All probabilities, amounts, discount factors and weights are represented as
``Decimal`` values quantised to module-level fixed scales.
"""

from decimal import Decimal
from typing import NewType

#: Fixed-point quantity (Decimal alias kept for type documentation).
Q = NewType("Q", Decimal)

#: 8 decimal places for probabilities / weights / discount factors.
RATE_QUANT: Decimal = Decimal("0.00000001")
#: 2 decimal places for monetary amounts.
AMOUNT_QUANT: Decimal = Decimal("0.01")
#: 6 decimal places for per-period multipliers before aggregation.
DETAIL_QUANT: Decimal = Decimal("0.000001")

ZERO: Decimal = Decimal(0)
ONE: Decimal = Decimal(1)


def D(value: object) -> Decimal:
    """Parse a value into Decimal without float rounding."""
    if isinstance(value, Decimal):
        parsed = value
    elif isinstance(value, bool):
        raise TypeError("boolean is not a valid Decimal input")
    elif isinstance(value, int):
        parsed = Decimal(value)
    elif isinstance(value, str):
        parsed = Decimal(value.strip())
    else:
        raise TypeError(f"refuse to convert {type(value).__name__} to Decimal")
    if not parsed.is_finite():
        raise ValueError("non-finite Decimal value")
    return parsed


def quantize(value: Decimal, quant: Decimal) -> Decimal:
    """Round ``value`` to the given fixed scale."""
    if not value.is_finite():
        raise ValueError("non-finite Decimal value")
    return value.quantize(quant, rounding=ROUND_HALF_UP)


def is_finite(value: Decimal) -> bool:
    """Return True iff the Decimal is neither NaN nor infinite."""
    return value.is_finite()


def fixed_sum(values, quant: Decimal) -> Decimal:
    """Exact-then-round sum: add at high precision, quantise once at the end."""
    with localcontext() as ctx:
        ctx.prec = 60
        total = sum((D(v) for v in values), ZERO)
    return quantize(Decimal(total), quant)
from decimal import Decimal, ROUND_HALF_UP, localcontext
from typing import NewType
