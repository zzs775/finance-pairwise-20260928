#include <optional>
#include <vector>

#include "rebalancer/model.hpp"
#include "rebalancer/oracle.hpp"
#include "rebalancer/solver.hpp"
#include "rebalancer/validation.hpp"
#include "rebalancer/types.hpp"
#include "test_util.hpp"

using namespace rebal;

namespace {

Problem normalize(Problem p) {
    Problem out;
    std::optional<InputError> err = validate_and_normalize(p, out);
    CHECK(!err.has_value());
    return out;
}

} // namespace

void run_solver() {
    // Equal-objective trades: with A=1, B=0, cash=1, price=1, target 50/50,
    // both [0,+1] and [-1,+2] yield 50/50 weights, turnover 1 and fee 0.
    // Canonical tie-break picks [0,+1] (smaller first component).
    Problem symmetric;
    symmetric.initial_cash = 1'000'000;
    symmetric.assets = {
        AssetInput{"AAA", 1'000'000, 1, 1, 5000, 0, kWeightScale, ""},
        AssetInput{"BBB", 1'000'000, 0, 1, 5000, 0, kWeightScale, ""},
    };
    symmetric.fee_tiers = {FeeTier{0, kOpenEnd, 0}};
    symmetric.max_lot_delta = 2;
    symmetric = normalize(std::move(symmetric));
    {
        Rebalancer solver;
        SolveResult result = solver.solve(symmetric);
        CHECK_EQ(result.status, SolveStatus::ProvedOptimal);
        CHECK_EQ(result.trade[0], 0);
        CHECK_EQ(result.trade[1], 1);

        SolveResult oracle_result = enumerate_oracle(symmetric, 2);
        CHECK(oracle_result.trade == result.trade);
    }

    // Infeasible: cashless book; AAA must reach >= 98% but even selling every
    // BBB leaves 95%. BBB is 10 shares with lot 10, so it can be liquidated;
    // instead use 9 shares with lot 10: at most 0 lots can be sold, leaving
    // AAA at 52.6% which violates its 98% minimum.
    Problem impossible;
    impossible.assets = {
        AssetInput{"AAA", 1'000'000, 10, 1, 9500, 9800, kWeightScale, ""},
        AssetInput{"BBB", 1'000'000, 9, 10, 500, 0, kWeightScale, ""},
    };
    impossible.fee_tiers = {FeeTier{0, kOpenEnd, 0}};
    impossible = normalize(std::move(impossible));
    {
        Rebalancer solver;
        SolveResult result = solver.solve(impossible);
        CHECK(!result.has_incumbent);
        CHECK(result.proven);
        CHECK_EQ(result.status, SolveStatus::Infeasible);
    }

    // Budget exhaustion: a tiny budget stops before completion. The returned
    // result must not be claimed proved, must carry bounds and stats, and the
    // node log must report per-node bounds and fates.
    Problem big;
    big.assets = {
        AssetInput{"AAA", 1'000'000, 3, 1, 3000, 0, kWeightScale, ""},
        AssetInput{"BBB", 1'000'000, 3, 1, 3500, 0, kWeightScale, ""},
        AssetInput{"CCC", 2'000'000, 2, 1, 3500, 0, kWeightScale, ""},
    };
    big.initial_cash = 5'000'000;
    big.fee_tiers = {FeeTier{0, kOpenEnd, 5000}};
    big.max_lot_delta = 6;
    big.node_budget = 5;
    big = normalize(std::move(big));
    {
        Rebalancer solver;
        SolveResult limited = solver.solve(big);
        CHECK_EQ(limited.status, SolveStatus::BudgetExhausted);
        CHECK(!limited.proven);
        CHECK(limited.stats.nodes <= big.node_budget);
        CHECK(!limited.node_log.empty());
        for (const NodeReport& report : limited.node_log) {
            CHECK(report.node_index > 0);
            CHECK(!report.fate.empty());
            CHECK(report.lower_bound.weight_deviation >= 0);
            CHECK(report.lower_bound.total_fee >= 0);
            CHECK(report.lower_bound.turnover >= 0);
            CHECK(report.depth >= 0);
            CHECK(static_cast<i64>(report.partial.size()) == report.depth);
        }
        // Reported overall lower bound must not exceed the incumbent.
        if (limited.has_incumbent) {
            CHECK(compare_objective(limited.lower_bound,
                                    limited.incumbent_obj) <= 0);
        }
        bool saw_branched = false;
        bool saw_other_fate = false;
        for (const NodeReport& report : limited.node_log) {
            if (report.fate == "branched") {
                saw_branched = true;
            } else {
                saw_other_fate = true;
            }
        }
        CHECK(saw_branched || saw_other_fate);
        CHECK(!solver.last_proven().has_value());

        // Re-run with an unlimited budget: this certifies and is retained.
        big.node_budget = 0;
        SolveResult full = solver.solve(big);
        CHECK(full.proven);
        CHECK(solver.last_proven().has_value());

        // A later budget run must not overwrite the retained full result.
        big.node_budget = 2;
        SolveResult limited_again = solver.solve(big);
        CHECK_EQ(limited_again.status, SolveStatus::BudgetExhausted);
        CHECK(solver.last_proven().has_value());
        CHECK_EQ(solver.last_proven()->status, SolveStatus::ProvedOptimal);
        CHECK(solver.last_proven()->trade == full.trade);

        // Oracle cross-check of the certified answer.
        SolveResult oracle_result = enumerate_oracle(big, 6);
        // Re-normalize with window to align.
        CHECK(oracle_result.trade == full.trade);
    }

    // Slack output is present and non-negative on the optimum.
    {
        Problem p;
        p.initial_cash = 10'000'000;
        p.assets = {
            AssetInput{"AAA", 1'000'000, 5, 1, 5000, 0, kWeightScale, ""},
            AssetInput{"BBB", 1'000'000, 5, 1, 5000, 0, kWeightScale, ""},
        };
        p.fee_tiers = {FeeTier{0, kOpenEnd, 0}};
        p = normalize(std::move(p));
        Rebalancer solver;
        SolveResult result = solver.solve(p);
        CHECK_EQ(result.status, SolveStatus::ProvedOptimal);
        CHECK(result.slack.cash_slack >= 0);
        CHECK(result.slack.funding_slack >= 0);
        for (const AssetSlack& slack : result.slack.assets) {
            CHECK(slack.min_slack >= 0);
            CHECK(slack.max_slack >= 0);
        }
    }

    rebal_test::finish("solver");
}


