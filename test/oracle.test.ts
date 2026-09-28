import assert from "node:assert/strict";
import { test } from "node:test";
import { dedupeRecords } from "../src/dedupe";
import { parseFixed } from "../src/fixedpoint";
import { oracleBreakdown } from "../src/oracle";
import { normalizeDataset } from "../src/validate";
import type { RawRecord } from "../src/types";

function records(side: "BUY" | "SELL", fillQty: string): RawRecord[] {
  return [
    {
      kind: "parent",
      id: "p1",
      symbol: "AAA",
      side,
      qty: "10",
      arrivalTime: "1000",
      deadlineTime: "2000",
      fee: "1",
    },
    {
      kind: "child",
      id: "c1",
      parentId: "p1",
      releaseTime: "1500",
      deadlineTime: "2000",
      qty: "10",
      fee: "0",
    },
    {
      kind: "fill",
      id: "f1",
      childId: "c1",
      time: "1700",
      qty: fillQty,
      price: "105",
      fee: "0.5",
    },
    { kind: "quote", source: "S1", seq: "1", symbol: "AAA", time: "0", bid: "99", ask: "101" },
    { kind: "quote", source: "S1", seq: "2", symbol: "AAA", time: "1000", bid: "101", ask: "103" },
    { kind: "quote", source: "S1", seq: "3", symbol: "AAA", time: "1500", bid: "103", ask: "105" },
    { kind: "quote", source: "S1", seq: "4", symbol: "AAA", time: "2000", bid: "109", ask: "111" },
  ];
}

function datasetOf(raws: RawRecord[]) {
  return normalizeDataset(dedupeRecords(raws).unique);
}

test("oracle recomputes BUY components fill by fill and interval by interval", () => {
  // arrival mid 102, release mid 104, deadline mid 110.
  // delay = 10*(104-102) = 20
  // trading = 10*(105-104) = 10
  // opportunity = 0 ; fee = 1.5 ; total = 31.5
  const breakdown = oracleBreakdown(datasetOf(records("BUY", "10")), "p1");
  assert.equal(breakdown.delay, parseFixed("20"));
  assert.equal(breakdown.trading, parseFixed("10"));
  assert.equal(breakdown.opportunity, 0n);
  assert.equal(breakdown.fee, parseFixed("1.5"));
  assert.equal(breakdown.total, parseFixed("31.5"));
});

test("oracle handles partial fills with deadline opportunity cost", () => {
  // filled 4: trading = 4*(105-104)=4 ; unfilled 6*(110-102)=48
  const breakdown = oracleBreakdown(datasetOf(records("BUY", "4")), "p1");
  assert.equal(breakdown.trading, parseFixed("4"));
  assert.equal(breakdown.opportunity, parseFixed("48"));
  assert.equal(breakdown.fee, parseFixed("1.5"));
  assert.equal(breakdown.total, parseFixed("73.5"));
});

test("oracle SELL signs price components negatively", () => {
  // delay = 10*(104-102)*-1 = -20 ; trading = 10*(105-104)*-1 = -10
  const breakdown = oracleBreakdown(datasetOf(records("SELL", "10")), "p1");
  assert.equal(breakdown.delay, parseFixed("-20"));
  assert.equal(breakdown.trading, parseFixed("-10"));
  assert.equal(breakdown.fee, parseFixed("1.5"));
});
