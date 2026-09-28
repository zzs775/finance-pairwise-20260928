"""Domain error types."""


class ECLError(Exception):
    """Base error for the ECL engine."""


class ValidationError(ECLError):
    """Raised when contract or assumption input fails validation."""


class VersionError(ECLError):
    """Raised for assumption-version binding or publication problems."""

