# Fixed-Income Schedule & Accrual Engine

A pure-JDK (Java 21) fixed-income schedule and accrual engine. It generates
short/long stubs, ex-dividend windows, amortizing notionals and
business-day-adjusted cash flows from in-spec synthetic bond terms. No
third-party libraries are used.

## Build & test

```powershell
# compile
powershell -ExecutionPolicy Bypass -File build.ps1

# compile and run the dependency-free test suite
powershell -ExecutionPolicy Bypass -File build.ps1 -Test
```

Outputs land in `out/`. The test harness reports
`checks=<n> failures=0 ... ALL TESTS PASSED` and exits non-zero on failure.

## CLI

```powershell
java -cp out fi.Main list
java -cp out fi.Main schedule regular
java -cp out fi.Main schedule eom-amort
java -cp out fi.Main json short-final
java -cp out fi.Main settle regular 2025-07-09
java -cp out fi.Main settle regular 2025-07-10
java -cp out fi.Main roundtrip long-initial
```

Sample bonds (frozen in `fi.SampleBonds`):

| id | coverage |
| --- | --- |
| `regular` | standard semi-annual, modified-following, 3 business-day ex-dividend |
| `short-final` | quarterly with a short final stub, act/365F, following |
| `long-initial` | long first stub, 30/360, preceding |
| `eom-amort` | EOM roll from a leap-day start, short initial stub, step rate and amortizing notional |

## Contract

`BondTerms` carries value date, maturity date, frequency, coupons, notional
plan, day count, EOM flag, business-day convention, ex-dividend business-day
count and the in-spec holiday calendar. Validation rejects with typed codes:

| code | trigger |
| --- | --- |
| `CONFLICTING_TERMS` | `NONE` stub on an irregular term, negative coupon rate, first step not on value date, outstanding notional rising after amortization |
| `ILLEGAL_FREQUENCY` | frequency months other than 1/3/6/12 |
| `NEGATIVE_NOTIONAL` | any outstanding notional below zero |
| `DATE_ORDER` | maturity not after value date, or non-ascending step/boundary dates |
| `INVALID_TERMS` | null/missing fields or malformed serialization |

## Schedule rules (frozen)

1. **Unadjusted boundaries first.** Regular dates are anchored at maturity
   and rolled backward in frequency increments. Payment dates are adjusted
   independently afterwards; adjusting a payment date never changes the
   unadjusted accrual start/end (asserted in `ScheduleTest`).
2. **Stubs.**
   - `NONE`: value date equals the first regular date.
   - `SHORT_FINAL` / `LONG_FINAL`: dates roll forward from value date; the
     final stub absorbs the last regular period for the long variant.
   - `SHORT_INITIAL` / `LONG_INITIAL`: dates roll backward from maturity; the
     initial stub absorbs the first regular period for the long variant.
3. **EOM and leap days.** When `eom=true` and an anchor is the last calendar
   day of its month, every rolled date lands on the last day of its month
   (February rolls to 28/29 as appropriate). When `eom=false` only the raw
   month shift applies.
4. **Business-day adjustment.** Weekend plus listed holidays are non-business
   days. `FOLLOWING`, `PRECEDING` and `MODIFIED_FOLLOWING` (roll back when the
   following date crosses months) are supported; `UNADJUSTED` keeps the date.
5. **Contiguous periods.** Adjacent periods share one boundary, so there are
   no gaps or overlaps.

## Accrual and amortization

- Day counts: `ACT/360`, `ACT/365F`, `30/360` (31→30 convention).
- Money is `BigDecimal` scale 2, rates scale 6, year fractions scale 10, all
  rounded half-up.
- A period is split at every in-force rate or notional step date. Segment
  coupons are computed independently and summed; therefore the sum of segment
  coupons is **exactly** the period coupon.
- Daily accrued interest sums completed segments plus the day-count fraction
  of the active segment. At the period start accrual is zero; at the period
  end it equals the period coupon exactly (`OracleTest` verifies this for
  every day of every sample, monotonic non-decreasing).
- Principal cash flows equal the decrease in outstanding notional at each
  boundary. The notional never rises within a schedule and never goes
  negative; validation rejects a rising plan before generation.

## Ex-dividend

The ex date is derived from the **unadjusted** period end by walking backward
`exDividendBusinessDays` using only in-spec business days (weekends and
listed holidays are skipped). Settlement strictly before the ex date means
the buyer receives the next coupon; settlement on or after the ex date means
the seller retains it. The CLI `settle` command prints a recomputable
diagnostic for the boundary dates (see the `ExDividendTest` one-day-before /
on / one-day-after checks).

## Versioned serialization

`fi.io.Codec` produces canonical version-1 JSON: object keys sorted, arrays
preserving schedule order, no insignificant whitespace, ISO dates and exact
`BigDecimal` literals.

- Round-trip is byte-idempotent: `encode(decode(encode(x))) == encode(x)`.
- Dates, cash-flow order and amounts are preserved.
- Every object has a frozen field whitelist; unknown fields from a newer
  producer are explicitly rejected (`INVALID_TERMS`).
- Versions newer than the reader are rejected; missing fields in older
  payloads are filled from the frozen defaults table (`eom=false`,
  `exDividendBusinessDays=0` for the version-1 baseline).

## Layout

```
src/fi/            engine, CLI and frozen synthetic samples
src/fi/io/         canonical JSON parser/writer and versioned codec
src/fi/model/      daily accrual result
test/fi/           dependency-free tests including the daily accrual oracle
build.ps1          compile (+ -Test to run the suite)
```
