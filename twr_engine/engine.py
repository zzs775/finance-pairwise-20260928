"""Top-level orchestration: raw input -> account and composite results."""

from __future__ import annotations

from dataclasses import dataclass, field

from .account import compute_account
from .composite import aggregate_composites
from .intervals import build_all_sub_periods
from .models import AccountResult, CompositeResult, EngineInput
from .normalize import normalize_input


@dataclass
class EngineOutput:
    normalized: EngineInput
    accounts: dict[str, AccountResult] = field(default_factory=dict)
    composites: dict[str, CompositeResult] = field(default_factory=dict)


def run(raw: EngineInput) -> EngineOutput:
    """Normalize input, split intervals and compute all returns."""
    normalized = normalize_input(raw)
    account_periods = build_all_sub_periods(normalized)
    accounts = {
        account_id: compute_account(account_id, periods)
        for account_id, periods in account_periods.items()
    }
    composites = aggregate_composites(normalized, account_periods)
    return EngineOutput(
        normalized=normalized,
        accounts=accounts,
        composites=composites,
    )
