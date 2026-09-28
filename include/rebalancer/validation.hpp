#pragma once

#include <optional>

#include "rebalancer/types.hpp"

namespace rebal {

// Validates raw input and writes a normalized copy (assets sorted by id,
// fee tiers sorted by lower bound) into `out`. Returns the first error.
std::optional<InputError> validate_and_normalize(const Problem& in, Problem& out);

} // namespace rebal
