//! Whole-file and per-segment decoding.

use crate::crc32::crc32;
use crate::directory::Directory;
use crate::error::{CodecError, Result};
use crate::header::{FileHeader, HEADER_LEN};
use crate::segment::{
    validate_quote, Quote, SegmentMeta, SEGMENT_TRAILER_LEN,
    SEGMENT_TRAILER_MARKER, TAG_ABSOLUTE, TAG_CHECKPOINT, TAG_DELTA,
};
use crate::varint::{read_varint, zigzag_decode_u64};

/// A parsed stream item; checkpoints resynchronize state but are not records.
#[derive(Debug)]
pub(crate) enum StreamEvent {
    Record(Quote),
    Checkpoint(Quote),
}

/// Parses one segment payload, verifying boundaries, trailer, and CRC first.
/// `seg.data_offset` provides the absolute base for all reported offsets.
pub(crate) fn parse_segment(file: &[u8], seg: &SegmentMeta) -> Result<Vec<StreamEvent>> {
    let base = seg.data_offset;
    let total = seg.data_length as usize;
    let end = (base as usize)
        .checked_add(total)
        .ok_or(CodecError::BadLength { offset: base })?;
    if (base as usize) < HEADER_LEN || end > file.len() || total < SEGMENT_TRAILER_LEN {
        return Err(CodecError::Truncated {
            offset: base.saturating_add(file.len() as u64),
        });
    }

    let payload_len = total - SEGMENT_TRAILER_LEN;
    let trailer = &file[end - SEGMENT_TRAILER_LEN..end];
    let record_count = u32::from_le_bytes(trailer[0..4].try_into().unwrap());
    let stored_crc = u32::from_le_bytes(trailer[4..8].try_into().unwrap());
    let marker = u32::from_le_bytes(trailer[8..12].try_into().unwrap());

    if marker != SEGMENT_TRAILER_MARKER {
        return Err(CodecError::BadLength {
            offset: base + total as u64 - 4,
        });
    }
    if record_count != seg.record_count {
        return Err(CodecError::BadLength {
            offset: base + payload_len as u64,
        });
    }
    if seg.crc32 != stored_crc {
        return Err(CodecError::CrcMismatch {
            offset: base + payload_len as u64 + 4,
            segment: seg.index,
        });
    }

    let payload = &file[base as usize..base as usize + payload_len];
    if crc32(payload) != stored_crc {
        return Err(CodecError::CrcMismatch {
            offset: base + payload_len as u64 + 4,
            segment: seg.index,
        });
    }

    let mut events = Vec::new();
    events
        .try_reserve(seg.record_count as usize + 1)
        .map_err(|_| CodecError::Overflow { offset: base })?;
    let mut pos = 0usize;
    let mut materialized: u32 = 0;
    let mut prev_ts: Option<i64> = None;
    let mut last_ts: i64 = 0;
    let mut last_delta: i64 = 0;
    let mut last_bid: i64 = 0;
    let mut last_ask: i64 = 0;

    while pos < payload_len {
        let tag_offset = base + pos as u64;
        let tag = payload[pos];
        pos += 1;

        match tag {
            TAG_ABSOLUTE | TAG_CHECKPOINT => {
                let ts = zigzag_decode_u64(read_varint(payload, &mut pos, base)?);
                let bid = zigzag_decode_u64(read_varint(payload, &mut pos, base)?);
                let ask = zigzag_decode_u64(read_varint(payload, &mut pos, base)?);
                let size = zigzag_decode_u64(read_varint(payload, &mut pos, base)?);
                let quote = Quote { ts, bid, ask, size };
                validate_quote(&quote, prev_ts, tag_offset)?;
                last_ts = ts;
                // Absolute records reset the delta ladder; checkpoints must
                // preserve the current delta because following records still
                // encode delta-of-delta against it.
                last_delta = if tag == TAG_ABSOLUTE {
                    0
                } else {
                    ts.checked_sub(prev_ts.unwrap_or(ts))
                        .ok_or(CodecError::Overflow { offset: tag_offset })?
                };
                last_bid = bid;
                last_ask = ask;
                prev_ts = Some(ts);
                if tag == TAG_ABSOLUTE {
                    events.push(StreamEvent::Record(quote));
                } else {
                    events.push(StreamEvent::Checkpoint(quote));
                }
                materialized += 1;
            }
            TAG_DELTA => {
                let dod = zigzag_decode_u64(read_varint(payload, &mut pos, base)?);
                let bid_delta = zigzag_decode_u64(read_varint(payload, &mut pos, base)?);
                let ask_delta = zigzag_decode_u64(read_varint(payload, &mut pos, base)?);
                let size = zigzag_decode_u64(read_varint(payload, &mut pos, base)?);

                let delta = last_delta
                    .checked_add(dod)
                    .ok_or(CodecError::Overflow { offset: tag_offset })?;
                let ts = last_ts
                    .checked_add(delta)
                    .ok_or(CodecError::Overflow { offset: tag_offset })?;
                let bid = last_bid
                    .checked_add(bid_delta)
                    .ok_or(CodecError::Overflow { offset: tag_offset })?;
                let ask = last_ask
                    .checked_add(ask_delta)
                    .ok_or(CodecError::Overflow { offset: tag_offset })?;

                let quote = Quote { ts, bid, ask, size };
                validate_quote(&quote, prev_ts, tag_offset)?;
                last_ts = ts;
                last_delta = delta;
                last_bid = bid;
                last_ask = ask;
                prev_ts = Some(ts);
                materialized += 1;
                events.push(StreamEvent::Record(quote));
            }
            other => {
                return Err(CodecError::TagUnknown {
                    offset: tag_offset,
                    tag: other,
                })
            }
        }
    }

    if materialized != seg.record_count {
        return Err(CodecError::BadLength {
            offset: base + payload_len as u64,
        });
    }
    Ok(events)
}

/// Parsed file: validated header and directory with segment metadata.
pub(crate) struct ParsedFile<'a> {
    #[allow(dead_code)]
    pub header: FileHeader,
    pub directory: Directory,
    pub file: &'a [u8],
}

pub(crate) fn parse_file(buf: &[u8]) -> Result<ParsedFile<'_>> {
    let header = FileHeader::decode(buf)?;
    let dir_end = header
        .directory_offset
        .checked_add(header.directory_length as u64)
        .ok_or(CodecError::DirectoryOutOfBounds {
            offset: header.directory_offset,
        })?;
    if dir_end > buf.len() as u64 {
        return Err(CodecError::Truncated { offset: dir_end });
    }
    let directory = Directory::decode(
        &buf[header.directory_offset as usize..dir_end as usize],
        header.segment_count,
        header.directory_offset,
    )?;
    directory.validate(&header, buf.len() as u64)?;
    Ok(ParsedFile { header, directory, file: buf })
}

/// Extracts materialized quote records from `events` starting at `start_event`.
pub(crate) fn quotes_from(
    events: &[StreamEvent],
    start_event: usize,
) -> impl Iterator<Item = &Quote> {
    events[start_event..].iter().map(|event| match event {
        StreamEvent::Record(quote) | StreamEvent::Checkpoint(quote) => quote,
    })
}

/// Fully decodes one segment's materialized quotes.
pub(crate) fn decode_segment(file: &[u8], seg: &SegmentMeta) -> Result<Vec<Quote>> {
    let events = parse_segment(file, seg)?;
    let mut quotes = Vec::new();
    quotes
        .try_reserve_exact(seg.record_count as usize)
        .map_err(|_| CodecError::Overflow {
            offset: seg.data_offset,
        })?;
    quotes.extend(quotes_from(&events, 0).cloned());
    if quotes.len() != seg.record_count as usize {
        return Err(CodecError::BadLength {
            offset: seg.data_offset,
        });
    }
    Ok(quotes)
}

/// Sequential whole-file decoder.
#[derive(Debug)]
pub struct Decoder<'a> {
    buf: &'a [u8],
}

impl<'a> Decoder<'a> {
    pub fn new(buf: &'a [u8]) -> Result<Self> {
        parse_file(buf)?;
        Ok(Decoder { buf })
    }

    /// Full sequential decode of every record.
    pub fn decode_all(&self) -> Result<Vec<Quote>> {
        let parsed = parse_file(self.buf)?;
        let total: usize = parsed
            .directory
            .segments
            .iter()
            .map(|seg| seg.record_count as usize)
            .sum();
        let mut quotes = Vec::new();
        quotes
            .try_reserve_exact(total)
            .map_err(|_| CodecError::Overflow { offset: HEADER_LEN as u64 })?;
        let mut prev_ts: Option<i64> = None;
        for seg in &parsed.directory.segments {
            let part = decode_segment(parsed.file, seg)?;
            if let Some(first) = part.first() {
                if let Some(prev) = prev_ts {
                    if first.ts <= prev {
                        return Err(CodecError::NonMonotonicTimestamp {
                            offset: seg.data_offset,
                        });
                    }
                }
            }
            prev_ts = part.last().map(|quote| quote.ts).or(prev_ts);
            quotes.extend(part);
        }
        Ok(quotes)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::encoder::encode_all;

    #[test]
    fn empty_file_decodes_empty() {
        let bytes = encode_all(&[], 16).unwrap();
        let decoded = Decoder::new(&bytes).unwrap().decode_all().unwrap();
        assert!(decoded.is_empty());
    }

    #[test]
    fn simple_roundtrip() {
        let quotes = vec![
            Quote::new(100, 9, 11, 1),
            Quote::new(101, 10, 12, 2),
            Quote::new(103, 11, 11, 3),
        ];
        let bytes = encode_all(&quotes, 16).unwrap();
        let decoded = Decoder::new(&bytes).unwrap().decode_all().unwrap();
        assert_eq!(decoded, quotes);
    }
}
