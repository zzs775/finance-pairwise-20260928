"""Decimal 尺度冻结与有限性工具。"""

from __future__ import annotations

from decimal import Decimal, Context, localcontext

from .errors import NonFiniteValueError

MONEY_QUANT = Decimal("0.01")
PRICE_QUANT = Decimal("0.00000001")
QTY_QUANT = Decimal("0.00000001")
FX_QUANT = Decimal("0.00000001")

CALC_CONTEXT = Context(prec=50)


def freeze(value: Decimal, quant: Decimal) -> Decimal:
    """把 value 量化到固定尺度；非有限值分类失败。"""
    if not is_finite(value):
        raise NonFiniteValueError(f"non-finite decimal: {value}")
    with localcontext(CALC_CONTEXT):
        return value.quantize(quant)


def is_finite(value: Decimal) -> bool:
    return value.is_finite()


def fmul(left: Decimal, right: Decimal, quant: Decimal) -> Decimal:
    """高精度乘法后冻结到 quant 尺度。"""
    with localcontext(CALC_CONTEXT):
        product = left * right
    return freeze(product, quant)


def fdiv(left: Decimal, right: Decimal, quant: Decimal) -> Decimal:
    """高精度除法后冻结到 quant 尺度。"""
    if right == 0:
        raise NonFiniteValueError("division by zero")
    with localcontext(CALC_CONTEXT):
        quotient = left / right
    return freeze(quotient, quant)


def fsum(values, quant: Decimal) -> Decimal:
    """高精度求和后冻结到 quant 尺度。"""
    with localcontext(CALC_CONTEXT):
        total = sum((value for value in values), Decimal(0))
    return freeze(total, quant)
