#include <optional>
#include <vector>

#include "rebalancer/fees.hpp"
#include "rebalancer/types.hpp"
#include "test_util.hpp"

using namespace rebal;

void run_fees() {
    const std::vector<FeeTier> tiers = {
        FeeTier{0, 10'000'000, 10'000},           // 1% up to 10
        FeeTier{10'000'000, kOpenEnd, 5'000},      // 0.5% above 10
    };

    CHECK_EQ(fee_for_notional(0, tiers), std::optional<i64>(0));
    CHECK_EQ(fee_for_notional(1'000'000, tiers), std::optional<i64>(10'000));
    CHECK_EQ(fee_for_notional(10'000'000, tiers), std::optional<i64>(100'000));
    // 10 at 1% + 10 at 0.5%
    CHECK_EQ(fee_for_notional(20'000'000, tiers),
             std::optional<i64>(100'000 + 50'000));

    // Empty tier list means zero fees.
    CHECK_EQ(fee_for_notional(5'000'000, {}), std::optional<i64>(0));

    // Negative notional is invalid.
    CHECK(!fee_for_notional(-1, tiers));

    // Touching boundaries do not overlap; positive-length interiors do.
    FeeTier a{0, 100, 1};
    FeeTier b{100, 200, 1};
    FeeTier c{99, 200, 1};
    CHECK(!tiers_overlap(a, b));
    CHECK(tiers_overlap(a, c));

    rebal_test::finish("fees");
}
