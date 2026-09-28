"""发展因子：volume_weighted / simple_average / 固定选择因子。"""

from dataclasses import dataclass, field
from decimal import Decimal
from enum import Enum

from .decimal_utils import ONE, ZERO, factor, qf
from .errors import ZeroDenominatorError


class FactorMethod(str, Enum):
    VOLUME_WEIGHTED = "volume_weighted"
    SIMPLE_AVERAGE = "simple_average"
    FIXED = "fixed"


class ZeroDenominatorPolicy(str, Enum):
    ERROR = "error"
    SKIP = "skip"
    USE_ONE = "use_one"


@dataclass(frozen=True)
class FactorStep:
    """单个发展期因子的可审计明细。"""

    dev_from: int
    method: str
    numerator: Decimal
    denominator: Decimal
    raw_ratio: Decimal
    factor: Decimal
    used_units: int
    units: list[tuple[int, Decimal, Decimal]] = field(default_factory=list)
    ratios: list[Decimal] = field(default_factory=list)


@dataclass
class FactorConfig:
    method: FactorMethod = FactorMethod.VOLUME_WEIGHTED
    zero_policy: ZeroDenominatorPolicy = ZeroDenominatorPolicy.ERROR
    fixed_factors: dict[int, Decimal] = field(default_factory=dict)


def compute_factors(
    triangle,
    config: FactorConfig | None = None,
    only_devs: set[int] | None = None,
) -> list[FactorStep]:
    """逐发展期计算因子，输出分子/分母/纳入单元/原始比率/舍入结果。"""
    config = config or FactorConfig()
    n = triangle.n
    boundary = triangle.observed_boundary()
    max_dev = max(boundary.values())
    steps: list[FactorStep] = []

    for dev in range(1, max_dev):
        if only_devs is not None and dev not in only_devs:
            continue
        if config.method is FactorMethod.FIXED:
            if dev not in config.fixed_factors:
                raise ZeroDenominatorError(
                    f"固定因子模式缺少 dev={dev} 的选择因子"
                )
            chosen = factor(config.fixed_factors[dev])
            steps.append(
                FactorStep(
                    dev_from=dev,
                    method=config.method.value,
                    numerator=ZERO,
                    denominator=ZERO,
                    raw_ratio=chosen,
                    factor=chosen,
                    used_units=0,
                    units=[],
                )
            )
            continue

        observed: list[tuple[int, Decimal, Decimal]] = []
        for i in range(n):
            if boundary.get(i, 0) >= dev + 1:
                left = triangle.cells[(i, dev)].amount
                right = triangle.cells[(i, dev + 1)].amount
                observed.append((i, left, right))

        units = [u for u in observed if u[1] != ZERO]
        skipped_zero = len(observed) - len(units)
        numerator = sum((u[2] for u in units), ZERO)
        denominator = sum((u[1] for u in units), ZERO)

        if denominator == ZERO:
            if config.zero_policy is ZeroDenominatorPolicy.USE_ONE:
                raw = ONE
            elif config.zero_policy is ZeroDenominatorPolicy.SKIP:
                raw = ONE
            else:
                raise ZeroDenominatorError(
                    f"dev={dev} 的因子分母为零（跳过零单元 {skipped_zero} 个，"
                    f"观测单元 {len(observed)} 个）"
                )
            steps.append(
                FactorStep(
                    dev_from=dev,
                    method=config.method.value,
                    numerator=numerator,
                    denominator=denominator,
                    raw_ratio=raw,
                    factor=factor(raw),
                    used_units=len(units),
                    units=units,
                    ratios=[qf(u[2] / u[1]) for u in units],
                )
            )
            continue

        if config.method is FactorMethod.VOLUME_WEIGHTED:
            raw = numerator / denominator
            ratios = [u[2] / u[1] for u in units]
        else:
            ratios = [u[2] / u[1] for u in units]
            raw = sum(ratios, ZERO) / Decimal(len(ratios))

        factor_value = qf(raw)
        steps.append(
            FactorStep(
                dev_from=dev,
                method=config.method.value,
                numerator=numerator,
                denominator=denominator,
                raw_ratio=raw,
                factor=factor_value,
                used_units=len(units),
                units=units,
                ratios=[qf(r) for r in ratios],
            )
        )
    return steps
