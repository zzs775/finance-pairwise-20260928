import { isFixedString, parseFixed } from "./fixedpoint";
import { makeError } from "./errors";
import { recordKey } from "./dedupe";
import type {
  ChildOrder,
  Dataset,
  Fill,
  ParentOrder,
  Quote,
  RawChildOrder,
  RawFill,
  RawParentOrder,
  RawQuote,
  RawRecord,
  RecordError,
} from "./types";

function isNonEmptyString(value: unknown): value is string {
  return typeof value === "string" && value.length > 0;
}

function parseTime(value: unknown): number | null {
  if (typeof value !== "string" || !/^[0-9]+$/.test(value)) return null;
  const ms = Number(value);
  if (!Number.isSafeInteger(ms)) return null;
  return ms;
}

function requireFields(
  record: Record<string, unknown>,
  fields: string[],
  key: string,
  errors: RecordError[],
): boolean {
  let ok = true;
  for (const field of fields) {
    const value = record[field];
    if (
      value === undefined ||
      value === null ||
      (typeof value === "string" && value.length === 0)
    ) {
      errors.push(makeError("E_MISSING_FIELD", key, `missing field ${field}`));
      ok = false;
    }
  }
  return ok;
}

function requireFixed(
  value: unknown,
  field: string,
  key: string,
  errors: RecordError[],
): bigint | null {
  if (!isNonEmptyString(value) || !isFixedString(value)) {
    errors.push(
      makeError("E_BAD_NUMBER", key, `field ${field} is not a fixed number`),
    );
    return null;
  }
  try {
    return parseFixed(value);
  } catch {
    errors.push(
      makeError("E_BAD_NUMBER", key, `field ${field} exceeds fixed scale`),
    );
    return null;
  }
}

function requireTime(
  value: unknown,
  field: string,
  key: string,
  errors: RecordError[],
): number | null {
  const parsed = parseTime(value);
  if (parsed === null) {
    errors.push(
      makeError("E_BAD_TIME", key, `field ${field} is not an epoch-ms integer`),
    );
  }
  return parsed;
}

function requirePositive(
  value: bigint | null,
  field: string,
  key: string,
  errors: RecordError[],
): bigint | null {
  if (value === null) return null;
  if (value <= 0n) {
    errors.push(makeError("E_BAD_NUMBER", key, `field ${field} must be > 0`));
    return null;
  }
  return value;
}

function requireNonNegative(
  value: bigint | null,
  field: string,
  key: string,
  errors: RecordError[],
): bigint | null {
  if (value === null) return null;
  if (value < 0n) {
    errors.push(makeError("E_BAD_NUMBER", key, `field ${field} must be >= 0`));
    return null;
  }
  return value;
}

function normalizeParent(
  raw: RawParentOrder,
  errors: RecordError[],
): ParentOrder | null {
  const key = recordKey(raw);
  if (!requireFields(raw as unknown as Record<string, unknown>,
    ["id", "symbol", "side", "qty", "arrivalTime", "deadlineTime", "fee"],
    key, errors)) {
    return null;
  }
  if (raw.side !== "BUY" && raw.side !== "SELL") {
    errors.push(makeError("E_BAD_SIDE", key, `unknown side ${raw.side}`));
    return null;
  }
  const qty = requirePositive(requireFixed(raw.qty, "qty", key, errors),
    "qty", key, errors);
  const fee = requireNonNegative(requireFixed(raw.fee, "fee", key, errors),
    "fee", key, errors);
  const arrivalTime = requireTime(raw.arrivalTime, "arrivalTime", key, errors);
  const deadlineTime = requireTime(raw.deadlineTime, "deadlineTime", key, errors);
  if (
    qty === null || fee === null ||
    arrivalTime === null || deadlineTime === null
  ) {
    return null;
  }
  if (arrivalTime > deadlineTime) {
    errors.push(
      makeError("E_CHILD_INTERVAL", key,
        "arrivalTime must be <= deadlineTime"),
    );
    return null;
  }
  return {
    kind: "parent",
    id: raw.id,
    symbol: raw.symbol,
    side: raw.side,
    qty,
    arrivalTime,
    deadlineTime,
    fee,
  };
}

function normalizeChild(
  raw: RawChildOrder,
  errors: RecordError[],
  parentsById: Map<string, ParentOrder>,
): ChildOrder | null {
  const key = recordKey(raw);
  if (!requireFields(raw as unknown as Record<string, unknown>,
    ["id", "parentId", "releaseTime", "deadlineTime", "qty", "fee"],
    key, errors)) {
    return null;
  }
  const parent = parentsById.get(raw.parentId);
  if (!parent) {
    errors.push(
      makeError("E_PARENT_NOT_FOUND", key,
        `parent ${raw.parentId} does not exist`),
    );
    return null;
  }
  const qty = requirePositive(requireFixed(raw.qty, "qty", key, errors),
    "qty", key, errors);
  const fee = requireNonNegative(requireFixed(raw.fee, "fee", key, errors),
    "fee", key, errors);
  const releaseTime = requireTime(raw.releaseTime, "releaseTime", key, errors);
  const deadlineTime = requireTime(raw.deadlineTime, "deadlineTime", key, errors);
  if (qty === null || fee === null ||
    releaseTime === null || deadlineTime === null) {
    return null;
  }
  if (releaseTime > deadlineTime) {
    errors.push(
      makeError("E_CHILD_INTERVAL", key,
        "releaseTime must be <= deadlineTime"),
    );
    return null;
  }
  if (releaseTime < parent.arrivalTime || deadlineTime > parent.deadlineTime) {
    errors.push(
      makeError("E_CHILD_INTERVAL", key,
        "child interval must stay within the parent interval"),
    );
    return null;
  }
  return {
    kind: "child",
    id: raw.id,
    parentId: raw.parentId,
    releaseTime,
    deadlineTime,
    qty,
    fee,
  };
}

function normalizeFill(
  raw: RawFill,
  errors: RecordError[],
  childrenById: Map<string, ChildOrder>,
  parentsById: Map<string, ParentOrder>,
): Fill | null {
  const key = recordKey(raw);
  if (!requireFields(raw as unknown as Record<string, unknown>,
    ["id", "childId", "time", "qty", "price", "fee"],
    key, errors)) {
    return null;
  }
  const child = childrenById.get(raw.childId);
  if (!child) {
    errors.push(
      makeError("E_PARENT_NOT_FOUND", key,
        `child ${raw.childId} does not exist`),
    );
    return null;
  }
  const parent = parentsById.get(child.parentId);
  if (!parent) {
    errors.push(
      makeError("E_PARENT_NOT_FOUND", key,
        `parent ${child.parentId} does not exist`),
    );
    return null;
  }
  if (raw.side !== undefined && raw.side !== parent.side) {
    errors.push(
      makeError("E_SIDE_MISMATCH", key,
        `fill side ${raw.side} disagrees with parent side ${parent.side}`),
    );
    return null;
  }
  const qty = requirePositive(requireFixed(raw.qty, "qty", key, errors),
    "qty", key, errors);
  const price = requirePositive(requireFixed(raw.price, "price", key, errors),
    "price", key, errors);
  const fee = requireNonNegative(requireFixed(raw.fee, "fee", key, errors),
    "fee", key, errors);
  const time = requireTime(raw.time, "time", key, errors);
  if (qty === null || price === null || fee === null || time === null) {
    return null;
  }
  if (time < child.releaseTime || time > child.deadlineTime) {
    errors.push(
      makeError("E_FILL_OUTSIDE_CHILD", key,
        "fill time is outside the child interval"),
    );
    return null;
  }
  // Fills are directional records; qty/price alone carry no side field, so the
  // only enforceable side check here is parent<->child consistency, performed
  // where child records are available.
  void parent;
  return {
    kind: "fill",
    id: raw.id,
    childId: raw.childId,
    time,
    qty,
    price,
    fee,
  };
}

function normalizeQuote(raw: RawQuote, errors: RecordError[]): Quote | null {
  const key = recordKey(raw);
  if (!requireFields(raw as unknown as Record<string, unknown>,
    ["source", "seq", "symbol", "time", "bid", "ask"],
    key, errors)) {
    return null;
  }
  const seq = parseTime(raw.seq);
  if (seq === null) {
    errors.push(makeError("E_BAD_TIME", key, "seq must be an integer"));
  }
  const bid = requireNonNegative(requireFixed(raw.bid, "bid", key, errors),
    "bid", key, errors);
  const ask = requireNonNegative(requireFixed(raw.ask, "ask", key, errors),
    "ask", key, errors);
  const time = requireTime(raw.time, "time", key, errors);
  if (seq === null || bid === null || ask === null || time === null) {
    return null;
  }
  if (bid > ask) {
    errors.push(makeError("E_BID_ABOVE_ASK", key, "bid must be <= ask"));
    return null;
  }
  return {
    kind: "quote",
    source: raw.source,
    seq,
    symbol: raw.symbol,
    time,
    bid,
    ask,
  };
}

export function normalizeDataset(records: RawRecord[]): Dataset {
  const errors: RecordError[] = [];
  const parents: ParentOrder[] = [];
  const children: ChildOrder[] = [];
  const fills: Fill[] = [];
  const quotes: Quote[] = [];

  // Pass 1: parents (children reference them).
  const parentsById = new Map<string, ParentOrder>();
  for (const record of records) {
    if (record.kind === "parent") {
      const parent = normalizeParent(record, errors);
      if (parent && !parentsById.has(parent.id)) {
        parentsById.set(parent.id, parent);
        parents.push(parent);
      }
    }
  }

  // Pass 2: children (fills reference them); reject child qty overflow.
  const childrenById = new Map<string, ChildOrder>();
  const childQtyByParent = new Map<string, bigint>();
  for (const record of records) {
    if (record.kind !== "child") continue;
    const child = normalizeChild(record, errors, parentsById);
    if (!child || childrenById.has(child.id)) continue;
    const used = childQtyByParent.get(child.parentId) ?? 0n;
    const parent = parentsById.get(child.parentId);
    if (parent && used + child.qty > parent.qty) {
      errors.push(
        makeError("E_FILL_QTY_EXCEEDED", recordKey(record),
          "cumulative child quantity exceeds parent quantity"),
      );
      continue;
    }
    childQtyByParent.set(child.parentId, used + child.qty);
    childrenById.set(child.id, child);
    children.push(child);
  }

  // Pass 3: fills; cumulative quantity per child must not exceed order qty.
  // Ties (same timestamp) are broken by fill business key for determinism.
  const fillsByChild = new Map<string, Fill[]>();
  for (const record of records) {
    if (record.kind !== "fill") continue;
    const fill = normalizeFill(record, errors, childrenById, parentsById);
    if (!fill) continue;
    fillsByChild.set(fill.childId, [...(fillsByChild.get(fill.childId) ?? []), fill]);
  }
  for (const [childId, childFills] of fillsByChild) {
    const sorted = [...childFills].sort((a, b) =>
      a.time !== b.time ? a.time - b.time : a.id < b.id ? -1 : a.id > b.id ? 1 : 0,
    );
    const child = childrenById.get(childId);
    let cumulative = 0n;
    for (const fill of sorted) {
      if (child && cumulative + fill.qty > child.qty) {
        errors.push(
          makeError("E_FILL_QTY_EXCEEDED", `fill:${fill.id}`,
            "cumulative fill quantity exceeds child quantity"),
        );
        continue;
      }
      cumulative += fill.qty;
      fills.push(fill);
    }
  }

  // Pass 4: quotes (already bid<=ask checked in normalization).
  for (const record of records) {
    if (record.kind === "quote") {
      const quote = normalizeQuote(record, errors);
      if (quote) quotes.push(quote);
    }
  }

  // Stable ordering by business key for normalized lists.
  parents.sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  children.sort((a, b) => (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
  fills.sort((a, b) =>
    a.childId !== b.childId
      ? a.childId < b.childId ? -1 : 1
      : a.time !== b.time ? a.time - b.time
      : a.id < b.id ? -1 : a.id > b.id ? 1 : 0,
  );
  quotes.sort((a, b) =>
    a.symbol !== b.symbol ? (a.symbol < b.symbol ? -1 : 1)
    : a.time !== b.time ? a.time - b.time
    : a.source !== b.source ? (a.source < b.source ? -1 : 1)
    : a.seq - b.seq,
  );

  errors.sort((a, b) =>
    a.code !== b.code ? (a.code < b.code ? -1 : 1)
    : a.recordKey < b.recordKey ? -1 : a.recordKey > b.recordKey ? 1 : 0,
  );

  return {
    parents,
    children,
    fills,
    quotes,
    errors,
    duplicates: [],
  };
}
