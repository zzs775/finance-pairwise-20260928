"""规范 JSON 序列化：因子、投影、修订结果的确定性输出。"""

import json
from decimal import Decimal


def to_canonical_json(result) -> str:
    """投影结果 -> 排序键、无多余空白差异的规范 JSON 字符串。"""
    return json.dumps(
        result_to_plain(result),
        sort_keys=True,
        ensure_ascii=False,
        separators=(",", ":"),
    )


def result_to_plain(result) -> dict:
    """投影结果 -> 纯 Python 内建类型（Decimal 转字符串保留尺度）。"""
    from .projection import ProjectionResult

    if not isinstance(result, ProjectionResult):
        raise TypeError("仅支持 ProjectionResult")

    factors = []
    for step in result.factors:
        factors.append(
            {
                "denominator": _s(step.denominator),
                "dev_from": step.dev_from,
                "factor": _s(step.factor),
                "method": step.method,
                "numerator": _s(step.numerator),
                "raw_ratio": _s(step.raw_ratio),
                "ratios": [_s(r) for r in step.ratios],
                "units": [
                    [u[0], _s(u[1]), _s(u[2])] for u in step.units
                ],
                "used_units": step.used_units,
            }
        )

    origins = {}
    for i, origin in result.origins.items():
        origins[str(origin.label)] = {
            "label": origin.label,
            "paid": _s(origin.paid),
            "projected": [_s(v) for v in origin.projected],
            "reserve": _s(origin.reserve),
            "ultimate": _s(origin.ultimate),
        }

    return {
        "factors": factors,
        "origins": origins,
        "tail_factor": _s(result.tail_factor),
        "total_reserve": _s(result.total_reserve()),
    }


def _s(value: Decimal) -> str:
    return format(value, "f")
