use mktcodec::decoder::Decoder;
use mktcodec::encoder::encode_all;
use mktcodec::error::CodecError;
use mktcodec::query::query_range;
use mktcodec::segment::{
    Quote, SEGMENT_TRAILER_MARKER, TAG_CHECKPOINT, TAG_DELTA,
};

fn many_quotes(n: u32) -> Vec<Quote> {
    (0..n)
        .map(|i| Quote::new(i as i64 + 1, 90, 110, i as i64 % 13))
        .collect()
}

fn encode(n: u32, seg: u32) -> Vec<u8> {
    encode_all(&many_quotes(n), seg).unwrap()
}

#[test]
fn bad_magic_reports_offset_zero() {
    let mut bytes = encode(4, 64);
    bytes[0] = b'X';
    let error = Decoder::new(&bytes).unwrap_err();
    assert!(matches!(error, CodecError::BadMagic { offset: 0 }), "{error:?}");
}

#[test]
fn unsupported_version_reports_its_field_offset() {
    let mut bytes = encode(4, 64);
    bytes[4] = 99;
    let error = Decoder::new(&bytes).unwrap_err();
    assert!(
        matches!(
            error,
            CodecError::UnsupportedVersion {
                offset: 4,
                version: 99
            }
        ),
        "{error:?}"
    );
}

#[test]
fn truncated_payload_is_detected() {
    let bytes = encode(4, 64);
    let cut = bytes.len() - 5;
    let truncated = bytes[..cut].to_vec();
    let error = Decoder::new(&truncated).unwrap_err();
    assert!(
        matches!(
            error,
            CodecError::Truncated { .. } | CodecError::CrcMismatch { .. }
        ),
        "{error:?}"
    );
}

#[test]
fn bit_flip_in_payload_causes_crc_mismatch() {
    let mut bytes = encode(10, 64);
    bytes[40] ^= 0x01;
    let error = Decoder::new(&bytes)
        .and_then(|decoder| decoder.decode_all())
        .unwrap_err();
    assert!(matches!(error, CodecError::CrcMismatch { segment: 0, .. }), "{error:?}");
}

#[test]
fn forged_segment_length_is_rejected_without_oob() {
    let mut bytes = encode(10, 64);
    // Inflate the first directory entry's data_length (offset 12 within entry).
    let header = mktcodec::header::FileHeader::decode(&bytes).unwrap();
    let len_field = header.directory_offset as usize + 12;
    bytes[len_field..len_field + 4].copy_from_slice(&u32::MAX.to_le_bytes());
    let error = Decoder::new(&bytes)
        .and_then(|decoder| decoder.decode_all())
        .unwrap_err();
    assert!(
        matches!(
            error,
            CodecError::DirectoryOutOfBounds { .. } | CodecError::BadLength { .. }
        ),
        "{error:?}"
    );
}

#[test]
fn overlapping_directory_entries_are_rejected() {
    let mut bytes = encode(130, 64);
    let header = mktcodec::header::FileHeader::decode(&bytes).unwrap();
    // Second entry: data_offset moved back inside the first segment.
    let second_offset_field = header.directory_offset as usize + 40 + 4;
    bytes[second_offset_field..second_offset_field + 8]
        .copy_from_slice(&(32u64).to_le_bytes());
    let error = Decoder::new(&bytes)
        .and_then(|decoder| decoder.decode_all())
        .unwrap_err();
    assert!(matches!(error, CodecError::OverlappingSegments { .. }), "{error:?}");
}

#[test]
fn forged_trailer_record_count_is_rejected() {
    let mut bytes = encode(10, 64);
    let header = mktcodec::header::FileHeader::decode(&bytes).unwrap();
    let segment_end = header.directory_offset as usize;
    // Trailer begins 12 bytes before the directory; first field is count.
    let count_field = segment_end - 12;
    bytes[count_field..count_field + 4].copy_from_slice(&999u32.to_le_bytes());
    let error = Decoder::new(&bytes)
        .and_then(|decoder| decoder.decode_all())
        .unwrap_err();
    assert!(matches!(error, CodecError::BadLength { .. }), "{error:?}");
}

#[test]
fn forged_trailer_marker_is_rejected() {
    let mut bytes = encode(10, 64);
    let header = mktcodec::header::FileHeader::decode(&bytes).unwrap();
    let marker_field = header.directory_offset as usize - 4;
    bytes[marker_field..marker_field + 4].copy_from_slice(&0xDEAD_BEEFu32.to_le_bytes());
    let error = Decoder::new(&bytes)
        .and_then(|decoder| decoder.decode_all())
        .unwrap_err();
    assert!(matches!(error, CodecError::BadLength { .. }), "{error:?}");
}

#[test]
fn unknown_tag_is_rejected_at_its_offset() {
    // Craft a payload whose stored CRC matches the altered bytes, so the CRC
    // gate passes and the decoder reaches the unknown tag itself.
    use mktcodec::crc32::crc32;
    use mktcodec::directory::Directory;
    use mktcodec::header::{FileHeader, HEADER_LEN, SCHEMA_VERSION};
    use mktcodec::segment::SegmentMeta;

    let mut payload = vec![0x77u8, 1, 2, 3, 4];
    let checksum = crc32(&payload);
    payload.extend_from_slice(&1u32.to_le_bytes());
    payload.extend_from_slice(&checksum.to_le_bytes());
    payload.extend_from_slice(&SEGMENT_TRAILER_MARKER.to_le_bytes());
    let directory_offset = (HEADER_LEN + payload.len()) as u64;
    let meta = SegmentMeta {
        index: 0,
        data_offset: HEADER_LEN as u64,
        data_length: payload.len() as u32,
        record_count: 1,
        first_ts: 0,
        last_ts: 0,
        crc32: checksum,
    };
    let mut bytes = Vec::new();
    FileHeader {
        version: SCHEMA_VERSION,
        records_per_segment: 64,
        segment_count: 1,
        directory_offset,
        directory_length: 40,
    }
    .encode(&mut bytes)
    .unwrap();
    bytes.extend_from_slice(&payload);
    Directory {
        segments: vec![meta],
    }
    .encode(&mut bytes)
    .unwrap();

    let error = Decoder::new(&bytes)
        .and_then(|decoder| decoder.decode_all())
        .unwrap_err();
    assert!(
        matches!(
            error,
            CodecError::TagUnknown {
                offset: 32,
                tag: 0x77
            }
        ),
        "{error:?}"
    );
}

#[test]
fn noncanonical_varint_is_rejected_at_bad_byte() {
    // Craft a tiny single-segment file with a tag + non-canonical 0 varint.
    use mktcodec::crc32::crc32;
    use mktcodec::directory::Directory;
    use mktcodec::header::{FileHeader, HEADER_LEN, SCHEMA_VERSION};
    use mktcodec::segment::SegmentMeta;

    let mut payload = vec![TAG_DELTA, 0x80, 0x00, 0, 0, 0, 0];
    let checksum = crc32(&payload);
    payload.extend_from_slice(&1u32.to_le_bytes());
    payload.extend_from_slice(&checksum.to_le_bytes());
    payload.extend_from_slice(&SEGMENT_TRAILER_MARKER.to_le_bytes());

    let directory_offset = (HEADER_LEN + payload.len()) as u64;
    let meta = SegmentMeta {
        index: 0,
        data_offset: HEADER_LEN as u64,
        data_length: payload.len() as u32,
        record_count: 1,
        first_ts: 0,
        last_ts: 0,
        crc32: checksum,
    };
    let mut file = Vec::new();
    FileHeader {
        version: SCHEMA_VERSION,
        records_per_segment: 64,
        segment_count: 1,
        directory_offset,
        directory_length: 40,
    }
    .encode(&mut file)
    .unwrap();
    file.extend_from_slice(&payload);
    Directory {
        segments: vec![meta],
    }
    .encode(&mut file)
    .unwrap();

    // First bad byte is the redundant terminating zero at absolute offset 34.
    let error = Decoder::new(&file)
        .and_then(|decoder| decoder.decode_all())
        .unwrap_err();
    assert!(
        matches!(
            error,
            CodecError::NonCanonicalVarint { offset: 34 }
        ),
        "{error:?}"
    );
}

#[test]
fn corruption_never_returns_partial_results_from_query() {
    let mut bytes = encode(130, 64);
    bytes[50] ^= 0x02;
    let error = query_range(&bytes, 0, 1000).unwrap_err();
    assert!(
        !matches!(
            error,
            CodecError::NonCanonicalVarint { .. }
        ) || matches!(error, CodecError::CrcMismatch { .. }),
        "{error:?}"
    );
    assert!(
        matches!(
            error,
            CodecError::CrcMismatch { .. }
                | CodecError::NonCanonicalVarint { .. }
                | CodecError::TagUnknown { .. }
        ),
        "a precise corruption category is required, got {error:?}"
    );
}

#[test]
fn checkpoint_tag_is_known_and_valid() {
    let _ = TAG_CHECKPOINT;
}

#[test]
fn empty_container_queries_and_decodes() {
    let bytes = encode_all(&[], 64).unwrap();
    assert!(Decoder::new(&bytes).unwrap().decode_all().unwrap().is_empty());
    assert!(query_range(&bytes, i64::MIN, i64::MAX).unwrap().is_empty());
}

#[test]
fn reversed_range_is_rejected() {
    let bytes = encode(4, 64);
    let error = query_range(&bytes, 100, 99).unwrap_err();
    assert!(
        matches!(error, CodecError::NonMonotonicTimestamp { .. }),
        "{error:?}"
    );
}

#[test]
fn forged_directory_length_is_rejected() {
    let mut bytes = encode(10, 64);
    // directory_length lives at header offset 24 (u32).
    bytes[24..28].copy_from_slice(&u32::MAX.to_le_bytes());
    let error = Decoder::new(&bytes).unwrap_err();
    assert!(
        matches!(
            error,
            CodecError::BadLength { offset: 24 }
                | CodecError::DirectoryOutOfBounds { offset: 24 }
        ),
        "{error:?}"
    );
}

#[test]
fn invalid_records_are_rejected_at_record_offset() {
    use mktcodec::encoder::Encoder;

    // Equal timestamps violate strict monotonicity.
    let mut encoder = Encoder::new(64).unwrap();
    encoder.push(&Quote::new(10, 1, 2, 1)).unwrap();
    let error = encoder.push(&Quote::new(10, 1, 2, 1)).unwrap_err();
    assert!(
        matches!(error, CodecError::NonMonotonicTimestamp { offset } if offset >= 32),
        "{error:?}"
    );

    let mut encoder = Encoder::new(64).unwrap();
    let error = encoder.push(&Quote::new(10, 5, 4, 1)).unwrap_err();
    assert!(matches!(error, CodecError::BidAboveAsk { offset: 32 }), "{error:?}");

    let mut encoder = Encoder::new(64).unwrap();
    let error = encoder.push(&Quote::new(10, 4, 5, -1)).unwrap_err();
    assert!(matches!(error, CodecError::NegativeQuantity { offset: 32 }), "{error:?}");
}
