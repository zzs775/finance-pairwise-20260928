import type { RawDataset, RawRecord } from "./types";

/**
 * Deterministic locally synthesized dataset. Scenario design:
 *  - parent p-buy: BUY 10, two children, partial fill
 *  - parent p-sell: SELL 5 on symbol BBB, one child, fully filled
 *  - two sources quote AAA at the same timestamp (source arbitration)
 *  - one fill and one quote are duplicated (idempotency check)
 * The emitted record order is deliberately scrambled.
 */
export function generateSample(seed: number): RawDataset {
  void seed; // deterministic output; seed reserved for future variants
  const records: RawRecord[] = [];

  records.push({
    kind: "parent",
    id: "p-buy",
    symbol: "AAA",
    side: "BUY",
    qty: "10",
    arrivalTime: "1000",
    deadlineTime: "3000",
    fee: "0.50",
  });
  records.push({
    kind: "child",
    id: "c-buy-1",
    parentId: "p-buy",
    releaseTime: "1200",
    deadlineTime: "2000",
    qty: "6",
    fee: "0",
  });
  records.push({
    kind: "child",
    id: "c-buy-2",
    parentId: "p-buy",
    releaseTime: "2000",
    deadlineTime: "3000",
    qty: "4",
    fee: "0",
  });
  records.push({
    kind: "fill",
    id: "f-1",
    childId: "c-buy-1",
    time: "1500",
    qty: "4",
    price: "101.00",
    fee: "0.10",
  });
  records.push({
    kind: "fill",
    id: "f-2",
    childId: "c-buy-1",
    time: "1800",
    qty: "2",
    price: "101.50",
    fee: "0.10",
  });
  // c-buy-2 is intentionally never filled: opportunity cost at deadline.

  records.push({
    kind: "parent",
    id: "p-sell",
    symbol: "BBB",
    side: "SELL",
    qty: "5",
    arrivalTime: "1000",
    deadlineTime: "2000",
    fee: "0.20",
  });
  records.push({
    kind: "child",
    id: "c-sell-1",
    parentId: "p-sell",
    releaseTime: "1000",
    deadlineTime: "2000",
    qty: "5",
    fee: "0",
  });
  records.push({
    kind: "fill",
    id: "f-3",
    childId: "c-sell-1",
    time: "1600",
    qty: "5",
    price: "50.20",
    fee: "0.05",
  });

  // AAA quotes, including a same-timestamp dual-source event at t=1000.
  const aaaQuotes: RawRecord[] = [
    { kind: "quote", source: "ZB", seq: "1", symbol: "AAA", time: "1000", bid: "99.00", ask: "102.00" },
    { kind: "quote", source: "AA", seq: "1", symbol: "AAA", time: "1000", bid: "99.50", ask: "100.50" },
    { kind: "quote", source: "AA", seq: "2", symbol: "AAA", time: "1200", bid: "99.80", ask: "100.60" },
    { kind: "quote", source: "AA", seq: "3", symbol: "AAA", time: "1500", bid: "100.50", ask: "101.50" },
    { kind: "quote", source: "AA", seq: "4", symbol: "AAA", time: "2000", bid: "101.00", ask: "102.00" },
    { kind: "quote", source: "AA", seq: "5", symbol: "AAA", time: "3000", bid: "103.00", ask: "104.00" },
  ];
  records.push(...aaaQuotes);
  // Duplicate quote (must be de-duplicated).
  const duplicateQuote = { ...(aaaQuotes[3] as RawRecord) };
  records.push(duplicateQuote);

  // BBB quotes persist until replaced; the leading-gap behavior is covered
  // by the dedicated quote-timeline tests.
  records.push({ kind: "quote", source: "Q1", seq: "1", symbol: "BBB", time: "1000", bid: "49.00", ask: "49.50" });
  records.push({ kind: "quote", source: "Q1", seq: "2", symbol: "BBB", time: "1600", bid: "49.80", ask: "50.40" });
  records.push({ kind: "quote", source: "Q1", seq: "3", symbol: "BBB", time: "2000", bid: "50.00", ask: "50.60" });

  // Duplicate fill (must be de-duplicated by business key).
  records.push(
    records.find((r) => r.kind === "fill" && r.id === "f-1") as RawRecord,
  );

  // Deterministic scramble by a fixed stride; canonical output is invariant.
  const stride = 1 + (Math.abs(seed) % 5);
  const scrambled = records.map(
    (_, index) => records[(index * stride) % records.length] as RawRecord,
  );
  return { records: scrambled };
}
