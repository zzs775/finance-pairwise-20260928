"""失败分类：所有可预期的输入/计算错误在此集中定义。"""

from __future__ import annotations


class PnlError(Exception):
    """引擎错误基类。"""


class ValidationError(PnlError):
    """输入校验失败基类。"""

    category: str = "validation"


class MissingFactorError(ValidationError):
    category = "missing_factor"


class DuplicateTradeError(ValidationError):
    category = "duplicate_trade"


class CurrencyMismatchError(ValidationError):
    category = "currency_mismatch"


class TimeInversionError(ValidationError):
    category = "time_inversion"


class NonFiniteValueError(ValidationError):
    category = "non_finite"


class ClosureError(PnlError):
    """期初 + 分量 != 期末，且残差无法吸收。"""


class ReferenceMismatchError(PnlError):
    """快速流水线与直接重估参考器不一致。"""

