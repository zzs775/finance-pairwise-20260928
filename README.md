# FX Forward Valuation Engine (JDK 21, standard library only)

A small, deterministic engine for valuing standard-tenor and broken-date FX
forwards against synthetic local market data. It uses only the JDK standard
library (`java.base`); there is no build tool or third-party dependency.

## Domain rules

- All pair directions, notionals, spot/forward rates and discount factors use
  `BigDecimal` with fixed scales (`Scales`): rates 8 dp, discount factors
  10 dp, money 2 dp, all in `MathContext.DECIMAL64`.
- Failures are classified by the `Failure` enum and surfaced as `FxException`:
  invalid currency, curve hole, negative discount factor, reversed date order,
  arithmetic overflow (`|x| > 1E18`, null or divide-by-zero), bad tenor,
  invalid/failed snapshot publish and method tolerance breach.
- Spot date is T+2 counted in common business days of the two currency
  calendars (weekends + holiday file). Delivery for a standard tenor is the
  calendar-month/week shift from spot, adjusted once with modified following;
  the tenor is never re-rolled. Broken dates must be on/after spot and be a
  common business day. All date math uses the fixed zone `UTC`.
- Standard tenors land on their curve node; broken dates interpolate the
  **log** discount factor linearly in ACT/365 year fraction, implemented
  deterministically with `BigDecimal` series (`DfCurve.ln/exp`). Dates outside
  the first/last node are `CURVE_HOLE` - no extrapolation.
- Every valuation binds to one immutable, versioned `MarketSnapshot`.
  Publishing validates the whole candidate before a single atomic reference
  swap, so a failed publish can never expose new spot with old curves.

## Valuation

Covered interest parity (counter per one base):

```
F = S * DF_base / DF_counter
```

Two present values are computed in counter currency and must agree within one
fixed minor unit (`0.01`):

1. Forward difference: `N * (F - K) * DF_counter`
2. Dual cash flows: receive `N` base / pay `N*K` counter,
   `N * S * DF_base - N * K * DF_counter`

By CIP the two expressions are identical. Full-precision intermediates are
used internally; the fixed scales are applied for display and money rounding.
BUY and SELL with the same terms are exact opposites. The final NPV rounds the
average of the two methods in the conservative direction (BUY: floor,
SELL: ceiling) and reports that tail separately as `conservativeResidual`.

## Layout

- `src/fx` - domain model and engine.
- `src/cli/Main.java` - command line front end.
- `test/fx` - zero-dependency test harness and suite.
- `fixtures/` - all synthetic local inputs:
  - `holidays/<CCY>.txt` - one ISO date per line (order independent).
  - `market/snapshots.csv` - version/as-of table.
  - `market/spot.csv` - spot rates per version.
  - `market/curves/<CCY>.csv` - explicit discount-factor nodes per version.
  - `trades/trades.csv` - standard and `BROKEN` trades.

## Build and test

```powershell
./build.ps1            # compile into ./out and run all tests
./build.ps1 -Clean     # wipe ./out first
```

Or directly:

```powershell
javac -encoding UTF-8 -d out (Get-ChildItem -Recurse -Filter *.java src,test |
  ForEach-Object FullName)
java -cp out fx.EngineTest
```

## CLI

```powershell
java -cp out cli.Main --trade-date 2025-01-06
java -cp out cli.Main --snapshot v2 --trade-date 2025-01-07
java -cp out cli.Main --fixtures fixtures --help
```

The CLI prints delivery date, both method NPVs, their residual and the final
conservative NPV for every fixture trade; a trade that fails classification is
reported with its `Failure` category without aborting the batch.

## Test coverage

The suite (20 checks) covers reversed pairs, dual-calendar spot and delivery
rules, broken dates (holiday/order rejection), log-DF interpolation interior
and node-boundary behavior, no-extrapolation, long/short symmetry, covered
interest parity identity, snapshot switching, atomic (failed) publish and
arithmetic overflow. Every input comes from the local `fixtures/` files.
