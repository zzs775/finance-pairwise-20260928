# Loan Cash-Flow Schedule Engine

A small, dependency-free Java 21 engine that builds principal-and-interest cash-flow
plans for synthetic local fixed- and floating-rate loan contracts. It supports three
amortization structures, irregular first periods, month-end and business-day rules,
versioned floating fixings, prepayments and two recast modes.

Only the JDK standard library is used.

## Build and test

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1   # compile to out/
powershell -ExecutionPolicy Bypass -File test.ps1    # compile and run the test suite
```

The test suite is a zero-dependency harness (`loancashflow.tests`) with hand-computed
short-contract oracles. It exits non-zero on any failed assertion.

## CLI

```powershell
java -cp out loancashflow.cli.Cli demo
java -cp out loancashflow.cli.Cli run examples/floating-loan.spec
```

Output is CSV with one row per period: accrual boundaries, payment date, opening and
closing balance, rate, year fraction, interest, scheduled principal, prepayment,
total principal, payment and the governing reset date.

Spec files use `key = value` lines; see `examples/floating-loan.spec`. Repeated keys
`fixing.<n> = resetDate, rawRate, version` and
`prepayment.<n> = periodOrdinal, amount, SHORTEN_TERM|KEEP_TERM` are supported.

## Model

- `model.Contract` — nominal, value date, first payment date, maturity, frequency,
  day count, amortization method, month-end/stub/business-day rules, holiday calendar,
  rate spec, fixing table and frozen prepayment instructions.
- `AmortizationMethod`: `EQUAL_PRINCIPAL`, `ANNUITY`, `BULLET`.
- `DayCount`: `ACT_365F`, `ACT_360`, `THIRTY_360` (bond basis).
- `BusinessDayConvention`: `NONE`, `FOLLOWING`, `MODIFIED_FOLLOWING`.
- `StubConvention`: `NONE`, `SHORT_FIRST`, `LONG_FIRST`.
- `RecastMode`: `SHORTEN_TERM`, `KEEP_TERM`.

### Date semantics

- All dates are `LocalDate` with a fixed time-zone-free semantic.
- Accrual boundaries are generated first and never changed afterwards. Business-day
  adjustment applies to the payment date only, so an adjusted payment date can never
  move an interest accrual boundary.
- With the month-end rule enabled, a grid anchored on a month-end date places every
  regular boundary on the last day of its month.
- `NONE` stubs require the regular grid to land exactly on maturity; otherwise
  `INVALID_TERM` is raised. `SHORT_FIRST` and `LONG_FIRST` accept the irregular edge.

### Money semantics

- Money is always exactly two decimal places, `HALF_UP`; rates and year fractions use
  ten decimal places, `HALF_UP`.
- Per period: accrual factor = `rate * yearFraction` (10dp), interest is then rounded
  to 2dp. The annuity payment is solved to 2dp.
- Every row obeys `closing = opening + advance - totalPrincipal`.
- Rounding residual is absorbed by the last live period's scheduled principal so the
  sum of principal equals the nominal to the cent; no negative balance is possible.

### Floating rates and fixings

- Each period selects the latest versioned fixing for the reset date in effect on its
  accrual start, then applies `max(floor, min(cap, fixing + spread))`.
- Missing fixings raise `MISSING_FIXING`.
- A higher fixing version is a correction for the same reset date.
- `ScheduleEngine.applyFixingCorrection` rebuilds fully.
  `ScheduleEngine.regenerateFromFirstAffected` reuses rows before the first affected
  period and recomputes from there; the tests assert both paths are identical period by
  period (rates, interest, principal, payment, closing balance).
- Floating annuities re-solve the level payment at every reset boundary.

### Prepayments and recasts

- Prepayments are attached to a frozen 1-based in-period ordinal position, never a
  calendar date, so date adjustment cannot relocate them.
- `SHORTEN_TERM` drops the schedule after the period in which the balance reaches zero.
- `KEEP_TERM` re-solves the level payment at the prepayment period over the remaining
  periods and keeps all period dates.
- A prepayment that would exceed the outstanding balance is capped to outstanding;
  an amount beyond the opening balance itself is rejected as `INVALID_PREPAYMENT`.

### Error classification

`engine.ErrorCode` distinguishes `INVALID_DATE`, `MISSING_FIXING`,
`NEGATIVE_PRINCIPAL`, `SCALE_OVERFLOW`, `INVALID_TERM`, `INVALID_RATE`,
`INVALID_PREPAYMENT`, `NEGATIVE_BALANCE` and `RESIDUAL_NOT_ABSORBED`, surfaced as
`LoanException` with a descriptive message.

## Coverage

The automated suite (`src/loancashflow/tests`) covers:

- all three amortization methods against hand-computed numbers;
- short and long first stubs, leap-day ACT/365 fractions, month-end rule;
- following and modified-following adjustment preserving accrual boundaries;
- floating floor/cap boundaries, reset selection, versioned fixing corrections;
- both recast modes, full-balance prepayment and the no-negative-balance guarantee;
- invalid dates, negative/sub-cent principal, missing fixings, floor/cap errors,
  non-regular grids and out-of-range or duplicate prepayment positions.
