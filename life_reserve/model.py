"""Input types: policy, mortality table, expenses, timing."""

from dataclasses import dataclass, field
from decimal import Decimal
from enum import Enum

from .errors import ValidationError


class Timing(str, Enum):
    """Cash-flow time point within each policy year."""

    BEGINNING = "beginning"
    END = "end"


ZERO = Decimal(0)
ONE = Decimal(1)


@dataclass(frozen=True)
class MortalityTable:
    """Per-age annual mortality rates q_x (age -> probability of death)."""

    rates: dict[int, Decimal]

    def q(self, age: int) -> Decimal:
        try:
            rate = self.rates[age]
        except KeyError:
            raise ValidationError(f"mortality rate missing for age {age}") from None
        if not (ZERO <= rate <= ONE):
            raise ValidationError(f"q_{age} = {rate} is outside [0, 1]")
        return rate

    def validate_coverage(self, issue_age: int, term: int) -> None:
        missing = [
            age
            for age in range(issue_age, issue_age + term)
            if age not in self.rates
        ]
        if missing:
            raise ValidationError(
                "mortality table does not cover ages "
                f"{issue_age}..{issue_age + term - 1}; missing: {missing}"
            )
        for age in range(issue_age, issue_age + term):
            self.q(age)
        ages = [age for age in self.rates if not isinstance(age, int) or age < 0]
        if ages:
            raise ValidationError(f"mortality table has invalid ages: {ages}")


@dataclass(frozen=True)
class ExpenseAssumptions:
    """Expenses loaded into the gross premium."""

    percent_of_premium: Decimal = ZERO  # fraction of each gross premium
    per_policy: Decimal = ZERO  # level per-policy expense, every year
    initial_expense: Decimal = ZERO  # extra expense paid in year 1

    def validate(self) -> None:
        if not (ZERO <= self.percent_of_premium < ONE):
            raise ValidationError(
                "expense percent of premium must lie in [0, 1), got "
                f"{self.percent_of_premium}"
            )
        if self.per_policy < ZERO:
            raise ValidationError("per-policy expense must be non-negative")
        if self.initial_expense < ZERO:
            raise ValidationError("initial expense must be non-negative")


@dataclass(frozen=True)
class Policy:
    """Term policy inputs."""

    issue_age: int
    term: int
    sum_assured: Decimal
    interest_rate: Decimal
    timing: Timing
    expenses: ExpenseAssumptions = field(default_factory=ExpenseAssumptions)

    def validate(self, table: MortalityTable) -> None:
        if not isinstance(self.issue_age, int) or self.issue_age < 0:
            raise ValidationError("issue_age must be a non-negative integer")
        if not isinstance(self.term, int) or self.term < 1:
            raise ValidationError("term must be a positive integer")
        if self.sum_assured <= ZERO:
            raise ValidationError("sum_assured must be positive")
        if not isinstance(self.interest_rate, Decimal):
            raise ValidationError("interest_rate must be Decimal")
        if ONE + self.interest_rate <= ZERO:
            raise ValidationError("interest_rate must satisfy 1 + i > 0")
        if not isinstance(self.timing, Timing):
            raise ValidationError("timing must be Timing.BEGINNING or Timing.END")
        self.expenses.validate()
        table.validate_coverage(self.issue_age, self.term)
