# Loan Covenant Monitoring Engine

A self-contained loan covenant monitoring engine written in plain **Java 21**
using only the JDK standard library. Every borrower and number in the bundled
fixtures is **locally fictional**; there is no network access and no external
dependency.

The engine consumes:

- synthetic financial facts (versioned, fixed-scale `BigDecimal`),
- payment events (scheduled vs actual date),
- in-task covenant definitions,
- injected logical dates (simulation clock),

and produces fixed-scale metric values with fact sources and a formula tree,
a five-state status machine, timestamped alerts, restatement handling and a
canonical, byte-deterministic snapshot format.

## Build and run

```powershell
# Compile all sources into out/
powershell -ExecutionPolicy Bypass -File build.ps1

# Automated tests (plain main, no JUnit dependency)
java -cp out covenant.tests.SelfTest

# Built-in fictional demo
java -cp out covenant.cli.Main
```

## Domain model

- `Unit` / `Dimension`: money (per currency), ratio, count, day, interest
  rate. Compatibility is exact (USD never mixes with EUR or count).
- `Fact`: borrower, metric, unit, half-open period `[periodStart, periodEnd)`,
  fixed-scale value, `version`, publication date `asOf`, and a `source` label.
- `PaymentEvent`: scheduled date, optional actual date, amount.
- `MetricSpec`: id, unit, period basis, annualization convention, rounding
  order, target scale and a direct formula (`Expr`).
- `CovenantTest`: one of `RATIO`, `MINIMUM`, `MAXIMUM`, `PAYMENT_DATE`,
  with threshold, grace days and a `breachIfMissing` flag.
- `Waiver`: scoped to exactly one borrower, one test and one period range.

## Tests and numeric rules

### Period bases (never mixed)

- `ROLLING_12`: facts fully contained in `[end-12 months, end)` are summed.
- `POINT_IN_TIME`: exactly one fact whose period equals the target period.
- `CUMULATIVE`: every fact fully contained in the target period is summed.

All periods use **half-open** boundaries: a fact ending exactly at the window
start is excluded, while a fact ending exactly at the window end is included.
Touching adjacent periods (`[a,b)` + `[b,c)`) do not overlap.

### Scale, annualization and rounding

- All arithmetic is `BigDecimal` with `RoundingMode.HALF_UP`.
- Each metric declares a fixed output `scale`.
- Annualization supports `NONE`, `ACT_365F`, `ACT_360`, `THIRTY_360`.
- `ROUND_BEFORE_ANNUALIZE` rounds the raw value first;
  `ROUND_AFTER_ANNUALIZE` rounds only the annualized result. The two orders
  give different answers and are independently tested.
- Division by zero is reported as `DivisionByZeroException`; absolute values
  at or above `1E18` are reported as `OverflowException`.

### Classified errors

`DuplicateFactException`, `UnitConflictException`,
`OverlappingPeriodException`, `DivisionByZeroException`,
`MissingDefinitionException`, `OverflowException` all extend the sealed
`CovenantException`. Missing facts can either throw (metric evaluation) or,
when a covenant test sets `breachIfMissing=false`, be treated as non-breach.

## Status machine

Statuses: `COMPLIANT`, `BREACHED`, `CURE_PENDING`, `WAIVED`, `DEFAULTED`.

- A breach measured on the period's exclusive end date opens a grace window
  using the **injected logical clock**: `firstBreachDate + graceDays`.
- Within grace the status is `CURE_PENDING`; curing returns to `COMPLIANT`
  with a `CURED` alert.
- The first day strictly after the grace window transitions to `DEFAULTED`.
- A waiver matching borrower + test + period and active on the evaluation
  date yields `WAIVED`. A waiver for another test, another period, or expired
  does not apply. When a waiver expires while grace remains, status returns
  to `CURE_PENDING`; once grace is exhausted it becomes `DEFAULTED`.
- `DEFAULTED` is terminal for the simulated run.
- Waivers never erase breach evidence: formula trees and evidence lines stay
  attached to the test period.

## Restatements

- Restating a fact adds a **new version**; the store keeps prior versions.
- Restatement rebuilds from the **earliest affected test period** onward.
- A valid restatement returns a rebuilt snapshot plus incremental
  `RESTATEMENT_CHANGED` alerts; full rebuilds are verified
  **byte-for-byte identical** (`Snapshot.canonicalBytes()` / `digest()`).
- A failing restatement (e.g. it introduces a zero denominator) returns the
  previously published snapshot instance with `applied=false`; the published
  monitor result is never replaced.

## Determinism

- Facts are stored and emitted in canonical order regardless of insertion
  order; tests and waiver lists are also ordered deterministically.
- `Snapshot.toCanonicalText()` is the single normative serialization used by
  both the CLI and the byte-identity checks.
- The day-by-day `Oracle` advances only forward on the injected clock and
  sorts alerts by date, borrower, test, period and type.

## Test coverage

`SelfTest` covers ratio boundaries, fixed scale and rounding order, rolling
windows and boundary dates, point-in-time vs cumulative, duplicate facts,
unit conflicts, overlapping periods, division by zero, missing definitions,
overflow, grace expiry, localized waivers with retained evidence,
payment-date tests, historical restatement (success and failure) and
input-order independence.

All borrower names (for example "HARBOR LIGHT FICTIONAL CO") and every
number are fictional test data.
