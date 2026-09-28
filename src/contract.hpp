#pragma once

#include <string>

#include "types.hpp"

namespace mcopt {

void validateContract(const Contract& contract);
std::string contractSummary(const Contract& contract);

}  // namespace mcopt
