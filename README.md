# Rolling VaR Backtest Engine

A revisable rolling Value-at-Risk back-test engine in standard-library-only
C++20. It maintains locally synthesised forecasts (`VaR`) and realised PnL,
computes window quantiles exactly, counts exceptions and emits deterministic
segmented reports. Late records and historical corrections replace prior
observations and only the affected window suffix is recomputed; after any
revision sequence the incremental engine matches a full-sort reference
("oracle") day by day.

## Build and test

```sh
cmake -S . -B build
cmake --build build
ctest --test-dir build --output-on-failure
```

The test binary is `build/varbt-tests`; the CLI is `build/varbt-cli`.

## Data model and sign convention

An observation consists of:

- `day` — integer business-day index;
- `version` — model version identifier (segment key);
- `var` — predicted VaR, a **non-negative loss amount**, fixed point;
- `pnl` — realised PnL, signed, fixed point;
- `seq` — strict reception sequence number, globally increasing.

All money values are signed 64-bit fixed-point integers with
`kFracDigits = 6` minor units (`1.000000 == 10^6`). Parsing rejects malformed
strings, extra-precision tails and magnitude overflow (`INVALID_NUMERIC` /
`OVERFLOW`).

### Classified rejections

| Code | Meaning |
| --- | --- |
| `DUPLICATE_KEY` | `(day, version)` already present on `add` |
| `DATE_HOLE` | forward insert skips a day, or a segment has an interior gap at materialization |
| `SIGN_CONVENTION` | `var < 0` |
| `ILLEGAL_WINDOW` | `window < 1` or `0 < num <= den` violated |
| `OVERFLOW` | fixed-point magnitude does not fit `int64` |
| `BAD_SEQ` | `seq == 0` or not strictly greater than the last accepted sequence |
| `CORRECTION_NOT_FOUND` | `correct` / `erase` refers to an unknown key |

## Frozen quantile rule

No approximate statistics are used. For a window of `n` PnL values and level
`p/q` (`0 < p <= q`), the nearest-rank 1-based index is frozen as

```
rank  = ceil(p * n / q)          // exact 128-bit integer arithmetic
Q     = rank-th smallest PnL     // ties are distinct multiset elements
```

The window at day `t` contains exactly days `[t-window+1, t]` present in the
same version; advancement evicts expired observations exactly. Days with
fewer than `window` observations are reported as `warmup` (no quantile, no
exception count).

## Exceptions

An exception is a realised **loss strictly exceeding** VaR:

```
loss      = -pnl
exception = (loss > var)         // equality at the boundary is NOT an exception
```

Warm-up days never count as exceptions.

## Version segments and cross-segment windows

- The report is segmented by `version` in lexicographic, stable order.
- A version's effective day is its minimum stored day; its sliding window
  starts empty and warms up independently.
- Records before a version's effective day are never reinterpreted by a newer
  version's parameters: there is no cross-version window borrowing, and
  different versions may run on overlapping or independent day ranges.
- Within one version the business-day axis must be contiguous at
  materialization. Forward insertions that would skip the next day are
  rejected immediately; late insertions (any earlier day) are accepted and
  interior gaps are reported as `DATE_HOLE` until filled.

## Revisions

- `add` inserts a new key strictly.
- `correct(day, version, var, pnl, seq)` replaces the stored forecast/PnL of
  an existing observation; the new sequence must still advance.
- Late records and corrections invalidate cached results from the affected
  day onward. The engine rebuilds the exact window immediately preceding the
  invalidation point and recomputes only that suffix, so results after any
  revision sequence are identical to recomputing everything with the oracle.

## Checkpoints

Checkpoints live in a directory and use two alternating slots
(`data-a.bin`/`commit-a`, `data-b.bin`/`commit-b`):

1. Serialize parameters, every observation, the last sequence number and the
   FNV-1a input digest; protect the payload with an IEEE CRC-32 frame.
2. Stage `data-*.tmp` and `commit-*.tmp` fully, then atomically publish them
   over the **older** slot (POSIX `rename`; Windows
   `MoveFileEx(MOVEFILE_REPLACE_EXISTING)`).
3. A commit marker carries the generation; a checkpoint is considered
   complete only when the marker and data frame agree.

Properties:

- A failure while staging or publishing returns `IO_ERROR` and never touches
  the newest complete slot.
- Loading prefers the newest complete slot and transparently falls back to
  the other slot when the newest data is corrupt or truncated.
- Corruption, bad magic, format-version mismatch, checksum mismatch, digest
  mismatch or illegal parameters yield `CORRUPT_CHECKPOINT` /
  `CHECKPOINT_VERSION_MISMATCH`, never a partially published engine.
- Repeated restores are idempotent: the loaded state is a function of the
  frame bytes.

## CLI

```sh
varbt-cli init    <db> <window> <num> <den>
varbt-cli add     <db> <day> <version> <var> <pnl> <seq>
varbt-cli correct <db> <day> <version> <var> <pnl> <seq>
varbt-cli erase   <db> <day> <version>
varbt-cli report  <db>
```

Each mutating command loads the checkpoint, applies one validated change and
publishes a new generation. Example:

```sh
varbt-cli init db 3 1 100
varbt-cli add db 1 v1 0.50 0.10 1
varbt-cli add db 2 v1 0.50 -0.80 2
varbt-cli add db 3 v1 0.50 0.20 3
varbt-cli report db
```

## Test coverage

`tests/test_main.cpp` covers:

- ordered multiset duplicate handling, deletion and randomized comparison
  against a sorted `std::vector` oracle;
- fixed-point parsing, formatting and overflow;
- every classified rejection;
- exact eviction boundaries and strict exception boundary (`loss == var`);
- late insertion and model-version switch with independent warm-up;
- checkpoint round-trip, idempotent restore, corrupt-slot fallback and
  save-failure preservation of the previous checkpoint;
- randomized interleaved insert/correct sequences compared day by day with
  the naive full-sort oracle.
