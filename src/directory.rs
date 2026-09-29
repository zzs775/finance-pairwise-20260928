//! Fixed-width segment directory written after all segments.

use crate::error::{CodecError, Result};
use crate::header::{FileHeader, HEADER_LEN, MAX_RECORDS_PER_SEGMENT};
use crate::segment::SegmentMeta;

pub const DIRECTORY_ENTRY_LEN: usize = 40;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Directory {
    pub segments: Vec<SegmentMeta>,
}

impl Directory {
    pub fn encode(&self, out: &mut Vec<u8>) -> Result<()> {
        for entry in &self.segments {
            let start = out.len();
            out.extend_from_slice(&entry.index.to_le_bytes());
            out.extend_from_slice(&entry.data_offset.to_le_bytes());
            out.extend_from_slice(&entry.data_length.to_le_bytes());
            out.extend_from_slice(&entry.record_count.to_le_bytes());
            out.extend_from_slice(&entry.first_ts.to_le_bytes());
            out.extend_from_slice(&entry.last_ts.to_le_bytes());
            out.extend_from_slice(&entry.crc32.to_le_bytes());
            debug_assert_eq!(out.len() - start, DIRECTORY_ENTRY_LEN);
        }
        Ok(())
    }

    /// Decodes exactly `count` entries from `buf`.
    /// `base` is the absolute file offset of `buf[0]`.
    pub fn decode(buf: &[u8], count: u32, base: u64) -> Result<Directory> {
        let need = (count as usize)
            .checked_mul(DIRECTORY_ENTRY_LEN)
            .ok_or(CodecError::BadLength { offset: base })?;
        if buf.len() < need {
            return Err(CodecError::Truncated {
                offset: base.saturating_add(buf.len() as u64),
            });
        }

        let mut segments = Vec::new();
        segments.try_reserve_exact(count as usize).map_err(|_| {
            CodecError::BadLength { offset: base }
        })?;

        for i in 0..count as usize {
            let off = i * DIRECTORY_ENTRY_LEN;
            let rd32 = |rel: usize| u32::from_le_bytes(buf[off + rel..off + rel + 4].try_into().unwrap());
            let rd64 = |rel: usize| u64::from_le_bytes(buf[off + rel..off + rel + 8].try_into().unwrap());
            segments.push(SegmentMeta {
                index: rd32(0),
                data_offset: rd64(4),
                data_length: rd32(12),
                record_count: rd32(16),
                first_ts: rd64(20) as i64,
                last_ts: rd64(28) as i64,
                crc32: rd32(36),
            });
        }
        Ok(Directory { segments })
    }

    /// Checks ordering, bounds against `file_len`, and that ranges never overlap.
    pub fn validate(&self, header: &FileHeader, file_len: u64) -> Result<()> {
        let dir_end = header
            .directory_offset
            .checked_add(header.directory_length as u64)
            .ok_or(CodecError::DirectoryOutOfBounds {
                offset: header.directory_offset,
            })?;
        if dir_end > file_len {
            return Err(CodecError::DirectoryOutOfBounds {
                offset: header.directory_offset,
            });
        }

        let expected = header.segment_count as usize;
        if self.segments.len() != expected {
            return Err(CodecError::BadLength {
                offset: header.directory_offset,
            });
        }

        let mut prev_end = HEADER_LEN as u64;
        for (i, seg) in self.segments.iter().enumerate() {
            if seg.index as usize != i {
                return Err(CodecError::BadLength {
                    offset: header.directory_offset + (i * DIRECTORY_ENTRY_LEN) as u64,
                });
            }
            if seg.record_count == 0 || seg.record_count > MAX_RECORDS_PER_SEGMENT {
                return Err(CodecError::BadLength {
                    offset: header.directory_offset + (i * DIRECTORY_ENTRY_LEN) as u64 + 16,
                });
            }
            if seg.first_ts > seg.last_ts {
                return Err(CodecError::NonMonotonicTimestamp {
                    offset: header.directory_offset + (i * DIRECTORY_ENTRY_LEN) as u64 + 20,
                });
            }
            if seg.data_offset < prev_end {
                return Err(CodecError::OverlappingSegments {
                    offset: seg.data_offset,
                });
            }
            let seg_end = seg
                .data_offset
                .checked_add(seg.data_length as u64)
                .ok_or(CodecError::BadLength {
                    offset: seg.data_offset,
                })?;
            if seg_end > header.directory_offset {
                return Err(CodecError::DirectoryOutOfBounds {
                    offset: seg.data_offset,
                });
            }
            prev_end = seg_end;
        }
        if expected > 0 && prev_end != header.directory_offset {
            return Err(CodecError::OverlappingSegments {
                offset: prev_end,
            });
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_header() -> FileHeader {
        FileHeader {
            version: 1,
            records_per_segment: 64,
            segment_count: 2,
            directory_offset: 124,
            directory_length: 80,
        }
    }

    #[test]
    fn roundtrip_and_validate() {
        let dir = Directory {
            segments: vec![
                SegmentMeta {
                    index: 0,
                    data_offset: 32,
                    data_length: 50,
                    record_count: 3,
                    first_ts: 0,
                    last_ts: 2,
                    crc32: 1,
                },
                SegmentMeta {
                    index: 1,
                    data_offset: 82,
                    data_length: 42,
                    record_count: 2,
                    first_ts: 3,
                    last_ts: 4,
                    crc32: 2,
                },
            ],
        };
        let mut buf = Vec::new();
        dir.encode(&mut buf).unwrap();
        assert_eq!(buf.len(), 80);
        let decoded = Directory::decode(&buf, 2, 124).unwrap();
        assert_eq!(decoded, dir);
        decoded.validate(&sample_header(), 204).unwrap();
    }

    #[test]
    fn rejects_overlap() {
        let dir = Directory {
            segments: vec![
                SegmentMeta {
                    index: 0,
                    data_offset: 32,
                    data_length: 60,
                    record_count: 3,
                    first_ts: 0,
                    last_ts: 2,
                    crc32: 1,
                },
                SegmentMeta {
                    index: 1,
                    data_offset: 80,
                    data_length: 44,
                    record_count: 2,
                    first_ts: 3,
                    last_ts: 4,
                    crc32: 2,
                },
            ],
        };
        assert!(matches!(
            dir.validate(&sample_header(), 204),
            Err(CodecError::OverlappingSegments { offset: 80 })
        ));
    }
}
