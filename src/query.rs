//! Random-access time-range queries ([start, end), half open).

use crate::decoder::{parse_file, parse_segment, quotes_from, StreamEvent};
use crate::error::{CodecError, Result};
use crate::segment::Quote;

/// Locates the event index from which materialized records are still valid for
/// timestamps `>= target`: just after the latest checkpoint whose ts < target,
/// or 0 (the absolute record) when no earlier checkpoint exists.
fn resume_index(events: &[StreamEvent], target: i64) -> usize {
    let mut resume = 0usize;
    for (idx, event) in events.iter().enumerate() {
        if let StreamEvent::Checkpoint(quote) = event {
            if quote.ts < target {
                resume = idx + 1;
            }
        }
    }
    resume
}

/// Returns quotes with `start <= ts < end`, touching only intersecting segments
/// and resuming from the nearest checkpoint.
pub fn query_range(buf: &[u8], start: i64, end: i64) -> Result<Vec<Quote>> {
    if start > end {
        return Err(CodecError::NonMonotonicTimestamp { offset: 0 });
    }
    if start == end {
        return Ok(Vec::new());
    }

    let parsed = parse_file(buf)?;
    let mut hits = Vec::new();

    for seg in &parsed.directory.segments {
        // Segment time spans are inclusive at both ends (first/last record).
        if seg.last_ts < start || seg.first_ts >= end {
            continue;
        }
        let events = parse_segment(parsed.file, seg)?;
        let resume = resume_index(&events, start);
        for quote in quotes_from(&events, resume) {
            if quote.ts < start {
                continue;
            }
            if quote.ts >= end {
                break;
            }
            hits.push(quote.clone());
        }
    }
    Ok(hits)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::encoder::encode_all;

    fn sample(records_per_segment: u32) -> (Vec<Quote>, Vec<u8>) {
        let quotes: Vec<Quote> = (0..200)
            .map(|i| Quote::new(1000 + i as i64, i as i64, i as i64 + 5, i as i64 % 7))
            .collect();
        let bytes = encode_all(&quotes, records_per_segment).unwrap();
        (quotes, bytes)
    }

    #[test]
    fn half_open_range_matches_sequential_filter() {
        let (quotes, bytes) = sample(50);
        let (start, end) = (1030, 1150);
        let expected: Vec<Quote> = quotes
            .iter()
            .filter(|q| q.ts >= start && q.ts < end)
            .cloned()
            .collect();
        assert_eq!(query_range(&bytes, start, end).unwrap(), expected);
    }

    #[test]
    fn checkpoint_resume_matches_sequential_filter() {
        let (quotes, bytes) = sample(30);
        let (start, end) = (1064, 1130);
        let expected: Vec<Quote> = quotes
            .iter()
            .filter(|q| q.ts >= start && q.ts < end)
            .cloned()
            .collect();
        assert_eq!(query_range(&bytes, start, end).unwrap(), expected);
    }

    #[test]
    fn empty_range_and_bounds() {
        let (_quotes, bytes) = sample(20);
        assert!(query_range(&bytes, 50, 50).unwrap().is_empty());
        assert!(query_range(&bytes, 50, 100).unwrap().is_empty());
        assert!(query_range(&bytes, 5000, 6000).unwrap().is_empty());
    }
}
