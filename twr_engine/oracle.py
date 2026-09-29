"""Mechanical, independent recomputation used to verify engine results.

The oracle deliberately re-derives everything from account sub-period detail
with plain multiplication instead of reusing the engine's aggregation code.
"""

from __future__ import annotations

from decimal import Decimal

from .errors import CapitalStatus


def oracle_twr(rates: list[Decimal]) -> Decimal:
    """Return ``product(1+r) - 1`` computed with an explicit loop."""
    product = Decimal("1")
    for rate in rates:
        product = product * (Decimal("1") + Decimal(rate))
    return product - Decimal("1")


def verify_account(result) -> bool:
    """Check ``1 + TWR == product(1 + sub_period rate)`` exactly.

    Accounts whose sub-periods include a zero/negative base carry no TWR and
    are verified only for consistent status flags.
    """
    for period in result.sub_periods:
        expected_status = _classify(period.begin_capital)
        if period.status != expected_status:
            return False
        if expected_status != CapitalStatus.OK.value:
            if period.rate is not None:
                return False
        else:
            if period.rate != period.pnl / period.begin_capital:
                return False

    if result.status != CapitalStatus.OK.value:
        return result.twr is None

    rates = [period.rate for period in result.sub_periods]
    return result.twr == oracle_twr(rates)


def recompute_composite(raw) -> dict:
    """Rebuild composite returns independently from raw daily account detail.

    Daily P&L is rebuilt straight from valuations and external flows with the
    accounting identity; composite segments are cut independently at
    membership and external-flow boundaries and linked geometrically.
    """
    from .models import CashFlowTiming

    accounts = {account.account_id for account in raw.accounts}
    del accounts

    flow_table: dict[tuple[str, object], dict[str, Decimal]] = {}
    for flow in raw.cash_flows:
        bucket = flow_table.setdefault(
            (flow.account_id, flow.day),
            {CashFlowTiming.OPEN: Decimal(0), CashFlowTiming.CLOSE: Decimal(0)},
        )
        bucket[flow.timing] += flow.amount

    valuations: dict[str, dict[object, object]] = {}
    for valuation in raw.valuations:
        valuations.setdefault(valuation.account_id, {})[valuation.day] = valuation

    grouped: dict[str, list] = {}
    for membership in raw.memberships:
        grouped.setdefault(membership.composite_id, []).append(membership)

    rebuilt: dict[str, dict] = {}
    for composite_id in sorted(grouped):
        memberships = grouped[composite_id]
        active_days = sorted(
            {
                day
                for membership in memberships
                for day in valuations.get(membership.account_id, {})
                if membership.start_day <= day <= membership.end_day
            }
        )

        def members(day):
            return frozenset(
                m.account_id
                for m in memberships
                if m.start_day <= day <= m.end_day
            )

        segments: list[dict] = []
        segment_start = None
        previous_day = None
        previous_members = frozenset()

        for day in active_days:
            current_members = members(day)
            new_segment = (
                segment_start is None
                or current_members != previous_members
                or any(
                    flow_table.get(
                        (account_id, day), {}
                    ).get(CashFlowTiming.OPEN, Decimal(0)) != 0
                    or flow_table.get(
                        (account_id, previous_day), {}
                    ).get(CashFlowTiming.CLOSE, Decimal(0)) != 0
                    for account_id in current_members
                )
                or any(
                    _oracle_begin_capital(
                        valuations.get(account_id, {}).get(day),
                        flow_table.get((account_id, day), {}),
                    ) <= 0
                    or _oracle_begin_capital(
                        valuations.get(account_id, {}).get(previous_day),
                        flow_table.get((account_id, previous_day), {}),
                    ) <= 0
                    for account_id in current_members
                )
            )
            if new_segment and segment_start is not None:
                segments.append(
                    _oracle_segment(
                        memberships, valuations, flow_table,
                        segment_start, previous_day,
                    )
                )
                segment_start = day
            elif segment_start is None:
                segment_start = day
            previous_members = current_members
            previous_day = day

        if segment_start is not None:
            segments.append(
                _oracle_segment(
                    memberships, valuations, flow_table,
                    segment_start, active_days[-1],
                )
            )

        linked = oracle_twr([segment["rate"] for segment in segments])
        rebuilt[composite_id] = {"segments": segments, "twr": linked}
    return rebuilt


def _oracle_segment(memberships, valuations, flow_table,
                    start_day, end_day) -> dict:
    weights: dict[str, Decimal] = {}
    total_capital = Decimal(0)
    contributions: list[tuple[str, Decimal, Decimal]] = []

    for membership in memberships:
        if not (membership.start_day <= start_day
                and membership.end_day >= end_day):
            continue
        account_valuations = valuations.get(membership.account_id, {})
        if start_day not in account_valuations:
            continue
        first = account_valuations[start_day]
        begin_capital = _oracle_begin_capital(
            first, flow_table.get((membership.account_id, start_day), {})
        )
        pnl = Decimal(0)
        day = start_day
        while day <= end_day:
            valuation = account_valuations.get(day)
            if valuation is not None:
                flows = flow_table.get((membership.account_id, day), {})
                day_open = flows.get("open", Decimal(0))
                day_close = flows.get("close", Decimal(0))
                pnl += (
                    valuation.closing - valuation.opening
                    - day_open - day_close
                )
            day = _add_day(day)
        rate = pnl / begin_capital if begin_capital > 0 else Decimal(0)
        contributions.append((membership.account_id, begin_capital, rate))
        total_capital += begin_capital

    segment_rate = Decimal(0)
    for account_id, capital, rate in contributions:
        if total_capital > 0:
            weight = capital / total_capital
            weights[account_id] = weight
            segment_rate += weight * rate

    return {
        "start_day": start_day,
        "end_day": end_day,
        "rate": segment_rate,
        "weights": weights,
    }


def _oracle_begin_capital(valuation, flows) -> Decimal:
    if valuation is None:
        return Decimal(0)
    return valuation.opening + flows.get("open", Decimal(0))


def _add_day(day):
    from datetime import timedelta

    return day + timedelta(days=1)


def _classify(begin_capital: Decimal) -> str:
    if begin_capital < 0:
        return CapitalStatus.NEGATIVE_BASE.value
    if begin_capital == 0:
        return CapitalStatus.ZERO_BASE.value
    return CapitalStatus.OK.value
