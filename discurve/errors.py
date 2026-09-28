"""Classified error hierarchy for curve construction."""


class CurveError(Exception):
    """Base class for all discurve errors."""


class IllegalDateError(CurveError):
    """A date is missing, unordered, or otherwise illegal."""


class InvalidQuoteError(CurveError):
    """A quoted instrument fails structural validation."""


class DuplicateNodeError(CurveError):
    """Two instruments resolve to the same curve node date."""


class MissingTenorError(CurveError):
    """An instrument references a curve tenor that does not exist."""


class NonPositiveDiscountError(CurveError):
    """A solved or interpolated discount factor is not strictly positive."""


class RootBracketingError(CurveError):
    """The bisection search could not bracket a sign change."""


class IterationLimitError(CurveError):
    """The bisection search exhausted its iteration budget."""


class NonFiniteValueError(CurveError):
    """An intermediate evaluation was NaN or infinite."""


class ExtrapolationError(CurveError):
    """A discount query fell outside the built node range."""


class PricingToleranceError(CurveError):
    """A re-pricing residual exceeded the fixed tolerance."""


class ScheduleError(CurveError):
    """Stub/schedule generation failed."""

