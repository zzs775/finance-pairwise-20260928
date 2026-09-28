import { readFileSync, writeFileSync } from "node:fs";
import { formatFixed } from "./fixedpoint";
import type { RawDataset, Report } from "./types";

/** Read a JSON dataset file using only node:fs built-ins. */
export function readDataset(path: string): RawDataset {
  const text = readFileSync(path, "utf8");
  const parsed = JSON.parse(text) as unknown;
  if (
    typeof parsed !== "object" ||
    parsed === null ||
    !Array.isArray((parsed as { records?: unknown }).records)
  ) {
    throw new Error(`input ${path} must be an object with a records array`);
  }
  return parsed as RawDataset;
}

/** Canonical JSON: keys sorted recursively, stable spacing. */
export function toCanonicalJson(report: Report): string {
  return JSON.stringify(sortValue(report), undefined, 2) + "\n";
}

export function writeReport(path: string, report: Report): void {
  writeFileSync(path, toCanonicalJson(report), "utf8");
}

function sortValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortValue);
  if (typeof value === "object" && value !== null) {
    const source = value as Record<string, unknown>;
    const sorted: Record<string, unknown> = {};
    for (const key of Object.keys(source).sort()) {
      const inner = source[key];
      sorted[key] = typeof inner === "bigint"
        ? formatFixed(inner)
        : sortValue(inner);
    }
    return sorted;
  }
  if (typeof value === "bigint") return formatFixed(value);
  return value;
}
