import { FIXED_SCALE, midPrice, mulDiv } from "./fixedpoint";
import type { PricePoint, Quote, QuoteGap, TwapSegment, TwapInfo } from "./types";

interface EffectiveQuote {
  time: number;
  source: string;
  seq: number;
  bid: bigint;
  ask: bigint;
}

function toPricePoint(quote: EffectiveQuote): PricePoint {
  return {
    time: quote.time,
    source: quote.source,
    seq: quote.seq,
    bid: quote.bid,
    ask: quote.ask,
  };
}

/**
 * Build the effective-quote timeline for one symbol.
 * At the same timestamp the quote with the smallest source order (then seq)
 * wins; a quote stays effective until the next timestamp.
 */
export class QuoteTimeline {
  private readonly effective: EffectiveQuote[];

  constructor(quotes: Quote[]) {
    // Collapse same-timestamp quotes: smallest source (lexicographic), then
    // smallest source sequence number, wins.
    const byTime = new Map<number, Quote[]>();
    for (const quote of quotes) {
      const bucket = byTime.get(quote.time) ?? [];
      bucket.push(quote);
      byTime.set(quote.time, bucket);
    }
    this.effective = [...byTime.entries()]
      .sort((a, b) => a[0] - b[0])
      .map(([time, bucket]) => {
        const winner = [...bucket].sort((a, b) =>
          a.source !== b.source
            ? a.source < b.source ? -1 : 1
            : a.seq - b.seq,
        )[0] as Quote;
        return {
          time,
          source: winner.source,
          seq: winner.seq,
          bid: winner.bid,
          ask: winner.ask,
        };
      });
  }

  /** Latest effective quote at or before `time`. Null on a leading gap. */
  atOrBefore(time: number): PricePoint | null {
    let chosen: EffectiveQuote | null = null;
    for (const quote of this.effective) {
      if (quote.time <= time) chosen = quote;
      else break;
    }
    return chosen ? toPricePoint(chosen) : null;
  }

  /**
   * TWAP of mid price over [start, end): partition by quote-effective
   * intervals. Leading/interior gaps are reported and excluded from the
   * weighted duration.
   */
  twap(start: number, end: number): TwapInfo {
    if (end < start) {
      throw new Error(`twap: inverted interval [${start}, ${end})`);
    }
    const durationMs = end - start;
    const segments: TwapSegment[] = [];
    const gaps: QuoteGap[] = [];

    // Walk interval boundaries: a quote is effective on
    // [max(quote.time, start), nextQuote.time) clipped to [start, end).
    const times = this.effective.map((q) => q.time);
    // Find the first effective quote at or before `start` (carried state).
    let activeIndex = -1;
    for (let i = 0; i < this.effective.length; i++) {
      if ((times[i] as number) <= start) activeIndex = i;
      else break;
    }

    let cursor = start;
    let quoteIndex = activeIndex;
    // Boundaries where the effective quote may change within the window.
    const changeTimes = times
      .filter((t) => t > start && t < end)
      .sort((a, b) => a - b);

    const pushGap = (gapStart: number, gapEnd: number): void => {
      if (gapEnd <= gapStart) return;
      const last = gaps[gaps.length - 1];
      if (last && last.endTime === gapStart) {
        last.endTime = gapEnd;
        last.durationMs = gapEnd - gapStart;
      } else {
        gaps.push({
          startTime: gapStart,
          endTime: gapEnd,
          durationMs: gapEnd - gapStart,
        });
      }
    };

    const pushSegment = (
      segStart: number,
      segEnd: number,
      quote: EffectiveQuote,
    ): void => {
      if (segEnd <= segStart) return;
      const mid = midPrice(quote.bid, quote.ask);
      segments.push({
        startTime: segStart,
        endTime: segEnd,
        durationMs: segEnd - segStart,
        source: quote.source,
        seq: quote.seq,
        midNumerator: quote.bid + quote.ask,
        mid,
      });
    };

    if (quoteIndex === -1) {
      // Leading gap up to the first in-window quote time (or whole window).
      const firstChange = changeTimes[0];
      const gapEnd = firstChange === undefined ? end : Math.min(firstChange, end);
      pushGap(start, gapEnd);
      cursor = gapEnd;
      if (firstChange !== undefined) {
        quoteIndex = this.effective.findIndex((q) => q.time === firstChange);
      }
    }

    for (const changeTime of changeTimes) {
      if (cursor >= end) break;
      if (changeTime <= cursor) {
        quoteIndex = this.effective.findIndex((q) => q.time === changeTime);
        continue;
      }
      const active = quoteIndex === -1 ? null : this.effective[quoteIndex];
      const segEnd = Math.min(changeTime, end);
      if (active) {
        pushSegment(cursor, segEnd, active);
      } else {
        pushGap(cursor, segEnd);
      }
      cursor = segEnd;
      quoteIndex = this.effective.findIndex((q) => q.time === changeTime);
    }
    if (cursor < end) {
      const active = quoteIndex === -1 ? null : this.effective[quoteIndex];
      if (active) pushSegment(cursor, end, active);
      else pushGap(cursor, end);
    }

    let numerator = 0n;
    let denominator = 0n;
    let coveredDurationMs = 0;
    for (const segment of segments) {
      numerator += segment.mid * BigInt(segment.durationMs);
      coveredDurationMs += segment.durationMs;
      denominator += BigInt(segment.durationMs) * FIXED_SCALE;
    }
    const twap = denominator === 0n
      ? 0n
      : mulDiv(numerator, FIXED_SCALE, denominator);

    return {
      twap,
      durationMs,
      coveredDurationMs,
      numerator,
      denominator,
      segments,
      gaps: mergeGaps(gaps),
    };
  }
}

export function mergeGaps(gaps: QuoteGap[]): QuoteGap[] {
  const merged: QuoteGap[] = [];
  for (const gap of [...gaps].sort((a, b) => a.startTime - b.startTime)) {
    const last = merged[merged.length - 1];
    if (last && last.endTime === gap.startTime) {
      last.endTime = gap.endTime;
      last.durationMs = last.endTime - last.startTime;
    } else {
      merged.push({ ...gap });
    }
  }
  return merged;
}
