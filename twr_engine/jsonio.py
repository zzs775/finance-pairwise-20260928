"""Deterministic canonical JSON for Decimal-bearing results.

Decimals serialize as canonical strings (never lossy floats), mappings sort
by key, and lists of records sort by their business key, so identical
semantic input always yields byte-identical output regardless of account
order or input chunking.
"""

from __future__ import annotations

import json
from dataclasses import asdict, is_dataclass
from datetime import date
from decimal import Decimal
from enum import Enum


def canonical_decimal(value: Decimal) -> str:
    """Render a Decimal in a fixed canonical textual form.

    Scientific notation is normalized to plain digits and trailing zeros are
    stripped while preserving the sign convention used by Decimal.
    """
    if value == 0:
        return "0"
    text = format(value, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text


def dumps(obj) -> str:
    """Serialize engine results to canonical, order-independent JSON text."""
    return json.dumps(
        _to_canonical(obj),
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    )


def _to_canonical(obj):
    if obj is None or isinstance(obj, bool):
        return obj
    if isinstance(obj, Decimal):
        return canonical_decimal(obj)
    if isinstance(obj, Enum):
        return obj.value
    if isinstance(obj, date):
        return obj.isoformat()
    if is_dataclass(obj):
        return _to_canonical(asdict(obj))
    if isinstance(obj, dict):
        return {str(key): _to_canonical(value) for key, value in obj.items()}
    if isinstance(obj, (list, tuple, frozenset, set)):
        return [_to_canonical(item) for item in obj]
    if isinstance(obj, (int, str)):
        return obj
    raise TypeError(f"cannot canonically serialize {type(obj)!r}")
