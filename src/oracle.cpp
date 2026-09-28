#include "rebalancer/oracle.hpp"

#include "rebalancer/model.hpp"

namespace rebal {

namespace {

// Full lexicographic ordering including the canonical stable tie-breaker.
// Returns true if candidate is strictly better than incumbent.
bool better(const std::vector<i64>& candidate_trade,
            const std::vector<i64>& candidate_lots,
            const Objective& candidate_obj,
            const std::vector<i64>& best_trade,
            const std::vector<i64>& best_lots,
            const Objective& best_obj) {
    int cmp = compare_objective(candidate_obj, best_obj);
    if (cmp != 0) {
        return cmp < 0;
    }
    cmp = compare_canonical(candidate_lots, best_lots);
    if (cmp != 0) {
        return cmp < 0;
    }
    return compare_canonical(candidate_trade, best_trade) < 0;
}

} // namespace

SolveResult enumerate_oracle(const Problem& problem, i64 range_lots) {
    SolveResult result;
    result.status = SolveStatus::Infeasible;
    result.proven = true;
    result.budget = range_lots;

    const std::size_t n = problem.assets.size();
    if (n == 0 || range_lots < 0) {
        return result;
    }

    std::vector<i64> lot_deltas(n, -range_lots);
    std::vector<i64> trade(n, 0);

    // Enumerate in mixed-radix order; digit order is asset order (ids are
    // sorted ascending), so iteration order is itself the canonical order and
    // the first minimum encountered is the stable tie-break winner.
    while (true) {
        for (std::size_t i = 0; i < n; ++i) {
            trade[i] = lot_deltas[i] * problem.assets[i].lot;
        }
        EvalResult eval = evaluate_trade(problem, trade);
        ++result.stats.nodes;
        ++result.stats.leaves;
        if (eval.overflow) {
            // Overflowing combinations are out of the representable domain.
        } else if (eval.feasible) {
            if (!result.has_incumbent ||
                better(trade, lot_deltas, eval.objective, result.trade,
                       result.canonical_key, result.incumbent_obj)) {
                result.has_incumbent = true;
                result.trade = trade;
                result.canonical_key = lot_deltas;
                result.incumbent_obj = eval.objective;
                result.lower_bound = eval.objective;
                result.slack = eval.slack;
                ++result.stats.incumbent_updates;
            }
        }

        std::size_t digit = 0;
        while (digit < n) {
            if (lot_deltas[digit] < range_lots) {
                ++lot_deltas[digit];
                break;
            }
            lot_deltas[digit] = -range_lots;
            ++digit;
        }
        if (digit == n) {
            break;
        }
    }

    if (result.has_incumbent) {
        result.status = SolveStatus::ProvedOptimal;
    }
    return result;
}

} // namespace rebal
