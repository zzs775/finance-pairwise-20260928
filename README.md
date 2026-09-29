# Loan Covenant Monitoring Engine

A deterministic, zero-dependency loan covenant monitoring engine written in
pure Java 21 (JDK standard library only). It ingests entirely fictional
synthetic financial facts, payment events and in-file covenant definitions,
then computes period metrics, grace windows, waivers and default states
through a day-by-day oracle.

All borrowers, accounts and numbers in this repository are synthetic.

## Features

- Four test families: `RATIO`, `MINIMUM`, `MAXIMUM`, `PAYMENT_DATE`.
- `BigDecimal` values at a fixed per-metric scale with `HALF_UP` rounding.
- Every metric declares its period basis, annualization method and rounding
  order; results list fact sources (id + version) and a rendered formula tree.
- Period bases never mix:
  - `ROLLING_12` — trailing 12-month window ending at the test date
    (`[periodEnd - 1y + 1d, periodEnd]`, inclusive).
  - `POINT_IN_TIME` — stock facts at exactly `periodEnd`.
  - `CUMULATIVE` — flow facts intersecting `[inception, periodEnd]`.
- Classified errors: `DUPLICATE_FACT`, `UNIT_CONFLICT`, `PERIOD_OVERLAP`,
  `DIVISION_BY_ZERO`, `MISSING_DEFINITION`, `INVALID_PERIOD`, `OVERFLOW`.
- State machine: `COMPLIANT`, `BREACHED`, `CURE_PENDING`, `WAIVED`,
  `DEFAULTED`, driven entirely by injected logical dates (never the clock).
- Waivers are scoped to exact covenant + period labels + date range and never
  erase the original breach evidence.
- Versioned fact restatements rebuild from the earliest affected period;
  incremental restatement and full rebuild share one code path and produce
  byte-identical canonical output. A failed restatement never replaces the
  published snapshot (facts and snapshot are both rolled back).
- Input ordering never changes canonical output (all collections are sorted).

## Build and test

Requires JDK 21. No build tool or external dependency is used.

```sh
# Windows PowerShell
./build.ps1          # compile src + test into out/
./test.ps1           # build then run the test suite

# POSIX
sh build.sh
java -cp out loanmonitor.test.TestRunner
```

## CLI

```sh
java -cp out loanmonitor.cli.Cli <run|oracle|rebuild> <input-file> <asOf>
```

- `run` / `oracle` — advance day-by-day from inception to `asOf` (the two are
  the same explicit daily path).
- `rebuild` — full rebuild of the same horizon.

Output is the canonical snapshot text (statuses, metrics with sources and
formula traces, retained breach evidence, date-stamped alerts). Example:

```sh
java -cp out loanmonitor.cli.Cli oracle examples/fixture.txt 2024-07-15
```

## Input format

Line-oriented, `#` starts a comment, whitespace separated:

```
meta INCEPTION yyyy-MM-dd
period label start end
fact id borrower account value unit FLOW|STOCK start end asOf version
payment id borrower schedule amount unit dueDate paidDate|.
waiver id covenantId period1,period2 effective expiry
covenant id borrower type threshold graceDays
metric covenantId name resultUnit basis annualization rounding scale formula
```

- Dates use `.` when not applicable (stock facts use only `asOf`; flow facts
  use `start`/`end`).
- `basis`: `ROLLING_12 | POINT_IN_TIME | CUMULATIVE`.
- `annualization`: `NONE | ACTUAL_365 | TWELVE_MONTHS`.
- `rounding`: `FINAL | INTERMEDIATE`.
- Formula is an s-expression: `account@UNIT`, `NUMBER[unit]`,
  `(+ a b)`, `(- a b)`, `(* a b)`, `(/ a b)`.

## Boundary semantics

- All window and period bounds are inclusive on both ends.
- `ROLLING_12` starts at `periodEnd.minusYears(1).plusDays(1)`.
- Flow facts are selected by inclusive intersection with the metric window;
  overlapping flow facts for the same borrower/account/unit/version are
  rejected as `PERIOD_OVERLAP`.
- A breach is observed on the certification date; the following date opens
  the grace window (`CURE_PENDING`); the day after `breachDate + graceDays`
  without cure defaults. Curing on or before the inclusive grace deadline
  returns to `COMPLIANT`.
- Rounding order: `FINAL` rounds only the result; `INTERMEDIATE` rounds each
  operand and arithmetic step to the metric scale first.
- `maxIntegerDigits` on a metric (default 0 = unlimited) bounds the unscaled
  integer part; exceeding it is classified `OVERFLOW`.

## Layout

- `src/loanmonitor/model` — value types (facts, covenants, periods, results).
- `src/loanmonitor/Loader.java` — deterministic input parser.
- `src/loanmonitor/FormulaInterpreter.java` — direct formula evaluator.
- `src/loanmonitor/MetricCalculator.java` — window selection, annualization,
  rounding.
- `src/loanmonitor/StateMachine.java` — pure status transitions.
- `src/loanmonitor/DailyOracle.java` — day-by-day advancement.
- `src/loanmonitor/RestatementManager.java` — versioned restatements.
- `test/loanmonitor/test` — zero-dependency harness and 49 fixture tests
   covering ratio boundaries, rolling windows, missing facts, grace expiry,
   partial waivers, historical restatement and overflow.
