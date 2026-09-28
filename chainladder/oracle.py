"""独立矩阵循环 oracle：用最朴素的嵌套循环实现，供交叉核验。"""

from decimal import Decimal

from .decimal_utils import ONE, ZERO, factor, q2


def oracle_factors(matrix: list[list[Decimal | None]]) -> list[tuple[Decimal, Decimal, Decimal]]:
    """返回每个发展期 (分子, 分母, 体积加权因子) 的朴素循环结果。"""
    rows = len(matrix)
    max_dev = 0
    for r in range(rows):
        length = len(matrix[r])
        if length > max_dev:
            max_dev = length

    output: list[tuple[Decimal, Decimal, Decimal]] = []
    for dev in range(max_dev - 1):
        numerator = Decimal("0.00")
        denominator = Decimal("0.00")
        count = 0
        for r in range(rows):
            left = None
            right = None
            if dev < len(matrix[r]):
                left = matrix[r][dev]
            if dev + 1 < len(matrix[r]):
                right = matrix[r][dev + 1]
            if left is not None and right is not None and left != ZERO:
                numerator = numerator + right
                denominator = denominator + left
                count += 1
        if denominator == ZERO:
            ratio = factor(ONE)
        else:
            ratio = factor(numerator / denominator)
        output.append((numerator, denominator, ratio))
    return output


def oracle_project(
    matrix: list[list[Decimal | None]],
    factors: list[Decimal],
    tail: Decimal,
) -> list[dict]:
    """朴素循环投影，返回每行 paid/reserve/ultimate。"""
    rows = len(matrix)
    max_dev = 0
    for r in range(rows):
        length = len(matrix[r])
        if length > max_dev:
            max_dev = length

    results: list[dict] = []
    for r in range(rows):
        k = 0
        paid = ZERO
        for c in range(len(matrix[r])):
            value = matrix[r][c]
            if value is not None:
                k = c + 1
                paid = value
        value = paid
        projected: list[Decimal] = []
        for dev in range(k, max_dev):
            value = q2(value * factors[dev - 1])
            projected.append(value)
        ultimate = q2(value * tail)
        reserve = q2(ultimate - paid)
        results.append(
            {
                "origin": r,
                "paid": paid,
                "projected": projected,
                "ultimate": ultimate,
                "reserve": reserve,
            }
        )
    return results
