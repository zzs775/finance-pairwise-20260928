#include <optional>
#include <algorithm>
#include <vector>

#include "rebalancer/validation.hpp"
#include "rebalancer/types.hpp"
#include "test_util.hpp"

using namespace rebal;

namespace {

Problem base_problem() {
    Problem p;
    p.assets = {
        AssetInput{"AAA", 1'000'000, 10, 1, 5000, 0, kWeightScale, "g1"},
        AssetInput{"BBB", 2'000'000, 5, 1, 5000, 0, kWeightScale, "g1"},
    };
    p.groups = {GroupLimit{"g1", 0, kWeightScale}};
    p.fee_tiers = {FeeTier{0, kOpenEnd, 10'000}};
    p.initial_cash = 1'000'000;
    return p;
}

ErrorKind normalize(const Problem& in) {
    Problem out;
    std::optional<InputError> err = validate_and_normalize(in, out);
    return err ? err->kind : ErrorKind::None;
}

} // namespace

void run_validation() {
    CHECK_EQ(normalize(base_problem()), ErrorKind::None);

    Problem p;
    CHECK_EQ(normalize(p), ErrorKind::EmptyAssets);

    p = base_problem();
    p.assets[0].id.clear();
    CHECK_EQ(normalize(p), ErrorKind::EmptyAssetId);

    p = base_problem();
    p.assets[1].id = p.assets[0].id;
    CHECK_EQ(normalize(p), ErrorKind::DuplicateAsset);

    p = base_problem();
    p.assets[0].price = -5;
    CHECK_EQ(normalize(p), ErrorKind::NegativePrice);
    p.assets[0].price = 0;
    CHECK_EQ(normalize(p), ErrorKind::NegativePrice);

    p = base_problem();
    p.assets[0].target_weight = 4000;
    CHECK_EQ(normalize(p), ErrorKind::BadTargetSum);

    p = base_problem();
    p.assets[0].min_weight = 9000;
    p.assets[0].max_weight = 100;
    CHECK_EQ(normalize(p), ErrorKind::BadAssetBounds);

    p = base_problem();
    p.groups.push_back(GroupLimit{"g1"});
    CHECK_EQ(normalize(p), ErrorKind::DuplicateGroup);

    p = base_problem();
    p.groups[0].name = "ghost";
    CHECK_EQ(normalize(p), ErrorKind::UnknownGroup);

    p = base_problem();
    p.groups.clear();
    CHECK_EQ(normalize(p), ErrorKind::UnknownGroup);

    p = base_problem();
    p.fee_tiers = {FeeTier{0, 5'000'000, 10'000}, FeeTier{4'000'000, kOpenEnd, 1}};
    CHECK_EQ(normalize(p), ErrorKind::OverlappingFeeTiers);

    p = base_problem();
    p.fee_tiers = {FeeTier{0, 5'000'000, 10'000}};
    CHECK_EQ(normalize(p), ErrorKind::BadFeeTier);

    p = base_problem();
    p.fee_tiers = {FeeTier{1'000'000, kOpenEnd, 10'000}};
    CHECK_EQ(normalize(p), ErrorKind::BadFeeTier);

    p = base_problem();
    p.initial_cash = -1;
    CHECK_EQ(normalize(p), ErrorKind::NegativeCash);

    p = base_problem();
    p.node_budget = -3;
    CHECK_EQ(normalize(p), ErrorKind::InvalidBudget);

    // Normalization sorts assets by id but keeps group membership.
    Problem q = base_problem();
    std::reverse(q.assets.begin(), q.assets.end());
    Problem normalized;
    CHECK(!validate_and_normalize(q, normalized));
    CHECK_EQ(normalized.assets[0].id, std::string("AAA"));
    CHECK_EQ(normalized.assets[1].id, std::string("BBB"));

    rebal_test::finish("validation");
}
