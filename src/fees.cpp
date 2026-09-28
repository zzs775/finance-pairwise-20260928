#include "rebalancer/fees.hpp"

#include <algorithm>

namespace rebal {

bool tiers_overlap(const FeeTier& a, const FeeTier& b) {
    // Half-open intervals [lo, hi) overlap on a positive-length interior.
    i64 left = std::max(a.notional_lo, b.notional_lo);
    i64 right = std::min(a.notional_hi, b.notional_hi);
    return left < right;
}

std::optional<i64> fee_for_notional(i64 notional, const std::vector<FeeTier>& tiers) {
    if (notional < 0) {
        return std::nullopt;
    }
    i64 total = 0;
    for (const FeeTier& tier : tiers) {
        if (tier.notional_lo >= notional) {
            continue;
        }
        i64 covered = std::min(notional, tier.notional_hi) - tier.notional_lo;
        if (covered <= 0) {
            continue;
        }
        i64 scaled = 0;
        if (!mul_checked(covered, tier.rate, scaled)) {
            return std::nullopt;
        }
        i64 part = scaled / kFeeRateScale;
        if (!add_checked(total, part, total)) {
            return std::nullopt;
        }
    }
    return total;
}

} // namespace rebal
