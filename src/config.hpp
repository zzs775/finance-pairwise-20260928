#pragma once

#include <cstdint>
#include <string>

#include "types.hpp"

namespace mcopt {

struct SimConfig {
    Contract contract;
    std::uint64_t seed = 0;
    std::uint64_t paths = 0;
    unsigned threads = 1;
    std::uint64_t chunkPaths = 0;
    std::uint64_t memoryBudgetBytes = 0;
    double confidenceLevel = 0.95;
    std::string checkpointPath;
    std::string outputPath;
};

SimConfig loadConfig(const std::string& path);

}  // namespace mcopt
