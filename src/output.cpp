#include "output.hpp"

namespace mcopt {

std::string renderCanonical(const SimConfig& config,
                            const PriceEstimate& estimate) {
    (void)config;
    (void)estimate;
    return {};
}

void writeOutputAtomic(const std::string& path, const std::string& text) {
    (void)path;
    (void)text;
}

}  // namespace mcopt
