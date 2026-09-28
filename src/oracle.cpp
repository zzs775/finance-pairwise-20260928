#include "varbt/oracle.hpp"

#include <algorithm>
#include <stdexcept>

namespace varbt {

namespace {

struct U128 {
    uint64_t lo = 0;
    uint64_t hi = 0;
};

U128 mul64(uint64_t a, uint64_t b) {
    uint64_t a0 = a & 0xFFFFFFFFULL;
    uint64_t a1 = a >> 32;
    uint64_t b0 = b & 0xFFFFFFFFULL;
    uint64_t b1 = b >> 32;
    uint64_t p00 = a0 * b0;
    uint64_t p01 = a0 * b1;
    uint64_t p10 = a1 * b0;
    uint64_t p11 = a1 * b1;
    uint64_t mid = (p00 >> 32) + (p01 & 0xFFFFFFFFULL) +
                   (p10 & 0xFFFFFFFFULL);
    U128 result;
    result.lo = (p00 & 0xFFFFFFFFULL) | (mid << 32);
    result.hi = p11 + (p01 >> 32) + (p10 >> 32) + (mid >> 32);
    return result;
}

// Exact floor(dividend / divisor) for a 128-bit dividend and 64-bit divisor.
// The mathematical quotient is known to fit in 64 bits. Shift-subtract keeps
// the arithmetic exact (no floating point, no approximation).
uint64_t floorDivU128(U128 dividend, uint64_t divisor) {
    uint64_t quotient = 0;
    U128 remainder{0, 0};
    for (int bit = 127; bit >= 0; --bit) {
        remainder.hi = (remainder.hi << 1) | (remainder.lo >> 63);
        remainder.lo <<= 1;
        remainder.lo |= (bit >= 64) ? ((dividend.hi >> (bit - 64)) & 1ULL)
                                    : ((dividend.lo >> bit) & 1ULL);
        quotient <<= 1;
        if (remainder.hi != 0 || remainder.lo >= divisor) {
            // Subtract (0, divisor) from remainder.
            uint64_t borrow = divisor > remainder.lo ? 1ULL : 0ULL;
            remainder.lo -= divisor;
            remainder.hi -= borrow;
            quotient |= 1;
        }
    }
    return quotient;
}

uint64_t ceilDivU128(U128 dividend, uint64_t divisor) {
    uint64_t quotient = floorDivU128(dividend, divisor);
    U128 product = mul64(quotient, divisor);
    if (product.hi != dividend.hi || product.lo != dividend.lo) {
        ++quotient;
    }
    return quotient;
}

// Frozen nearest-rank rule: for window size n and level p/q (0 < p <= q),
// the 1-based rank is r = ceil(p*n/q); the returned value is the r-th
// smallest PnL. Ties participate fully as distinct multiset elements.
size_t nearestRankIndex(int64_t level_num, int64_t level_den, size_t n) {
    uint64_t rank1 = ceilDivU128(
        mul64(static_cast<uint64_t>(level_num), static_cast<uint64_t>(n)),
        static_cast<uint64_t>(level_den));
    if (rank1 < 1) {
        rank1 = 1;
    }
    if (rank1 > n) {
        rank1 = n;
    }
    return static_cast<size_t>(rank1) - 1;
}

} // namespace

std::vector<DayResult> oracleRun(const Params& params,
                                 std::vector<OracleObservation> rows) {
    std::sort(rows.begin(), rows.end(),
              [](const OracleObservation& a, const OracleObservation& b) {
                  return a.day < b.day;
              });

    std::vector<DayResult> results;
    results.reserve(rows.size());
    std::vector<int64_t> window;

    for (const OracleObservation& row : rows) {
        window.push_back(row.pnl);
        if (static_cast<int64_t>(window.size()) > params.window) {
            window.erase(window.begin());
        }

        DayResult result;
        if (static_cast<int64_t>(window.size()) < params.window) {
            result.warmup = true;
            result.quantile = 0;
            result.exception = false;
        } else {
            std::vector<int64_t> sorted = window;
            std::sort(sorted.begin(), sorted.end());
            size_t index = nearestRankIndex(params.level_num,
                                            params.level_den, sorted.size());
            result.quantile = sorted[index];
            result.warmup = false;
            // Exception: realised loss strictly exceeds predicted VaR.
            // VaR is stated as a non-negative loss amount, so loss = -pnl.
            // Equality (-pnl == var) is explicitly not an exception.
            result.exception = row.pnl < 0 && (-row.pnl) > row.var;
        }
        results.push_back(result);
    }
    return results;
}

} // namespace varbt
