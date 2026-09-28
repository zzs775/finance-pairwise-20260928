"""Valuation runs: atomic publish, incremental vs full recomputation."""

import json

from .engine import price_all
from .models import ContractECL, ValuationResult
from .validation import ValidationError


def serialize_result(result: ValuationResult) -> bytes:
    """Canonical byte serialisation used for full vs incremental comparison."""
    payload = {
        "valuation_id": result.valuation_id,
        "version": result.version,
        "run_date": result.run_date,
        "contracts": [
            {
                "contract_id": contract.contract_id,
                "total_ecl": str(contract.total_ecl),
                "lines": [
                    {
                        "scenario": line.scenario,
                        "period": line.period,
                        "marginal_pd": str(line.marginal_pd),
                        "lgd": str(line.lgd),
                        "ead": str(line.ead),
                        "discount_factor": str(line.discount_factor),
                        "weight": str(line.weight),
                        "period_ecl": str(line.period_ecl),
                        "weighted_ecl": str(line.weighted_ecl),
                        "rounding_delta": str(line.rounding_delta),
                    }
                    for line in contract.lines
                ],
            }
            for contract in result.contracts
        ],
    }
    return json.dumps(
        payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False
    ).encode("utf-8")


class ValuationService:
    """Owns published results; a failed version never replaces a result."""

    def __init__(self, registry, run_date: str = "1970-01-01") -> None:
        self._registry = registry
        self._run_date = run_date
        self._results: dict[str, ValuationResult] = {}

    @property
    def results(self) -> dict[str, ValuationResult]:
        return dict(self._results)

    def _build(
        self,
        valuation_id: str,
        contracts,
        version,
        overrides: dict[str, ContractECL] | None = None,
    ) -> ValuationResult:
        assumptions = self._registry.bind(version)
        priced = price_all(contracts, assumptions)
        if overrides:
            priced = tuple(
                overrides.get(contract.contract_id, contract) for contract in priced
            )
        return ValuationResult(
            valuation_id=valuation_id,
            version=assumptions.version,
            run_date=self._run_date,
            contracts=priced,
        )

    def run_full(
        self,
        valuation_id: str,
        contracts,
        version: str | None = None,
    ) -> ValuationResult:
        """Price every contract and publish the result atomically.

        Any error propagates before the published-results map is touched.
        """
        result = self._build(valuation_id, contracts, version)
        self._results[valuation_id] = result
        return result

    def run_incremental(
        self,
        valuation_id: str,
        contracts,
        new_version: str,
    ) -> ValuationResult:
        """Re-price only affected contracts.

        Unaffected contract results (including every detail line) are reused
        verbatim from the prior run.  The merged output is asserted to be
        byte-identical to a full recomputation before publication.
        """
        previous = self._results.get(valuation_id)
        if previous is None:
            raise ValidationError(
                f"no published valuation {valuation_id!r} to revise"
            )
        prior_assumptions = self._registry.bind(previous.version)
        affected = set(
            self._registry.affected_contracts(
                prior_assumptions.version, new_version, contracts
            )
        )
        prior_by_id = {c.contract_id: c for c in previous.contracts}
        overrides: dict[str, ContractECL] = {}
        new_assumptions = self._registry.bind(new_version)
        for contract in contracts:
            cid = contract.contract_id
            if cid not in affected:
                if cid not in prior_by_id:
                    raise ValidationError(
                        f"new contract {cid!r} cannot be priced incrementally"
                    )
                overrides[cid] = prior_by_id[cid]
        changed_contracts = [c for c in contracts if c.contract_id in affected]
        repriced = price_all(changed_contracts, new_assumptions)
        overrides.update({c.contract_id: c for c in repriced})

        merged = self._build(
            valuation_id,
            sorted(contracts, key=lambda c: c.contract_id),
            new_version,
            overrides=overrides,
        )
        full = self._build(
            valuation_id,
            sorted(contracts, key=lambda c: c.contract_id),
            new_version,
        )
        merged_bytes = serialize_result(merged)
        if merged_bytes != serialize_result(full):
            raise ValidationError(
                "incremental result diverges from full recomputation"
            )
        self._results[valuation_id] = merged
        return merged
