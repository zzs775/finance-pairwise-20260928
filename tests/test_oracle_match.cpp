#include <optional>
#include <algorithm>
#include <string>
#include <vector>

#include "rebalancer/model.hpp"
#include "rebalancer/oracle.hpp"
#include "rebalancer/solver.hpp"
#include "rebalancer/validation.hpp"
#include "rebalancer/types.hpp"
#include "test_util.hpp"

using namespace rebal;

namespace {

struct Lcg {
    std::uint64_t state = 88172645463325252ULL;
    std::uint64_t next() {
        state ^= state << 13;
        state ^= state >> 7;
        state ^= state << 17;
        return state;
    }
    i64 range(i64 lo, i64 hi) {
        return lo + static_cast<i64>(next() % static_cast<std::uint64_t>(hi - lo + 1));
    }
};

Problem random_problem(Lcg& lcg, int n) {
    Problem p;
    p.initial_cash = lcg.range(0, 10) * 1'000'000;
    i64 remaining = kWeightScale;
    for (int i = 0; i < n; ++i) {
        i64 target = 0;
        if (i + 1 < n) {
            target = lcg.range(0, remaining);
            remaining -= target;
        } else {
            target = remaining;
        }
        AssetInput asset;
        asset.id = std::string(1, static_cast<char>('A' + i));
        asset.price = lcg.range(1, 3) * 500'000;
        asset.lot = lcg.range(1, 3);
        asset.holding = asset.lot * lcg.range(0, 5);
        asset.target_weight = target;
        p.assets.push_back(asset);
    }
    if (lcg.range(0, 1) == 0) {
        p.fee_tiers = {FeeTier{0, kOpenEnd, lcg.range(0, 20) * 1000}};
    } else {
        i64 boundary = lcg.range(2, 8) * 1'000'000;
        p.fee_tiers = {
            FeeTier{0, boundary, lcg.range(5, 30) * 1000},
            FeeTier{boundary, kOpenEnd, lcg.range(0, 10) * 1000},
        };
    }
    Problem normalized_problem;
    std::optional<InputError> err = validate_and_normalize(p, normalized_problem);
    CHECK(!err.has_value());
    return normalized_problem;
}

} // namespace

void run_oracle_match() {
    Lcg lcg;
    for (int iter = 0; iter < 120; ++iter) {
        int n = static_cast<int>(lcg.range(2, 4));
        i64 window = lcg.range(1, 3);
        Problem p = random_problem(lcg, n);
        p.max_lot_delta = window;

        SolveResult oracle_result = enumerate_oracle(p, window);
        Rebalancer solver;
        SolveResult solver_result = solver.solve(p);

        CHECK_EQ(oracle_result.has_incumbent, solver_result.has_incumbent);
        CHECK(solver_result.proven);
        if (oracle_result.has_incumbent) {
            CHECK_EQ(solver_result.status, SolveStatus::ProvedOptimal);
            CHECK_EQ(solver_result.incumbent_obj.weight_deviation,
                     oracle_result.incumbent_obj.weight_deviation);
            CHECK_EQ(solver_result.incumbent_obj.total_fee,
                     oracle_result.incumbent_obj.total_fee);
            CHECK_EQ(solver_result.incumbent_obj.turnover,
                     oracle_result.incumbent_obj.turnover);
            CHECK(solver_result.trade == oracle_result.trade);
            // Independently re-evaluate the reported optimum.
            EvalResult eval = evaluate_trade(p, solver_result.trade);
            CHECK(eval.feasible);
            CHECK_EQ(eval.objective.weight_deviation,
                     solver_result.incumbent_obj.weight_deviation);
            CHECK_EQ(eval.objective.total_fee, solver_result.incumbent_obj.total_fee);
            CHECK_EQ(eval.objective.turnover, solver_result.incumbent_obj.turnover);
        } else {
            CHECK_EQ(solver_result.status, SolveStatus::Infeasible);
        }

        // Input permutation must not change the answer keyed by asset id.
        Problem reordered = p;
        std::reverse(reordered.assets.begin(), reordered.assets.end());
        Problem renorm;
        CHECK(!validate_and_normalize(reordered, renorm));
        renorm.max_lot_delta = window;
        SolveResult oracle2 = enumerate_oracle(renorm, window);
        Rebalancer solver2;
        SolveResult solver2_result = solver2.solve(renorm);
        for (std::size_t i = 0; i < p.assets.size(); ++i) {
            const std::string& id = p.assets[i].id;
            std::size_t j = 0;
            while (renorm.assets[j].id != id) {
                ++j;
            }
            CHECK_EQ(solver2_result.trade[j], solver_result.trade[i]);
            CHECK_EQ(oracle2.trade[j], oracle_result.trade[i]);
        }
    }

    // A handful of slightly larger windows with five assets.
    for (int iter = 0; iter < 8; ++iter) {
        Problem p = random_problem(lcg, 5);
        p.max_lot_delta = 2;
        SolveResult oracle_result = enumerate_oracle(p, 2);
        Rebalancer solver;
        SolveResult solver_result = solver.solve(p);
        CHECK_EQ(oracle_result.has_incumbent, solver_result.has_incumbent);
        if (oracle_result.has_incumbent) {
            CHECK(solver_result.trade == oracle_result.trade);
        }
    }

    rebal_test::finish("oracle_match");
}
