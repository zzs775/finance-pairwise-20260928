# Offline FIX Session Recovery Engine

Pure-JDK (Java 21, no third-party dependencies) offline engine that consumes a
local synthetic FIX 4.4 message log, recovers session state, maintains inbound
and outbound sequence numbers, handles retransmit ranges, GapFill and PossDup
semantics, and commits applied business messages through a crash-safe
journal + checkpoint two-phase protocol.

All test fixtures are fictitious local data. No network sockets are opened.

## Build and test

```powershell
./build.ps1            # compile main classes to target/classes
./build.ps1 -Test      # compile and run the test suite
```

```bash
./build.sh test
```

The build is plain `javac`; the tests use a small in-repo assertion framework
(`fix.recovery.test.TestFramework`), so no JUnit or build tool is required.

## Synthetic log format

One FIX message per line. Lines may use the real SOH (`0x01`) or a literal
`|` delimiter. The engine validates a fixed tag subset:

`8 BeginString` (must be `FIX.4.4`), `9 BodyLength`, `35 MsgType`,
`49 SenderCompID`, `56 TargetCompID`, `43 PossDupFlag`, `52 SendingTime`,
`34 MsgSeqNum`, `122 OrigSendingTime`, `123 GapFillFlag`, `7 BeginSeqNo`,
`16 EndSeqNo`, `36 NewSeqNo`, `10 CheckSum`.

`BodyLength` counts bytes from after `9=` SOH up to the byte before `10=`.
`CheckSum` is the sum of all preceding bytes modulo 256, rendered as 3 digits.

Malformed input never reaches business state and is graded in the event log:

| condition | grade |
| --- | --- |
| empty line | INFO |
| truncated line, malformed field, duplicate tag | ERROR |
| wrong BodyLength, wrong CheckSum, invalid SendingTime | ERROR |
| unknown MsgType, missing required tag, non-numeric seq | ERROR |
| MsgSeqNum out of range (`<=0` or `> Integer.MAX_VALUE`) | FATAL |

FATAL input terminates recovery. ERROR input is recorded and skipped.

## Recovery semantics

- An inbound sequence gap emits a canonical `ResendRequest(35=2)`
  (`7=BeginSeqNo`, `16=EndSeqNo`, `0` means through latest) into the event log.
- Future messages are buffered in a `TreeMap` and applied in sequence order as
  gaps fill; shuffled input therefore produces the same canonical result.
- Each business message (`35=D`, `35=8`) is applied at most once; duplicate
  copies, with or without `43=Y`, are graded and never reapplied.
- `SequenceReset(35=4)` only moves forward (`36 > 34`); `GapFillFlag(123)=Y`
  ranges must not cover already-acknowledged business messages. Any backward
  reset aborts recovery with `RecoveryAbortedException`.

## Two-phase commit and crash recovery

For every applied business message the engine runs:

1. append the canonical business line to `business.log`;
2. phase-1 barrier (data durable) — crash point `AFTER_JOURNAL_APPEND` /
   `AFTER_JOURNAL_PREPARE`;
3. atomically write `checkpoint.ckp` (temp file + fsync + rename) — crash point
   `AFTER_CHECKPOINT_WRITE`;
4. append the durable `COMMIT n=.. line=.. bytes=.. next=..` decision record —
   crash point `AFTER_JOURNAL_COMMIT`.

On restart, bytes after the final `COMMIT` record (an unresolved transaction)
are truncated; the journal is the authoritative log and the checkpoint is
rebuilt from it when needed. Replaying the same input therefore neither loses
nor double-applies a message, and the recovered journal is byte-identical to a
fault-free run on the same input.

## Send-side retransmit

`ResendPlanner` answers a resend range against the persisted outbound store:
business messages are retransmitted with `43=Y` and their original
`OrigSendingTime(122)` preserved; runs of session/admin messages collapse into
GapFill SequenceReset messages. A range never produces a sequence hole and
never emits a record beyond the persisted high-water mark.

## Naive replay oracle

`ReplayOracle` is an independent, deliberately simple replay: it verifies that
expected sequence numbers are monotonic, that each business MsgSeqNum is
applied at most once, and folds the same canonical lines into an FNV-1a 64-bit
digest. The engine digest must equal the oracle digest; shuffling commutative
blocks or re-reading checkpoints does not change it.

## CLI

```bash
java -cp target/classes fix.recovery.Cli recover \
  --in path/to/inbound.log --work path/to/workdir [--oracle] [--crash POINT]
java -cp target/classes fix.recovery.Cli oracle --in path/to/inbound.log
```

`--crash` accepts `AFTER_JOURNAL_APPEND`, `AFTER_JOURNAL_PREPARE`,
`AFTER_CHECKPOINT_WRITE`, or `AFTER_JOURNAL_COMMIT` to inject a crash for
recovery testing; rerunning the same command resumes and converges.

## Layout

- `src/main/java/fix/recovery` — parser, checks, state, commit, planner,
  engine, oracle, CLI.
- `src/test/java/fix/recovery/test` — fixtures and tests for gaps,
  duplicates, reordering, illegal resets, checksum corruption and crash
  recovery at every commit point.
