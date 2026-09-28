import { FIXED_SCALE, FIXED_SCALE_EXP, formatFixed } from "./fixedpoint";
import type { Attribution, Formula, Operand, Side } from "./types";

export function operand(name: string, value: bigint, scale: number): Operand {
  return { name, value: formatScaled(value, scale), scale };
}

/**
 * Render a bigint at its declared scale. Scale 0 means a raw integer
 * (counts, denominators); scale 9 means the global fixed-point scale.
 */
export function formatScaled(value: bigint, scale: number): string {
  if (scale === FIXED_SCALE_EXP) return formatFixed(value);
  if (scale === 0) return value.toString();
  const factor = 10n ** BigInt(scale);
  const negative = value < 0n;
  const magnitude = negative ? -value : value;
  const whole = magnitude / factor;
  const fraction = (magnitude % factor)
    .toString()
    .padStart(scale, "0")
    .replace(/0+$/, "");
  const body = fraction === "" ? whole.toString() : `${whole}.${fraction}`;
  return negative && magnitude !== 0n ? `-${body}` : body;
}

export function formula(expression: string, operands: Operand[]): Formula {
  return { expression, operands };
}

export function attribution(
  key: Attribution["key"],
  amount: bigint,
  formula: Formula,
): Attribution {
  return { key, amount, formula };
}

/** Direction multiplier: +1 for BUY, -1 for SELL (fixed scale 1). */
export function sideSign(side: Side): bigint {
  return side === "BUY" ? FIXED_SCALE : -FIXED_SCALE;
}

export function zeroAttribution(key: Attribution["key"]): Attribution {
  return attribution(
    key,
    0n,
    formula("0", [operand("zero", 0n, FIXED_SCALE_EXP)]),
  );
}

export function sumAttributions(items: Attribution[]): Attribution {
  if (items.length === 0) {
    throw new Error("sumAttributions: nothing to sum");
  }
  const key = items[0]?.key;
  if (!key) throw new Error("sumAttributions: empty item");
  for (const item of items) {
    if (item.key !== key) {
      throw new Error(`sumAttributions: mixed keys ${item.key} vs ${key}`);
    }
  }
  const amount = items.reduce((sum, item) => sum + item.amount, 0n);
  const operands: Operand[] = items.map((item, index) =>
    operand(`${key}_${index}`, item.amount, FIXED_SCALE_EXP),
  );
  const expression = items.map((_, index) => `${key}_${index}`).join(" + ");
  return attribution(key, amount, formula(expression, operands));
}
