#pragma once

#include <optional>

#include "rebalancer/types.hpp"

namespace rebal {

// True if two half-open fee intervals overlap on a positive-length interior.
bool tiers_overlap(const FeeTier& a, const FeeTier& b);

// Marginal tiered fee for a non-negative notional. Returns nullopt on
// malformed tiers or overflow.
std::optional<i64> fee_for_notional(i64 notional, const std::vector<FeeTier>& tiers);

} // namespace rebal
