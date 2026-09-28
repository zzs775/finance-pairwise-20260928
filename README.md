# dcurve

Single-currency discount-curve bootstrapper implemented with **Python 3.13
standard library only**.  It builds discount factors segment by segment from
local **deposit**, **FRA** and **fixed-vs-floating par swap** quotes, then
re-prices a set of bond cash flows on the calibrated curve.

All money math uses `decimal.Decimal` at a fixed 50-digit context.

## Model and formulas

Discount factors `D(t)` live on a value date `t0` with `D(t0) = 1`.

### Deposits and FRAs

Year fractions use the quote's day count.  A deposit (and a forward-start
FRA, treated as a forward deposit) is par when

```
PV = N * ((1 + r * tau) * D(T) - D(S)) = 0
```

so its node is solved analytically inside the bracketed solver as
`D(T) = D(S) / (1 + r*tau)`.

### Par swaps

The fixed leg pays coupons on a schedule built by unadjusted backward rolls
from maturity, followed by business-day adjustment and short/long-first stub
handling.  With `tau_i` the accrual year fractions, the annuity is
`A = sum_i tau_i D(t_i)`.  The floating leg is worth par at each reset,
hence the par-swap residual solved at maturity `T` is

```
PV = N * (K * A + D(T) - D(T0)) = 0
```

This equation is linear in the unknown terminal `D(T)`; it is still solved
with the mandated bracketed bisection.

### Bootstrapping

1. Instruments are sorted by maturity with a **stable** tiebreak on id.
2. At each unique maturity the unknown node `D(T)` is varied; quotes ending
   at that date are priced against a trial curve built from solved nodes
   plus the trial node.
3. The solver searches `x = ln D(T)` in a bracket of `+/- 6` natural-log
   units around the previous node's `ln D` (around zero for the first node).
   Bracket endpoints must show opposite-sign unit-notional residuals.
4. Bisection stops when the unit-notional residual is `<= 1e-18`, or the
   log-width is `< 1e-20`, after at most 200 iterations.
5. After solving, every quote is re-priced independently and must pass the
   acceptance tolerance below; otherwise the whole build fails.

### Interpolation

Between nodes the curve is **log-linear** in the discount factor
(equivalently linear in continuously compounded zero rate):

```
ln D(t) = (1-u) ln D(t_i) + u ln D(t_{i+1}),   u = (t-t_i)/(t_{i+1}-t_i)
```

Queries strictly before the value date or strictly after the last node are
rejected with `ExtrapolationError`; `D(value_date) = 1` without a node.

## Scale

- PVs are reported in the quote's currency unit for the quoted notional;
  bootstrapping normalises residuals by notional so a 1e6 notional solves
  identically to a unit notional.
- Day counts supported: `act/360`, `act/365f`, `30/360`.
- Calendars: `WEEKEND` (Sat/Sun only) and the fixed in-problem `TARGET`
  calendar (weekends plus New Year, Good Friday, Easter Monday, Labour Day
  and Christmas/Boxing Day for 2024-2030).
- Business-day conventions: `following`, `modified_following`, `preceding`,
  `modified_preceding`, `none`.
- Stubs: `none`, `short_first`, `long_first`.

## Fixed tolerance

`dcurve.constants.REPRICING_TOLERANCE = 1e-12` is the absolute unit-notional
acceptance bound applied to every input instrument after construction.
Solved nodes are accurate to roughly 18-20 digits (50-digit arithmetic), so
the acceptance bound is a deliberately loose safety margin.

## Classified errors

| Exception | Trigger |
| --- | --- |
| `IllegalDateError` | non-date input, reversed intervals, non-positive periods, schedule without a declared stub |
| `DuplicateNodeError` | two same-kind instruments anchor the same maturity |
| `MissingTenorError` | pricing needs a cash flow beyond the last calibrated node |
| `NonPositiveDiscountFactorError` | zero/negative/NaN solved or supplied node |
| `NoBracketError` | endpoint residuals do not bracket zero |
| `IterationLimitError` | bisection exceeds 200 iterations |
| `NonFiniteValueError` | a midpoint residual is NaN/infinite |
| `ExtrapolationError` | discount query outside calibrated range |
| `RepricingToleranceError` | post-solve re-pricing exceeds `1e-12` |
| `QuoteValidationError` | bad ids, notionals, rates, calendar, unsorted flows |

## Versioning and atomic publication

`QuoteBook` is immutable; `with_version()` assigns a SHA-256 content
fingerprint (sorted by id, exact Decimal strings) so reordering quotes
produces the same `version_id`.  `CurveHolder.publish(book)` bootstraps
fully and replaces the held curve **only on success**; on any classified
failure the previously published curve remains queryable with its original
version.  Every discount factor is bound to the curve's `version_id`.

## Output

Canonical JSON (compact separators, exact Decimal strings, fixed key order)
contains the version id, value date, nodes, and one diagnostic per input
instrument:

- `model_pv` - model present value in notional units
- `market_target` - zero for every par quote
- `residual` - `model_pv - market_target`
- `iterations` - bisection iterations spent solving that node

plus `present_value` per bond.  Reordering input instruments yields
byte-identical JSON.

## Reproduction

No third-party packages are required; Python 3.13 is enough.

```powershell
# run the whole automated suite
python -m unittest discover -s tests -v

# build the bundled example curve and reprice the example bond
python -m dcurve examples/quotes.json -b examples/bonds.json

# write canonical JSON to a file instead of stdout
python -m dcurve examples/quotes.json -b examples/bonds.json -o result.json
```

Input JSON shape:

```json
{
  "value_date": "2025-01-13",
  "deposits": [
    {"id": "D3M", "start": "2025-01-13", "maturity": "2025-04-14",
     "rate": "0.03", "day_count": "act/360", "notional": "1"}
  ],
  "fras": [
    {"id": "F3Mx6M", "start": "2025-04-14", "maturity": "2025-07-14",
     "rate": "0.032", "day_count": "act/360", "notional": "1"}
  ],
  "swaps": [
    {"id": "S1Y", "effective": "2025-01-13", "maturity": "2026-01-13",
     "payment_tenor": "6M", "fixed_rate": "0.035",
     "day_count": "act/360", "calendar": "TARGET",
     "business_day_convention": "modified_following", "stub": "none",
     "notional": "1"}
  ]
}
```

Bonds are supplied separately:

```json
{"bonds": [{"id": "BOND-A", "flows": [
  ["2025-07-14", "0.0175"], ["2026-01-13", "1.0175"]]}]}
```

## Test coverage

- tenors, all three day counts, TARGET holidays, modified conventions
- short-first and long-first stubs, irregular-effective rejection
- node strict increase, duplicate/non-positive node rejection
- log-linear interpolation and both extrapolation boundaries
- analytic deposit fixture and hand-computable two-period swap oracle
- independent `reference_cashflow_pv` cross-check
- no-bracket, iteration cap, non-finite midpoint classification
- failed build keeps the previous curve fully queryable
- input-order invariance of nodes, diagnostics and canonical JSON
- CLI success path, classified CLI failure and bond repricing
