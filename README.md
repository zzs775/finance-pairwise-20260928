# mktcodec

Local, std-only segmented binary codec for synthetic market-quote time series.
It compresses `(timestamp, bid, ask, volume)` records into a randomly
addressable container: time-range queries decode only the intersecting
segments and resume from the nearest absolute checkpoint.

No third-party crates are used; the crate builds with a stable Rust toolchain.

## Records and invariants

A quote is four signed 64-bit integer fields:

| field  | meaning                              |
| ------ | ------------------------------------ |
| `ts`   | timestamp, strictly increasing      |
| `bid`  | best bid, must satisfy `bid <= ask`  |
| `ask`  | best ask                             |
| `size` | traded volume, must be non-negative  |

Prices and quantities are integers; there is no floating point. Every
validation failure is reported with the absolute byte offset of the first bad
byte and a stable error category (see `src/error.rs`).

## Container format

All multi-byte integers are **little endian**.

- **Magic:** `MQT1` (`4D 51 54 31`).
- **Schema version:** `1` (`SCHEMA_VERSION` in `src/header.rs`).
- **File header:** fixed 32 bytes
  (`magic u32 | version u16 | records_per_segment u32 | segment_count u32 |
  directory_offset u64 | directory_length u32 | reserved`).
- **Segments:** one contiguous payload per segment, followed by a 12-byte
  trailer (`record_count u32 | crc32 u32 | marker u32 = 0x53435254`).
  Segment ranges must be exactly adjacent and never overlap.
- **Directory:** fixed 40-byte entries written after all segments
  (`index u32 | data_offset u64 | data_length u32 | record_count u32 |
  first_ts i64 | last_ts i64 | crc32 u32`).

`MAX_RECORDS_PER_SEGMENT` is `2^20`; the configured records-per-segment value
must be between 1 and that cap.

### Record stream

Inside a segment payload:

- `0x01` **absolute** record: `ts, bid, ask, size` (always the first record of
  a segment).
- `0x02` **delta** record: `delta_of_delta(ts), bid_delta, ask_delta, size`.
- `0x03` **checkpoint** record: absolute `ts, bid, ask, size` emitted every
  `CHECKPOINT_INTERVAL = 64` records and at each segment start. Checkpoints
  restart the delta ladder, so random reads can seek to the latest checkpoint
  before the query start instead of decoding from segment offset zero.

Each value is ZigZag mapped (`(n << 1) ^ (n >> 63)`) then written as an
unsigned LEB128 varint. Decoders reject:

- varints longer than 10 bytes (`varint_too_long`),
- the 10th group carrying bits outside u64 (`overflow`),
- redundant trailing zero groups such as `80 00` (`non_canonical_varint`),
- truncated streams (`truncated`).

### Integrity model

Before any allocation of decoded records, a segment verifies its declared
bounds against the file length, the trailer marker, the directory count, and
the CRC-32 (IEEE/zlib polynomial `0xEDB88320`) of the payload. Only after the
checksum matches is the payload parsed. Truncation, bit flips, overlapping
directory entries, and forged lengths therefore fail closed with a typed
error; no partially trusted records are returned.

## Random access semantics

`query_range(bytes, start, end)` returns quotes in the **half open** interval
`start <= ts < end`. It consults the directory, skips segments whose
`[first_ts, last_ts]` span does not intersect the query, and inside each
intersecting segment resumes just after the latest checkpoint with
`checkpoint.ts < start`. Results are bit-for-bit identical to a full sequential
decode followed by the same timestamp filter (exercised in
`tests/roundtrip.rs`).

## Mechanical oracle

`mktcodec::oracle::assert_canonical_roundtrip` encodes a quote set, decodes it,
compares every field of every record, then re-encodes and asserts the second
byte vector equals the first. Encoding is canonical: equal logical input always
produces identical bytes.

## CLI

The text interchange format is JSON Lines, one object per line:

```json
{"ts":1000,"bid":99,"ask":101,"size":5}
```

Commands (`src/main.rs`):

```sh
cargo run --release -- encode  out.mq 40 < records.jsonl
cargo run --release -- decode  out.mq
cargo run --release -- query   out.mq 1300 1320
cargo run --release -- verify  out.mq
```

`query` takes `[start, end)` i64 bounds. `verify` validates the full container
and prints the record count; corrupted input exits non-zero with the error
category and first bad byte offset.

## Reproduce

```sh
cargo test --release
cargo build --release
```

The test suite covers:

- field and i64 extremes across segment boundaries,
- constant-gap / sawtooth / deterministic pseudo-random compression patterns,
- checkpoint and cross-segment boundary queries,
- random range queries compared against full sequential decode plus filter,
- non-canonical varints, CRC failures, truncation, unknown tags,
  overlapping directories, and forged lengths/counts/markers.

End-to-end smoke test:

```sh
cargo run --release -- encode sample.mq 40 < records.jsonl
cargo run --release -- verify sample.mq
cargo run --release -- query sample.mq 1300 1320
```
