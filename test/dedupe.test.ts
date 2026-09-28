import assert from "node:assert/strict";
import { test } from "node:test";
import { dedupeRecords, recordKey } from "../src/dedupe";
import type { RawRecord } from "../src/types";

function fill(id: string, childId: string): RawRecord {
  return {
    kind: "fill",
    id,
    childId,
    time: "100",
    qty: "1",
    price: "10",
    fee: "0",
  };
}

function quote(source: string, seq: string): RawRecord {
  return {
    kind: "quote",
    source,
    seq,
    symbol: "AAA",
    time: "100",
    bid: "9",
    ask: "11",
  };
}

test("recordKey is a stable business key", () => {
  assert.equal(recordKey(fill("f1", "c1")), "fill:f1");
  assert.equal(recordKey(quote("SRC-A", "7")), "quote:SRC-A:7");
});

test("duplicates are idempotently removed with occurrence counts", () => {
  const a = fill("f1", "c1");
  const b = fill("f2", "c1");
  const altered: RawRecord = { ...a, price: "999" } as RawRecord;
  const result = dedupeRecords([a, b, altered, a]);
  assert.equal(result.unique.length, 2);
  // The first occurrence is kept, later payloads are ignored.
  assert.equal((result.unique[0] as { price: string }).price, "10");
  assert.deepEqual(result.duplicates, [
    { recordKey: "fill:f1", occurrences: 3 },
  ]);
});

test("canonical output is independent of input order", () => {
  const a = fill("f1", "c1");
  const b = fill("f2", "c1");
  const c = quote("S1", "1");
  const first = dedupeRecords([c, b, a]);
  const second = dedupeRecords([a, c, b, b]);
  assert.deepEqual(
    first.unique.map(recordKey),
    second.unique.map(recordKey),
  );
  assert.deepEqual(first.unique.map(recordKey), [
    "fill:f1",
    "fill:f2",
    "quote:S1:1",
  ]);
});
