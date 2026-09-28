"""链梯投影：逐期因子、尾因子、终极赔付与准备金。"""

from dataclasses import dataclass, field
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal

from .decimal_utils import ONE, ZERO, factor, q2
from .errors import ShapeError
from .factors import FactorConfig, FactorStep, compute_factors


@dataclass(frozen=True)
class OriginResult:
    origin: int
    label: int
    paid: Decimal
    reserve: Decimal
    ultimate: Decimal
    projected: list[Decimal] = field(default_factory=list)


@dataclass
class ProjectionResult:
    factors: list[FactorStep]
    origins: dict[int, OriginResult]
    tail_factor: Decimal
    config: FactorConfig = field(default_factory=FactorConfig)

    def total_reserve(self) -> Decimal:
        return q2(sum((o.reserve for o in self.origins.values()), ZERO))

    def total_paid(self) -> Decimal:
        return q2(sum((o.paid for o in self.origins.values()), ZERO))

    def total_ultimate(self) -> Decimal:
        return q2(sum((o.ultimate for o in self.origins.values()), ZERO))


def project(
    triangle,
    config: FactorConfig | None = None,
    tail_factor: object = None,
    steps: list[FactorStep] | None = None,
) -> ProjectionResult:
    """选定因子与尾因子后逐期投影；已观察单元不被覆盖。"""
    config = config or FactorConfig()
    steps = steps if steps is not None else compute_factors(triangle, config)
    factor_by_dev = {step.dev_from: step.factor for step in steps}
    tail = factor(tail_factor) if tail_factor is not None else factor(ONE)
    if tail < factor(ONE):
        raise ShapeError(f"尾因子不得小于 1: {tail}")

    n = triangle.n
    boundary = triangle.observed_boundary()
    max_dev = max(boundary.values())
    origins: dict[int, OriginResult] = {}

    for i in range(n):
        k = boundary[i]
        paid = triangle.cells[(i, k)].amount
        value = paid
        projected: list[Decimal] = []
        for dev in range(k, max_dev):
            value = q2(value * factor_by_dev[dev])
            projected.append(q2(value))
        base = projected[-1] if projected else paid
        ultimate = q2(base * tail)
        reserve = q2(ultimate - paid)
        origins[i] = OriginResult(
            origin=i,
            label=triangle.origin_labels[i],
            paid=paid,
            reserve=reserve,
            ultimate=ultimate,
            projected=projected,
        )

    return ProjectionResult(
        factors=steps, origins=origins, tail_factor=tail, config=config
    )


def project_parallel(
    triangle,
    config: FactorConfig | None = None,
    tail_factor: object = None,
    max_workers: int = 4,
) -> ProjectionResult:
    """事故期并行投影；各事故期互不共享可变状态，结果与串行 project 完全一致。"""
    config = config or FactorConfig()
    steps = compute_factors(triangle, config)
    tail = factor(tail_factor) if tail_factor is not None else factor(ONE)
    if tail < factor(ONE):
        raise ShapeError(f"尾因子不得小于 1: {tail}")

    boundary = triangle.observed_boundary()
    max_dev = max(boundary.values())
    factor_by_dev = {step.dev_from: step.factor for step in steps}

    def build(i: int) -> tuple[int, OriginResult]:
        k = boundary[i]
        paid = triangle.cells[(i, k)].amount
        value = paid
        projected: list[Decimal] = []
        for dev in range(k, max_dev):
            value = q2(value * factor_by_dev[dev])
            projected.append(value)
        base = projected[-1] if projected else paid
        ultimate = q2(base * tail)
        return i, OriginResult(
            origin=i,
            label=triangle.origin_labels[i],
            paid=paid,
            reserve=q2(ultimate - paid),
            ultimate=ultimate,
            projected=projected,
        )

    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        pairs = list(pool.map(build, range(triangle.n)))
    origins = {i: origin for i, origin in sorted(pairs)}
    return ProjectionResult(
        factors=steps, origins=origins, tail_factor=tail, config=config
    )
