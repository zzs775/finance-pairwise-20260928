"""Single-period waterfall: fees, interest, reserve, principal, losses.

Interest cash and principal cash live in strictly separated accounts.
Interest collections may cover fees/interest first; shortfalls switch to
principal cash only when the deal explicitly allows it. Principal cash may
never be re-routed as interest when switching is disabled.

Any failure inside a period rolls the ledger back to its opening snapshot,
so all account and tranche balances stay at period-opening levels.
"""

from copy import deepcopy
from decimal import Decimal

from .errors import (
    DuplicateTrancheError,
    InsufficientFundsError,
    NegativeCashError,
    PriorityConflictError,
    TriggerConfigurationError,
)
from .models import (
    Account,
    PeriodResult,
    PeriodState,
    PoolPeriod,
    PrincipalMode,
    PriorityProfile,
    WaterfallConfig,
)
from .money import ZERO, checked_add, money_sum, to_money, to_rate
from .triggers import validate_triggers
from .accounts import Ledger
from .allocation import pro_rata_pay, sequential_pay
from .triggers import evaluate_triggers


STEPS = (
    "fees",
    "interest",
    "reserve_topup",
    "principal",
    "reserve_release",
    "losses",
)


def validate_config(config: WaterfallConfig) -> None:
    """Static validation: ids, priorities, rates and trigger references."""
    tranche_ids: set[str] = set()
    ranks: set[int] = set()
    if not config.tranches:
        raise DuplicateTrancheError("at least one tranche is required")
    for tranche in config.tranches:
        if not tranche.id:
            raise DuplicateTrancheError("tranche id must be non-empty")
        if tranche.id in tranche_ids:
            raise DuplicateTrancheError(
                f"duplicate tranche id: {tranche.id}"
            )
        tranche_ids.add(tranche.id)
        if tranche.seniority in ranks:
            raise DuplicateTrancheError(
                f"duplicate seniority rank: {tranche.seniority}"
            )
        ranks.add(tranche.seniority)
        if tranche.initial_balance < 0:
            raise NegativeCashError(
                f"tranche {tranche.id} negative initial balance"
            )
        if not tranche.coupon_rate.is_finite() or tranche.coupon_rate < 0:
            raise NegativeCashError(
                f"tranche {tranche.id} bad coupon rate"
            )
        object.__setattr__(
            tranche, "coupon_rate", to_rate(tranche.coupon_rate)
        )

    fee_ids: set[str] = set()
    for fee in config.fees:
        if not fee.id or fee.id in fee_ids:
            raise DuplicateTrancheError(f"missing/duplicate fee id: {fee.id}")
        fee_ids.add(fee.id)
        if fee.fixed_amount < 0 or not fee.rate.is_finite() or fee.rate < 0:
            raise NegativeCashError(f"fee {fee.id} has negative terms")
        object.__setattr__(fee, "rate", to_rate(fee.rate))
        object.__setattr__(
            fee, "fixed_amount", to_money(fee.fixed_amount)
        )

    if not config.profiles:
        raise PriorityConflictError("at least one priority profile is required")
    profile_ids = {profile.id for profile in config.profiles}
    if len(profile_ids) != len(config.profiles):
        raise PriorityConflictError("duplicate profile ids")
    if config.default_profile_id not in profile_ids:
        raise PriorityConflictError(
            f"default profile {config.default_profile_id!r} is not defined"
        )
    for profile in config.profiles:
        _validate_profile(profile, tranche_ids)

    if not config.reserve.target_rate.is_finite() or config.reserve.target_rate < 0:
        raise NegativeCashError("reserve target rate must be finite and >= 0")
    if config.reserve.min_balance < 0:
        raise NegativeCashError("reserve minimum must be >= 0")
    object.__setattr__(
        config.reserve, "target_rate", to_rate(config.reserve.target_rate)
    )
    object.__setattr__(
        config.reserve, "min_balance", to_money(config.reserve.min_balance)
    )

    validate_triggers(config.triggers, profile_ids)


def _validate_profile(profile: PriorityProfile, tranche_ids: set[str]) -> None:
    for order_name in ("interest_order", "principal_order"):
        order = getattr(profile, order_name)
        if not order:
            raise PriorityConflictError(
                f"profile {profile.id!r} {order_name} is empty"
            )
        if len(order) != len(set(order)):
            raise PriorityConflictError(
                f"profile {profile.id!r} {order_name} repeats a tranche"
            )
        unknown = set(order) - tranche_ids
        if unknown:
            raise PriorityConflictError(
                f"profile {profile.id!r} {order_name} references unknown "
                f"tranches: {sorted(unknown)}"
            )
        missing = tranche_ids - set(order)
        if missing:
            raise PriorityConflictError(
                f"profile {profile.id!r} {order_name} omits tranches: "
                f"{sorted(missing)}"
            )


def validate_period(period: PoolPeriod, config: WaterfallConfig) -> None:
    for field_name in (
        "beginning_pool_balance",
        "interest_collections",
        "principal_collections",
        "realized_losses",
    ):
        value = getattr(period, field_name)
        if not value.is_finite() or value < 0:
            raise NegativeCashError(
                f"period {period.index} field {field_name} is negative "
                "or non-finite"
            )
    required = {trigger.indicator for trigger in config.triggers}
    missing = required - set(period.indicators)
    if missing:
        raise TriggerConfigurationError(
            f"period {period.index} missing frozen indicators: "
            f"{sorted(missing)}"
        )
    for name, value in period.indicators.items():
        if not value.is_finite():
            raise NegativeCashError(
                f"period {period.index} indicator {name} is non-finite"
            )


def initial_state(config: WaterfallConfig) -> PeriodState:
    balances = {
        tranche.id: to_money(tranche.initial_balance)
        for tranche in sorted(config.tranches, key=lambda t: t.id)
    }
    return PeriodState(
        period_index=-1,
        interest_cash=ZERO,
        principal_cash=ZERO,
        reserve_cash=ZERO,
        tranche_balances=balances,
    )


def beginning_state_for(
    config: WaterfallConfig,
    period: PoolPeriod,
    prior: PeriodState | None,
) -> PeriodState:
    """Carry persistent balances forward; reset per-period accumulators."""
    base = initial_state(config) if prior is None else prior
    if period.realized_losses > period.beginning_pool_balance:
        raise NegativeCashError(
            f"period {period.index} realized losses "
            f"{period.realized_losses} exceed pool balance "
            f"{period.beginning_pool_balance}"
        )
    state = PeriodState(
        period_index=period.index,
        interest_cash=to_money(period.interest_collections),
        principal_cash=to_money(period.principal_collections),
        reserve_cash=to_money(base.reserve_cash),
        tranche_balances=dict(base.tranche_balances),
    )
    for tranche in config.tranches:
        balance = state.tranche_balances[tranche.id]
        coupon = to_money(balance * to_rate(tranche.coupon_rate))
        state.tranche_interest_due[tranche.id] = coupon
    return state


def run_period(
    config: WaterfallConfig,
    period: PoolPeriod,
    prior: PeriodState | None = None,
) -> PeriodResult:
    validate_config(config)
    validate_period(period, config)
    opening = beginning_state_for(config, period, prior)
    frozen_opening = deepcopy(opening)
    ledger = Ledger(opening)

    try:
        profiles = {profile.id: profile for profile in config.profiles}
        fired = evaluate_triggers(config.triggers, period.indicators)
        if fired:
            profile = profiles[fired[0].profile_id]
        else:
            profile = profiles[config.default_profile_id]

        opening.tranche_interest_due = {
            tranche_id: checked_add(
                opening.tranche_interest_due[tranche_id],
                prior.interest_shortfall.get(tranche_id, ZERO)
                if prior is not None
                else ZERO,
            )
            for tranche_id in opening.tranche_interest_due
        }

        _pay_fees(config, ledger, period)
        _pay_interest(config, ledger, profile)
        _top_up_reserve(config, ledger, period)
        _pay_principal(config, ledger, profile, period)
        _release_reserve(config, ledger, profile, period, bool(fired))
        _allocate_losses(config, ledger, period)
        _sweep_undistributed(ledger)
    except Exception:
        ledger.restore(frozen_opening)
        raise

    ending = ledger.state
    return PeriodResult(
        period_index=period.index,
        beginning_state=frozen_opening,
        ending_state=ending,
        audit=ledger.audit,
        fired_triggers=tuple(trigger.id for trigger in fired),
        profile_id=profile.id,
    )


def _fee_dues(config: WaterfallConfig, period: PoolPeriod) -> dict[str, Decimal]:
    dues: dict[str, Decimal] = {}
    for fee in config.fees:
        dues[fee.id] = checked_add(
            to_money(fee.fixed_amount),
            to_money(period.beginning_pool_balance * to_rate(fee.rate)),
        )
    return dues


def _draw(
    ledger: Ledger,
    due: Decimal,
    accounts: list[tuple[Account, str]],
    step: str,
    action: str,
    memo: str,
) -> Decimal:
    """Draw the requested amounts from explicitly named accounts in order.

    Returns the total actually drawn; callers must handle shortfalls.
    """
    drawn = ZERO
    outstanding = to_money(due)
    for account, source_action in accounts:
        remaining_need = to_money(outstanding - drawn)
        if remaining_need <= 0:
            break
        take = min(remaining_need, ledger.balance(account))
        if take:
            ledger.debit(account, take, step, source_action, memo)
            drawn = checked_add(drawn, take)
    return drawn


def _pay_fees(
    config: WaterfallConfig, ledger: Ledger, period: PoolPeriod
) -> None:
    dues = _fee_dues(config, period)
    for fee in config.fees:
        due = dues[fee.id]
        sources: list[tuple[Account, str]] = [
            (Account.INTEREST, "fee_interest_funding")
        ]
        if config.allow_interest_principal_switch:
            sources.append((Account.PRINCIPAL, "fee_principal_funding"))
        paid = _draw(
            ledger, due, sources, "fees", "fee_funding", f"fund fee {fee.id}"
        )
        ledger.pay_fee(fee.id, paid, "fees", f"pay fee {fee.id}")


def _pay_interest(
    config: WaterfallConfig,
    ledger: Ledger,
    profile: PriorityProfile,
) -> None:
    paid: dict[str, Decimal] = {}
    shortfalls: dict[str, Decimal] = {}
    for tranche_id in profile.interest_order:
        due = ledger.state.tranche_interest_due.get(tranche_id, ZERO)
        sources = [
            (Account.INTEREST, "interest_funding"),
            (Account.RESERVE, "reserve_interest_funding"),
        ]
        if config.allow_interest_principal_switch:
            sources.append((Account.PRINCIPAL, "principal_switch_interest"))
        tranche_paid = ZERO
        gap = due
        for account, action in sources:
            if gap <= 0:
                break
            take = min(gap, ledger.balance(account))
            if take:
                ledger.debit(
                    account,
                    take,
                    "interest",
                    action,
                    f"fund interest {tranche_id}",
                )
                tranche_paid = checked_add(tranche_paid, take)
                gap = to_money(gap - take)
        paid[tranche_id] = tranche_paid
        shortfalls[tranche_id] = gap
        if tranche_paid:
            ledger.pay_tranche(
                tranche_id,
                interest_amount=tranche_paid,
                step="interest",
                memo="interest payment",
            )
    ledger.state.interest_shortfall = {
        tranche_id: shortfalls[tranche_id]
        for tranche_id in profile.interest_order
        if shortfalls[tranche_id] > 0
    }


def _reserve_target(config: WaterfallConfig, period: PoolPeriod) -> Decimal:
    target = to_money(
        period.beginning_pool_balance * to_rate(config.reserve.target_rate)
    )
    return max(target, to_money(config.reserve.min_balance))


def _top_up_reserve(
    config: WaterfallConfig,
    ledger: Ledger,
    period: PoolPeriod,
) -> None:
    target = _reserve_target(config, period)
    current = ledger.balance(Account.RESERVE)
    need = to_money(max(ZERO, target - current))
    if need <= 0:
        return
    sources = [(Account.INTEREST, "reserve_interest_funding")]
    if config.allow_interest_principal_switch:
        sources.append((Account.PRINCIPAL, "reserve_principal_funding"))
    drawn = _draw(
        ledger,
        need,
        sources,
        "reserve_topup",
        "reserve_funding",
        "top up reserve",
    )
    if drawn:
        ledger.credit(
            Account.RESERVE,
            drawn,
            "reserve_topup",
            "reserve_topup",
            "reserve top-up",
        )


def _pay_principal(
    config: WaterfallConfig,
    ledger: Ledger,
    profile: PriorityProfile,
    period: PoolPeriod,
) -> None:
    if config.allow_interest_principal_switch:
        leftover_interest = ledger.balance(Account.INTEREST)
        if leftover_interest > 0:
            ledger.transfer(
                Account.INTEREST,
                Account.PRINCIPAL,
                leftover_interest,
                "principal",
                "interest_to_principal_switch",
                "excess interest converts to principal",
            )

    available = ledger.balance(Account.PRINCIPAL)
    if available <= 0:
        return
    balances = {
        tranche_id: ledger.tranche_balance(tranche_id)
        for tranche_id in profile.principal_order
    }
    if profile.principal_mode is PrincipalMode.SEQUENTIAL:
        paid, _remaining, _leftover = sequential_pay(
            available, list(profile.principal_order), balances
        )
    elif profile.principal_mode is PrincipalMode.PRO_RATA:
        paid, _leftover = pro_rata_pay(available, balances, cap=balances)
    else:  # pragma: no cover - guarded by validation/enum
        raise PriorityConflictError(
            f"unknown principal mode: {profile.principal_mode}"
        )

    total_paid = money_sum(paid.values())
    if total_paid:
        ledger.debit(
            Account.PRINCIPAL,
            total_paid,
            "principal",
            "principal_funding",
            f"{profile.principal_mode.value} principal distribution",
        )
    for tranche_id in profile.principal_order:
        if paid[tranche_id]:
            ledger.pay_tranche(
                tranche_id,
                principal_amount=paid[tranche_id],
                step="principal",
                memo=f"{profile.principal_mode.value} principal",
            )


def _release_reserve(
    config: WaterfallConfig,
    ledger: Ledger,
    profile: PriorityProfile,
    period: PoolPeriod,
    trigger_fired: bool,
) -> None:
    if trigger_fired or not config.reserve.releasable:
        return
    target = _reserve_target(config, period)
    excess = to_money(max(ZERO, ledger.balance(Account.RESERVE) - target))
    if excess <= 0:
        return
    ledger.transfer(
        Account.RESERVE,
        Account.PRINCIPAL,
        excess,
        "reserve_release",
        "reserve_release",
        "release excess reserve",
    )
    balances = {
        tranche_id: ledger.tranche_balance(tranche_id)
        for tranche_id in profile.principal_order
    }
    if profile.principal_mode is PrincipalMode.SEQUENTIAL:
        paid, _remaining, _leftover = sequential_pay(
            excess, list(profile.principal_order), balances
        )
    else:
        paid, _leftover = pro_rata_pay(excess, balances, cap=balances)
    total_paid = money_sum(paid.values())
    if total_paid:
        ledger.debit(
            Account.PRINCIPAL,
            total_paid,
            "reserve_release",
            "released_principal_funding",
            "distribute released reserve as principal",
        )
    for tranche_id in profile.principal_order:
        if paid[tranche_id]:
            ledger.pay_tranche(
                tranche_id,
                principal_amount=paid[tranche_id],
                step="reserve_release",
                memo="released reserve principal",
            )


def _allocate_losses(
    config: WaterfallConfig,
    ledger: Ledger,
    period: PoolPeriod,
) -> None:
    losses = to_money(period.realized_losses)
    if losses <= 0:
        return
    remaining = losses
    junior_first = sorted(config.tranches, key=lambda t: -t.seniority)
    for tranche in junior_first:
        if remaining <= 0:
            break
        balance = ledger.tranche_balance(tranche.id)
        absorb = min(balance, remaining)
        if absorb:
            ledger.absorb_loss(
                tranche.id, absorb, "losses", "loss penetrates junior first"
            )
            remaining = to_money(remaining - absorb)
    if remaining > 0:
        raise InsufficientFundsError(
            f"period {period.index} losses {losses} exceed total note "
            f"balance by {remaining}; no credit support remains"
        )


def _sweep_undistributed(ledger: Ledger) -> None:
    ledger.state.undistributed_interest = ledger.balance(Account.INTEREST)
    ledger.state.undistributed_principal = ledger.balance(Account.PRINCIPAL)
