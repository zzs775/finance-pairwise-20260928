"""Split valuation history into external-cash-flow-delimited sub-periods.

Frozen intra-day order on every valuation day::

    opening valuation -> opening cash flow -> period P&L
                      -> closing cash flow -> closing valuation

An opening flow starts a new sub-period on that day; a closing flow ends the
current sub-period on that day. The deployed capital for a day is therefore
``opening + opening_flow`` and the accounting identity is::

    closing = opening + opening_flow + pnl + closing_flow
"""

from __future__ import annotations

from decimal import Decimal

from .errors import CapitalStatus
from .models import (
    CashFlowTiming,
    EngineInput,
    SubPeriod,
)


def capital_status(begin_capital: Decimal) -> str:
    """Classify a capital base as ok / zero_base / negative_base."""
    if begin_capital < 0:
        return CapitalStatus.NEGATIVE_BASE.value
    if begin_capital == 0:
        return CapitalStatus.ZERO_BASE.value
    return CapitalStatus.OK.value


def split_account_sub_periods(account_id: str, valuations: list,
                              cash_flows: list) -> list[SubPeriod]:
    """Return ordered sub-periods for one account under the frozen convention."""
    if not valuations:
        return []

    flows: dict[object, dict[CashFlowTiming, Decimal]] = {}
    for flow in cash_flows:
        per_day = flows.setdefault(
            flow.day,
            {CashFlowTiming.OPEN: Decimal(0), CashFlowTiming.CLOSE: Decimal(0)},
        )
        per_day[flow.timing] += flow.amount

    sub_periods: list[SubPeriod] = []
    pending = _PendingSegment()
    previous_closing = None
    previous_ended = False

    for valuation in sorted(valuations, key=lambda item: item.day):
        day = valuation.day
        open_flow = flows.get(day, {}).get(CashFlowTiming.OPEN, Decimal(0))
        close_flow = flows.get(day, {}).get(CashFlowTiming.CLOSE, Decimal(0))

        day_begin = valuation.opening + open_flow
        if previous_closing is not None and (
            previous_closing != valuation.opening
            or previous_closing <= 0
            or valuation.opening <= 0
            or previous_ended
        ):
            pending.close(sub_periods, account_id)
        elif pending.active and day_begin <= 0:
            pending.close(sub_periods, account_id)

        pnl = (
            valuation.pnl
            if valuation.pnl is not None
            else valuation.closing - valuation.opening - open_flow - close_flow
        )

        if close_flow != 0:
            if pending.active:
                pending.close(sub_periods, account_id)
            previous_closing = valuation.closing
            previous_ended = True

            pending.start(day, day_begin, open_flow + close_flow)
            pending.accumulate(day, pnl, valuation.closing)
            pending.close(sub_periods, account_id)
            continue

        if not pending.active:
            pending.start(day, valuation.opening + open_flow, open_flow)
        elif open_flow != 0:
            pending.close(sub_periods, account_id)
            pending.start(day, valuation.opening + open_flow, open_flow)
        elif day_begin <= 0:
            pending.close(sub_periods, account_id)
            pending.start(day, day_begin, open_flow)

        pending.accumulate(day, pnl, valuation.closing)
        previous_closing = valuation.closing
        previous_ended = False

    pending.close(sub_periods, account_id)
    return sub_periods


def build_all_sub_periods(raw: EngineInput) -> dict[str, list[SubPeriod]]:
    """Split every account and key the result by account id."""
    valuations: dict[str, list] = {}
    for valuation in raw.valuations:
        valuations.setdefault(valuation.account_id, []).append(valuation)

    cash_flows: dict[str, list] = {}
    for flow in raw.cash_flows:
        cash_flows.setdefault(flow.account_id, []).append(flow)

    result: dict[str, list[SubPeriod]] = {}
    for account in raw.accounts:
        result[account.account_id] = split_account_sub_periods(
            account.account_id,
            valuations.get(account.account_id, []),
            cash_flows.get(account.account_id, []),
        )
    return result


class _PendingSegment:
    """Mutable accumulator for the sub-period currently being built."""

    __slots__ = (
        "active",
        "start_day",
        "end_day",
        "begin_capital",
        "external_flow",
        "pnl",
        "end_capital",
    )

    def __init__(self) -> None:
        self.active = False
        self.start_day = None
        self.end_day = None
        self.begin_capital = Decimal(0)
        self.external_flow = Decimal(0)
        self.pnl = Decimal(0)
        self.end_capital = Decimal(0)

    def start(self, day, begin_capital: Decimal,
              external_flow: Decimal) -> None:
        self.active = True
        self.start_day = day
        self.end_day = day
        self.begin_capital = begin_capital
        self.external_flow = external_flow
        self.pnl = Decimal(0)
        self.end_capital = Decimal(0)

    def accumulate(self, day, pnl: Decimal, end_capital: Decimal) -> None:
        self.end_day = day
        self.pnl += pnl
        self.end_capital = end_capital

    def close(self, target: list[SubPeriod], account_id: str) -> None:
        if not self.active:
            return
        status = capital_status(self.begin_capital)
        rate = None
        if status == CapitalStatus.OK.value:
            rate = self.pnl / self.begin_capital
        target.append(
            SubPeriod(
                account_id=account_id,
                start_day=self.start_day,
                end_day=self.end_day,
                begin_capital=self.begin_capital,
                external_flow=self.external_flow,
                pnl=self.pnl,
                end_capital=self.end_capital,
                rate=rate,
                status=status,
            )
        )
        self.active = False
