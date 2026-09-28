"""Immutable curve versions and atomic publish/replace semantics."""

from dataclasses import dataclass
from datetime import date
from decimal import Decimal

from .errors import CurveError
from .interpolator import DiscountCurve


@dataclass(frozen=True)
class CurveVersion:
    version_id: str
    quote_version_id: str
    valuation_date: date
    curve: DiscountCurve


class CurveStore:
    """Holds the current published version; replacement is atomic."""

    def __init__(self) -> None:
        self._current: CurveVersion | None = None

    def publish(self, version: CurveVersion) -> None:
        if not isinstance(version, CurveVersion):
            raise CurveError("publish expects a CurveVersion")
        if version.curve.valuation_date != version.valuation_date:
            raise CurveError("version valuation date must match curve")
        # Single reference assignment: readers either see the old complete
        # version or the new complete version, never a partial one.
        self._current = version

    def current(self) -> CurveVersion:
        if self._current is None:
            raise CurveError("no curve version has been published yet")
        return self._current

    def discount(self, day: date) -> Decimal:
        return self.current().curve.discount(day)

    @property
    def version_id(self) -> str:
        return self.current().version_id
