import assert from "node:assert/strict";
import { test } from "node:test";
import {
  attribution,
  formatScaled,
  formula,
  operand,
  sideSign,
  sumAttributions,
  zeroAttribution,
} from "../src/attribution";
import { FIXED_SCALE, parseFixed } from "../src/fixedpoint";

test("operand carries formatted value and scale", () => {
  assert.deepEqual(operand("p", parseFixed("12.5"), 9), {
    name: "p",
    value: "12.5",
    scale: 9,
  });
});

test("formatScaled renders scale-0 values as raw integers", () => {
  assert.equal(formatScaled(2n, 0), "2");
  assert.equal(formatScaled(-7n, 0), "-7");
  assert.equal(formatScaled(parseFixed("12.5"), 9), "12.5");
});

test("side sign is +1 for BUY and -1 for SELL", () => {
  assert.equal(sideSign("BUY"), FIXED_SCALE);
  assert.equal(sideSign("SELL"), -FIXED_SCALE);
});

test("zero attribution has amount zero and explicit formula", () => {
  const zero = zeroAttribution("fee");
  assert.equal(zero.key, "fee");
  assert.equal(zero.amount, 0n);
  assert.equal(zero.formula.expression, "0");
});

test("sumAttributions aggregates same-key items", () => {
  const a = attribution("delay", parseFixed("1.5"), formula("a", []));
  const b = attribution("delay", parseFixed("-0.25"), formula("b", []));
  const sum = sumAttributions([a, b]);
  assert.equal(sum.amount, parseFixed("1.25"));
  assert.equal(sum.formula.expression, "delay_0 + delay_1");
});

test("sumAttributions rejects mixed keys", () => {
  assert.throws(() =>
    sumAttributions([
      attribution("delay", 1n, formula("a", [])),
      attribution("trading", 1n, formula("b", [])),
    ]),
  );
});
