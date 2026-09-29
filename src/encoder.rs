//! Streaming segmented encoder.

use crate::crc32::crc32;
use crate::directory::{Directory, DIRECTORY_ENTRY_LEN};
use crate::error::{CodecError, Result};
use crate::header::{
    FileHeader, HEADER_LEN, MAX_RECORDS_PER_SEGMENT, SCHEMA_VERSION,
};
use crate::segment::Quote;
use crate::segment::{
    validate_quote, SegmentMeta, SEGMENT_TRAILER_MARKER,
    TAG_ABSOLUTE, TAG_CHECKPOINT, TAG_DELTA,
};
use crate::varint::{write_varint, zigzag_encode_i64};

#[derive(Default)]
pub struct Encoder {
    records_per_segment: u32,
    body: Vec<u8>,
    current: Vec<u8>,
    segments: Vec<SegmentMeta>,
    prev_ts: Option<i64>,
    prev_delta: i64,
    in_segment: u32,
    first_ts: i64,
    last_ts: i64,
    last_bid: i64,
    last_ask: i64,
    last_size: i64,
    total_records: u64,
}

impl Encoder {
    pub fn new(records_per_segment: u32) -> Result<Self> {
        if records_per_segment == 0 || records_per_segment > MAX_RECORDS_PER_SEGMENT {
            return Err(CodecError::RecordLimitExceeded { offset: 6 });
        }
        Ok(Encoder {
            records_per_segment,
            ..Default::default()
        })
    }

    pub fn push(&mut self, quote: &Quote) -> Result<()> {
        let offset = HEADER_LEN as u64 + self.body.len() as u64 + self.current.len() as u64;
        validate_quote(quote, self.prev_ts, offset)?;

        if self.in_segment == self.records_per_segment {
            self.close_segment()?;
        }

        if self.in_segment == 0 {
            self.emit_absolute(quote);
            self.first_ts = quote.ts;
            self.prev_delta = 0;
        } else {
            let delta = checked_sub(quote.ts, self.prev_ts.unwrap(), offset)?;
            let dod = checked_sub(delta, self.prev_delta, offset)?;
            let bid_delta = checked_sub(quote.bid, self.last_bid, offset)?;
            let ask_delta = checked_sub(quote.ask, self.last_ask, offset)?;

            let interval = crate::segment::CHECKPOINT_INTERVAL;
            if self.in_segment % interval == 0 {
                self.emit_checkpoint(quote);
                // The checkpoint is an absolute restart: the following delta
                // record encodes delta-of-delta against zero.
                self.prev_delta = 0;
            } else {
                self.current.push(TAG_DELTA);
                write_varint(&mut self.current, zigzag_encode_i64(dod));
                write_varint(&mut self.current, zigzag_encode_i64(bid_delta));
                write_varint(&mut self.current, zigzag_encode_i64(ask_delta));
                write_varint(&mut self.current, zigzag_encode_i64(quote.size));
            }
            self.prev_delta = delta;
        }

        self.prev_ts = Some(quote.ts);
        self.last_bid = quote.bid;
        self.last_ask = quote.ask;
        self.last_size = quote.size;
        self.last_ts = quote.ts;
        self.in_segment += 1;
        self.total_records += 1;
        Ok(())
    }

    pub fn finish(mut self) -> Result<Vec<u8>> {
        if self.in_segment > 0 {
            self.close_segment()?;
        }

        let segment_count = self.segments.len() as u32;
        let directory_offset = (HEADER_LEN + self.body.len()) as u64;
        let directory_length =
            (self.segments.len() * DIRECTORY_ENTRY_LEN) as u32;

        let header = FileHeader {
            version: SCHEMA_VERSION,
            records_per_segment: self.records_per_segment,
            segment_count,
            directory_offset,
            directory_length,
        };

        let mut out = Vec::new();
        out.try_reserve(
            HEADER_LEN
                .saturating_add(self.body.len())
                .saturating_add(directory_length as usize),
        )
        .map_err(|_| CodecError::Overflow { offset: 0 })?;
        header.encode(&mut out)?;
        out.extend_from_slice(&self.body);
        Directory {
            segments: self.segments,
        }
        .encode(&mut out)?;
        Ok(out)
    }

    fn emit_absolute(&mut self, quote: &Quote) {
        self.current.push(TAG_ABSOLUTE);
        write_varint(&mut self.current, zigzag_encode_i64(quote.ts));
        write_varint(&mut self.current, zigzag_encode_i64(quote.bid));
        write_varint(&mut self.current, zigzag_encode_i64(quote.ask));
        write_varint(&mut self.current, zigzag_encode_i64(quote.size));
    }

    fn emit_checkpoint(&mut self, quote: &Quote) {
        self.current.push(TAG_CHECKPOINT);
        write_varint(&mut self.current, zigzag_encode_i64(quote.ts));
        write_varint(&mut self.current, zigzag_encode_i64(quote.bid));
        write_varint(&mut self.current, zigzag_encode_i64(quote.ask));
        write_varint(&mut self.current, zigzag_encode_i64(quote.size));
    }

    fn close_segment(&mut self) -> Result<()> {
        if self.in_segment == 0 {
            return Ok(());
        }
        let payload_len = self.current.len();
        let checksum = crc32(&self.current);

        self.current
            .extend_from_slice(&self.in_segment.to_le_bytes());
        self.current.extend_from_slice(&checksum.to_le_bytes());
        self.current
            .extend_from_slice(&SEGMENT_TRAILER_MARKER.to_le_bytes());

        let data_offset = (HEADER_LEN + self.body.len()) as u64;
        let data_length = self.current.len() as u32;
        self.segments.push(SegmentMeta {
            index: (self.segments.len()) as u32,
            data_offset,
            data_length,
            record_count: self.in_segment,
            first_ts: self.first_ts,
            last_ts: self.last_ts,
            crc32: checksum,
        });
        let _ = payload_len;
        self.body.append(&mut self.current);
        self.in_segment = 0;
        self.prev_delta = 0;
        Ok(())
    }
}

/// Encodes all quotes in one shot.
pub fn encode_all(quotes: &[Quote], records_per_segment: u32) -> Result<Vec<u8>> {
    let mut encoder = Encoder::new(records_per_segment)?;
    for quote in quotes {
        encoder.push(quote)?;
    }
    encoder.finish()
}

fn checked_sub(a: i64, b: i64, offset: u64) -> Result<i64> {
    i64::checked_sub(a, b).ok_or(CodecError::Overflow { offset })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::varint::read_varint;

    #[test]
    fn rejects_zero_segment_size() {
        assert!(matches!(
            Encoder::new(0),
            Err(CodecError::RecordLimitExceeded { offset: 6 })
        ));
    }

    #[test]
    fn empty_file_is_header_only() {
        let bytes = encode_all(&[], 64).unwrap();
        assert_eq!(bytes.len(), HEADER_LEN);
        let header = FileHeader::decode(&bytes).unwrap();
        assert_eq!(header.segment_count, 0);
    }

    #[test]
    fn varint_import_used() {
        // Sanity: reader is wired in through decoder; smoke-check writer here.
        let mut buf = Vec::new();
        write_varint(&mut buf, 300);
        let mut pos = 0;
        assert_eq!(read_varint(&buf, &mut pos, 0).unwrap(), 300);
    }
}
