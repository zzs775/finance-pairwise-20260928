#pragma once

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace rebal {

using i64 = std::int64_t;
using u64 = std::uint64_t;

// Fixed-point scales (all monetary math is done in scaled integers).
inline constexpr i64 kWeightScale = 10000;     // weights in basis points
inline constexpr i64 kFeeRateScale = 1000000; // fee rates: parts per million
inline constexpr i64 kValueScale = 1000000;   // prices / cash: 1e6 per unit
inline constexpr i64 kOpenEnd = INT64_MAX;    // open-ended fee tier boundary

// Overflow-checked integer helpers (multiplication widened to 128 bits).
bool mul_checked(i64 a, i64 b, i64& out) noexcept;
bool add_checked(i64 a, i64 b, i64& out) noexcept;

struct AssetInput {
    std::string id;
    i64 price = 0;         // > 0, scaled by kValueScale
    i64 holding = 0;       // >= 0, integer shares
    i64 lot = 1;           // > 0, trade quantities are multiples of lot
    i64 target_weight = 0; // basis points
    i64 min_weight = 0;    // per-asset post-trade lower bound, bps
    i64 max_weight = kWeightScale;
    std::string group;     // group name; empty string means "no group"
};

struct GroupLimit {
    std::string name;
    i64 min_weight = 0;
    i64 max_weight = kWeightScale;
};

// Marginal fee tier over half-open notional interval [notional_lo, notional_hi).
// notional_hi == kOpenEnd means the tier extends to infinity.
struct FeeTier {
    i64 notional_lo = 0;
    i64 notional_hi = 0;
    i64 rate = 0; // fee per million of notional
};

enum class ErrorKind {
    None,
    EmptyAssets,
    EmptyAssetId,
    DuplicateAsset,
    NegativePrice,
    NonPositiveLot,
    NegativeHolding,
    BadTargetSum,
    BadAssetBounds,
    BadGroupBounds,
    UnknownGroup,
    DuplicateGroup,
    BadFeeTier,
    OverlappingFeeTiers,
    NegativeCash,
    InvalidBudget,
    Overflow,
};

struct InputError {
    ErrorKind kind = ErrorKind::None;
    std::string detail;
};

struct Problem {
    std::vector<AssetInput> assets; // normalized: sorted by id
    i64 initial_cash = 0;           // scaled by kValueScale, >= 0
    std::vector<GroupLimit> groups;
    std::vector<FeeTier> fee_tiers; // normalized: sorted by notional_lo
    i64 node_budget = 0;            // 0 means unlimited
    i64 max_lot_delta = 0;          // > 0: restrict deltas to +/- this many lots
};

// Lexicographic objective: (weight deviation, total fee, turnover).
// Components are never combined into a weighted sum.
struct Objective {
    i64 weight_deviation = 0; // sum |post_weight_bps - target_bps|
    i64 total_fee = 0;
    i64 turnover = 0;         // sum of traded notional, both sides
};

int compare_objective(const Objective& a, const Objective& b);

// Stable tie-breaker: lexicographic order of signed deltas by ascending
// (sorted) asset id. Smaller vector wins.
int compare_canonical(const std::vector<i64>& a, const std::vector<i64>& b);

struct AssetSlack {
    std::string id;
    i64 post_weight = 0;
    i64 min_slack = 0; // post_weight - min_weight
    i64 max_slack = 0; // max_weight - post_weight
};

struct GroupSlack {
    std::string name;
    i64 post_weight = 0;
    i64 min_slack = 0;
    i64 max_slack = 0;
};

struct SlackReport {
    i64 post_cash = 0;
    i64 cash_slack = 0;    // post_cash - 0
    i64 funding_slack = 0; // initial_cash + sells - buys - fees
    std::vector<AssetSlack> assets;
    std::vector<GroupSlack> groups;
};

enum class SolveStatus {
    Infeasible,
    ProvedOptimal,
    BudgetExhausted,
};

struct NodeReport {
    i64 node_index = 0;
    i64 depth = 0;
    std::vector<i64> partial; // decisions in lot units, -1 means undecided
    Objective lower_bound;
    std::string fate; // branched | leaf | pruned-bound | pruned-infeasible | dominated
};

struct SearchStats {
    i64 nodes = 0;
    i64 leaves = 0;
    i64 pruned_bound = 0;
    i64 pruned_infeasible = 0;
    i64 dominated = 0;
    i64 incumbent_updates = 0;
};

struct SolveResult {
    SolveStatus status = SolveStatus::Infeasible;
    bool proven = false;
    bool has_incumbent = false;
    std::vector<i64> trade;            // signed share deltas, multiples of lot
    std::vector<i64> canonical_key;    // signed lot deltas in sorted-asset order
    Objective incumbent_obj{};
    Objective lower_bound{};
    SlackReport slack{};
    SearchStats stats{};
    i64 budget = 0;
    std::vector<NodeReport> node_log;
};

} // namespace rebal
