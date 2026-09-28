"""Static configuration and period state data types."""

from dataclasses import dataclass, field
from decimal import Decimal
from enum import Enum

from .money import ZERO


class Account(str, Enum):
    INTEREST = "interest"
    PRINCIPAL = "principal"
    RESERVE = "reserve"


class PrincipalMode(str, Enum):
    SEQUENTIAL = "sequential"
    PRO_RATA = "pro_rata"


@dataclass(frozen=True)
class Fee:
    id: str
    rate: Decimal = ZERO
    fixed_amount: Decimal = ZERO


@dataclass(frozen=True)
class Tranche:
    id: str
    seniority: int
    initial_balance: Decimal
    coupon_rate: Decimal


@dataclass(frozen=True)
class PriorityProfile:
    id: str
    principal_mode: PrincipalMode
    interest_order: tuple[str, ...] = ()
    principal_order: tuple[str, ...] = ()


@dataclass(frozen=True)
class ReserveSpec:
    target_rate: Decimal
    min_balance: Decimal = ZERO
    releasable: bool = True


@dataclass(frozen=True)
class Trigger:
    id: str
    indicator: str
    operator: str
    threshold: Decimal
    profile_id: str


@dataclass(frozen=True)
class WaterfallConfig:
    tranches: tuple[Tranche, ...]
    fees: tuple[Fee, ...]
    reserve: ReserveSpec
    triggers: tuple[Trigger, ...]
    profiles: tuple[PriorityProfile, ...]
    default_profile_id: str
    allow_interest_principal_switch: bool = True


@dataclass(frozen=True)
class PoolPeriod:
    index: int
    beginning_pool_balance: Decimal
    interest_collections: Decimal
    principal_collections: Decimal
    realized_losses: Decimal
    indicators: dict[str, Decimal] = field(default_factory=dict)


@dataclass
class PeriodState:
    period_index: int
    interest_cash: Decimal
    principal_cash: Decimal
    reserve_cash: Decimal
    tranche_balances: dict[str, Decimal]
    tranche_interest_due: dict[str, Decimal] = field(default_factory=dict)
    fees_paid: dict[str, Decimal] = field(default_factory=dict)
    interest_paid: dict[str, Decimal] = field(default_factory=dict)
    principal_paid: dict[str, Decimal] = field(default_factory=dict)
    losses_allocated: dict[str, Decimal] = field(default_factory=dict)
    interest_shortfall: dict[str, Decimal] = field(default_factory=dict)
    undistributed_interest: Decimal = ZERO
    undistributed_principal: Decimal = ZERO


@dataclass(frozen=True)
class AuditEntry:
    period_index: int
    step: str
    action: str
    account: str
    before: Decimal
    amount: Decimal
    after: Decimal
    memo: str = ""
    direction: str = "debit"


@dataclass
class PeriodResult:
    period_index: int
    beginning_state: PeriodState
    ending_state: PeriodState
    audit: list[AuditEntry]
    fired_triggers: tuple[str, ...]
    profile_id: str


@dataclass
class EngineResult:
    periods: list[PeriodResult]
