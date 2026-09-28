import assert from "node:assert/strict";
import { test } from "node:test";
import {
  FIXED_SCALE,
  formatFixed,
  isFixedString,
  midPrice,
  mulDiv,
  parseFixed,
  signOf,
  weightedAverage,
} from "../src/fixedpoint";

test("parseFixed handles integers, fractions and sign", () => {
  assert.equal(parseFixed("12"), 12n * FIXED_SCALE);
  assert.equal(parseFixed("12.34"), BigInt("12340000000"));
  assert.equal(parseFixed("-0.000000001"), -1n);
  assert.equal(parseFixed("0"), 0n);
  assert.throws(() => parseFixed("1.0000000001"));
  assert.throws(() => parseFixed("abc"));
});

test("formatFixed round-trips and trims trailing zeros", () => {
  assert.equal(formatFixed(parseFixed("12.34")), "12.34");
  assert.equal(formatFixed(-1n), "-0.000000001");
  assert.equal(formatFixed(0n), "0");
  assert.equal(formatFixed(parseFixed("-7")), "-7");
});

test("isFixedString rejects malformed numbers", () => {
  for (const ok of ["0", "-1", "1.5", "100.000000001"]) {
    assert.equal(isFixedString(ok), true, ok);
  }
  for (const bad of ["", "-", ".5", "1.", "01", "1e3", " 1", "1 "]) {
    assert.equal(isFixedString(bad), false, bad);
  }
});

test("signOf", () => {
  assert.equal(signOf(5n), 1n);
  assert.equal(signOf(0n), 0n);
  assert.equal(signOf(-9n), -1n);
});

test("mulDiv is floor-rounded for negatives", () => {
  assert.equal(mulDiv(3n * FIXED_SCALE, parseFixed("1.5"), FIXED_SCALE), parseFixed("4.5"));
  assert.equal(mulDiv(7n, 1n, 2n), 3n);
  assert.equal(mulDiv(-7n, 1n, 2n), -4n);
  assert.equal(mulDiv(7n, -1n, 2n), -4n);
});

test("weightedAverage returns null for zero total weight", () => {
  assert.equal(weightedAverage([]), null);
  const q1 = parseFixed("2");
  const q2 = parseFixed("3");
  assert.equal(
    weightedAverage([
      { weight: q1, value: parseFixed("100") },
      { weight: q2, value: parseFixed("200") },
    ]),
    parseFixed("160"),
  );
});

test("midPrice floors toward zero on integer halves", () => {
  assert.equal(midPrice(parseFixed("100"), parseFixed("102")), parseFixed("101"));
  assert.equal(midPrice(1n, 2n), 1n);
});
