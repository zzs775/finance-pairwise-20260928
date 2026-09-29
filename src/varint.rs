//! ZigZag + LEB128 varint primitives (little-endian base 128).

use crate::error::{CodecError, Result};

/// Maximum number of bytes in a canonical u64 LEB128 varint.
pub const MAX_VARINT_LEN: usize = 10;

pub fn zigzag_encode_i64(value: i64) -> u64 {
    let bits = (value as u64) << 1;
    let sign = (value >> 63) as u64;
    bits ^ sign
}

pub fn zigzag_decode_u64(value: u64) -> i64 {
    ((value >> 1) as i64) ^ -((value & 1) as i64)
}

pub fn write_varint(buf: &mut Vec<u8>, value: u64) {
    let mut remaining = value;
    loop {
        let mut byte = (remaining & 0x7F) as u8;
        remaining >>= 7;
        if remaining != 0 {
            byte |= 0x80;
        }
        buf.push(byte);
        if remaining == 0 {
            break;
        }
    }
}

/// Reads a canonical varint at `*pos`, advancing `*pos`.
/// `base` is the absolute file offset of `buf[0]`, so reported offsets stay
/// absolute even when `buf` is a segment slice.
pub fn read_varint(buf: &[u8], pos: &mut usize, base: u64) -> Result<u64> {
    let mut result: u64 = 0;
    let mut shift: u32 = 0;
    let mut groups = 0usize;

    loop {
        if *pos >= buf.len() {
            return Err(CodecError::Truncated {
                offset: base.saturating_add(*pos as u64),
            });
        }
        let byte = buf[*pos];
        let byte_offset = base + *pos as u64;
        *pos += 1;
        groups += 1;

        if groups == MAX_VARINT_LEN {
            // The final group may only contribute the single high bit.
            if byte & 0x80 != 0 {
                return Err(CodecError::VarintTooLong {
                    offset: byte_offset,
                });
            }
            if byte & 0x7E != 0 {
                return Err(CodecError::Overflow {
                    offset: byte_offset,
                });
            }
            result |= ((byte & 0x01) as u64) << shift;
            break;
        }

        result |= ((byte & 0x7F) as u64) << shift;
        shift += 7;

        if byte & 0x80 == 0 {
            // Canonical encoding: the terminating group must carry data,
            // otherwise the encoding is redundant (e.g. 0x80 0x00 for 0).
            if groups > 1 && byte == 0 {
                return Err(CodecError::NonCanonicalVarint {
                    offset: byte_offset,
                });
            }
            break;
        }
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zigzag_pairs() {
        for (signed, unsigned) in [
            (0i64, 0u64),
            (-1, 1),
            (1, 2),
            (-2, 3),
            (2, 4),
            (i64::MAX, 0xFFFF_FFFF_FFFF_FFFE),
            (i64::MIN, 0xFFFF_FFFF_FFFF_FFFF),
        ] {
            assert_eq!(zigzag_encode_i64(signed), unsigned);
            assert_eq!(zigzag_decode_u64(unsigned), signed);
        }
    }

    #[test]
    fn varint_roundtrip() {
        for value in [0u64, 1, 127, 128, 300, u32::MAX as u64, u64::MAX - 1, u64::MAX] {
            let mut buf = Vec::new();
            write_varint(&mut buf, value);
            let mut pos = 0;
            assert_eq!(read_varint(&buf, &mut pos, 0).unwrap(), value);
            assert_eq!(pos, buf.len());
        }
    }

    #[test]
    fn rejects_noncanonical() {
        let mut pos = 0;
        assert!(matches!(
            read_varint(&[0x80, 0x00], &mut pos, 10),
            Err(CodecError::NonCanonicalVarint { offset: 11 })
        ));
    }

    #[test]
    fn rejects_too_long() {
        let mut pos = 0;
        assert!(matches!(
            read_varint(
                &[0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01],
                &mut pos,
                0
            ),
            Err(CodecError::VarintTooLong { offset: 9 })
        ));
    }

    #[test]
    fn rejects_overflow() {
        // Ten groups, terminating byte contributes bits beyond u64.
        let enc = [0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x02];
        let mut pos = 0;
        assert!(matches!(
            read_varint(&enc, &mut pos, 0),
            Err(CodecError::Overflow { offset: 9 })
        ));
    }

    #[test]
    fn rejects_truncated() {
        let mut pos = 0;
        assert!(matches!(
            read_varint(&[0x80], &mut pos, 5),
            Err(CodecError::Truncated { offset: 6 })
        ));
    }
}
