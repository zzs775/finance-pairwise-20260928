"""Versioned assumption registry with publication semantics."""

from .models import AssumptionSet
from .errors import VersionError
from . import validation


class AssumptionRegistry:
    """Holds published assumption versions; failed versions never publish."""

    def __init__(self) -> None:
        self._versions: dict[str, AssumptionSet] = {}

    def publish(self, assumptions: AssumptionSet) -> None:
        """Validate and publish a version; a failure keeps the old one."""
        if assumptions.version in self._versions:
            raise VersionError(
                f"version already published and immutable: "
                f"{assumptions.version}")
        # Validation runs before any state mutation, so a failed publication
        # never replaces or removes an already published result.
        snapshot = dict(self._versions)
        validation.validate_assumptions(assumptions)
        self._versions[assumptions.version] = assumptions
        assert self._versions != snapshot or not snapshot

    def all_versions(self) -> tuple[str, ...]:
        """Return published version identifiers sorted by effective date."""
        return tuple(
            item.version for item in sorted(
                self._versions.values(),
                key=lambda item: (item.effective_date, item.version)))

    def get(self, version: str) -> AssumptionSet:
        """Return a published version."""
        try:
            return self._versions[version]
        except KeyError:
            raise VersionError(f"unknown assumption version: {version}") from None

    @property
    def latest(self) -> AssumptionSet:
        """Return the most recently effective published version."""
        if not self._versions:
            raise VersionError("no assumption version published")
        return max(self._versions.values(),
                   key=lambda item: (item.effective_date, item.version))
