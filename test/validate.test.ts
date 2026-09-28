import assert from "node:assert/strict";
import { test } from "node:test";
import { normalizeDataset } from "../src/validate";
import type { RawRecord } from "../src/types";

function parent(over: Partial<RawRecord> = {}): RawRecord {
  return {
    kind: "parent",
    id: "p1",
    symbol: "AAA",
    side: "BUY",
    qty: "10",
    arrivalTime: "1000",
    deadlineTime: "2000",
    fee: "0",
    ...over,
  } as Partial<RawRecord> as RawRecord;
}

function child(over: Partial<RawRecord> = {}): RawRecord {
  return {
    kind: "child",
    id: "c1",
    parentId: "p1",
    releaseTime: "1000",
    deadlineTime: "2000",
    qty: "10",
    fee: "0",
    ...over,
  } as Partial<RawRecord> as RawRecord;
}

function fill(over: Partial<RawRecord> = {}): RawRecord {
  return {
    kind: "fill",
    id: "f1",
    childId: "c1",
    time: "1500",
    qty: "5",
    price: "100",
    fee: "0",
    ...over,
  } as Partial<RawRecord> as RawRecord;
}

function quote(over: Partial<RawRecord> = {}): RawRecord {
  return {
    kind: "quote",
    source: "S1",
    seq: "1",
    symbol: "AAA",
    time: "900",
    bid: "99",
    ask: "101",
    ...over,
  } as Partial<RawRecord> as RawRecord;
}

function codes(records: RawRecord[]): string[] {
  return normalizeDataset(records).errors.map((e) => e.code);
}

test("valid dataset normalizes without errors", () => {
  const ds = normalizeDataset([parent(), child(), fill(), quote()]);
  assert.equal(ds.errors.length, 0);
  assert.equal(ds.parents.length, 1);
  assert.equal(ds.fills.length, 1);
  assert.equal(ds.quotes.length, 1);
});

test("bad side yields E_BAD_SIDE", () => {
  assert.deepEqual(codes([parent({ side: "LONG" })]), ["E_BAD_SIDE"]);
});

test("fill side disagreeing with parent yields E_SIDE_MISMATCH", () => {
  assert.deepEqual(
    codes([parent(), child(), fill({ side: "SELL" })]),
    ["E_SIDE_MISMATCH"],
  );
});

test("missing parent and child relationships are reported", () => {
  assert.deepEqual(codes([child()]), ["E_PARENT_NOT_FOUND"]);
  assert.deepEqual(codes([parent(), child({ parentId: "p1" }), fill({ childId: "ghost" })]),
    ["E_PARENT_NOT_FOUND"]);
});

test("time interval violations", () => {
  assert.deepEqual(codes([parent({ arrivalTime: "3000" })]),
    ["E_CHILD_INTERVAL"]);
  assert.deepEqual(
    codes([parent(), child({ releaseTime: "900" })]),
    ["E_CHILD_INTERVAL"],
  );
  assert.deepEqual(
    codes([parent(), child(), fill({ time: "2500" })]),
    ["E_FILL_OUTSIDE_CHILD"],
  );
});

test("cumulative fill qty beyond child qty is rejected per record", () => {
  const ds = normalizeDataset([
    parent(),
    child({ qty: "6" }),
    fill({ id: "f1", qty: "4" }),
    fill({ id: "f2", qty: "4" }),
  ]);
  assert.equal(ds.fills.length, 1);
  assert.deepEqual(ds.errors.map((e) => e.code), ["E_FILL_QTY_EXCEEDED"]);
});

test("cumulative child qty beyond parent qty", () => {
  const ds = normalizeDataset([
    parent({ qty: "5" }),
    child({ id: "c1", qty: "3" }),
    child({ id: "c2", qty: "3" }),
  ]);
  assert.equal(ds.children.length, 1);
  assert.deepEqual(ds.errors.map((e) => e.code), ["E_FILL_QTY_EXCEEDED"]);
});

test("bid above ask", () => {
  assert.deepEqual(codes([quote({ bid: "102", ask: "101" })]),
    ["E_BID_ABOVE_ASK"]);
});

test("bad numbers, times and missing fields get stable codes", () => {
  assert.deepEqual(codes([parent({ qty: "1.0000000001" })]), ["E_BAD_NUMBER"]);
  assert.deepEqual(codes([parent({ arrivalTime: "noon" })]), ["E_BAD_TIME"]);
  const broken = parent() as unknown as Record<string, unknown>;
  delete broken.symbol;
  assert.deepEqual(codes([broken as unknown as RawRecord]), ["E_MISSING_FIELD"]);
});

test("out-of-order and duplicate inputs normalize identically", () => {
  const records = [fill(), quote(), child(), parent(), fill()];
  const a = normalizeDataset(records);
  const b = normalizeDataset([...records].reverse());
  assert.deepEqual(
    a.fills.map((f) => f.id),
    b.fills.map((f) => f.id),
  );
  assert.equal(a.fills.length, b.fills.length);
  // normalizeDataset does not de-duplicate (dedupe is a separate pipeline
  // stage); both identical fill copies survive, in stable order.
  assert.equal(a.fills.length, 2);
  assert.deepEqual(a.fills.map((f) => f.id), ["f1", "f1"]);
});
