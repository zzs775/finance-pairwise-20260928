"""Classified failure types for the waterfall engine."""


class WaterfallError(Exception):
    """Base class for every engine failure."""


class ValidationError(WaterfallError):
    """Base class for static configuration / input validation failures."""


class NegativeCashError(ValidationError):
    """A cash amount, balance, rate or proportion is negative."""


class DuplicateTrancheError(ValidationError):
    """Two tranches share an id or a seniority rank."""


class PriorityConflictError(ValidationError):
    """Payment order / priority rules are ambiguous or contradictory."""


class TriggerConfigurationError(ValidationError):
    """A trigger rule is missing required parameters or references bad fields."""


class CashOverflowError(WaterfallError):
    """An arithmetic result exceeded the engine's fixed-scale magnitude cap."""


class AllocationError(WaterfallError):
    """A distribution step could not be completed consistently."""


class InsufficientFundsError(AllocationError):
    """An account was asked to disburse more cash than it held."""


class TriggerEvaluationError(WaterfallError):
    """A trigger could not be evaluated against the frozen period indicators."""
