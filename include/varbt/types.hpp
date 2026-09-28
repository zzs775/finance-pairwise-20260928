#pragma once

#include <cstdint>
#include <string>

namespace varbt {

// Fractional digits used for the fixed-point representation. All VaR / PnL
// values are stored as int64_t minor units (value * 10^kFracDigits).
inline constexpr int kFracDigits = 6;
inline constexpr int64_t kScale = 1'000'000;

enum class ErrorCode {
    Ok = 0,
    DuplicateKey,
    DateHole,
    SignConvention,
    IllegalWindow,
    Overflow,
    BadSeq,
    CorrectionNotFound,
    CorruptCheckpoint,
    CheckpointVersionMismatch,
    IoError,
    InvalidNumeric,
    InvalidCommand,
};

inline const char* errorName(ErrorCode code) {
    switch (code) {
    case ErrorCode::Ok: return "OK";
    case ErrorCode::DuplicateKey: return "DUPLICATE_KEY";
    case ErrorCode::DateHole: return "DATE_HOLE";
    case ErrorCode::SignConvention: return "SIGN_CONVENTION";
    case ErrorCode::IllegalWindow: return "ILLEGAL_WINDOW";
    case ErrorCode::Overflow: return "OVERFLOW";
    case ErrorCode::BadSeq: return "BAD_SEQ";
    case ErrorCode::CorrectionNotFound: return "CORRECTION_NOT_FOUND";
    case ErrorCode::CorruptCheckpoint: return "CORRUPT_CHECKPOINT";
    case ErrorCode::CheckpointVersionMismatch: return "CHECKPOINT_VERSION_MISMATCH";
    case ErrorCode::IoError: return "IO_ERROR";
    case ErrorCode::InvalidNumeric: return "INVALID_NUMERIC";
    case ErrorCode::InvalidCommand: return "INVALID_COMMAND";
    }
    return "UNKNOWN";
}

struct Params {
    int64_t window = 0;      // trailing window length in business days, >= 1
    int64_t level_num = 0;   // quantile level numerator
    int64_t level_den = 0;   // quantile level denominator; 0 < num < den
};

struct Observation {
    int64_t day = 0;        // business day index (contiguous within a version)
    std::string version;    // model version identifier
    int64_t var = 0;        // predicted VaR, non-negative, fixed point
    int64_t pnl = 0;        // realised PnL, signed, fixed point
    uint64_t seq = 0;       // strict reception sequence number
};

struct DayResult {
    int64_t quantile = 0;   // frozen nearest-rank quantile of windowed PnL
    bool exception = false; // loss strictly exceeds VaR
    bool warmup = false;    // fewer than `window` observations available
};

} // namespace varbt
