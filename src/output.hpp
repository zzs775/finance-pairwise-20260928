#pragma once

#include <string>

#include "config.hpp"
#include "stats.hpp"

namespace mcopt {

std::string renderCanonical(const SimConfig& config,
                            const PriceEstimate& estimate);
void writeOutputAtomic(const std::string& path, const std::string& text);

}  // namespace mcopt
