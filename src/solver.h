#pragma once

#include "types.h"

namespace blockalloc {

AllocationResult solve(const Instance& inst, const SolveOptions& opts = {});

}  // namespace blockalloc
