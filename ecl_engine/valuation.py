"""Valuation runs binding one assumption version, with published results."""

from .models import Contract, AssumptionSet
from . import engine, validation


def _weight_fingerprint(assumptions: AssumptionSet) -> tuple:
    return tuple(sorted(
        (scenario.name, scenario.weight) for scenario in assumptions.scenarios))


def affected_contract_ids(old: AssumptionSet,
                          new: AssumptionSet) -> set[str]:
    """Return contracts whose PD/LGD changed, or all if weights changed."""
    if _weight_fingerprint(old) != _weight_fingerprint(new):
        return set()  # empty set means "every contract"
    changed: set[str] = set()
    contract_ids = set(old.curves) | set(new.curves)
    for contract_id in contract_ids:
        for name in ("cumulative_pd", "lgd"):
            old_curve = old.curves.get(contract_id, {}).get(name)
            new_curve = new.curves.get(contract_id, {}).get(name)
            if old_curve != new_curve:
                changed.add(contract_id)
    return changed


class ValuationRun:
    """Binds one assumption version and stores immutable published results."""

    def __init__(self, assumptions: AssumptionSet) -> None:
        validation.validate_assumptions(assumptions)
        self._assumptions = assumptions
        self._results: dict[str, engine.ContractECL] = {}
        self._published_version: str | None = None

    @property
    def assumptions(self) -> AssumptionSet:
        return self._assumptions

    @property
    def published_version(self) -> str | None:
        return self._published_version

    def run(self, contracts) -> dict:
        """Compute ECL for all contracts and publish the results."""
        results: dict[str, engine.ContractECL] = {}
        for contract in contracts:
            validation.validate_contract(contract)
            results[contract.contract_id] = engine.compute_contract_ecl(
                contract, self._assumptions.scenarios)
        self._results = dict(sorted(results.items()))
        self._published_version = self._assumptions.version
        return dict(self._results)

    def results(self) -> dict:
        """Return the currently published, immutable results."""
        return dict(self._results)

    def revise(self, assumptions: AssumptionSet, contracts) -> dict:
        """Recompute only contracts affected by revised assumptions."""
        if self._published_version is None:
            return self.run(contracts)
        # Validate before mutating anything: a failed new version can never
        # replace the already published results.
        validation.validate_assumptions(assumptions)
        affected = affected_contract_ids(self._assumptions, assumptions)
        contracts_by_id = {
            contract.contract_id: contract for contract in contracts}
        recomputed: dict[str, engine.ContractECL] = {}
        for contract_id, contract in sorted(contracts_by_id.items()):
            if not affected or contract_id in affected:
                validation.validate_contract(contract)
                recomputed[contract_id] = engine.compute_contract_ecl(
                    contract, assumptions.scenarios)
        merged = dict(self._results)
        merged.update(recomputed)
        self._results = dict(sorted(merged.items()))
        self._assumptions = assumptions
        return dict(self._results)
