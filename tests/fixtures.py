"""Shared small deal builders used by the waterfall tests."""

from decimal import Decimal

from abs_waterfall.models import (
    Fee,
    PoolPeriod,
    PriorityProfile,
    ReserveSpec,
    Tranche,
    Trigger,
    WaterfallConfig,
    PrincipalMode,
)


def build_config(
    *,
    trigger: Trigger | None = None,
    mode_normal: PrincipalMode = PrincipalMode.SEQUENTIAL,
    mode_trigger: PrincipalMode = PrincipalMode.PRO_RATA,
    allow_switch: bool = True,
    releasable: bool = True,
    target_rate: str = "0.02",
    min_balance: str = "5.00",
    fees: tuple[Fee, ...] | None = None,
    tranches: tuple[Tranche, ...] | None = None,
) -> WaterfallConfig:
    if tranches is None:
        tranches = (
            Tranche("A", 1, Decimal("700.00"), Decimal("0.05")),
            Tranche("B", 2, Decimal("200.00"), Decimal("0.08")),
            Tranche("EQ", 3, Decimal("100.00"), Decimal("0")),
        )
    order = tuple(tranche.id for tranche in tranches)
    profiles = (
        PriorityProfile("normal", mode_normal, order, order),
        PriorityProfile("lockout", mode_trigger, order, order),
    )
    if fees is None:
        fees = (Fee("servicing", rate=Decimal("0.01")),)
    reserve = ReserveSpec(
        target_rate=Decimal(target_rate),
        min_balance=Decimal(min_balance),
        releasable=releasable,
    )
    triggers = (trigger,) if trigger is not None else ()
    return WaterfallConfig(
        tranches=tranches,
        fees=fees,
        reserve=reserve,
        triggers=triggers,
        profiles=profiles,
        default_profile_id="normal",
        allow_interest_principal_switch=allow_switch,
    )


def dlq_trigger(threshold: str = "0.50") -> Trigger:
    return Trigger(
        id="dlq_trigger",
        indicator="dlq",
        operator=">=",
        threshold=Decimal(threshold),
        profile_id="lockout",
    )


def period(
    index: int,
    *,
    pool: str = "1000.00",
    interest: str = "60.00",
    principal: str = "100.00",
    losses: str = "0.00",
    dlq: str = "0.10",
) -> PoolPeriod:
    return PoolPeriod(
        index=index,
        beginning_pool_balance=Decimal(pool),
        interest_collections=Decimal(interest),
        principal_collections=Decimal(principal),
        realized_losses=Decimal(losses),
        indicators={"dlq": Decimal(dlq)},
    )
