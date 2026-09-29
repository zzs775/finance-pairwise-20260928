"""Probability recursion: conditional survival -> death/survival branches."""

from dataclasses import dataclass
from decimal import Decimal

from .model import MortalityTable, Policy
from .model import ONE, ZERO


@dataclass(frozen=True)
class YearState:
    """Probability state at one policy year."""

    year: int
    age: int
    q: Decimal
    p: Decimal
    in_force: Decimal
    death_prob: Decimal
    survive_prob: Decimal


def project_probabilities(policy: Policy, table: MortalityTable) -> list[YearState]:
    policy.validate(table)

    states: list[YearState] = []
    in_force = ONE
    for year in range(1, policy.term + 1):
        age = policy.issue_age + year - 1
        q = table.q(age)
        p = ONE - q
        death_prob = in_force * q
        # Partition the in-force mass itself (not two independent products)
        # so that death + survive == in_force exactly under Decimal rounding.
        survive_prob = in_force - death_prob
        if death_prob + survive_prob != in_force:
            raise ValueError(
                f"probability mass broken in year {year}: "
                f"{death_prob} + {survive_prob} != {in_force}"
            )
        states.append(
            YearState(
                year=year,
                age=age,
                q=q,
                p=p,
                in_force=in_force,
                death_prob=death_prob,
                survive_prob=survive_prob,
            )
        )
        in_force = survive_prob
    return states
