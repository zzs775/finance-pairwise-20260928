"""历史修订：以单元版本替换原值，仅重算受影响因子与后续投影。"""

from .decimal_utils import D, q2
from .errors import IllegalVersionError, NegativePaymentError
from .model import Cell, Triangle
from .factors import FactorConfig, compute_factors
from .projection import ProjectionResult, project


def _to_internal_index(triangle: Triangle, origin) -> int:
    if origin in triangle.origin_labels:
        return triangle.origin_labels.index(origin)
    raise KeyError(f"未知事故期: {origin}")


def revise(triangle: Triangle, cell: Cell) -> list[int]:
    """以更高版本单元替换原值，返回受影响的发展期起点列表。"""
    i = _to_internal_index(triangle, cell.origin)
    key = (i, cell.dev)
    history = triangle.history.get(key)
    if not history:
        raise KeyError(f"不能对不存在的单元做修订: origin={cell.origin}, dev={cell.dev}")
    current = history[-1]
    if cell.version <= current.version:
        raise IllegalVersionError(
            f"修订版本必须严格递增: 现有 {current.version}, 收到 {cell.version}"
        )
    replacement = Cell(
        origin=i,
        dev=cell.dev,
        amount=q2(D(cell.amount)),
        version=cell.version,
    )
    if replacement.amount < q2(D(0)):
        raise NegativePaymentError(f"修订后出现负赔付: {key}")
    boundary = triangle.observed_boundary()
    candidate_row = []
    for dev in range(1, boundary[i] + 1):
        amount = replacement.amount if dev == cell.dev else triangle.cells[(i, dev)].amount
        candidate_row.append(amount)
    prev = None
    for idx, amount in enumerate(candidate_row, start=1):
        if amount < q2(D(0)):
            raise NegativePaymentError(f"修订后出现负赔付: origin={i}, dev={idx}")
        if prev is not None and amount < prev:
            raise NegativePaymentError(
                f"修订后累计非减违规: origin={i}, dev={idx}"
            )
        prev = amount

    triangle.cells[key] = replacement
    history.append(replacement)
    candidates = set()
    if cell.dev - 1 >= 1:
        candidates.add(cell.dev - 1)
    if boundary[i] >= cell.dev + 1:
        candidates.add(cell.dev)
    affected = sorted(candidates)
    return affected


def incremental_recompute(
    triangle: Triangle,
    previous: ProjectionResult,
    affected_devs: list[int],
    config: FactorConfig | None = None,
    tail_factor: object = None,
) -> ProjectionResult:
    """仅重算受影响因子及后续投影，结果必须与全量重算一致。"""
    config = config or previous.config
    tail = tail_factor if tail_factor is not None else previous.tail_factor
    affected_set = set(affected_devs)
    if affected_set:
        fresh = {
            step.dev_from: step
            for step in compute_factors(triangle, config, only_devs=affected_set)
        }
        merged = [
            fresh.get(step.dev_from, step)
            if step.dev_from in affected_set
            else step
            for step in previous.factors
        ]
    else:
        merged = previous.factors
    return project(triangle, config, tail, steps=merged)


def full_recompute(
    triangle: Triangle,
    config: FactorConfig | None = None,
    tail_factor: object = None,
) -> ProjectionResult:
    """全量链梯重算，作为增量更新的对照基准。"""
    return project(triangle, config or FactorConfig(), tail_factor)
