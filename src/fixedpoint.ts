/**
 * Fixed-point decimal arithmetic on bigint.
 * Scale: FIXED_SCALE = 10^9 units per one currency / quantity unit.
 */

export const FIXED_SCALE_EXP = 9;
export const FIXED_SCALE: bigint = 10n ** BigInt(FIXED_SCALE_EXP);

/**
 * Parse a decimal string ("12", "12.3", "-0.000000001") into a scaled bigint.
 * Fractional digits beyond FIXED_SCALE_EXP are rejected.
 */
export function parseFixed(text: string): bigint {
  if (typeof text !== "string" || !isFixedString(text)) {
    throw new Error(`invalid fixed-point number: ${String(text)}`);
  }
  const negative = text.startsWith("-");
  const body = negative ? text.slice(1) : text;
  const dot = body.indexOf(".");
  const whole = dot === -1 ? body : body.slice(0, dot);
  const fraction = dot === -1 ? "" : body.slice(dot + 1);
  if (fraction.length > FIXED_SCALE_EXP) {
    throw new Error(`fixed-point precision exceeded: ${text}`);
  }
  const padded = fraction.padEnd(FIXED_SCALE_EXP, "0");
  const magnitude =
    (whole === "" ? 0n : BigInt(whole)) * FIXED_SCALE + BigInt(padded);
  return negative ? -magnitude : magnitude;
}

/** Format a scaled bigint back to a canonical decimal string. */
export function formatFixed(value: bigint): string {
  const negative = value < 0n;
  const magnitude = negative ? -value : value;
  const whole = magnitude / FIXED_SCALE;
  const fraction = magnitude % FIXED_SCALE;
  const fractionText = fraction
    .toString()
    .padStart(FIXED_SCALE_EXP, "0")
    .replace(/0+$/, "");
  const body =
    fractionText.length === 0
      ? whole.toString()
      : `${whole.toString()}.${fractionText}`;
  return negative && magnitude !== 0n ? `-${body}` : body;
}

export function isFixedString(text: string): boolean {
  return (
    typeof text === "string" &&
    /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?$/.test(text)
  );
}

/** Signed sign: 1n / 0n / -1n. */
export function signOf(value: bigint): bigint {
  return value > 0n ? 1n : value < 0n ? -1n : 0n;
}

/**
 * Multiply two scaled values and divide by a third (q * p / FIXED_SCALE
 * style). Rounding: floor for magnitude, sign preserved.
 */
export function mulDiv(a: bigint, b: bigint, c: bigint): bigint {
  if (c === 0n) throw new Error("mulDiv: zero divisor");
  const product = a * b;
  // Floor division, preserving well-defined rounding for negative operands.
  const quotient = product / c;
  const remainder = product % c;
  if (remainder !== 0n && (remainder < 0n) !== (c < 0n)) {
    return quotient - 1n;
  }
  return quotient;
}

/**
 * Weighted average: sum(qty*price)/sum(qty) over fixed scale.
 * Returns null when totalQty is zero.
 */
export function weightedAverage(
  terms: { weight: bigint; value: bigint }[],
): bigint | null {
  let totalWeight = 0n;
  let numerator = 0n;
  for (const term of terms) {
    if (term.weight < 0n) {
      throw new Error("weightedAverage: negative weight");
    }
    totalWeight += term.weight;
    numerator += term.weight * term.value;
  }
  if (totalWeight === 0n) return null;
  return mulDiv(numerator, FIXED_SCALE, totalWeight * FIXED_SCALE);
}

/** Mid of bid/ask, floor((bid+ask)/2) on the fixed scale. */
export function midPrice(bid: bigint, ask: bigint): bigint {
  return (bid + ask) / 2n;
}
