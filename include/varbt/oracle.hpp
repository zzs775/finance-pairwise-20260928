#pragma once

#include <string>
#include <vector>

#include "varbt/types.hpp"

namespace varbt {

// Full-sort reference implementation. Given every stored observation of one
// version (already validated), recomputes every window from scratch.
struct OracleObservation {
    int64_t day;
    int64_t var;
    int64_t pnl;
};

std::vector<DayResult> oracleRun(const Params& params,
                                 std::vector<OracleObservation> rows);

} // namespace varbt
