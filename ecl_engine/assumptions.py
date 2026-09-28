"""Versioned assumption registry with effective dates and revision tracking."""

from decimal import Decimal

from .models import AssumptionSet
from .validation import ValidationError, validate_weights


def _contract_fingerprint(assumptions: AssumptionSet, contract_id: str) -> tuple:
    """Stable structural fingerprint of PD/LGD/EAD/discount for a contract."""
    weights = tuple(
        sorted((w.name, str(Decimal(w.weight))) for w in assumptions.scenarios)
    )
    inputs = assumptions.inputs.get(contract_id, ())
    parts = []
    for scenario in sorted(inputs, key=lambda s: s.name):
        parts.append(
            (
                scenario.name,
                tuple((p.period, str(p.value)) for p in scenario.cumulative_pd.points),
                tuple((p.period, str(p.value)) for p in scenario.lgd.points),
                tuple((p.period, str(p.value)) for p in scenario.ead.points),
                tuple((p.period, str(p.value)) for p in scenario.discount.points),
            )
        )
    return (weights, tuple(parts))


def validate_assumption_set(assumptions: AssumptionSet) -> None:
    """Validate weights closure and per-contract input coverage."""
    if not assumptions.version:
        raise ValidationError("assumption set requires a version")
    if not assumptions.effective_date:
        raise ValidationError("assumption set requires an effective date")
    validate_weights(
        [(w.name, w.weight) for w in assumptions.scenarios],
        Decimal("0.00000001"),
    )
    scenario_names = {w.name for w in assumptions.scenarios}
    for contract_id, inputs in assumptions.inputs.items():
        names = [item.name for item in inputs]
        if sorted(names) != sorted(scenario_names) or len(set(names)) != len(names):
            raise ValidationError(
                f"contract {contract_id!r}: scenario inputs must match "
                f"scenario weights exactly"
            )


class AssumptionRegistry:
    """Holds published assumption versions; failed uploads never publish."""

    def __init__(self) -> None:
        self._versions: dict[str, AssumptionSet] = {}
        self._order: list[str] = []

    def publish(self, assumption_set: AssumptionSet) -> None:
        """Validate and atomically publish a new version.

        Validation happens before any mutation, so a failed version can never
        replace or remove an already published one.
        """
        validate_assumption_set(assumption_set)
        if assumption_set.version in self._versions:
            raise ValidationError(
                f"version {assumption_set.version!r} already published"
            )
        self._versions[assumption_set.version] = assumption_set
        self._order.append(assumption_set.version)

    def get(self, version: str) -> AssumptionSet:
        try:
            return self._versions[version]
        except KeyError as exc:
            raise KeyError(f"unknown assumption version {version!r}") from exc

    def latest(self) -> AssumptionSet:
        if not self._order:
            raise KeyError("no assumption versions published")
        return self._versions[self._order[-1]]

    def bind(self, version: str | None = None) -> AssumptionSet:
        """Bind a valuation to one immutable version (latest if None)."""
        bound = self.latest() if version is None else self.get(version)
        return bound

    @property
    def versions(self) -> tuple[str, ...]:
        return tuple(self._order)

    def affected_contracts(
        self,
        old_version: str,
        new_version: str,
        contracts,
    ) -> tuple[str, ...]:
        """Return ids whose PD/LGD/EAD/discount/weight inputs changed."""
        old = self.get(old_version)
        new = self.get(new_version)
        weights_changed = tuple(
            sorted((w.name, str(w.weight)) for w in old.scenarios)
        ) != tuple(sorted((w.name, str(w.weight)) for w in new.scenarios))
        changed: list[str] = []
        for contract in contracts:
            cid = contract.contract_id
            if weights_changed or _contract_fingerprint(
                old, cid
            ) != _contract_fingerprint(new, cid):
                changed.append(cid)
        return tuple(sorted(changed))
