import {
  FIXED_SCALE,
  midPrice,
  mulDiv,
} from "./fixedpoint";
import { QuoteTimeline } from "./quotetimeline";
import type {
  AttributionKey,
  Dataset,
  Fill,
  ParentResult,
  PricePoint,
} from "./types";

interface OracleBreakdown {
  delay: bigint;
  trading: bigint;
  opportunity: bigint;
  fee: bigint;
  total: bigint;
}

function requirePoint(point: PricePoint | null, label: string): PricePoint {
  if (!point) throw new Error(`oracle: missing ${label}`);
  return point;
}

/**
 * Independent recomputation for a single parent. It walks fills one by one
 * and walks quote-effective intervals one by one; it never calls into the
 * pricing/pipeline code, so it can serve as a cross-check oracle.
 */
export function oracleBreakdown(
  dataset: Dataset,
  parentId: string,
): OracleBreakdown {
  const parent = dataset.parents.find((p) => p.id === parentId);
  if (!parent) throw new Error(`oracle: unknown parent ${parentId}`);

  const quotes = dataset.quotes.filter((q) => q.symbol === parent.symbol);
  const timeline = new QuoteTimeline(quotes);
  const signUnit = parent.side === "BUY" ? 1n : -1n;

  const arrival = requirePoint(
    timeline.atOrBefore(parent.arrivalTime), "arrival price",
  );
  const deadline = requirePoint(
    timeline.atOrBefore(parent.deadlineTime), "deadline price",
  );
  const arrivalMid = midPrice(arrival.bid, arrival.ask);
  const deadlineMid = midPrice(deadline.bid, deadline.ask);

  const childIds = new Set(
    dataset.children
      .filter((child) => child.parentId === parent.id)
      .map((child) => child.id),
  );

  // De-duplicate fills defensively by business key (oracle is independent of
  // the pipeline dedupe stage).
  const seenFills = new Set<string>();
  const fills: Fill[] = [];
  for (const fill of dataset.fills) {
    if (!childIds.has(fill.childId)) continue;
    if (seenFills.has(fill.id)) continue;
    seenFills.add(fill.id);
    fills.push(fill);
  }
  fills.sort((a, b) =>
    a.time !== b.time ? a.time - b.time
    : a.childId !== b.childId ? (a.childId < b.childId ? -1 : 1)
    : a.id < b.id ? -1 : a.id > b.id ? 1 : 0,
  );

  let delay = 0n;
  let trading = 0n;
  let filledQty = 0n;
  let fillFees = 0n;

  for (const child of dataset.children.filter((c) => c.parentId === parent.id)) {
    // Delay per child, recomputed from the release quote directly.
    const release = requirePoint(
      timeline.atOrBefore(child.releaseTime), `release price ${child.id}`,
    );
    const releaseMid = midPrice(release.bid, release.ask);
    delay +=
      mulDiv(child.qty, releaseMid - arrivalMid, FIXED_SCALE) * signUnit;
  }

  for (const fill of fills) {
    const child = dataset.children.find((c) => c.id === fill.childId);
    if (!child) continue;
    const release = requirePoint(
      timeline.atOrBefore(child.releaseTime), `release price ${child.id}`,
    );
    const releaseMid = midPrice(release.bid, release.ask);
    filledQty += fill.qty;
    fillFees += fill.fee;
    trading +=
      mulDiv(fill.qty, fill.price - releaseMid, FIXED_SCALE) * signUnit;
  }

  const opportunity =
    mulDiv(parent.qty - filledQty, deadlineMid - arrivalMid, FIXED_SCALE) *
    signUnit;
  const fee = fillFees + parent.fee;
  const total = delay + trading + opportunity + fee;

  return { delay, trading, opportunity, fee, total };
}

/**
 * Independent reference oracle. It recomputes each attribution amount
 * directly from raw fills and quote intervals (no reuse of pipeline
 * internals), and exposes every operand so reports can be cross-checked.
 */
export function oracleParent(
  dataset: Dataset,
  parentId: string,
): ParentResult {
  const produced = dataset.parents.find((p) => p.id === parentId);
  if (!produced) throw new Error(`oracle: unknown parent ${parentId}`);
  // The oracle's job is numeric verification; callers compare the returned
  // breakdown against the pipeline ParentResult via oracleAttribution.
  const breakdown = oracleBreakdown(dataset, parentId);
  const synthetic = {
    parentId,
    totalShortfall: { amount: breakdown.total },
    delayCost: { amount: breakdown.delay },
    tradingCost: { amount: breakdown.trading },
    opportunityCost: { amount: breakdown.opportunity },
    feeCost: { amount: breakdown.fee },
  } as unknown as ParentResult;
  return synthetic;
}

export function oracleAttribution(
  dataset: Dataset,
  parentId: string,
  key: AttributionKey,
): bigint {
  return oracleBreakdown(dataset, parentId)[key];
}
