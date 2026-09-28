"""Canonical, order-independent JSON reporting.

All per-id maps are emitted sorted by key and Decimal values as fixed
two-place strings, so reordering any configuration or input collection
produces byte-identical reports.
"""

import json

from .models import EngineResult, PeriodResult


def _money(value) -> str:
    return format(value, ".2f")


def _sorted_money_map(values: dict) -> dict[str, str]:
    return {key: _money(values[key]) for key in sorted(values)}


def _state_dict(state) -> dict:
    return {
        "period_index": state.period_index,
        "accounts": {
            "interest": _money(state.interest_cash),
            "principal": _money(state.principal_cash),
            "reserve": _money(state.reserve_cash),
        },
        "tranche_balances": _sorted_money_map(state.tranche_balances),
        "tranche_interest_due": _sorted_money_map(
            state.tranche_interest_due
        ),
        "fees_paid": _sorted_money_map(state.fees_paid),
        "interest_paid": _sorted_money_map(state.interest_paid),
        "principal_paid": _sorted_money_map(state.principal_paid),
        "losses_allocated": _sorted_money_map(state.losses_allocated),
        "interest_shortfall": _sorted_money_map(
            state.interest_shortfall
        ),
        "undistributed_interest": _money(state.undistributed_interest),
        "undistributed_principal": _money(state.undistributed_principal),
    }


def period_to_dict(result: PeriodResult) -> dict:
    return {
        "period_index": result.period_index,
        "profile_id": result.profile_id,
        "fired_triggers": list(result.fired_triggers),
        "beginning_state": _state_dict(result.beginning_state),
        "ending_state": _state_dict(result.ending_state),
        "audit": [
            {
                "step": entry.step,
                "action": entry.action,
                "account": entry.account,
                "before": _money(entry.before),
                "amount": _money(entry.amount),
                "after": _money(entry.after),
                "memo": entry.memo,
                "direction": entry.direction,
            }
            for entry in result.audit
        ],
    }


def engine_to_dict(result: EngineResult) -> dict:
    return {
        "periods": [
            period_to_dict(period_result)
            for period_result in sorted(
                result.periods, key=lambda item: item.period_index
            )
        ]
    }


def to_canonical_json(result: EngineResult) -> str:
    return json.dumps(
        engine_to_dict(result),
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    )
