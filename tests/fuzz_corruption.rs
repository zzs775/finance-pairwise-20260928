use mktcodec::decoder::Decoder;
use mktcodec::encoder::encode_all;
use mktcodec::query::query_range;
use mktcodec::segment::Quote;

fn build() -> Vec<u8> {
    let quotes: Vec<Quote> = (0..200)
        .enumerate()
        .map(|(i, _)| Quote::new(i as i64 * 3 + 7, i as i64 / 4, i as i64 / 4 + 5, i as i64 % 9))
        .collect();
    encode_all(&quotes, 37).unwrap()
}

#[test]
fn single_byte_flips_never_panic_and_never_silently_accept() {
    let original = build();
    let baseline = Decoder::new(&original).unwrap().decode_all().unwrap();
    assert_eq!(baseline.len(), 200);

    let mut accepted_but_changed = 0usize;
    for index in 0..original.len() {
        for bit in 0u32..8 {
            let mut damaged = original.clone();
            damaged[index] ^= 1 << bit;
            match Decoder::new(&damaged).and_then(|decoder| decoder.decode_all()) {
                Ok(decoded) => {
                    // Acceptable only if the mutation hit a truly ignored
                    // reserved byte and decoding is unchanged.
                    if decoded != baseline {
                        accepted_but_changed += 1;
                    }
                }
                Err(_) => {}
            }
            // Queries must never panic either.
            let _ = query_range(&damaged, 0, 10_000);
        }
    }
    assert_eq!(accepted_but_changed, 0, "bit flips changed decoded data undetected");
}

#[test]
fn every_truncation_prefix_is_safe() {
    let original = build();
    for cut in 0..original.len() {
        let prefix = &original[..cut];
        let _ = Decoder::new(prefix).and_then(|decoder| decoder.decode_all());
        let _ = query_range(prefix, 0, 10_000);
    }
}

#[test]
fn random_extra_trailing_and_length_mutations_are_safe() {
    let original = build();
    let mut state = 0x9E37_79B9_7F4A_7C15u64;
    let mut next = || {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        state
    };

    for _ in 0..2000 {
        let mut damaged = original.clone();
        match next() % 4 {
            0 => {
                let idx = (next() as usize) % damaged.len();
                damaged[idx] = (next() & 0xFF) as u8;
            }
            1 => {
                let idx = (next() as usize) % damaged.len().max(1);
                let n = 1 + (next() % 8) as usize;
                let end = (idx + n).min(damaged.len());
                damaged.truncate(end);
            }
            2 => {
                let count = (next() % 16) as usize;
                damaged.extend((0..count).map(|_| (next() & 0xFF) as u8));
            }
            _ => {
                // Corrupt a length-ish u32 field in the directory.
                let header = mktcodec::header::FileHeader::decode(&damaged).unwrap();
                if header.directory_length >= 4 {
                    let idx = header.directory_offset as usize
                        + (next() % header.directory_length as u64) as usize;
                    damaged[idx] ^= 0xFF;
                }
            }
        }
        let _ = Decoder::new(&damaged).and_then(|decoder| decoder.decode_all());
        let _ = query_range(&damaged, -100, 100);
    }
}
