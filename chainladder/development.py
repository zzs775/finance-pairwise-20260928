"""累计 <-> 增量赔付的双向可逆转换。"""

from decimal import Decimal

from .decimal_utils import ZERO, q2
from .errors import NegativePaymentError


def cumulative_to_incremental(values: list[Decimal]) -> list[Decimal]:
    """单行：累计序列 -> 增量序列（首项等于首个累计值）。"""
    result: list[Decimal] = []
    prev = ZERO
    for value in values:
        incr = q2(value - prev)
        if incr < ZERO:
            raise NegativePaymentError(f"增量为负（累计非减违规）: {prev} -> {value}")
        result.append(incr)
        prev = value
    return result


def incremental_to_cumulative(values: list[Decimal]) -> list[Decimal]:
    """单行：增量序列 -> 累计序列（逐项求和）。"""
    result: list[Decimal] = []
    total = ZERO
    for value in values:
        if value < ZERO:
            raise NegativePaymentError(f"负增量: {value}")
        total = q2(total + value)
        result.append(total)
    return result


def triangle_cumulative_to_incremental(matrix: dict[int, list[Decimal]]) -> dict[int, list[Decimal]]:
    """整个三角形：累计矩阵 -> 增量矩阵。"""
    return {origin: cumulative_to_incremental(row) for origin, row in sorted(matrix.items())}


def triangle_incremental_to_cumulative(matrix: dict[int, list[Decimal]]) -> dict[int, list[Decimal]]:
    """整个三角形：增量矩阵 -> 累计矩阵。"""
    return {origin: incremental_to_cumulative(row) for origin, row in sorted(matrix.items())}
