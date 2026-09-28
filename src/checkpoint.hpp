#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "stats.hpp"
#include "types.hpp"

namespace mcopt {

struct CheckpointData {
    std::string summary;
    std::uint64_t seed = 0;
    std::uint64_t nextPath = 0;
    std::uint64_t totalPaths = 0;
    double sum = 0.0;
    double cSum = 0.0;
    double sumSq = 0.0;
    double cSq = 0.0;
    std::uint64_t barrierHits = 0;
    std::vector<std::pair<std::uint64_t, BlockPartial>> pending;
};

void saveCheckpoint(const std::string& path, const CheckpointData& data);
CheckpointData loadCheckpoint(const std::string& path);

}  // namespace mcopt
