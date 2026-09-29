"""Domain exceptions."""


class LifeReserveError(Exception):
    """Base error for the reserve engine."""


class ValidationError(LifeReserveError):
    """Raised when policy or mortality-table inputs are invalid."""


class ReserveMismatchError(LifeReserveError):
    """Raised when the two reserve algorithms disagree beyond tolerance."""
