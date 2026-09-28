import type { ErrorCode, RecordError } from "./types";

export function makeError(
  code: ErrorCode,
  recordKey: string,
  message: string,
): RecordError {
  return { code, recordKey, message };
}

/** Stable sort: by code then recordKey. */
export function sortErrors(errors: RecordError[]): RecordError[] {
  return [...errors].sort((a, b) => {
    if (a.code !== b.code) return a.code < b.code ? -1 : 1;
    if (a.recordKey !== b.recordKey) {
      return a.recordKey < b.recordKey ? -1 : 1;
    }
    return a.message < b.message ? -1 : a.message > b.message ? 1 : 0;
  });
}
