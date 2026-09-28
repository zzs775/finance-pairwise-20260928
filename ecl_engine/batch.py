"""Deterministic batching: incremental results equal full recompute byte-for-byte."""

import json

from .fixed import Decimal


def _line_to_list(line) -> list:
    return [
        line.contract_id,
        line.period,
        line.scenario,
        str(line.marginal_pd),
        str(line.lgd),
        str(line.ead),
        str(line.discount_factor),
        str(line.weight),
        str(line.raw_product),
        str(line.rounded_ecl),
        str(line.rounding_adj),
    ]


def _contract_to_list(result) -> list:
    scenario_totals = [
        [item.scenario, str(item.ecl)]
        for item in sorted(result.scenario_totals, key=lambda item: item.scenario)
    ]
    lines = sorted(
        (_line_to_list(line) for line in result.lines),
        key=lambda item: (item[1], item[2]),
    )
    return [
        result.contract_id,
        result.stage,
        result.period_start,
        result.period_end,
        scenario_totals,
        lines,
        str(result.total_ecl),
    ]

def serialize_results(results: dict) -> bytes:
    """Deterministic canonical byte serialization of valuation results."""
    contracts = [
        _contract_to_list(results[contract_id])
        for contract_id in sorted(results)
    ]
    payload = {"contracts": contracts}
    return (json.dumps(
        payload, sort_keys=True, separators=(",", ":"),
        ensure_ascii=False, allow_nan=False) + "\n").encode("utf-8")


def _compute_all(contracts, assumptions) -> dict:
    from . import engine, validation

    results = {}
    for contract in contracts:
        validation.validate_contract(contract)
        results[contract.contract_id] = engine.compute_contract_ecl(
            contract, assumptions.scenarios)
    return results


def run_full(contracts, assumptions) -> bytes:
    """Canonical bytes of a full recompute."""
    return serialize_results(_compute_all(contracts, assumptions))


def run_batches(contracts, assumptions, batch_size: int = 2) -> bytes:
    """Run contracts in incremental batches and merge canonically."""
    if batch_size <= 0:
        raise ValueError("batch_size must be positive")
    merged: dict = {}
    contracts = list(contracts)
    for offset in range(0, len(contracts), batch_size):
        batch = contracts[offset:offset + batch_size]
        merged.update(_compute_all(batch, assumptions))
    return serialize_results(merged)
