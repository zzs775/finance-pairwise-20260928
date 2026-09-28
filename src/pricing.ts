import type {
  ChildResult,
  Dataset,
  Fill,
  ParentResult,
  ParentOrder,
  PricePoint,
  RecordError,
  Attribution,
} from "./types";
import type { QuoteTimeline } from "./quotetimeline";
import { QuoteTimeline as Timeline } from "./quotetimeline";
import {
  FIXED_SCALE,
  FIXED_SCALE_EXP,
  midPrice,
  mulDiv,
  weightedAverage,
} from "./fixedpoint";
import {
  attribution,
  formula,
  operand,
  sideSign,
  zeroAttribution,
} from "./attribution";
import { makeError } from "./errors";

const SCALE = FIXED_SCALE_EXP;

function midOf(point: PricePoint): bigint {
  return midPrice(point.bid, point.ask);
}

function midOperands(point: PricePoint, prefix: string) {
  return [
    operand(`${prefix}Bid`, point.bid, SCALE),
    operand(`${prefix}Ask`, point.ask, SCALE),
    operand(`${prefix}MidNumerator`, point.bid + point.ask, SCALE),
    operand(`${prefix}MidDenominator`, 2n, 0),
  ];
}

function costFormula(
  name: string,
  sign: bigint,
  operandsList: ReturnType<typeof operand>[],
): ReturnType<typeof formula> {
  return formula(`sign * ${name} / 10^${SCALE}`, [
    operand("sign", sign, SCALE),
    ...operandsList,
  ]);
}

export function buildTimelines(dataset: Dataset): Map<string, QuoteTimeline> {
  const bySymbol = new Map<string, typeof dataset.quotes>();
  for (const quote of dataset.quotes) {
    const list = bySymbol.get(quote.symbol) ?? [];
    list.push(quote);
    bySymbol.set(quote.symbol, list);
  }
  const timelines = new Map<string, QuoteTimeline>();
  for (const [symbol, quotes] of bySymbol) {
    timelines.set(symbol, new Timeline(quotes));
  }
  return timelines;
}

interface ChildAnalysis {
  result: ChildResult;
  /** Aggregated signed cost components for this child. */
  delayAmount: bigint;
  tradingAmount: bigint;
  feeAmount: bigint;
}

export function analyzeChild(
  dataset: Dataset,
  timelines: Map<string, QuoteTimeline>,
  childId: string,
  errors: RecordError[] = [],
): ChildAnalysis {
  const child = dataset.children.find((c) => c.id === childId);
  if (!child) throw new Error(`analyzeChild: unknown child ${childId}`);
  const parent = dataset.parents.find((p) => p.id === child.parentId);
  if (!parent) throw new Error(`analyzeChild: missing parent ${child.parentId}`);
  const timeline = timelines.get(parent.symbol);
  if (!timeline) {
    errors.push(makeError("E_NO_QUOTE", `parent:${parent.id}`,
      `no quotes for symbol ${parent.symbol}`));
    throw new Error(`analyzeChild: no timeline for ${parent.symbol}`);
  }

  const arrival = timeline.atOrBefore(parent.arrivalTime);
  if (!arrival) {
    errors.push(makeError("E_NO_QUOTE", `parent:${parent.id}`,
      "no quote at or before parent arrival"));
    throw new Error("analyzeChild: no arrival price");
  }
  const release = timeline.atOrBefore(child.releaseTime);
  if (!release) {
    errors.push(makeError("E_NO_QUOTE", `child:${child.id}`,
      "no quote at or before child release"));
    throw new Error("analyzeChild: no release price");
  }
  const deadline = timeline.atOrBefore(child.deadlineTime);
  if (!deadline) {
    errors.push(makeError("E_NO_QUOTE", `child:${child.id}`,
      "no quote at or before child deadline"));
    throw new Error("analyzeChild: no deadline price");
  }

  const childFills = dataset.fills
    .filter((fill) => fill.childId === child.id)
    .sort((a, b) =>
      a.time !== b.time ? a.time - b.time
      : a.id < b.id ? -1 : a.id > b.id ? 1 : 0,
    );

  let filledQty = 0n;
  let fillFeeTotal = 0n;
  const vwapTerms: { weight: bigint; value: bigint }[] = [];
  for (const fill of childFills) {
    filledQty += fill.qty;
    fillFeeTotal += fill.fee;
    vwapTerms.push({ weight: fill.qty, value: fill.price });
  }
  const vwap = weightedAverage(vwapTerms);

  const twap = timeline.twap(child.releaseTime, child.deadlineTime);
  const sign = sideSign(parent.side);

  const arrivalMid = midOf(arrival);
  const releaseMid = midOf(release);

  // Delay cost: sign * qty * (releaseMid - arrivalMid)
  const delayDelta = releaseMid - arrivalMid;
  const delayAmount =
    mulDiv(child.qty, delayDelta, FIXED_SCALE) * (sign / FIXED_SCALE);
  const delayCost = attribution(
    "delay",
    delayAmount,
    costFormula("qty * (releaseMid - arrivalMid)", sign, [
      operand("qty", child.qty, SCALE),
      ...midOperands(arrival, "arrival"),
      ...midOperands(release, "release"),
      operand("releaseMid", releaseMid, SCALE),
      operand("arrivalMid", arrivalMid, SCALE),
    ]),
  );

  // Trading cost: sign * sum[fill.qty * (fill.price - releaseMid)]
  let tradingAmount = 0n;
  const tradingOperands: ReturnType<typeof operand>[] = [];
  childFills.forEach((fill: Fill, index: number) => {
    const piece =
      mulDiv(fill.qty, fill.price - releaseMid, FIXED_SCALE) *
      (sign / FIXED_SCALE);
    tradingAmount += piece;
    tradingOperands.push(
      operand(`fill_${index}_qty[${fill.id}]`, fill.qty, SCALE),
      operand(`fill_${index}_price[${fill.id}]`, fill.price, SCALE),
      operand(`fill_${index}_cost`, piece, SCALE),
    );
  });
  tradingOperands.push(operand("releaseMid", releaseMid, SCALE));
  const tradingCost = childFills.length === 0
    ? zeroAttribution("trading")
    : attribution(
      "trading",
      tradingAmount,
      costFormula(
        "sum(fill.qty * (fill.price - releaseMid))",
        sign,
        tradingOperands,
      ),
    );

  // Explicit fees: fees actually paid on fills (sign-independent; they are
  // always a cost). Order-level fees without fills remain on the parent.
  const feeCost = attribution(
    "fee",
    fillFeeTotal,
    formula("sum(fill.fee)", [
      operand("fillFeeTotal", fillFeeTotal, SCALE),
    ]),
  );

  const result: ChildResult = {
    childId: child.id,
    parentId: parent.id,
    side: parent.side,
    orderedQty: child.qty,
    filledQty,
    vwap,
    twap,
    arrivalPrice: arrival,
    releasePrice: release,
    deadlinePrice: deadline,
    delayCost,
    tradingCost: childFills.length === 0 ? zeroAttribution("trading") : tradingCost,
    feeCost,
  };

  return {
    result,
    delayAmount,
    tradingAmount,
    feeAmount: fillFeeTotal,
  };
}

export function analyzeParent(
  dataset: Dataset,
  timelines: Map<string, QuoteTimeline>,
  parentId: string,
  childResults: ChildResult[],
  errors: RecordError[] = [],
): ParentResult {
  const parent = dataset.parents.find((p) => p.id === parentId) as ParentOrder;
  const timeline = timelines.get(parent.symbol);
  if (!timeline) throw new Error(`analyzeParent: no timeline for ${parent.symbol}`);

  const arrival = timeline.atOrBefore(parent.arrivalTime);
  if (!arrival) throw new Error("analyzeParent: no arrival price");
  const deadline = timeline.atOrBefore(parent.deadlineTime);
  if (!deadline) {
    errors.push(makeError("E_NO_QUOTE", `parent:${parent.id}`,
      "no quote at or before parent deadline"));
    throw new Error("analyzeParent: no deadline price");
  }

  const parentTwap = timeline.twap(parent.arrivalTime, parent.deadlineTime);
  const sign = sideSign(parent.side);
  const arrivalMid = midOf(arrival);
  const deadlineMid = midOf(deadline);

  // Child-level components aggregate directly.
  const analyses = childResults
    .filter((child) => child.parentId === parent.id);
  const delayAmount = analyses.reduce(
    (sum, child) => sum + child.delayCost.amount, 0n,
  );
  const tradingAmount = analyses.reduce(
    (sum, child) => sum + child.tradingCost.amount, 0n,
  );
  const childFeeAmount = analyses.reduce(
    (sum, child) => sum + child.feeCost.amount, 0n,
  );

  // Fees on the parent record itself are explicit cost even when no fill.
  const feeAmount = childFeeAmount + parent.fee;
  const feeCost: Attribution = attribution(
    "fee",
    feeAmount,
    formula("sum(child.fill.fee) + parent.fee", [
      operand("childFeeTotal", childFeeAmount, SCALE),
      operand("parentFee", parent.fee, SCALE),
    ]),
  );

  const orderedQty = parent.qty;
  const filledQty = analyses.reduce(
    (sum, child) => sum + child.filledQty, 0n,
  );
  const unfilledQty = orderedQty - filledQty;

  // Opportunity cost: sign * unfilledQty * (deadlineMid - arrivalMid)
  const opportunityAmount =
    mulDiv(unfilledQty, deadlineMid - arrivalMid, FIXED_SCALE) *
    (sign / FIXED_SCALE);
  const opportunityCost = attribution(
    "opportunity",
    opportunityAmount,
    costFormula("unfilledQty * (deadlineMid - arrivalMid)", sign, [
      operand("orderedQty", orderedQty, SCALE),
      operand("filledQty", filledQty, SCALE),
      operand("unfilledQty", unfilledQty, SCALE),
      ...midOperands(arrival, "arrival"),
      ...midOperands(deadline, "deadline"),
      operand("arrivalMid", arrivalMid, SCALE),
      operand("deadlineMid", deadlineMid, SCALE),
    ]),
  );

  const delayCost = attribution(
    "delay",
    delayAmount,
    formula("sum(child.delayCost)", [
      operand("delayTotal", delayAmount, SCALE),
    ]),
  );
  const tradingCost = attribution(
    "trading",
    tradingAmount,
    formula("sum(child.tradingCost)", [
      operand("tradingTotal", tradingAmount, SCALE),
    ]),
  );

  const totalShortfallAmount =
    delayAmount + tradingAmount + opportunityAmount + feeAmount;
  const totalShortfall = attribution(
    "total",
    totalShortfallAmount,
    formula("delay + trading + opportunity + fee", [
      operand("delay", delayAmount, SCALE),
      operand("trading", tradingAmount, SCALE),
      operand("opportunity", opportunityAmount, SCALE),
      operand("fee", feeAmount, SCALE),
    ]),
  );

  return {
    parentId: parent.id,
    symbol: parent.symbol,
    side: parent.side,
    orderedQty,
    filledQty,
    arrivalPrice: arrival,
    deadlinePrice: deadline,
    parentTwap,
    childResults: analyses,
    delayCost,
    tradingCost,
    opportunityCost,
    feeCost,
    totalShortfall,
  };
}
