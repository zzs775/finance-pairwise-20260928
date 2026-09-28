# Pre-trade Multi-dimensional Risk Reservation Engine

A deterministic, concurrency-safe pre-trade risk engine written in pure Go
(standard library only, Go 1.26). Orders are locally synthesized; there is no
network, broker or exchange integration.

## What it does

`Reserve` atomically checks and debits **three** risk dimensions at once:

1. **Cash** — buy notional (`price * qty`) against the account's cash limit.
2. **Risk limit** — gross notional for both buys and sells.
3. **Position** — sell quantity against available per-symbol holdings.

On every dimension the invariant

```
committed + reserved <= total
```

holds at all times. Reservation is two-phase: all three dimensions are
validated first, and only when every check passes are the balances debited.
Any single rejection leaves the engine state untouched (atomicity).

All amounts, prices and quantities are signed `int64` fixed-point integers;
every add/subtract/multiply goes through checked helpers (`Add64`, `Sub64`,
`Mul64`) so overflow is detected and reported as `CodeOverflow`.

## State machine

```
             Reserve
               │
               ▼
          ┌─────────┐   Confirm (now <= ExpireAt)   ┌───────────┐
          │reserved │ ───────────────────────────▶ │ confirmed │  terminal
          └─────────┘                               └───────────┘
            │     │
   Release  │     │ ExpireDue (tick >= ExpireAt)
            ▼     ▼
     ┌──────────┐  ┌─────────┐
     │ released │  │ expired │  terminal
     └──────────┘  └─────────┘
```

- Every reservation terminates exactly once; re-confirming/re-releasing a
  terminal reservation returns the first answer with `Replay: true`, while a
  transition that conflicts with the terminal state returns
  `CodeInvalidState`.
- **Same-tick freeze rule:** a `Confirm` at `now == ExpireAt` wins over expiry
  ("freeze priority"). At `now > ExpireAt` confirm is refused and
  `ExpireDue` reclaims the reservation. This makes the tie deterministic.
- `ExpireDue(now)` pops the stable min-heap (ordered by `(expireAt,
  reservationSeq)`) and returns reclaimed keys in that order.

## Idempotency

Every order carries a `Key`. The engine remembers the first call and its
result for each key:

- same key, identical parameters -> the stored first result (`Replay: true`),
- same key, different parameters -> `CodeDuplicateConflict`.

The parameter fingerprint excludes the key itself and covers account, symbol,
side, price, quantity and logical expiry tick.

## Error classification

`Result.Code` (or `ClassifiedError.Code` for account setup) is one of:
`negative`, `unknown_account`, `unknown_key`, `duplicate_conflict`,
`invalid_argument`, `overflow`, `insufficient_cash`,
`insufficient_position`, `insufficient_risk`, `invalid_state`.

## Determinism and concurrency

- Time comes only from an injectable logical clock (`LogicalClock` / the
  `Clock` interface). Wall-clock time is recorded for diagnostics but never
  participates in a decision; goroutine scheduling cannot change the outcome.
- All public operations are linearized under one mutex; each successful state
  transition appends one dense audit entry (`Seq` 1..N) at its
  linearization point.

## Serial reference model and linearization tests

`reference.go` contains `SerialModel`, a deliberately simple, single-threaded
implementation (maps + linear scan; no heap, no mutex). It encodes the same
specification independently.

`TestConcurrentLinearizability`:

1. launches a fixed set of goroutines released from a single start barrier;
2. each goroutine runs a deterministic script (reserve, duplicate retry,
   confirm, repeat confirm, release) using distinct keys plus shared account
   contention;
3. captures the engine's audit log — the exact linearization order — and
   replays it operation-by-operation into `SerialModel`;
4. asserts that per-operation results match, audit sequence numbers are dense
   and unique, total committed+reserved never exceeds any limit, and the
   canonical snapshots are byte-for-byte identical;
5. replays the same captured history onto a fresh engine and asserts the same
   snapshot again (history determinism).

This proves the concurrent history maps to one legal serial execution.

## Snapshot and recovery

`Snapshot` / `Restore` capture and rebuild the full state: account totals and
live buckets, every reservation and its status, the idempotency table, the
audit log and the sequence counter. `Canonical()` emits deterministic,
indented JSON (Go's `encoding/json` sorts map keys). Restoring rebuilds the
expiry heap for the surviving `reserved` items, so recovery preserves expiry
ordering and the freeze rule.

## Layout

- `clock.go` — injectable logical clock.
- `fixed.go` — checked fixed-point arithmetic.
- `errors.go`, `order.go`, `account.go` — error codes, order types and the
  three risk dimensions.
- `heap.go` — stable expiry min-heap.
- `idempotency.go` — first-result table.
- `engine.go` — concurrent engine, state machine, audit log.
- `snapshot.go` — canonical JSON snapshot and restore.
- `reference.go` — serial reference model.
- `*_test.go` — arithmetic, multi-dimension shortages, idempotency,
  duplicate confirm/release, expiry races, overflow, concurrency and snapshot
  recovery.
- `cmd/riskengine/main.go` — CLI demo on synthetic accounts/orders.

## Usage

```sh
go test ./...
go run ./cmd/riskengine
```

All accounts and orders used by the tests and CLI are local, synthetic data.
