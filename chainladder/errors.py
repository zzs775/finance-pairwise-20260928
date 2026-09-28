"""错误分类：三角形校验与计算过程中的可区分异常。"""


class TriangleError(Exception):
    """所有链梯引擎错误的基类。"""


class ShapeError(TriangleError):
    """三角形形状非法（行列越界、非三角形等）。"""


class DuplicateCellError(TriangleError):
    """同一 (事故期, 发展期) 出现重复观测单元。"""


class NegativePaymentError(TriangleError):
    """累计赔付出现负值。"""


class InternalHoleError(TriangleError):
    """已观察边界内部存在空洞（缺单元）。"""


class IllegalVersionError(TriangleError):
    """修订版本号非法（非递增/非正整数等）。"""


class ZeroDenominatorError(TriangleError):
    """因子分母为零且未配置零分母策略。"""
