import type { DuplicateInfo, RawRecord } from "./types";

/** Business key used for idempotent de-duplication. */
export function recordKey(record: RawRecord): string {
  switch (record.kind) {
    case "parent":
      return `parent:${record.id}`;
    case "child":
      return `child:${record.id}`;
    case "fill":
      return `fill:${record.id}`;
    case "quote":
      return `quote:${record.source}:${record.seq}`;
  }
}

/**
 * Stable merge: drop duplicates (keeping the first occurrence in the
 * canonical key order), report occurrence counts >= 2.
 * Output ordering is independent of input ordering.
 */
export function dedupeRecords(records: RawRecord[]): {
  unique: RawRecord[];
  duplicates: DuplicateInfo[];
} {
  const counts = new Map<string, number>();
  const firstByKey = new Map<string, RawRecord>();
  const keyOrder: string[] = [];
  for (const record of records) {
    const key = recordKey(record);
    const count = counts.get(key) ?? 0;
    if (count === 0) {
      firstByKey.set(key, record);
      keyOrder.push(key);
    }
    counts.set(key, count + 1);
  }
  // Canonical merge order: sort business keys so input permutation never
  // changes the result.
  const sortedKeys = [...keyOrder].sort();
  const unique = sortedKeys.map((key) => {
    const record = firstByKey.get(key);
    if (!record) throw new Error(`dedupe: missing record for key ${key}`);
    return record;
  });
  const duplicates: DuplicateInfo[] = sortedKeys
    .filter((key) => (counts.get(key) ?? 0) > 1)
    .map((key) => ({ recordKey: key, occurrences: counts.get(key) ?? 0 }));
  return { unique, duplicates };
}
