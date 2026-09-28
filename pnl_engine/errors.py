"""分类失败错误类型。"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class FailureCode(str, Enum):
    MISSING_FACTOR = "missing_factor"
    DUPLICATE_TRANSACTION = "duplicate_transaction"
    CURRENCY_MISMATCH = "currency_mismatch"
    TIME_INVERSION = "time_inversion"
    NON_FINITE_VALUE = "non_finite_value"


@dataclass(frozen=True)
class Failure:
    code: FailureCode
    message: str
    record_id: str | None = None


class PnLEngineError(Exception):
    """引擎基类错误。"""


class ValidationError(PnLEngineError):
    def __init__(self, failures: list[Failure]):
        self.failures = failures
        super().__init__("; ".join(f"{f.code.value}: {f.message}" for f in failures))


class ReconciliationError(PnLEngineError):
    """期初价值 + 归因分量 != 期末价值。"""
