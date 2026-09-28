#pragma once

#include "rebalancer/types.hpp"

namespace rebal {

class Rebalancer {
public:
    // Runs the integer branch-and-bound search. A budget-exhausted run never
    // overwrites the last fully proved result.
    SolveResult solve(const Problem& problem);

    const std::optional<SolveResult>& last_proven() const { return last_proven_; }

private:
    std::optional<SolveResult> last_proven_;
};

} // namespace rebal
