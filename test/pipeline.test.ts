import assert from "node:assert/strict";
import { test } from "node:test";
import { dedupeRecords } from "../src/dedupe";
import { toCanonicalJson } from "../src/io";
import { generateSample } from "../src/sample";
import { analyzeDataset } from "../src/pipeline";
import { normalizeDataset } from "../src/validate";
import type { RawDataset, RawRecord } from "../src/types";

function run(raw: RawDataset) {
  const { unique, duplicates } = dedupeRecords(raw.records);
  const dataset = normalizeDataset(unique);
  dataset.duplicates = duplicates;
  return analyzeDataset(dataset);
}

test("sample report is ok, reconciled and de-duplicated", () => {
  const report = run(generateSample(0));
  assert.equal(report.status, "ok");
  assert.equal(report.duplicates.length, 2);
  assert.equal(report.errors.length, 0);
  assert.ok(report.global);
  const g = report.global;
  const sum =
    g.delayCost.amount + g.tradingCost.amount +
    g.opportunityCost.amount + g.feeCost.amount;
  assert.equal(sum, g.totalShortfall.amount);
  assert.equal(g.totalShortfall.amount.toString(), "23200000000");
});

test("input permutation produces byte-identical canonical JSON", () => {
  const a = run(generateSample(1));
  const reversedRecords = [...generateSample(1).records].reverse();
  const b = run({ records: reversedRecords });
  assert.equal(toCanonicalJson(a), toCanonicalJson(b));
});

test("invalid records fail the whole report", () => {
  const sample = generateSample(0);
  const bad: RawRecord = {
    kind: "parent",
    id: "p-bad",
    symbol: "AAA",
    side: "SIDEWAYS",
    qty: "1",
    arrivalTime: "1",
    deadlineTime: "2",
    fee: "0",
  };
  const report = run({ records: [...sample.records, bad] });
  assert.equal(report.status, "failed");
  assert.ok(report.errors.some((e) => e.code === "E_BAD_SIDE"));
});

test("missing arrival quote fails the report with a stable code", () => {
  // Remove every BBB quote at or before the sell parent arrival (t=1000).
  const sample = generateSample(0);
  const records = sample.records.filter((r) =>
    !(r.kind === "quote" && r.symbol === "BBB" && Number(r.time) <= 1000),
  );
  const report = run({ records });
  assert.equal(report.status, "failed");
  assert.ok(report.errors.some((e) => e.code === "E_NO_QUOTE"));
});

test("bid above ask fails validation", () => {
  const sample = generateSample(0);
  const records = sample.records.map((r) =>
    r.kind === "quote" && r.source === "AA" && r.seq === "5"
      ? { ...r, bid: "200", ask: "100" }
      : r,
  );
  const report = run({ records });
  assert.equal(report.status, "failed");
  assert.ok(report.errors.some((e) => e.code === "E_BID_ABOVE_ASK"));
});
