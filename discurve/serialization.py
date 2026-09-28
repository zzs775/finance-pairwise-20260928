"""Canonical JSON encoding (deterministic across input ordering)."""

import json
from dataclasses import asdict, is_dataclass
from datetime import date
from decimal import Decimal
from typing import Any


def _canonical_decimal(value: Decimal) -> str:
    if not value.is_finite():
        raise ValueError("cannot serialize non-finite Decimal")
    normalized = value.normalize()
    if normalized == 0:
        return "0"
    text = format(normalized, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text


def to_jsonable(value: Any) -> Any:
    if isinstance(value, Decimal):
        return _canonical_decimal(value)
    if isinstance(value, date):
        return value.isoformat()
    if is_dataclass(value):
        return {
            key: to_jsonable(item)
            for key, item in asdict(value, dict_factory=_ordered_dict_factory).items()
        }
    if isinstance(value, dict):
        return {
            key: to_jsonable(value[key]) for key in sorted(value, key=str)
        }
    if isinstance(value, (list, tuple)):
        return [to_jsonable(item) for item in value]
    if isinstance(value, (str, int, bool)) or value is None:
        return value
    if isinstance(value, float):
        raise TypeError("raw floats are not allowed; use Decimal")
    raise TypeError(f"cannot canonically serialize {type(value)!r}")


def _ordered_dict_factory(items):
    return dict(sorted(items, key=lambda pair: pair[0]))


def canonical_dumps(value: Any) -> str:
    return json.dumps(
        to_jsonable(value),
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    )
