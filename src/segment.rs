//! Quote records and segment metadata.

use crate::error::Result;
use crate::error::CodecError;

/// One market quote: timestamp, best bid/ask (integer units), volume.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Quote {
    pub ts: i64,
    pub bid: i64,
    pub ask: i64,
    pub size: i64,
}

impl Quote {
    pub fn new(ts: i64, bid: i64, ask: i64, size: i64) -> Self {
        Self { ts, bid, ask, size }
    }
}

/// Static, fixed-width directory entry describing one segment payload.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SegmentMeta {
    pub index: u32,
    pub data_offset: u64,
    pub data_length: u32,
    pub record_count: u32,
    pub first_ts: i64,
    pub last_ts: i64,
    pub crc32: u32,
}

/// Stream item tags inside a segment payload.
pub const TAG_ABSOLUTE: u8 = 0x01;
pub const TAG_DELTA: u8 = 0x02;
pub const TAG_CHECKPOINT: u8 = 0x03;

/// Records between absolute checkpoints inside a segment.
pub const CHECKPOINT_INTERVAL: u32 = 64;

/// Trailer after each segment payload: record_count, crc32, marker.
pub const SEGMENT_TRAILER_LEN: usize = 12;
pub const SEGMENT_TRAILER_MARKER: u32 = 0x54_52_43_53;

/// Validates one record against invariants and the previous timestamp.
/// `offset` is the absolute byte offset where the offending record begins.
pub fn validate_quote(quote: &Quote, prev_ts: Option<i64>, offset: u64) -> Result<()> {
    if let Some(prev) = prev_ts {
        if quote.ts <= prev {
            return Err(CodecError::NonMonotonicTimestamp { offset });
        }
    }
    if quote.bid > quote.ask {
        return Err(CodecError::BidAboveAsk { offset });
    }
    if quote.size < 0 {
        return Err(CodecError::NegativeQuantity { offset });
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_valid_sequence() {
        let prev = None;
        let q1 = Quote::new(10, 99, 100, 5);
        validate_quote(&q1, prev, 0).unwrap();
        let q2 = Quote::new(11, 100, 101, 0);
        validate_quote(&q2, Some(10), 9).unwrap();
    }

    #[test]
    fn rejects_invalid_with_offset() {
        let bad_ts = Quote::new(5, 1, 2, 1);
        assert_eq!(
            validate_quote(&bad_ts, Some(5), 7).unwrap_err(),
            CodecError::NonMonotonicTimestamp { offset: 7 }
        );
        let bad_px = Quote::new(6, 3, 2, 1);
        assert_eq!(
            validate_quote(&bad_px, Some(5), 8).unwrap_err(),
            CodecError::BidAboveAsk { offset: 8 }
        );
        let bad_size = Quote::new(6, 2, 3, -1);
        assert_eq!(
            validate_quote(&bad_size, Some(5), 9).unwrap_err(),
            CodecError::NegativeQuantity { offset: 9 }
        );
    }
}
