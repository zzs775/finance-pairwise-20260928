# ECL Engine

Rule-driven expected credit loss (ECL) engine. **Python 3.13, standard
library only.** All bundled data is locally generated, anonymized synthetic
records.

## Rules implemented

1. **Fixed-scale arithmetic.** Every probability, amount, discount factor and
   weight is quantized to `Decimal` at 10 decimal places (`ecl_engine/fixed.py`).
   Non-finite values (`Inf`, `NaN`) are rejected. Cumulative PD must stay in
   `[0, 1]` and be non-decreasing; negative EAD, non-closing weights and term
   length gaps are all classified as `ValidationError`.
2. **Marginal PD and stage ranges.** Cumulative PD is converted to per-period
   marginal PD by first differences (`ecl_engine/curves.py`). Stage 1 uses the
   in-scope 12-period window (truncated at the remaining term); stages 2 and 3
   use the full remaining term (`ecl_engine/stages.py`). Migration partition
   helpers cover periods `1..n` exactly once — no repetition, no omission.
3. **Per-period ECL with full audit trail.** Each line equals
   `marginal_pd * lgd * ead * discount_factor * weight`. Every multiplier, the
   raw product, the rounded ECL and the per-line rounding adjustment are
   emitted (`ecl_engine/engine.py`). The contract total is the **exact sum** of
   the rounded lines, so totals always add up to the detail.
4. **Versioned assumptions.** Assumption sets carry a version and effective
   date; a valuation binds a single version. A failed new version never
   replaces published results, and published versions are immutable. PD/LGD
   revisions recompute only affected contracts; weight revisions recompute all
   (`ecl_engine/registry.py`, `ecl_engine/valuation.py`).
5. **Reference enumerator and deterministic batching.** A flat independent
   enumerator (`ecl_engine/reference.py`) expands every period for small
   contracts so marginal probabilities, stage windows and scenario weights can
   be cross-checked. Canonical JSON serialization (`ecl_engine/batch.py`)
   guarantees incremental batches are **byte-for-byte identical** to a full
   recompute, and reordering inputs never changes the output.

## Layout

- `ecl_engine/` — engine package (errors, fixed scale, models, validation,
  curves, stages, engine, registry, valuation, reference, batch, cli)
- `tests/` — `unittest` suite (74 tests): three stages, cumulative-PD
  boundaries, term truncation, scenario weight tails, version revision,
  migration and overflow
- `examples/demo_input.json` — anonymized synthetic contracts

## Usage

Run the built-in synthetic dataset:

```sh
python -m ecl_engine demo
python -m ecl_engine demo --batch-size 1 -o examples/demo_output.json
```

Run contracts from a JSON file:

```sh
python -m ecl_engine run examples/demo_input.json -o result.json
```

Run the tests:

```sh
python -m unittest discover -s tests -v
```

## Input format

```json
{
  "assumptions": {
    "version": "v1",
    "effective_date": "2026-01-31",
    "scenarios": [
      {"name": "base", "weight": "0.60"},
      {"name": "adverse", "weight": "0.40"}
    ]
  },
  "contracts": [
    {
      "contract_id": "ANON-0001",
      "stage": 1,
      "remaining_periods": 3,
      "curves": {
        "cumulative_pd": ["0.01", "0.025", "0.04"],
        "lgd": ["0.45", "0.45", "0.45"],
        "ead": ["100000", "98000", "96000"],
        "discount": ["0.995", "0.985", "0.970"]
      }
    }
  ]
}
```

All four curves must contain exactly `remaining_periods` values (a mismatch is
reported as a term gap). Scenario weights must sum exactly to `1.0000000000`.
Exit code `2` indicates a validation or input error.

## Programmatic use

```python
from ecl_engine import engine

result = engine.compute_contract_ecl(contract, assumptions.scenarios)
print(result.total_ecl)
for line in result.lines:
    print(line.period, line.scenario, line.raw_product, line.rounded_ecl)
```
