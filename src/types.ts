/**
 * Domain types for the implementation-shortfall analyzer.
 *
 * All money / price / quantity / fee values are fixed-point bigints scaled by
 * FIXED_SCALE (see fixedpoint.ts). On the JSON boundary they are decimal
 * strings, e.g. "12.340000000" for 12.34 currency units.
 */

export type Side = "BUY" | "SELL";

/** Error codes are stable strings, never generated dynamically. */
export type ErrorCode =
  | "E_BAD_SIDE"
  | "E_PARENT_NOT_FOUND"
  | "E_SIDE_MISMATCH"
  | "E_CHILD_INTERVAL"
  | "E_FILL_OUTSIDE_CHILD"
  | "E_FILL_QTY_EXCEEDED"
  | "E_BID_ABOVE_ASK"
  | "E_DUPLICATE_KEY"
  | "E_BAD_NUMBER"
  | "E_BAD_TIME"
  | "E_MISSING_FIELD"
  | "E_NO_QUOTE"
  | "E_QUOTE_GAP"
  | "E_ORPHAN_QUOTE"
  | "E_RECONCILE";

/** A single raw record as found in the input JSON file. Values are strings. */
export interface RawParentOrder {
  kind: "parent";
  id: string;
  symbol: string;
  side: string;
  qty: string;
  arrivalTime: string;
  deadlineTime: string;
  fee: string;
}

export interface RawChildOrder {
  kind: "child";
  id: string;
  parentId: string;
  releaseTime: string;
  deadlineTime: string;
  qty: string;
  fee: string;
}

export interface RawFill {
  kind: "fill";
  id: string;
  childId: string;
  /** Optional; when present it must match the parent side. */
  side?: string;
  time: string;
  qty: string;
  price: string;
  fee: string;
}

export interface RawQuote {
  kind: "quote";
  source: string;
  seq: string;
  symbol: string;
  time: string;
  bid: string;
  ask: string;
}

export type RawRecord = RawParentOrder | RawChildOrder | RawFill | RawQuote;

export interface RawDataset {
  records: RawRecord[];
}

/** Normalized records: every numeric value is a scaled bigint. */
export interface ParentOrder {
  kind: "parent";
  id: string;
  symbol: string;
  side: Side;
  qty: bigint;
  arrivalTime: number;
  deadlineTime: number;
  fee: bigint;
}

export interface ChildOrder {
  kind: "child";
  id: string;
  parentId: string;
  releaseTime: number;
  deadlineTime: number;
  qty: bigint;
  fee: bigint;
}

export interface Fill {
  kind: "fill";
  id: string;
  childId: string;
  time: number;
  qty: bigint;
  price: bigint;
  fee: bigint;
}

export interface Quote {
  kind: "quote";
  source: string;
  seq: number;
  symbol: string;
  time: number;
  bid: bigint;
  ask: bigint;
}

/** A resolved effective price at a moment in time. */
export interface PricePoint {
  time: number;
  source: string;
  seq: number;
  bid: bigint;
  ask: bigint;
}

export interface Dataset {
  parents: ParentOrder[];
  children: ChildOrder[];
  fills: Fill[];
  quotes: Quote[];
  errors: RecordError[];
  duplicates: DuplicateInfo[];
}

export interface RecordError {
  code: ErrorCode;
  /** A stable business key identifying the offending record. */
  recordKey: string;
  message: string;
}

export interface DuplicateInfo {
  recordKey: string;
  occurrences: number;
}

export interface Operand {
  name: string;
  value: string;
  scale: number;
}

export interface Formula {
  expression: string;
  operands: Operand[];
}

export type AttributionKey =
  | "delay"
  | "trading"
  | "opportunity"
  | "fee"
  | "total";

export interface Attribution {
  key: AttributionKey;
  amount: bigint;
  formula: Formula;
}

export interface TwapInfo {
  twap: bigint;
  durationMs: number;
  coveredDurationMs: number;
  numerator: bigint;
  denominator: bigint;
  segments: TwapSegment[];
  gaps: QuoteGap[];
}

export interface TwapSegment {
  startTime: number;
  endTime: number;
  durationMs: number;
  source: string;
  seq: number;
  midNumerator: bigint;
  mid: bigint;
}

export interface QuoteGap {
  startTime: number;
  endTime: number;
  durationMs: number;
}

export interface ChildResult {
  childId: string;
  parentId: string;
  side: Side;
  orderedQty: bigint;
  filledQty: bigint;
  vwap: bigint | null;
  twap: TwapInfo;
  arrivalPrice: PricePoint;
  releasePrice: PricePoint;
  deadlinePrice: PricePoint | null;
  delayCost: Attribution;
  tradingCost: Attribution;
  feeCost: Attribution;
}

export interface ParentResult {
  parentId: string;
  symbol: string;
  side: Side;
  orderedQty: bigint;
  filledQty: bigint;
  arrivalPrice: PricePoint;
  deadlinePrice: PricePoint | null;
  parentTwap: TwapInfo;
  childResults: ChildResult[];
  delayCost: Attribution;
  tradingCost: Attribution;
  opportunityCost: Attribution;
  feeCost: Attribution;
  totalShortfall: Attribution;
}

export interface GlobalResult {
  parentResults: ParentResult[];
  delayCost: Attribution;
  tradingCost: Attribution;
  opportunityCost: Attribution;
  feeCost: Attribution;
  totalShortfall: Attribution;
}

export interface ReconcileResult {
  ok: boolean;
  mismatches: RecordError[];
}

export interface Report {
  status: "ok" | "failed";
  scale: number;
  benchmark: {
    time: "parent_arrival";
    price: "mid";
  };
  errors: RecordError[];
  duplicates: DuplicateInfo[];
  global: GlobalResult | null;
}
