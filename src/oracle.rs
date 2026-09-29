//! Mechanical canonical round-trip oracle.

use crate::decoder::Decoder;
use crate::encoder::encode_all;
use crate::error::{CodecError, Result};
use crate::segment::Quote;

/// decode(encode(x)) == x field-by-field, and re-encoding reproduces bytes.
pub fn assert_canonical_roundtrip(
    quotes: &[Quote],
    records_per_segment: u32,
) -> Result<()> {
    let first = encode_all(quotes, records_per_segment)?;
    let decoded = Decoder::new(&first)?.decode_all()?;
    if decoded.len() != quotes.len() {
        return Err(CodecError::BadLength { offset: 0 });
    }
    for (idx, (want, got)) in quotes.iter().zip(decoded.iter()).enumerate() {
        if want != got {
            return Err(CodecError::Overflow {
                offset: idx as u64,
            });
        }
    }
    let second = encode_all(&decoded, records_per_segment)?;
    if first != second {
        return Err(CodecError::BadLength { offset: 0 });
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn canonical_roundtrip_patterns() {
        let quotes: Vec<Quote> = (0..300)
            .map(|i| {
                let ts = i64::MIN / 2 + (i as i64) * 10;
                let bid = (i as i64 % 3) - 1;
                Quote::new(ts, bid, bid + 2, (i as i64) % 5)
            })
            .collect();
        for size in [1u32, 32, 64, 65, 200, 300] {
            assert_canonical_roundtrip(&quotes, size).unwrap();
        }
    }

    #[test]
    fn canonical_roundtrip_extremes() {
        let quotes = vec![
            Quote::new(i64::MIN, i64::MIN, i64::MAX, 0),
            Quote::new(i64::MIN + 1, i64::MIN + 7, i64::MAX - 1, i64::MAX),
        ];
        assert_canonical_roundtrip(&quotes, 64).unwrap();
    }
}
