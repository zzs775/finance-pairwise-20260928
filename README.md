# Loan Cashflow Engine

Pure Java 21 implementation of a loan cashflow planning engine. No external
dependencies: only the JDK standard library is used.

It builds principal-and-interest schedules for locally synthesised fixed and
floating rate contracts, including:

- amortization: equal principal, equal payment (annuity), bullet;
- short/long first periods via an explicit first payment date;
- month-end (EOM) rule and business-day adjustment
  (`NONE`, `FOLLOWING`, `MODIFIED_FOLLOWING`);
- day counts `ACT/365F`, `ACT/360`, `30/360`;
- floating resets with versioned fixings, spread, floor and cap;
- prepayments booked at a frozen in-period position, with two recasts:
  shorten term or keep term (payment recomputation);
- fixing corrections recomputed from the first affected period, identical to
  a full regeneration.

## Build and test

```powershell
./build.ps1
./test.ps1
```

`build.ps1` compiles `src/main/java` into `out/`; `test.ps1` then compiles and
runs the JDK-only test suite.

## CLI example

```powershell
java -cp out loanengine.cli.Main `
  --notional=10000.00 --valueDate=2025-01-31 --maturityDate=2025-04-30 `
  --rate=0.06 --amortization=EQUAL_PAYMENT --dayCount=ACT_365F
```

CSV columns: period, accrual start/end, payment date, opening balance,
advance, applied rate, interest, scheduled principal, prepayment, total
payment, closing balance, fixing version and recast flag.

## Numeric conventions

- money scale is fixed at 2 decimal places (`Contract.MONEY_SCALE`), rounding
  `HALF_UP`; values with a finer scale are rejected as `SCALE_OVERFLOW`;
- rates are stored at 10 decimal places, year fractions at 10 decimal places;
- per period: `interest = round(opening * rate * yearFraction, 2)`;
- `closing = opening + advance - scheduledPrincipal - prepayment`, exactly;
- the level annuity payment is solved from a constant period factor derived
  from the first period of each level-payment segment (initial generation,
  new reset segment, keep-term recast); the final period always absorbs the
  legal cent tail, so principal repays the notional exactly;
- no path may produce a negative closing balance; oversized prepayments are
  capped at the outstanding balance.

## Date semantics

- dates are computed with `java.time` local dates, with no implicit timezone
  conversion;
- payment dates are adjusted with the chosen convention, but accrual
  boundaries are never moved by payment adjustment;
- without an explicit first payment date, regular dates run forward from the
  value date; an explicit first payment date defines the anchor sequence and
  may create a short or long first (stub) period; the final date is always the
  maturity date.

## Errors

`LoanException.Category` classifies:

- `INVALID_DATE` - inconsistent dates, bad first payment date, bad prepayment
  date, non-whole schedule without an explicit first payment date;
- `MISSING_FIXING` - a floating period has no published fixing for the
  applicable reset date;
- `NEGATIVE_PRINCIPAL` - non-positive notional/prepayment or negative closing
  balance;
- `SCALE_OVERFLOW` - money with more than 2 decimal places, or an unrepaid
  tail at maturity.

## Tests

The suite in `src/test/java` uses a tiny built-in assertion runner and covers
all three amortization methods, short/long stubs, leap days, month-end
sequences, business-day adjustment, floating rate boundaries, both recasts,
prepayment clamping, fixing correction consistency and error classification.
Short hand-computed contracts are used as oracles.
