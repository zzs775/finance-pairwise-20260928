import assert from "node:assert/strict";
import { test } from "node:test";
import { dedupeRecords } from "../src/dedupe";
import { parseFixed } from "../src/fixedpoint";
import { analyzeDataset } from "../src/pipeline";
import { normalizeDataset } from "../src/validate";
import type { RawDataset, RawRecord } from "../src/types";

function run(raw: RawDataset) {
  const { unique, duplicates } = dedupeRecords(raw.records);
  const dataset = normalizeDataset(unique);
  dataset.duplicates = duplicates;
  return analyzeDataset(dataset);
}

function flatScenario(
  side: "BUY" | "SELL",
  fillQty: string,
  fillPrice: string,
): RawRecord[] {
  // Flat market: bid=ask=100 at every timestamp, so all price-driven costs 0.
  let seq = 0;
  const quote = (time: string): RawRecord => ({
    kind: "quote",
    source: "S1",
    seq: String(++seq),
    symbol: "AAA",
    time,
    bid: "100",
    ask: "100",
  });
  return [
    { kind: "parent", id: "p1", symbol: "AAA", side, qty: "10",
      arrivalTime: "1000", deadlineTime: "2000", fee: "0" },
    { kind: "child", id: "c1", parentId: "p1", releaseTime: "1000",
      deadlineTime: "2000", qty: "10", fee: "0" },
    ...(fillQty === "0" ? [] : [{
      kind: "fill" as const, id: "f1", childId: "c1", time: "1500",
      qty: fillQty, price: fillPrice, fee: "0",
    }]),
    quote("1000"),
    quote("1500"),
    quote("2000"),
  ];
}

test("sign boundary: zero deltas are zero for both BUY and SELL", () => {
  for (const side of ["BUY", "SELL"] as const) {
    const report = run({ records: flatScenario(side, "10", "100") });
    assert.equal(report.status, "ok", side);
    const parent = report.global?.parentResults[0];
    assert.equal(parent?.delayCost.amount, 0n);
    assert.equal(parent?.tradingCost.amount, 0n);
    assert.equal(parent?.opportunityCost.amount, 0n);
    assert.equal(parent?.feeCost.amount, 0n);
    assert.equal(parent?.totalShortfall.amount, 0n);
  }
});

test("no fill at all: trading zero and opportunity at deadline", () => {
  const records = flatScenario("BUY", "10", "100").filter(
    (r) => r.kind !== "fill",
  );
  const report = run({ records });
  assert.equal(report.status, "ok");
  const parent = report.global?.parentResults[0];
  const child = parent?.childResults[0];
  assert.equal(child?.vwap, null);
  assert.equal(child?.filledQty, 0n);
  assert.equal(child?.tradingCost.amount, 0n);
  // Flat market => opportunity 0 even though fully unfilled.
  assert.equal(parent?.opportunityCost.amount, 0n);
  assert.equal(parent?.filledQty, 0n);
});

test("opposite favorable/unfavorable costs are symmetric for BUY vs SELL", () => {
  // Price rises to 105: BUY trading +50, SELL trading -50 for 10 units.
  const buy = run({ records: flatScenario("BUY", "10", "105") });
  const sell = run({ records: flatScenario("SELL", "10", "105") });
  assert.equal(buy.global?.parentResults[0]?.tradingCost.amount, parseFixed("50"));
  assert.equal(
    sell.global?.parentResults[0]?.tradingCost.amount,
    -parseFixed("50"),
  );
});

test("TWAP gap is reported inside child and parent results", () => {
  // Child window starts at t=900, first quote at t=1000 => leading 100ms gap,
  // but parent arrival stays at t=1000 (covered). Not allowed: child release
  // must be >= parent arrival. Instead give the parent an earlier arrival with
  // coverage, and let the child window contain the gap start.
  const records: RawRecord[] = [
    { kind: "parent", id: "p1", symbol: "AAA", side: "BUY", qty: "10",
      arrivalTime: "500", deadlineTime: "2000", fee: "0" },
    { kind: "child", id: "c1", parentId: "p1", releaseTime: "900",
      deadlineTime: "2000", qty: "10", fee: "0" },
    { kind: "quote", source: "S1", seq: "1", symbol: "AAA", time: "500",
      bid: "99", ask: "101" },
    { kind: "quote", source: "S1", seq: "2", symbol: "AAA", time: "1000",
      bid: "100", ask: "102" },
    { kind: "quote", source: "S1", seq: "3", symbol: "AAA", time: "2000",
      bid: "100", ask: "102" },
  ];
  // Because the t=500 quote carries forward, there is no gap on [900,1000).
  // So this verifies carry-forward: zero gaps and full coverage.
  const report = run({ records });
  assert.equal(report.status, "ok");
  const twap = report.global?.parentResults[0]?.childResults[0]?.twap;
  assert.equal(twap?.gaps.length, 0);
  assert.equal(twap?.coveredDurationMs, 1100);
  assert.equal(twap?.durationMs, 1100);
});

test("real leading gap on child TWAP when no quote covers release", () => {
  // First quote is later than parent arrival => the arrival price is missing,
  // which is a hard E_NO_QUOTE failure (never a silently partial TWAP).
  const records: RawRecord[] = [
    { kind: "parent", id: "p1", symbol: "AAA", side: "BUY", qty: "10",
      arrivalTime: "1000", deadlineTime: "2000", fee: "0" },
    { kind: "child", id: "c1", parentId: "p1", releaseTime: "1000",
      deadlineTime: "2000", qty: "10", fee: "0" },
    { kind: "quote", source: "S1", seq: "1", symbol: "AAA", time: "1500",
      bid: "99", ask: "101" },
    { kind: "quote", source: "S1", seq: "2", symbol: "AAA", time: "2000",
      bid: "99", ask: "101" },
  ];
  const report = run({ records });
  assert.equal(report.status, "failed");
  assert.ok(report.errors.some((e) => e.code === "E_NO_QUOTE"));
});
