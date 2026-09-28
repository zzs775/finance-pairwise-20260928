import assert from "node:assert/strict";
import { test } from "node:test";
import { parseFixed } from "../src/fixedpoint";
import { QuoteTimeline, mergeGaps } from "../src/quotetimeline";
import type { Quote } from "../src/types";

function quote(
  time: number,
  bid: string,
  ask: string,
  source = "S1",
  seq = 1,
  symbol = "AAA",
): Quote {
  return {
    kind: "quote",
    source,
    seq,
    symbol,
    time,
    bid: parseFixed(bid),
    ask: parseFixed(ask),
  };
}

test("atOrBefore uses the latest quote not later than the moment", () => {
  const tl = new QuoteTimeline([
    quote(100, "98", "102"),
    quote(200, "99", "103"),
  ]);
  assert.equal(tl.atOrBefore(50), null);
  assert.equal(tl.atOrBefore(100)?.bid, parseFixed("98"));
  assert.equal(tl.atOrBefore(150)?.bid, parseFixed("98"));
  assert.equal(tl.atOrBefore(200)?.ask, parseFixed("103"));
});

test("same-timestamp quotes are adjudicated by source then seq", () => {
  const tl = new QuoteTimeline([
    quote(100, "90", "110", "ZB", 5),
    quote(100, "95", "105", "AA", 9),
    quote(100, "94", "106", "AA", 3),
  ]);
  const point = tl.atOrBefore(100);
  assert.equal(point?.source, "AA");
  assert.equal(point?.seq, 3);
});

test("twap weights mid price by quote-effective duration", () => {
  // [100,200): mid 100 ; [200,300): mid 110 => twap 105
  const tl = new QuoteTimeline([
    quote(100, "99", "101"),
    quote(200, "109", "111"),
  ]);
  const info = tl.twap(100, 300);
  assert.equal(info.twap, parseFixed("105"));
  assert.equal(info.coveredDurationMs, 200);
  assert.equal(info.gaps.length, 0);
  assert.equal(info.segments.length, 2);
});

test("twap carries an earlier quote into the window", () => {
  const tl = new QuoteTimeline([quote(0, "8", "12")]);
  const info = tl.twap(100, 200);
  assert.equal(info.twap, parseFixed("10"));
  assert.equal(info.segments[0]?.startTime, 100);
});

test("leading quote gap is explicitly reported and excluded", () => {
  const tl = new QuoteTimeline([quote(150, "9", "11")]);
  const info = tl.twap(100, 200);
  assert.equal(info.twap, parseFixed("10"));
  assert.equal(info.coveredDurationMs, 50);
  assert.deepEqual(info.gaps, [{ startTime: 100, endTime: 150, durationMs: 50 }]);
});

test("fully missing quotes yield an all-gap twap of zero", () => {
  const tl = new QuoteTimeline([]);
  const info = tl.twap(100, 200);
  assert.equal(info.twap, 0n);
  assert.equal(info.coveredDurationMs, 0);
  assert.deepEqual(info.gaps, [{ startTime: 100, endTime: 200, durationMs: 100 }]);
});

test("first quote exactly at window end leaves the whole window a gap", () => {
  const tl = new QuoteTimeline([quote(200, "9", "11")]);
  const info = tl.twap(100, 200);
  assert.equal(info.twap, 0n);
  assert.equal(info.coveredDurationMs, 0);
  assert.deepEqual(info.gaps, [
    { startTime: 100, endTime: 200, durationMs: 100 },
  ]);
});

test("leading gap then coverage produces one gap and one segment", () => {
  const tl = new QuoteTimeline([
    quote(150, "9", "11"),
    quote(180, "19", "21"),
  ]);
  const info = tl.twap(100, 200);
  // [100,150) gap ; [150,180) mid 10 (30ms) ; [180,200) mid 20 (20ms)
  assert.equal(info.coveredDurationMs, 50);
  assert.equal(info.twap, parseFixed("14")); // (10*30 + 20*20)/50
  assert.deepEqual(info.gaps, [
    { startTime: 100, endTime: 150, durationMs: 50 },
  ]);
});

test("interior quote replacement at window start wins immediately", () => {
  const tl = new QuoteTimeline([
    quote(0, "0", "2"),
    quote(100, "100", "102"),
  ]);
  const info = tl.twap(100, 200);
  assert.equal(info.segments.length, 1);
  assert.equal(info.twap, parseFixed("101"));
});

test("mergeGaps joins adjacent intervals", () => {
  assert.deepEqual(
    mergeGaps([
      { startTime: 10, endTime: 20, durationMs: 10 },
      { startTime: 20, endTime: 30, durationMs: 10 },
      { startTime: 40, endTime: 50, durationMs: 10 },
    ]),
    [
      { startTime: 10, endTime: 30, durationMs: 20 },
      { startTime: 40, endTime: 50, durationMs: 10 },
    ],
  );
});
