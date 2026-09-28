"""Decimal 冻结尺度工具（骨架：常量与空实现）。"""

from __future__ import annotations

from decimal import ROUND_HALF_EVEN, Context, InvalidOperation
from decimal import Decimal

PRICE_SCALE = 8
QTY_SCALE = 6
FX_SCALE = 10
AMOUNT_SCALE = 8

PRICE_Q = Decimal(1).scaleb(-PRICE_SCALE)
QTY_Q = Decimal(1).scaleb(-QTY_SCALE)
FX_Q = Decimal(1).scaleb(-FX_SCALE)
AMOUNT_Q = Decimal(1).scaleb(-AMOUNT_SCALE)

_MONEY_CONTEXT = Context(prec=60, rounding=ROUND_HALF_EVEN)


def freeze(value: object, quantum: Decimal) -> Decimal:
    dec = _coerce(value)
    if not dec.is_finite():
        raise ValueError(f"non-finite decimal value: {dec}")
    return _MONEY_CONTEXT.create_decimal(
        dec.quantize(quantum, rounding=ROUND_HALF_EVEN, context=_MONEY_CONTEXT)
    )


def freeze_price(value: object) -> Decimal:
    return freeze(value, PRICE_Q)


def freeze_qty(value: object) -> Decimal:
    return freeze(value, QTY_Q)


def freeze_fx(value: object) -> Decimal:
    return freeze(value, FX_Q)


def freeze_amount(value: object) -> Decimal:
    return freeze(value, AMOUNT_Q)


def is_finite_decimal(value: object) -> bool:
    try:
        return _coerce(value).is_finite()
    except (ValueError, InvalidOperation):
        return False


def qmul(left: Decimal, right: Decimal, quantum: Decimal) -> Decimal:
    """舍入乘法：返回冻结尺度结果。"""
    product = _MONEY_CONTEXT.multiply(left, right)
    return _MONEY_CONTEXT.create_decimal(
        product.quantize(quantum, rounding=ROUND_HALF_EVEN, context=_MONEY_CONTEXT)
    )


def _coerce(value: object) -> Decimal:
    if isinstance(value, Decimal):
        return value
    if isinstance(value, int):
        return Decimal(value)
    if isinstance(value, str):
        text = value.strip()
        if not text:
            raise ValueError("empty decimal string")
        return Decimal(text)
    raise ValueError(f"unsupported numeric type: {type(value).__name__}")
