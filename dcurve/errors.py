"""Classified error hierarchy for curve construction and pricing.

Every failure mode required by the specification has its own exception
class so callers (and the CLI) can react to the error category.
"""


class CurveError(Exception):
    """Base class for all domain errors raised by this package."""


class IllegalDateError(CurveError):
    """A date is missing, unordered, or otherwise not a valid calendar date."""


class DuplicateNodeError(CurveError):
    """Two instruments anchor discount-factor nodes on the same maturity date."""


class MissingTenorError(CurveError):
    """An instrument needs a curve segment that has not been bootstrapped."""


class NonPositiveDiscountFactorError(CurveError):
    """A solved or supplied discount factor is zero or negative."""


class NoBracketError(CurveError):
    """The solver could not find a sign-changing bracket for a node."""


class IterationLimitError(CurveError):
    """The bisection solver exhausted its iteration budget without converging."""


class NonFiniteValueError(CurveError):
    """A midpoint evaluation produced a non-finite (NaN/infinite) Decimal."""


class ExtrapolationError(CurveError):
    """A discount factor was requested outside the calibrated node range."""


class RepricingToleranceError(CurveError):
    """A bootstrapped instrument re-prices outside the fixed tolerance."""


class QuoteValidationError(CurveError):
    """An immutable quote book failed structural validation."""
