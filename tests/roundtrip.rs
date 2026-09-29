use mktcodec::decoder::Decoder;
use mktcodec::encoder::encode_all;
use mktcodec::error::CodecError;
use mktcodec::query::query_range;
use mktcodec::segment::Quote;

fn sequential_filter(quotes: &[Quote], start: i64, end: i64) -> Vec<Quote> {
    quotes
        .iter()
        .filter(|quote| quote.ts >= start && quote.ts < end)
        .cloned()
        .collect()
}

#[test]
fn cross_segment_extremes_roundtrip() {
    let quotes = vec![
        Quote::new(i64::MIN, i64::MIN, i64::MIN, 0),
        Quote::new(i64::MIN + 1, -5, -5, i64::MAX),
        Quote::new(i64::MIN + 2, -6, i64::MAX, i64::MAX),
        Quote::new(i64::MIN + 3, -7, i64::MAX - 1, 0),
        Quote::new(i64::MIN + 4, i64::MAX - 1, i64::MAX - 1, i64::MAX),
        Quote::new(i64::MIN + 5, i64::MAX, i64::MAX, 0),
    ];
    for size in [1u32, 2] {
        let bytes = encode_all(&quotes, size).unwrap();
        let decoded = Decoder::new(&bytes).unwrap().decode_all().unwrap();
        assert_eq!(decoded, quotes, "segment size {size}");
    }
}

#[test]
fn high_end_timestamps_roundtrip() {
    let quotes = vec![
        Quote::new(i64::MAX - 2, 1, 2, 0),
        Quote::new(i64::MAX - 1, 1, 2, i64::MAX),
        Quote::new(i64::MAX, 1, 2, 0),
    ];
    let bytes = encode_all(&quotes, 1).unwrap();
    assert_eq!(
        Decoder::new(&bytes).unwrap().decode_all().unwrap(),
        quotes
    );
}

#[test]
fn timestamp_span_beyond_i64_must_overflow() {
    let quotes = vec![
        Quote::new(i64::MIN, 0, 0, 0),
        Quote::new(i64::MAX, 0, 0, 0),
    ];
    let error = encode_all(&quotes, 64).unwrap_err();
    assert!(matches!(error, CodecError::Overflow { .. }), "{error:?}");
}

#[test]
fn repeated_compression_pattern() {
    // Constant gaps and repeated prices compress into a tiny payload.
    let quotes: Vec<Quote> = (0..1000)
        .map(|i| Quote::new(i as i64 * 1000, 100, 100, 7))
        .collect();
    let bytes = encode_all(&quotes, 128).unwrap();
    let decoded = Decoder::new(&bytes).unwrap().decode_all().unwrap();
    assert_eq!(decoded, quotes);

    // Sawtooth prices stress zigzag around zero.
    let saw: Vec<Quote> = (0..1000)
        .map(|i| {
            let p = if i % 2 == 0 { 1000 } else { -1000 };
            Quote::new(i as i64, p, p + 3, i as i64 % 2)
        })
        .collect();
    let bytes = encode_all(&saw, 50).unwrap();
    assert_eq!(
        Decoder::new(&bytes).unwrap().decode_all().unwrap(),
        saw
    );
}

#[test]
fn random_ranges_always_match_sequential_filter() {
    let quotes: Vec<Quote> = (0..500)
        .map(|i| Quote::new(i as i64 * 7 + 3, i as i64 / 3, i as i64 / 3 + 9, i as i64 % 11))
        .collect();

    for seg_size in [1u32, 17, 64, 65, 128, 500] {
        let bytes = encode_all(&quotes, seg_size).unwrap();
        let boundaries = [
            i64::MIN,
            -1,
            0,
            3,
            100,
            1000,
            2000,
            3496,
            3497,
            i64::MAX,
        ];
        for &start in &boundaries {
            for &end in &boundaries {
                if start > end {
                    continue;
                }
                let expected = sequential_filter(&quotes, start, end);
                let actual = query_range(&bytes, start, end).unwrap();
                assert_eq!(
                    actual, expected,
                    "seg={seg_size} range=[{start},{end})"
                );
            }
        }
    }
}

#[test]
fn checkpoint_boundary_query_includes_checkpoint_record() {
    let quotes: Vec<Quote> = (0..200)
        .map(|i| Quote::new(i as i64, i as i64, i as i64 + 1, 0))
        .collect();
    let bytes = encode_all(&quotes, 64).unwrap();
    // Record 64 (0-based) is an in-segment checkpoint; 128 starts a new segment.
    assert_eq!(
        query_range(&bytes, 64, 65).unwrap(),
        vec![Quote::new(64, 64, 65, 0)]
    );
    assert_eq!(
        query_range(&bytes, 127, 130).unwrap(),
        vec![
            Quote::new(127, 127, 128, 0),
            Quote::new(128, 128, 129, 0),
            Quote::new(129, 129, 130, 0),
        ]
    );
}

#[test]
fn deterministic_pseudo_random_stress() {
    // xorshift-based deterministic generator (no external rng dependency).
    let mut state = 0x1234_5678u64;
    let mut next = || {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        state
    };

    let mut quotes = Vec::new();
    let mut ts = -1_000_000i64;
    for _ in 0..2000 {
        ts = ts.saturating_add((next() % 100) as i64 + 1);
        let mid = (next() % 400) as i64 - 200;
        let spread = (next() % 50) as i64;
        quotes.push(Quote::new(ts, mid, mid + spread, (next() % 1000) as i64));
    }

    for seg_size in [33u32, 64, 100, 256] {
        let bytes = encode_all(&quotes, seg_size).unwrap();
        let decoded = Decoder::new(&bytes).unwrap().decode_all().unwrap();
        assert_eq!(decoded.len(), quotes.len());
        assert_eq!(decoded, quotes);

        for _ in 0..40 {
            let a = (next() % 4000) as i64 - 1_000_500;
            let b = a + (next() % 3000) as i64 + 1;
            let expected = sequential_filter(&quotes, a, b);
            assert_eq!(query_range(&bytes, a, b).unwrap(), expected);
        }
    }
}
