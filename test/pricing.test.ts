import assert from "node:assert/strict";
import { test } from "node:test";
import { dedupeRecords } from "../src/dedupe";
import { parseFixed } from "../src/fixedpoint";
import { normalizeDataset } from "../src/validate";
import { analyzeChild, analyzeParent, buildTimelines } from "../src/pricing";
import type { RawRecord } from "../src/types";

function rawSet(side: "BUY" | "SELL", fillPrice: string, fillQty = "10"): RawRecord[] {
  // Parent window [1000,2000), single child fully covering it.
  // Quotes: t=0 mid 100, t=1000 mid 102, t=2000 mid 110.
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
      releaseTime: "1000",
      deadlineTime: "2000",
      qty: "10",
      fee: "0",
    },
    {
      kind: "fill",
      id: "f1",
      childId: "c1",
      time: "1500",
      qty: fillQty,
      price: fillPrice,
      fee: "0.5",
    },
    { kind: "quote", source: "S1", seq: "1", symbol: "AAA", time: "0", bid: "99", ask: "101" },
    { kind: "quote", source: "S1", seq: "2", symbol: "AAA", time: "1000", bid: "101", ask: "103" },
    { kind: "quote", source: "S1", seq: "3", symbol: "AAA", time: "2000", bid: "109", ask: "111" },
  ];
}

function analyze(records: RawRecord[]) {
  const { unique } = dedupeRecords(records);
  const dataset = normalizeDataset(unique);
  const timelines = buildTimelines(dataset);
  const child = analyzeChild(dataset, timelines, "c1", dataset.errors);
  const parent = analyzeParent(dataset, timelines, "p1", [child.result], dataset.errors);
  return { dataset, child: child.result, parent };
}

test("fully filled BUY: four-term identity equals raw shortfall", () => {
  // Filled 10 @105 (fees 0.5 + parent 1 = 1.5), arrival mid 102.
  // Raw shortfall = 10*(105-102) + 1.5 = 31.5
  const { parent, dataset } = analyze(rawSet("BUY", "105"));
  assert.equal(dataset.errors.length, 0);
  assert.equal(parent.filledQty, parseFixed("10"));
  assert.equal(parent.delayCost.amount, 0n); // release == arrival
  assert.equal(parent.tradingCost.amount, parseFixed("30"));
  assert.equal(parent.opportunityCost.amount, 0n);
  assert.equal(parent.feeCost.amount, parseFixed("1.5"));
  assert.equal(parent.totalShortfall.amount, parseFixed("31.5"));
  const sum =
    parent.delayCost.amount +
    parent.tradingCost.amount +
    parent.opportunityCost.amount +
    parent.feeCost.amount;
  assert.equal(sum, parent.totalShortfall.amount);
});

test("SELL flips the sign of price-driven costs", () => {
  // Sell 10 @105 vs arrival mid 102 => trading cost -30 (favorable).
  const { parent } = analyze(rawSet("SELL", "105"));
  assert.equal(parent.tradingCost.amount, parseFixed("-30"));
  assert.equal(parent.feeCost.amount, parseFixed("1.5"));
  assert.equal(parent.totalShortfall.amount, parseFixed("-28.5"));
});

test("partial fill books opportunity cost at deadline price", () => {
  // Buy 10, fill only 4 @105, deadline mid 110, arrival mid 102.
  // trading = 4*(105-102) = 12
  // opportunity = 6*(110-102) = 48
  // fee = 0.5 + 1 = 1.5 ; total = 61.5
  const { parent, child } = analyze(rawSet("BUY", "105", "4"));
  assert.equal(child.filledQty, parseFixed("4"));
  assert.equal(child.vwap, parseFixed("105"));
  assert.equal(parent.tradingCost.amount, parseFixed("12"));
  assert.equal(parent.opportunityCost.amount, parseFixed("48"));
  assert.equal(parent.feeCost.amount, parseFixed("1.5"));
  assert.equal(parent.totalShortfall.amount, parseFixed("61.5"));
});

test("delay cost reflects release vs arrival mid when release is later", () => {
  const records = rawSet("BUY", "102");
  // Push child release to t=2000 where mid is 110; deadline 2000.
  const child = records.find((r) => r.kind === "child") as Extract<RawRecord, { kind: "child" }>;
  child.releaseTime = "2000";
  const fill = records.find((r) => r.kind === "fill") as Extract<RawRecord, { kind: "fill" }>;
  fill.time = "2000";
  const { parent } = analyze(records);
  // delay = 10*(110-102) = 80 ; trading = 10*(102-110) = -80
  assert.equal(parent.delayCost.amount, parseFixed("80"));
  assert.equal(parent.tradingCost.amount, parseFixed("-80"));
});
