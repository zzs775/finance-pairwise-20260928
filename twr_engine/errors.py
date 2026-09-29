"""Domain errors and status types for the TWR engine."""

from __future__ import annotations

from enum import Enum


class TWRError(Exception):
    """Base class for all engine errors."""


class DuplicateValuationError(TWRError):
    """Raised when two non-identical valuations share the same business key."""


class UnattributedCashFlowError(TWRError):
    """Raised when a cash flow references an unknown account."""


class InvertedMembershipError(TWRError):
    """Raised when a membership interval ends before it starts."""


class ValidationError(TWRError):
    """Raised for malformed input that cannot be normalized."""


class CapitalStatus(str, Enum):
    """Explicit status for a sub-period capital base."""

    OK = "ok"
    ZERO_BASE = "zero_base"
    NEGATIVE_BASE = "negative_base"
