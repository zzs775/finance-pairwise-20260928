#include <optional>
#include <cstdint>
#include <vector>

#include "rebalancer/model.hpp"
#include "rebalancer/validation.hpp"
#include "rebalancer/types.hpp"
#include "test_util.hpp"

using namespace rebal;

namespace {

Problem normalized(Problem p) {
    Problem out;
    std::optional<InputError> err = validate_and_normalize(p, out);
    CHECK(!err.has_value());
    return out;
}

} // namespace

void run_model() {
    // Balanced book: two 1.0-priced assets, 10 shares each, 0 cash.
    // Total = 20; weights are 5000 bps each. No trade is feasible.
    Problem p;
    p.assets = {
        AssetInput{"AAA", 1'000'000, 10, 1, 5000, 0, kWeightScale, ""},
        AssetInput{"BBB", 1'000'000, 10, 1, 5000, 0, kWeightScale, ""},
    };
    p.fee_tiers = {FeeTier{0, kOpenEnd, 0}};
    p = normalized(std::move(p));

    EvalResult stay = evaluate_trade(p, {0, 0});
    CHECK(stay.feasible);
    CHECK_EQ(stay.objective.weight_deviation, 0);
    CHECK_EQ(stay.slack.assets[0].post_weight, 5000);

    // Selling one AAA and buying one BBB is internally funded.
    EvalResult shift = evaluate_trade(p, {-1, +1});
    CHECK(shift.feasible);
    CHECK(shift.objective.weight_deviation > 0);
    CHECK_EQ(shift.objective.turnover, 2'000'000);

    // Buying without cash violates the non-negative cash constraint.
    EvalResult unpaid = evaluate_trade(p, {0, +1});
    CHECK(!unpaid.feasible);
    CHECK(unpaid.slack.cash_slack < 0);

    // Shorting violates non-negative holdings.
    EvalResult oversell = evaluate_trade(p, {-11, 0});
    CHECK(!oversell.feasible);

    // Cash-tight scenario: price 1, cash 2'020'000, 1% fee.
    Problem tight;
    tight.initial_cash = 2'020'000;
    tight.assets = {
        AssetInput{"AAA", 1'000'000, 0, 1, 5000, 0, kWeightScale, ""},
        AssetInput{"BBB", 1'000'000, 0, 1, 5000, 0, kWeightScale, ""},
    };
    tight.fee_tiers = {FeeTier{0, kOpenEnd, 10'000}};
    tight = normalized(std::move(tight));
    CHECK(evaluate_trade(tight, {1, 1}).feasible);
    CHECK_EQ(evaluate_trade(tight, {1, 1}).slack.post_cash, 0);
    CHECK(!evaluate_trade(tight, {1, 2}).feasible);

    // Lot conflict: trades must be multiples of the lot.
    Problem lots;
    lots.initial_cash = 100'000'000;
    lots.assets = {
        AssetInput{"AAA", 1'000'000, 100, 100, 5000, 0, kWeightScale, ""},
        AssetInput{"BBB", 1'000'000, 100, 100, 5000, 0, kWeightScale, ""},
    };
    lots.fee_tiers = {FeeTier{0, kOpenEnd, 0}};
    lots = normalized(std::move(lots));
    CHECK(evaluate_trade(lots, {-100, +100}).feasible);
    CHECK(!evaluate_trade(lots, {-50, +50}).feasible);

    // Group bounds: g1 may hold at most 25% post trade.
    Problem grouped;
    grouped.assets = {
        AssetInput{"AAA", 1'000'000, 10, 1, 5000, 0, kWeightScale, "g1"},
        AssetInput{"BBB", 1'000'000, 10, 1, 5000, 0, kWeightScale, ""},
    };
    grouped.groups = {GroupLimit{"g1", 0, 2500}};
    grouped.fee_tiers = {FeeTier{0, kOpenEnd, 0}};
    grouped = normalized(std::move(grouped));
    CHECK(!evaluate_trade(grouped, {0, 0}).feasible);
    // Sell 6 AAA / buy 6 BBB: AAA value 4 of total 20 = 2000 bps.
    EvalResult rebalance = evaluate_trade(grouped, {-6, +6});
    CHECK(rebalance.feasible);
    CHECK_EQ(rebalance.slack.groups[0].post_weight, 2000);
    CHECK(rebalance.slack.groups[0].max_slack >= 0);

    // Overflow: a huge price times a large holding must be flagged, not wrap.
    Problem huge;
    huge.initial_cash = 0;
    huge.assets = {
        AssetInput{"AAA", INT64_MAX, 10, 1, 5000, 0, kWeightScale, ""},
        AssetInput{"BBB", 1'000'000, 1, 1, 5000, 0, kWeightScale, ""},
    };
    huge.fee_tiers = {FeeTier{0, kOpenEnd, 0}};
    huge = normalized(std::move(huge));
    EvalResult overflowing = evaluate_trade(huge, {1, 0});
    CHECK(overflowing.overflow || !overflowing.feasible);

    rebal_test::finish("model");
}
