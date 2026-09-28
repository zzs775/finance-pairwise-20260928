"""规范 JSON 序列化（骨架）。"""

from __future__ import annotations

import dataclasses
import json
from decimal import Decimal
from typing import Any


def to_canonical(obj: Any) -> str:
    return json.dumps(
        _normalize(obj),
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    )


def decimal_to_text(value: Decimal) -> str:
    if not value.is_finite():
        raise ValueError(f"non-finite decimal: {value}")
    return format(value, "f")


def _normalize(obj: Any) -> Any:
    if dataclasses.is_dataclass(obj) and not isinstance(obj, type):
        return {
            key: _normalize(value)
            for key, value in sorted(dataclasses.asdict(obj).items())
        }
    if isinstance(obj, Decimal):
        return decimal_to_text(obj)
    if isinstance(obj, dict):
        return {str(key): _normalize(value) for key, value in sorted(obj.items())}
    if isinstance(obj, (list, tuple)):
        return [_normalize(item) for item in obj]
    if isinstance(obj, (str, int, bool)) or obj is None:
        return obj
    if isinstance(obj, float):
        if obj != obj or obj in (float("inf"), float("-inf")):
            raise ValueError(f"non-finite float: {obj}")
        return decimal_to_text(Decimal(str(obj)))
    if hasattr(obj, "value"):
        return _normalize(obj.value)
    raise TypeError(f"cannot canonicalize type: {type(obj).__name__}")
