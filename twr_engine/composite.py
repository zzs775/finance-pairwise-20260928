"""Composite aggregation by recomputable beginning-capital weights.

Composite sub-periods are cut at every external-flow day and at every
membership change day. Within one composite sub-period the member set and
deployed capital are constant, so each member's rate is its P&L divided by its
beginning capital and the composite rate is a beginning-capital weighted
average of member rates.
"""

from __future__ import annotations

from decimal import Decimal

from .errors import CapitalStatus
from .models import (
    CashFlowTiming,
    CompositeResult,
    CompositeSubPeriod,
    EngineInput,
)


def members_on_day(memberships: list, day) -> frozenset[str]:
    """Return accounts whose closed membership interval covers ``day``."""
    return frozenset(
        item.account_id
        for item in memberships
        if item.start_day <= day <= item.end_day
    )


def aggregate_composites(raw: EngineInput,
                         account_periods: dict) -> dict[str, CompositeResult]:
    """Aggregate member account daily records per composite."""
    daily_by_account = _build_daily_records(raw)

    composites: dict[str, list] = {}
    for membership in raw.memberships:
        composites.setdefault(membership.composite_id, []).append(membership)

    results: dict[str, CompositeResult] = {}
    for composite_id in sorted(composites):
        memberships = composites[composite_id]
        results[composite_id] = _aggregate_one(
            composite_id, memberships, daily_by_account
        )
    return results


def _build_daily_records(raw: EngineInput) -> dict[str, list[dict]]:
    """Per-account ordered daily records: deployed capital, pnl and rate."""
    flows: dict[str, dict[object, dict[CashFlowTiming, Decimal]]] = {}
    for flow in raw.cash_flows:
        per_day = flows.setdefault(flow.account_id, {})
        timing = per_day.setdefault(
            flow.day,
            {CashFlowTiming.OPEN: Decimal(0), CashFlowTiming.CLOSE: Decimal(0)},
        )
        timing[flow.timing] += flow.amount

    valuations: dict[str, list] = {}
    for valuation in raw.valuations:
        valuations.setdefault(valuation.account_id, []).append(valuation)

    daily: dict[str, list[dict]] = {}
    for account_id, items in valuations.items():
        records: list[dict] = []
        for valuation in sorted(items, key=lambda item: item.day):
            per_day = flows.get(account_id, {}).get(valuation.day, {})
            open_flow = per_day.get(CashFlowTiming.OPEN, Decimal(0))
            close_flow = per_day.get(CashFlowTiming.CLOSE, Decimal(0))
            pnl = (
                valuation.pnl
                if valuation.pnl is not None
                else valuation.closing - valuation.opening - open_flow
                - close_flow
            )
            begin_capital = valuation.opening + open_flow
            status = CapitalStatus.OK.value
            rate = None
            if begin_capital < 0:
                status = CapitalStatus.NEGATIVE_BASE.value
            elif begin_capital == 0:
                status = CapitalStatus.ZERO_BASE.value
            else:
                rate = pnl / begin_capital
            records.append(
                {
                    "day": valuation.day,
                    "begin_capital": begin_capital,
                    "open_flow": open_flow,
                    "close_flow": close_flow,
                    "pnl": pnl,
                    "rate": rate,
                    "status": status,
                }
            )
        daily[account_id] = records
    return daily


def _aggregate_one(composite_id: str, memberships: list,
                   daily_by_account: dict[str, list[dict]]) -> CompositeResult:
    active_days = sorted(
        {
            record["day"]
            for membership in memberships
            for record in daily_by_account.get(membership.account_id, [])
            if membership.start_day <= record["day"] <= membership.end_day
        }
    )

    sub_periods: list[CompositeSubPeriod] = []
    if not active_days:
        return CompositeResult(composite_id=composite_id,
                               sub_periods=sub_periods, twr=Decimal(0))

    day_index: dict[object, dict[str, dict]] = {}
    for membership in memberships:
        for record in daily_by_account.get(membership.account_id, []):
            day_index.setdefault(record["day"], {})[membership.account_id] = record

    segment_start = active_days[0]
    previous_day = None

    for day in active_days:
        members = members_on_day(memberships, day)
        boundary = False
        if previous_day is not None:
            previous_members = members_on_day(memberships, previous_day)
            if members != previous_members:
                boundary = True
            else:
                for account_id in members:
                    record = day_index.get(day, {}).get(account_id)
                    previous_record = day_index.get(previous_day, {}).get(
                        account_id
                    )
                    if record is None or previous_record is None:
                        if (record is None) != (previous_record is None):
                            boundary = True
                            break
                        continue
                    if (record["open_flow"] != 0
                            or previous_record["close_flow"] != 0
                            or record["begin_capital"] <= 0
                            or previous_record["begin_capital"] <= 0):
                        boundary = True
                        break
        if boundary:
            sub_periods.append(
                _build_segment(
                    composite_id, memberships, daily_by_account,
                    segment_start, previous_day,
                )
            )
            segment_start = day
        previous_day = day

    sub_periods.append(
        _build_segment(
            composite_id, memberships, daily_by_account,
            segment_start, active_days[-1],
        )
    )

    factor = Decimal(1)
    for segment in sub_periods:
        factor *= Decimal(1) + segment.rate
    statuses = {segment.status for segment in sub_periods}
    if CapitalStatus.NEGATIVE_BASE.value in statuses:
        status = CapitalStatus.NEGATIVE_BASE.value
    elif CapitalStatus.ZERO_BASE.value in statuses:
        status = CapitalStatus.ZERO_BASE.value
    else:
        status = CapitalStatus.OK.value
    return CompositeResult(
        composite_id=composite_id,
        sub_periods=sub_periods,
        twr=factor - Decimal(1) if status == CapitalStatus.OK.value else None,
        status=status,
    )


def _build_segment(composite_id: str, memberships: list,
                   daily_by_account: dict[str, list[dict]],
                   start_day, end_day) -> CompositeSubPeriod:
    """Weight member segment rates by beginning capital on ``start_day``."""
    members = members_on_day(memberships, start_day)
    total_capital = Decimal(0)
    contributions: list[tuple[str, Decimal, Decimal]] = []
    for account_id in sorted(members):
        records = [
            record
            for record in daily_by_account.get(account_id, [])
            if start_day <= record["day"] <= end_day
        ]
        if not records:
            continue
        pnl = sum((record["pnl"] for record in records), Decimal(0))
        begin_capital = records[0]["begin_capital"]
        rate = pnl / begin_capital if begin_capital > 0 else Decimal(0)
        total_capital += begin_capital
        contributions.append((account_id, begin_capital, rate))

    weights: dict[str, Decimal] = {}
    rate = Decimal(0)
    status = CapitalStatus.OK.value
    if total_capital < 0:
        status = CapitalStatus.NEGATIVE_BASE.value
    elif total_capital == 0:
        status = CapitalStatus.ZERO_BASE.value
    if total_capital > 0:
        for account_id, begin_capital, member_rate in contributions:
            weight = begin_capital / total_capital
            weights[account_id] = weight
            if member_rate is not None:
                rate += weight * member_rate

    return CompositeSubPeriod(
        composite_id=composite_id,
        start_day=start_day,
        end_day=end_day,
        rate=rate,
        status=status,
        weights=weights,
    )
