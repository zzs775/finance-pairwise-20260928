#pragma once

#include <string>

#include "types.h"

namespace blockalloc {

std::string render_report(const Instance& inst,
                          const AllocationResult& result,
                          const ConflictReport& conflict,
                          const OracleResult& oracle);

}  // namespace blockalloc
