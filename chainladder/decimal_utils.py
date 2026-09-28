"""Decimal 固定尺度工具：金额与因子的量化、舍入与比较。"""

from decimal import Decimal, ROUND_HALF_UP, getcontext

MONEY_SCALE = 2
FACTOR_SCALE = 6

getcontext().prec = 60


def D(value: object) -> Decimal:
    """把 int/str/Decimal 安全转为 Decimal（禁止 float 直传）。"""
    if isinstance(value, Decimal):
        return value
    if isinstance(value, bool):
        raise TypeError("布尔值不是合法金额")
    if isinstance(value, int):
        return Decimal(value)
    if isinstance(value, str):
        return Decimal(value.strip())
    raise TypeError(f"不支持的金额类型: {type(value).__name__}")


def q2(value: Decimal) -> Decimal:
    """量化为金额尺度（2 位小数，ROUND_HALF_UP）。"""
    return value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def qf(value: Decimal) -> Decimal:
    """量化为因子尺度（6 位小数，ROUND_HALF_UP）。"""
    return value.quantize(Decimal("0.000001"), rounding=ROUND_HALF_UP)


def money(value: object) -> Decimal:
    """转 Decimal 并量化到金额尺度。"""
    return q2(D(value))


def factor(value: object) -> Decimal:
    """转 Decimal 并量化到因子尺度。"""
    return qf(D(value))


ZERO = Decimal("0")
ONE = Decimal("1")
ROUND = ROUND_HALF_UP
