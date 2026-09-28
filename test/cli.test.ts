import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { main, parseArgs } from "../src/cli";
import { generateSample } from "../src/sample";

test("parseArgs recognizes all flags", () => {
  const args = parseArgs(["-i", "in.json", "--output", "out.json", "--sample", "--check"]);
  assert.equal(args.input, "in.json");
  assert.equal(args.output, "out.json");
  assert.equal(args.sample, true);
  assert.equal(args.check, true);
});

test("main runs the sample and writes canonical output to a file", async () => {
  const dir = mkdtempSync(join(tmpdir(), "is-analyzer-"));
  try {
    const output = join(dir, "report.json");
    const code = await main(["--sample", "--check", "--output", output]);
    assert.equal(code, 0);
    const parsed = JSON.parse(readFileSync(output, "utf8"));
    assert.equal(parsed.status, "ok");
    assert.ok(parsed.global);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("main exits 1 under --check when the dataset is invalid", async () => {
  const dir = mkdtempSync(join(tmpdir(), "is-analyzer-"));
  try {
    const input = join(dir, "in.json");
    const sample = generateSample(0);
    sample.records.push({
      kind: "parent",
      id: "p-bad",
      symbol: "AAA",
      side: "SIDEWAYS",
      qty: "1",
      arrivalTime: "1",
      deadlineTime: "2",
      fee: "0",
    });
    writeFileSync(input, JSON.stringify(sample), "utf8");
    const code = await main(["--input", input, "--check"]);
    assert.equal(code, 1);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("main exits 2 for unreadable input", async () => {
  const code = await main(["--input", "does-not-exist.json"]);
  assert.equal(code, 2);
});
