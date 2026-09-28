#pragma once

#include <optional>
#include <string>

#include "rebalancer/types.hpp"

namespace rebal {

struct ParseResult {
    bool ok = false;
    std::string error;
    Problem problem;
    i64 oracle_window = 0; // > 0: also run the enumeration oracle
};

// Parses the line-oriented problem format documented in the README.
ParseResult parse_problem_text(const std::string& text);

// Stable, machine-friendly rendering of a solve result. Output ordering is
// fixed by sorted asset id and therefore independent of input order.
std::string render_result(const Problem& problem, const SolveResult& result);

} // namespace rebal
