#pragma once

#include "rebalancer/types.hpp"

namespace rebal {

// Exhaustive enumeration oracle for small instances: each asset delta is a
// lot multiple in [-range_lots, +range_lots]. Every combination is checked,
// so the returned optimum is proved. The problem must be normalized.
SolveResult enumerate_oracle(const Problem& problem, i64 range_lots);

} // namespace rebal
