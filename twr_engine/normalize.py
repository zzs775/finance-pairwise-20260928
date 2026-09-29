"""Idempotent normalization of unordered, possibly duplicated input.

Records are deduplicated by their business key (exact duplicates collapse to
one), validated, and returned in a deterministic order so that account order
or file chunking never affects results.
"""

from __future__ import annotations

from decimal import Decimal

from .errors import (
    DuplicateValuationError,
    InvertedMembershipError,
    UnattributedCashFlowError,
)
from .models import (
    Account,
    CashFlow,
    CashFlowTiming,
    EngineInput,
    Membership,
    Valuation,
)


def _as_decimal(value: Decimal | int | str) -> Decimal:
    """Coerce numeric inputs to Decimal and drop a negative zero sign."""
    if isinstance(value, Decimal):
        result = value
    else:
        result = Decimal(str(value))
    if result == 0:
        return Decimal(0)
    return result


def normalize_input(raw: EngineInput) -> EngineInput:
    """Validate, de-duplicate by business key and deterministically order input.

    - Valuation key: ``(account, day)``; conflicting duplicates raise.
    - Cash flow key: ``(account, day, timing, amount)``; exact duplicates
      collapse, same-day same-timing flows are summed; flows without a known
      account raise :class:`UnattributedCashFlowError`.
    - Membership key: the full interval tuple; inverted intervals raise.
    """
    accounts = {_coerce_account(item) for item in raw.accounts}
    account_ids = {item.account_id for item in accounts}

    valuations = _normalize_valuations(raw.valuations)
    cash_flows = _normalize_cash_flows(raw.cash_flows, account_ids)
    memberships = _normalize_memberships(raw.memberships)

    return EngineInput(
        valuations=valuations,
        cash_flows=cash_flows,
        accounts=sorted(accounts, key=lambda item: item.account_id),
        memberships=memberships,
    )


def _coerce_account(item: Account) -> Account:
    return Account(account_id=str(item.account_id))


def _normalize_valuations(items: list) -> list[Valuation]:
    unique: dict[tuple[str, object], Valuation] = {}
    for raw_item in items:
        item = _coerce_valuation(raw_item)
        key = (item.account_id, item.day)
        existing = unique.get(key)
        if existing is not None and existing != item:
            raise DuplicateValuationError(
                f"conflicting valuations for account {item.account_id!r} "
                f"on {item.day}"
            )
        unique[key] = item
    return [unique[key] for key in sorted(unique, key=lambda k: (k[0], k[1]))]


def _coerce_valuation(item: Valuation) -> Valuation:
    if isinstance(item, Valuation):
        return Valuation(
            account_id=item.account_id,
            day=item.day,
            opening=_as_decimal(item.opening),
            closing=_as_decimal(item.closing),
            pnl=None if item.pnl is None else _as_decimal(item.pnl),
        )
    return Valuation(
        account_id=str(item.account_id),
        day=item.day,
        opening=_as_decimal(item.opening),
        closing=_as_decimal(item.closing),
        pnl=None if item.pnl is None else _as_decimal(item.pnl),
    )


def _normalize_cash_flows(items: list, account_ids: set[str]) -> list[CashFlow]:
    exact: set[tuple[str, object, Decimal, CashFlowTiming]] = set()
    totals: dict[tuple[str, object, CashFlowTiming], Decimal] = {}
    for raw_item in items:
        item = _coerce_cash_flow(raw_item)
        if item.account_id not in account_ids:
            raise UnattributedCashFlowError(
                f"cash flow for unknown account {item.account_id!r} on "
                f"{item.day}"
            )
        dedup_key = (item.account_id, item.day, item.amount, item.timing)
        if dedup_key in exact:
            continue
        exact.add(dedup_key)
        agg_key = (item.account_id, item.day, item.timing)
        totals[agg_key] = totals.get(agg_key, Decimal(0)) + item.amount

    ordered = sorted(
        totals,
        key=lambda key: (key[0], key[1], key[2].value),
    )
    return [
        CashFlow(
            account_id=key[0],
            day=key[1],
            amount=_as_decimal(totals[key]),
            timing=key[2],
        )
        for key in ordered
    ]


def _coerce_cash_flow(item: CashFlow) -> CashFlow:
    if isinstance(item, CashFlow):
        timing = item.timing
    else:
        timing = item.timing
    if not isinstance(timing, CashFlowTiming):
        timing = CashFlowTiming(str(timing))
    return CashFlow(
        account_id=str(item.account_id),
        day=item.day,
        amount=_as_decimal(item.amount),
        timing=timing,
    )


def _normalize_memberships(items: list) -> list[Membership]:
    unique: set[Membership] = set()
    for raw_item in items:
        item = _coerce_membership(raw_item)
        if item.start_day > item.end_day:
            raise InvertedMembershipError(
                f"membership of account {item.account_id!r} in composite "
                f"{item.composite_id!r} is inverted: {item.start_day} > "
                f"{item.end_day}"
            )
        unique.add(item)
    return sorted(
        unique,
        key=lambda item: (
            item.composite_id,
            item.account_id,
            item.start_day,
            item.end_day,
        ),
    )


def _coerce_membership(item: Membership) -> Membership:
    if isinstance(item, Membership):
        return item
    return Membership(
        composite_id=str(item.composite_id),
        account_id=str(item.account_id),
        start_day=item.start_day,
        end_day=item.end_day,
    )
