"""可修订赔付三角形链梯法引擎（仅标准库）。"""

from .model import Cell, Triangle
from .factors import (
    FactorConfig,
    FactorMethod,
    FactorStep,
    ZeroDenominatorPolicy,
    compute_factors,
)
from .development import (
    cumulative_to_incremental,
    incremental_to_cumulative,
    triangle_cumulative_to_incremental,
    triangle_incremental_to_cumulative,
)
from .projection import (
    OriginResult,
    ProjectionResult,
    project,
    project_parallel,
)
from .revision import full_recompute, incremental_recompute, revise
from .serialization import result_to_plain, to_canonical_json
from .errors import (
    TriangleError,
    DuplicateCellError,
    NegativePaymentError,
    InternalHoleError,
    IllegalVersionError,
    ZeroDenominatorError,
    ShapeError,
)

__all__ = [
    "Cell",
    "Triangle",
    "FactorConfig",
    "FactorMethod",
    "FactorStep",
    "ZeroDenominatorPolicy",
    "compute_factors",
    "cumulative_to_incremental",
    "incremental_to_cumulative",
    "triangle_cumulative_to_incremental",
    "triangle_incremental_to_cumulative",
    "OriginResult",
    "ProjectionResult",
    "project",
    "project_parallel",
    "revise",
    "incremental_recompute",
    "full_recompute",
    "result_to_plain",
    "to_canonical_json",
    "TriangleError",
    "DuplicateCellError",
    "NegativePaymentError",
    "InternalHoleError",
    "IllegalVersionError",
    "ZeroDenominatorError",
    "ShapeError",
]
