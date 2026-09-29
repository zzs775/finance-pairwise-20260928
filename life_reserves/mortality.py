"""Mortality table validation and probability helpers."""

from __future__ import annotations

from decimal import Decimal


def validate_mortality(
    issue_age: int, term: int, rates: dict[int, Decimal]
) -> list[Decimal]:
    """Return ``q_x`` for ages ``issue_age .. issue_age+term-1``.

    The mortality table must cover every attained age touched by the
    contract.  Extra ages in the table are allowed and ignored.

    Raises:
        ValueError: if the table is missing an age, an age key is not an
            integer, a rate is not a ``Decimal``, or any probability is
            outside ``[0, 1]``.
    """
    if not isinstance(issue_age, int) or issue_age < 0:
        raise ValueError(f"issue_age must be a non-negative integer, got {issue_age!r}")
    if not isinstance(term, int) or term <= 0:
        raise ValueError(f"term must be a positive integer, got {term!r}")
    if not isinstance(rates, dict):
        raise ValueError("mortality must be a dict mapping age to Decimal probability")

    q_values: list[Decimal] = []
    for age in range(issue_age, issue_age + term):
        if age not in rates:
            raise ValueError(
                f"mortality table does not cover attained age {age} "
                f"(required ages {issue_age}..{issue_age + term - 1})"
            )
        q = rates[age]
        if not isinstance(q, Decimal):
            raise ValueError(
                f"mortality rate at age {age} must be Decimal, got {type(q).__name__}"
            )
        if not (Decimal(0) <= q <= Decimal(1)):
            raise ValueError(
                f"mortality rate at age {age} must lie in [0, 1], got {q}"
            )
        q_values.append(q)
    return q_values


def conditional_survival(q: Decimal) -> Decimal:
    """Return the one-year conditional survival probability ``p = 1 - q``."""
    return Decimal(1) - q
