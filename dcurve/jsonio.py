"""Canonical JSON (de)serialisation preserving Decimal scale exactly.

Decimals are emitted as their exact string form (``str(Decimal)``), never
as float, so trailing zeros and the chosen scale survive a round trip.
Output uses fixed key ordering and compact separators, guaranteeing that
re-ordering input instruments yields byte-identical JSON.
"""

import json
from datetime import date
from decimal import Decimal

from .bootstrap import BondValuation, BootstrapResult, Diagnostic
from .dates import (
    BusinessDayConvention,
    DayCountConvention,
    StubType,
    Tenor,
)
from .errors import QuoteValidationError
from .instruments import (
    BondCashflows,
    BondPortfolio,
    DepositQuote,
    FRAQuote,
    QuoteBook,
    SwapQuote,
)


def _decimal_string(value: Decimal) -> str:
    return format(value, "f")


def dumps(payload: object) -> str:
    return json.dumps(
        payload,
        sort_keys=False,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    )


def loads(text: str):
    return json.loads(text)


def diagnostic_to_dict(diagnostic: Diagnostic) -> dict:
    return {
        "id": diagnostic.id,
        "kind": diagnostic.kind,
        "maturity": diagnostic.maturity,
        "model_pv": _decimal_string(diagnostic.model_pv),
        "market_target": _decimal_string(diagnostic.market_target),
        "residual": _decimal_string(diagnostic.residual),
        "iterations": diagnostic.iterations,
    }


def valuation_to_dict(valuation: BondValuation) -> dict:
    return {
        "id": valuation.id,
        "present_value": _decimal_string(valuation.present_value),
    }


def result_to_payload(result: BootstrapResult,
                      valuations: tuple[BondValuation, ...] = ()) -> dict:
    curve = result.curve
    return {
        "version_id": curve.version_id,
        "value_date": curve.value_date.isoformat(),
        "nodes": [
            {"date": node.date.isoformat(),
             "discount_factor": _decimal_string(node.discount_factor)}
            for node in curve.nodes
        ],
        "diagnostics": [
            diagnostic_to_dict(item) for item in result.diagnostics
        ],
        "bond_valuations": [
            valuation_to_dict(item) for item in valuations
        ],
    }


def _parse_date(text: str, field_name: str) -> date:
    if not isinstance(text, str):
        raise QuoteValidationError(f"{field_name} must be an ISO date string")
    try:
        return date.fromisoformat(text)
    except ValueError as exc:
        raise QuoteValidationError(
            f"{field_name} is not a valid ISO date: {text!r}") from exc


def _parse_decimal(text: str, field_name: str) -> Decimal:
    try:
        value = Decimal(str(text))
    except Exception as exc:  # InvalidOperation
        raise QuoteValidationError(
            f"{field_name} is not a valid decimal: {text!r}") from exc
    if not value.is_finite():
        raise QuoteValidationError(
            f"{field_name} must be finite: {text!r}")
    return value


def _common(entry: dict, names: tuple[str, ...]):
    missing = [name for name in names if name not in entry]
    if missing:
        raise QuoteValidationError(f"missing fields {missing} in {entry!r}")


def _deposit_from_dict(entry: dict) -> DepositQuote:
    _common(entry, ("id", "start", "maturity", "rate", "day_count"))
    notional = _parse_decimal(
        entry.get("notional", "1"), f"deposit {entry.get('id')!r}.notional")
    return DepositQuote(
        id=entry["id"],
        start=_parse_date(entry["start"], f"deposit {entry['id']!r}.start"),
        maturity=_parse_date(
            entry["maturity"], f"deposit {entry['id']!r}.maturity"),
        rate=_parse_decimal(entry["rate"], f"deposit {entry['id']!r}.rate"),
        day_count=DayCountConvention(entry["day_count"]),
        notional=notional,
    )


def _fra_from_dict(entry: dict) -> FRAQuote:
    _common(entry, ("id", "start", "maturity", "rate", "day_count"))
    notional = _parse_decimal(
        entry.get("notional", "1"), f"fra {entry.get('id')!r}.notional")
    return FRAQuote(
        id=entry["id"],
        start=_parse_date(entry["start"], f"fra {entry['id']!r}.start"),
        maturity=_parse_date(
            entry["maturity"], f"fra {entry['id']!r}.maturity"),
        rate=_parse_decimal(entry["rate"], f"fra {entry['id']!r}.rate"),
        day_count=DayCountConvention(entry["day_count"]),
        notional=notional,
    )


def _swap_from_dict(entry: dict) -> SwapQuote:
    _common(entry, ("id", "effective", "maturity", "payment_tenor",
                    "fixed_rate", "day_count", "calendar",
                    "business_day_convention", "stub"))
    notional = _parse_decimal(
        entry.get("notional", "1"), f"swap {entry.get('id')!r}.notional")
    return SwapQuote(
        id=entry["id"],
        effective=_parse_date(
            entry["effective"], f"swap {entry['id']!r}.effective"),
        maturity=_parse_date(
            entry["maturity"], f"swap {entry['id']!r}.maturity"),
        payment_tenor=Tenor.parse(entry["payment_tenor"]),
        fixed_rate=_parse_decimal(
            entry["fixed_rate"], f"swap {entry['id']!r}.fixed_rate"),
        day_count=DayCountConvention(entry["day_count"]),
        calendar_name=entry["calendar"],
        business_day_convention=BusinessDayConvention(
            entry["business_day_convention"]),
        stub=StubType(entry["stub"]),
        notional=notional,
    )


def book_from_payload(payload: dict) -> QuoteBook:
    _common(payload, ("value_date",))
    deposits = tuple(_deposit_from_dict(dict(entry))
                     for entry in payload.get("deposits", []))
    fras = tuple(_fra_from_dict(dict(entry))
                 for entry in payload.get("fras", []))
    swaps = tuple(_swap_from_dict(dict(entry))
                  for entry in payload.get("swaps", []))
    return QuoteBook(
        value_date=_parse_date(payload["value_date"], "value_date"),
        deposits=deposits,
        fras=fras,
        swaps=swaps,
    )


def bonds_from_payload(payload: dict) -> BondPortfolio:
    bonds = []
    for entry in payload.get("bonds", []):
        _common(entry, ("id", "flows"))
        flows = tuple(
            (_parse_date(item[0], f"bond {entry['id']!r} flow date"),
             _parse_decimal(item[1], f"bond {entry['id']!r} flow amount"))
            for item in entry["flows"]
        )
        bonds.append(BondCashflows(id=entry["id"], flows=flows))
    return BondPortfolio(bonds=tuple(bonds))
