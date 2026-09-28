"""Sequential and stable pro-rata (largest-remainder) allocation.

Money lives on a 0.01 grid, so pro-rata splits work in integer *cents*.
Ties are broken deterministically: larger fractional remainder first, then
the lexicographically smaller tranche id. Reordering the input dicts never
changes the result.
"""

from collections.abc import Mapping
from decimal import ROUND_FLOOR, Decimal

from .errors import AllocationError, NegativeCashError
from .money import MONEY_SCALE, ZERO, checked_add, money_sum, to_money


def _cents(amount: Decimal) -> int:
    return int((amount / MONEY_SCALE).to_integral_value(rounding=ROUND_FLOOR))


def _from_cents(units: int) -> Decimal:
    return to_money(Decimal(units) * MONEY_SCALE)


def largest_remainder(units: int, weights: Mapping[str, Decimal]) -> dict[str, int]:
    """Split *units* integer units by *weights* with stable tie-breaking."""
    if units < 0:
        raise NegativeCashError(f"cannot allocate negative units: {units}")
    keys = sorted(weights)
    clean = {key: Decimal(weights[key]) for key in keys}
    for value in clean.values():
        if not value.is_finite() or value < 0:
            raise NegativeCashError(f"non-finite or negative weight: {value}")
    total_weight = sum(clean.values(), Decimal(0))
    shares = {key: 0 for key in keys}
    if units == 0:
        return shares
    if total_weight == 0:
        raise AllocationError("cannot allocate with zero total weight")

    raw = {
        key: (Decimal(units) * clean[key]) / total_weight for key in keys
    }
    floors = {key: int(raw[key].to_integral_value(rounding=ROUND_FLOOR)) for key in keys}
    assigned = sum(floors.values())
    remainder_units = units - assigned
    ranked = sorted(
        keys,
        key=lambda key: (-(raw[key] - Decimal(floors[key])), key),
    )
    for key in ranked[:remainder_units]:
        floors[key] += 1
    assert sum(floors.values()) == units
    return floors


def sequential_pay(
    available: Decimal,
    order: list[str],
    dues: Mapping[str, Decimal],
) -> tuple[dict[str, Decimal], dict[str, Decimal], Decimal]:
    """Pay dues strictly in *order* until *available* is exhausted.

    Returns (paid_by_id, remaining_due_by_id, leftover_cash).
    """
    available = to_money(available)
    remaining_cash = available
    paid: dict[str, Decimal] = {}
    remaining_due: dict[str, Decimal] = {}
    for tranche_id in order:
        due = to_money(dues.get(tranche_id, ZERO))
        amount = due if due <= remaining_cash else remaining_cash
        paid[tranche_id] = amount
        remaining_due[tranche_id] = due - amount
        remaining_cash -= amount
    return paid, remaining_due, remaining_cash


def pro_rata_pay(
    available: Decimal,
    weights: Mapping[str, Decimal],
    cap: Mapping[str, Decimal] | None = None,
) -> tuple[dict[str, Decimal], Decimal]:
    """Split *available* by *weights*, never exceeding *cap* per recipient.

    Capped recipients' surplus is redistributed among the others using the
    same stable largest-remainder rule. Returns (paid_by_id, leftover_cash).
    """
    available = to_money(available)
    keys = sorted(weights)
    clean_weights = {key: Decimal(weights[key]) for key in keys}
    clean_caps: dict[str, Decimal | None]
    if cap is None:
        clean_caps = {key: None for key in keys}
    else:
        clean_caps = {key: to_money(cap.get(key, ZERO)) for key in keys}

    paid = {key: ZERO for key in keys}
    pool = available
    active = set(keys)

    while pool > ZERO and active:
        units = largest_remainder(_cents(pool), {k: clean_weights[k] for k in active})
        round_paid = {key: ZERO for key in keys}
        hit_cap: set[str] = set()
        for key in active:
            offered = _from_cents(units[key])
            limit = clean_caps[key]
            room = offered if limit is None else min(offered, limit - paid[key])
            room = to_money(room)
            round_paid[key] = room
            if limit is not None and paid[key] + room >= limit:
                hit_cap.add(key)
        distributed = money_sum(round_paid.values())
        if distributed == ZERO:
            break
        for key, amount in round_paid.items():
            paid[key] = checked_add(paid[key], amount)
        pool = to_money(pool - distributed)
        active.difference_update(hit_cap)

    leftover = pool
    if money_sum(paid.values()) + leftover != available:
        raise AllocationError("pro-rata allocation failed to conserve cash")
    return paid, leftover
