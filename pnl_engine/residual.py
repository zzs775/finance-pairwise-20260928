"""跨币种舍入尾差与最小可追踪差异集。"""

from __future__ import annotations

from decimal import Decimal

from .decimalx import MONEY_QUANT, fsum
from .errors import ClosureError
from .types import AssetResult, DiffItem


def check_closure(result: AssetResult) -> Decimal:
    """返回 期初+分量+residual 与期末的剩余差值；为零即精确闭合。"""
    explained = fsum(
        [result.start_value]
        + [component.value for component in result.components]
        + [result.residual],
        MONEY_QUANT,
    )
    return fsum([result.end_value - explained], MONEY_QUANT)


def minimal_diffs(results: list[AssetResult]) -> tuple[DiffItem, ...]:
    """输出非零残差的最小可追踪差异集：逐资产定位。

    每个资产至多一条 residual 级差异（该资产已包含全部分量明细），
    按 asset_id 排序以保证输出确定性。
    """
    diffs: list[DiffItem] = []
    for result in sorted(results, key=lambda item: item.asset_id):
        gap = check_closure(result)
        if gap != 0 or result.residual != 0:
            diffs.append(
                DiffItem(
                    asset_id=result.asset_id,
                    kind="residual",
                    expected=Decimal("0.00"),
                    actual=fsum([result.residual + gap], MONEY_QUANT),
                )
            )
    return tuple(diffs)


def assert_closure(
    results: list[AssetResult], *, strict: bool
) -> tuple[DiffItem, ...]:
    """strict=True（题内控制夹具）时任何残差都应失败。"""
    diffs = minimal_diffs(results)
    if strict and diffs:
        rendered = "; ".join(
            f"{diff.asset_id}:{diff.kind}={diff.actual}" for diff in diffs
        )
        raise ClosureError(f"unexplained residual: {rendered}")
    return diffs
