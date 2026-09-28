"""Minimal-currency-unit oracle that independently expands and closes flows.

The oracle never shares code with the production allocator. It expands every
distribution into one-cent (``0.01``) tokens and assigns each token one at a
time, which makes conservation and tie-breaking directly auditable.
"""

from decimal import ROUND_FLOOR, Decimal

from .errors import AllocationError
from .models import PeriodResult
from .money import MONEY_SCALE, ZERO, money_sum

CENT = MONEY_SCALE


def _cents(amount: Decimal) -> int:
    return int((amount / CENT).to_integral_value(rounding=ROUND_FLOOR))


def oracle_largest_remainder(
    units: int, weights: dict[str, Decimal]
) -> dict[str, int]:
    """Independent reference: assign units one at a time.

    Floors are granted first; each remaining token goes to the largest
    static fractional remainder (exact share minus its floor); ties resolve
    by the smaller id. The token loop is intentionally explicit so that the
    expansion can be inspected one currency unit at a time.
    """
    if units < 0:
        raise AllocationError(f"oracle units must be >= 0, got {units}")
    keys = sorted(weights)
    total = sum((Decimal(weights[key]) for key in keys), Decimal(0))
    if units and total <= 0:
        raise AllocationError("oracle requires positive total weight")
    exact = {
        key: (Decimal(units) * Decimal(weights[key])) / total for key in keys
    }
    floors = {
        key: int(exact[key].to_integral_value(rounding=ROUND_FLOOR))
        for key in keys
    }
    fractions = {key: exact[key] - Decimal(floors[key]) for key in keys}
    remaining = units - sum(floors.values())
    ranked = sorted(keys, key=lambda key: (-fractions[key], key))
    allocated = dict(floors)
    while remaining > 0:
        recipient = ranked.pop(0)
        allocated[recipient] += 1
        remaining -= 1
    return allocated


def assert_cash_closure(
    config: object,
    period: object,
    result: PeriodResult,
) -> None:
    """Pool cash in = payments + fees + reserve change + undistributed."""
    ending = result.ending_state
    beginning = result.beginning_state
    sources = to_money_sum(
        period.interest_collections, period.principal_collections
    )
    uses = money_sum(list(ending.fees_paid.values()))
    uses += money_sum(list(ending.interest_paid.values()))
    uses += money_sum(list(ending.principal_paid.values()))
    uses += ending.reserve_cash - beginning.reserve_cash
    uses += ending.undistributed_interest + ending.undistributed_principal
    if sources != uses:
        raise AllocationError(
            f"period {period.index} cash not closed: sources {sources} "
            f"!= uses {uses}"
        )
    account_total = (
        ending.interest_cash
        + ending.principal_cash
        + ending.reserve_cash
    )
    expected_idle = (
        ending.undistributed_interest
        + ending.undistributed_principal
        + ending.reserve_cash
    )
    if account_total != expected_idle:
        raise AllocationError(
            f"period {period.index} idle cash mismatch: "
            f"{account_total} != {expected_idle}"
        )


def assert_loss_closure(
    config: object,
    period: object,
    result: PeriodResult,
) -> None:
    """Realized losses equal write-downs; note balance change is explained."""
    ending = result.ending_state
    beginning = result.beginning_state
    allocated = money_sum(list(ending.losses_allocated.values()))
    if allocated != period.realized_losses:
        raise AllocationError(
            f"period {period.index} loss not closed: allocated {allocated} "
            f"!= realized {period.realized_losses}"
        )
    principal_paid = money_sum(list(ending.principal_paid.values()))
    opening_notes = money_sum(list(beginning.tranche_balances.values()))
    ending_notes = money_sum(list(ending.tranche_balances.values()))
    if opening_notes - ending_notes != principal_paid + allocated:
        raise AllocationError(
            f"period {period.index} note balance change does not match "
            f"principal paid {principal_paid} plus losses {allocated}"
        )


def to_money_sum(*values: Decimal) -> Decimal:
    total = ZERO
    for value in values:
        total += value
    return total
