//! Error categories with the first offending byte offset.

use std::fmt;

/// All codec failures. Every parse/validation error carries the absolute byte
/// offset (from the start of the file) of the first bad byte when known.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CodecError {
    Io(String),
    BadMagic { offset: u64 },
    UnsupportedVersion { offset: u64, version: u16 },
    Truncated { offset: u64 },
    BadLength { offset: u64 },
    OverlappingSegments { offset: u64 },
    RecordLimitExceeded { offset: u64 },
    NonMonotonicTimestamp { offset: u64 },
    BidAboveAsk { offset: u64 },
    NegativeQuantity { offset: u64 },
    Overflow { offset: u64 },
    VarintTooLong { offset: u64 },
    NonCanonicalVarint { offset: u64 },
    CrcMismatch { offset: u64, segment: u32 },
    BadCheckpoint { offset: u64 },
    TagUnknown { offset: u64, tag: u8 },
    DirectoryOutOfBounds { offset: u64 },
}

impl CodecError {
    /// Absolute byte offset of the first bad byte, if applicable.
    pub fn offset(&self) -> Option<u64> {
        match self {
            CodecError::Io(_) => None,
            CodecError::BadMagic { offset }
            | CodecError::UnsupportedVersion { offset, .. }
            | CodecError::Truncated { offset }
            | CodecError::BadLength { offset }
            | CodecError::OverlappingSegments { offset }
            | CodecError::RecordLimitExceeded { offset }
            | CodecError::NonMonotonicTimestamp { offset }
            | CodecError::BidAboveAsk { offset }
            | CodecError::NegativeQuantity { offset }
            | CodecError::Overflow { offset }
            | CodecError::VarintTooLong { offset }
            | CodecError::NonCanonicalVarint { offset }
            | CodecError::CrcMismatch { offset, .. }
            | CodecError::BadCheckpoint { offset }
            | CodecError::TagUnknown { offset, .. }
            | CodecError::DirectoryOutOfBounds { offset } => Some(*offset),
        }
    }

    /// Stable machine-readable category name.
    pub fn category(&self) -> &'static str {
        match self {
            CodecError::Io(_) => "io",
            CodecError::BadMagic { .. } => "bad_magic",
            CodecError::UnsupportedVersion { .. } => "unsupported_version",
            CodecError::Truncated { .. } => "truncated",
            CodecError::BadLength { .. } => "bad_length",
            CodecError::OverlappingSegments { .. } => "overlapping_segments",
            CodecError::RecordLimitExceeded { .. } => "record_limit_exceeded",
            CodecError::NonMonotonicTimestamp { .. } => "non_monotonic_timestamp",
            CodecError::BidAboveAsk { .. } => "bid_above_ask",
            CodecError::NegativeQuantity { .. } => "negative_quantity",
            CodecError::Overflow { .. } => "overflow",
            CodecError::VarintTooLong { .. } => "varint_too_long",
            CodecError::NonCanonicalVarint { .. } => "non_canonical_varint",
            CodecError::CrcMismatch { .. } => "crc_mismatch",
            CodecError::BadCheckpoint { .. } => "bad_checkpoint",
            CodecError::TagUnknown { .. } => "tag_unknown",
            CodecError::DirectoryOutOfBounds { .. } => "directory_out_of_bounds",
        }
    }
}

impl fmt::Display for CodecError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self.offset() {
            Some(off) => write!(f, "{} at byte offset {}", self.category(), off),
            None => write!(f, "{}", self.category()),
        }
    }
}

impl std::error::Error for CodecError {}

pub type Result<T> = std::result::Result<T, CodecError>;
