#include "rebalancer/validation.hpp"

#include <algorithm>
#include <set>

#include "rebalancer/fees.hpp"

namespace rebal {

namespace {

InputError fail(ErrorKind kind, std::string detail) {
    return InputError{kind, std::move(detail)};
}

} // namespace

std::optional<InputError> validate_and_normalize(const Problem& in, Problem& out) {
    out = Problem{};

    if (in.assets.empty()) {
        return fail(ErrorKind::EmptyAssets, "asset list must not be empty");
    }

    std::vector<AssetInput> assets = in.assets;
    std::sort(assets.begin(), assets.end(),
              [](const AssetInput& a, const AssetInput& b) { return a.id < b.id; });

    std::set<std::string> seen;
    i64 target_sum = 0;
    for (const AssetInput& asset : assets) {
        if (asset.id.empty()) {
            return fail(ErrorKind::EmptyAssetId, "asset id must not be empty");
        }
        if (!seen.insert(asset.id).second) {
            return fail(ErrorKind::DuplicateAsset, "duplicate asset id: " + asset.id);
        }
        if (asset.price < 0) {
            return fail(ErrorKind::NegativePrice, "negative price for: " + asset.id);
        }
        if (asset.price == 0) {
            return fail(ErrorKind::NegativePrice, "price must be positive for: " + asset.id);
        }
        if (asset.lot <= 0) {
            return fail(ErrorKind::NonPositiveLot, "lot must be positive for: " + asset.id);
        }
        if (asset.holding < 0) {
            return fail(ErrorKind::NegativeHolding, "negative holding for: " + asset.id);
        }
        if (asset.target_weight < 0 || asset.target_weight > kWeightScale) {
            return fail(ErrorKind::BadTargetSum,
                        "target weight out of range for: " + asset.id);
        }
        if (!add_checked(target_sum, asset.target_weight, target_sum)) {
            return fail(ErrorKind::Overflow, "target weight sum overflow");
        }
        if (asset.min_weight < 0 || asset.max_weight > kWeightScale ||
            asset.min_weight > asset.max_weight) {
            return fail(ErrorKind::BadAssetBounds,
                        "bad per-asset weight bounds for: " + asset.id);
        }
    }
    if (target_sum != kWeightScale) {
        return fail(ErrorKind::BadTargetSum,
                    "target weights must sum to 10000 bps, got " +
                        std::to_string(target_sum));
    }

    std::set<std::string> group_names;
    for (const AssetInput& asset : assets) {
        if (!asset.group.empty()) {
            group_names.insert(asset.group);
        }
    }

    std::vector<GroupLimit> groups = in.groups;
    std::sort(groups.begin(), groups.end(),
              [](const GroupLimit& a, const GroupLimit& b) { return a.name < b.name; });
    std::set<std::string> group_seen;
    for (const GroupLimit& group : groups) {
        if (!group_seen.insert(group.name).second) {
            return fail(ErrorKind::DuplicateGroup, "duplicate group: " + group.name);
        }
        if (!group_names.count(group.name)) {
            return fail(ErrorKind::UnknownGroup,
                        "group declared but not referenced: " + group.name);
        }
        if (group.min_weight < 0 || group.max_weight > kWeightScale ||
            group.min_weight > group.max_weight) {
            return fail(ErrorKind::BadGroupBounds, "bad bounds for group: " + group.name);
        }
    }
    for (const std::string& name : group_names) {
        if (!group_seen.count(name)) {
            return fail(ErrorKind::UnknownGroup,
                        "asset references group without limits: " + name);
        }
    }

    std::vector<FeeTier> tiers = in.fee_tiers;
    std::sort(tiers.begin(), tiers.end(), [](const FeeTier& a, const FeeTier& b) {
        return a.notional_lo < b.notional_lo;
    });
    i64 expected_lo = 0;
    for (std::size_t i = 0; i < tiers.size(); ++i) {
        const FeeTier& tier = tiers[i];
        if (tier.notional_lo < 0) {
            return fail(ErrorKind::BadFeeTier, "negative fee tier lower bound");
        }
        if (tier.notional_hi <= tier.notional_lo && tier.notional_hi != kOpenEnd) {
            return fail(ErrorKind::BadFeeTier, "empty or inverted fee tier interval");
        }
        if (tier.rate < 0) {
            return fail(ErrorKind::BadFeeTier, "negative fee rate");
        }
        if (tier.notional_lo != expected_lo) {
            return fail(ErrorKind::BadFeeTier,
                        "fee tiers must partition [0, +inf) without gaps; gap near " +
                            std::to_string(expected_lo));
        }
        if (i + 1 < tiers.size()) {
            if (tiers_overlap(tier, tiers[i + 1])) {
                return fail(ErrorKind::OverlappingFeeTiers, "overlapping fee tiers");
            }
            expected_lo = tier.notional_hi;
        } else if (tier.notional_hi != kOpenEnd) {
            return fail(ErrorKind::BadFeeTier, "last fee tier must be open-ended");
        }
    }

    if (in.initial_cash < 0) {
        return fail(ErrorKind::NegativeCash, "initial cash must not be negative");
    }
    if (in.node_budget < 0) {
        return fail(ErrorKind::InvalidBudget, "node budget must not be negative");
    }
    if (in.max_lot_delta < 0) {
        return fail(ErrorKind::InvalidBudget, "max_lot_delta must not be negative");
    }

    out.assets = std::move(assets);
    out.groups = std::move(groups);
    out.fee_tiers = std::move(tiers);
    out.initial_cash = in.initial_cash;
    out.node_budget = in.node_budget;
    out.max_lot_delta = in.max_lot_delta;
    return std::nullopt;
}

} // namespace rebal
