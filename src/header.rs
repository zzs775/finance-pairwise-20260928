//! Fixed 32-byte little-endian file header.

use crate::error::{CodecError, Result};

pub const MAGIC: [u8; 4] = *b"MQT1";
pub const SCHEMA_VERSION: u16 = 1;
pub const HEADER_LEN: usize = 32;

/// Hard cap on records inside a single segment.
pub const MAX_RECORDS_PER_SEGMENT: u32 = 1 << 20;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileHeader {
    pub version: u16,
    pub records_per_segment: u32,
    pub segment_count: u32,
    pub directory_offset: u64,
    pub directory_length: u32,
}

impl FileHeader {
    pub fn encode(&self, out: &mut Vec<u8>) -> Result<()> {
        let start = out.len();
        out.extend_from_slice(&MAGIC);
        out.extend_from_slice(&self.version.to_le_bytes());
        out.extend_from_slice(&self.records_per_segment.to_le_bytes());
        out.extend_from_slice(&self.segment_count.to_le_bytes());
        out.extend_from_slice(&self.directory_offset.to_le_bytes());
        out.extend_from_slice(&self.directory_length.to_le_bytes());
        // Reserved: must be zero in canonical files.
        out.resize(start + HEADER_LEN, 0);
        debug_assert_eq!(out.len() - start, HEADER_LEN);
        Ok(())
    }

    /// Decodes the header at the very start of `buf`.
    pub fn decode(buf: &[u8]) -> Result<FileHeader> {
        if buf.len() < HEADER_LEN {
            return Err(CodecError::Truncated {
                offset: buf.len() as u64,
            });
        }
        let mut pos = 0usize;
        let take = |n: usize, pos: &mut usize| {
            let chunk: [u8; 8] = {
                let mut a = [0u8; 8];
                a[..n].copy_from_slice(&buf[*pos..*pos + n]);
                *pos += n;
                a
            };
            chunk
        };

        let magic = &buf[pos..pos + 4];
        if magic != MAGIC {
            return Err(CodecError::BadMagic { offset: 0 });
        }
        pos += 4;

        let version = u16::from_le_bytes(take(2, &mut pos)[..2].try_into().unwrap());
        if version != SCHEMA_VERSION {
            return Err(CodecError::UnsupportedVersion {
                offset: 4,
                version,
            });
        }
        let records_per_segment =
            u32::from_le_bytes(take(4, &mut pos)[..4].try_into().unwrap());
        let segment_count = u32::from_le_bytes(take(4, &mut pos)[..4].try_into().unwrap());
        let directory_offset = u64::from_le_bytes(take(8, &mut pos));
        let directory_length = u32::from_le_bytes(take(4, &mut pos)[..4].try_into().unwrap());

        if records_per_segment == 0 || records_per_segment > MAX_RECORDS_PER_SEGMENT {
            return Err(CodecError::BadLength { offset: 6 });
        }
        // Structural range checks precede the reserved-bytes check so a forged
        // directory length is reported at its own field (offset 24).
        let dir_end = directory_offset
            .checked_add(directory_length as u64)
            .ok_or(CodecError::DirectoryOutOfBounds { offset: 24 })?;
        if directory_length as usize != (segment_count as usize)
            .checked_mul(crate::directory::DIRECTORY_ENTRY_LEN)
            .ok_or(CodecError::BadLength { offset: 24 })?
        {
            return Err(CodecError::BadLength { offset: 24 });
        }
        if (segment_count > 0 && directory_offset < HEADER_LEN as u64)
            || dir_end < directory_offset
        {
            return Err(CodecError::DirectoryOutOfBounds { offset: 16 });
        }

        let reserved = &buf[pos..HEADER_LEN];
        if reserved.iter().any(|&b| b != 0) {
            return Err(CodecError::BadLength { offset: pos as u64 });
        }

        let header = FileHeader {
            version,
            records_per_segment,
            segment_count,
            directory_offset,
            directory_length,
        };
        Ok(header)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_header() {
        let h = FileHeader {
            version: SCHEMA_VERSION,
            records_per_segment: 100,
            segment_count: 3,
            directory_offset: 4096,
            directory_length: 120,
        };
        let mut buf = Vec::new();
        h.encode(&mut buf).unwrap();
        assert_eq!(buf.len(), HEADER_LEN);
        assert_eq!(FileHeader::decode(&buf).unwrap(), h);
    }

    #[test]
    fn rejects_truncated_and_bad_magic() {
        assert!(matches!(
            FileHeader::decode(&[0u8; 10]),
            Err(CodecError::Truncated { .. })
        ));
        let mut buf = vec![0u8; HEADER_LEN];
        buf[0..4].copy_from_slice(b"XXXX");
        assert!(matches!(
            FileHeader::decode(&buf),
            Err(CodecError::BadMagic { offset: 0 })
        ));
    }
}
