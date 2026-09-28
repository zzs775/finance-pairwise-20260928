#include "checkpoint.hpp"

namespace mcopt {

void saveCheckpoint(const std::string& path, const CheckpointData& data) {
    (void)path;
    (void)data;
}

CheckpointData loadCheckpoint(const std::string& path) {
    (void)path;
    return {};
}

}  // namespace mcopt
