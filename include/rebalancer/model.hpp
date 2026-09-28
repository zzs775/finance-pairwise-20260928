#pragma once

#include "rebalancer/types.hpp"

namespace rebal {

struct EvalResult {
    bool feasible = false;
    bool overflow = false;
    Objective objective;
    SlackReport slack;
};

// Evaluates a signed trade vector (share deltas, multiples of lot) against
// every post-trade constraint and computes the objective and slacks.
EvalResult evaluate_trade(const Problem& problem, const std::vector<i64>& trade);

} // namespace rebal
