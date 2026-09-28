"""规范 JSON：与输入顺序、字典遍历和并行度无关。"""

from __future__ import annotations

import json
from dataclasses import fields, is_dataclass
from decimal import Decimal
from enum import Enum

from .types import Report


def to_canonical_json(report: Report) -> str:
    payload = _to_plain(report)
    return json.dumps(
        payload,
        sort_keys=True,
        ensure_ascii=False,
        separators=(",", ":"),
    )


def _to_plain(value):
    if isinstance(value, Decimal):
        return format(value, "f")
    if isinstance(value, Enum):
        return value.value
    if is_dataclass(value):
        return {
            field.name: _to_plain(getattr(value, field.name))
            for field in fields(value)
        }
    if isinstance(value, (list, tuple)):
        return [_to_plain(item) for item in value]
    if isinstance(value, dict):
        return {key: _to_plain(item) for key, item in value.items()}
    return value
