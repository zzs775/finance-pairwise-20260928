#pragma once

#include <atomic>
#include <cstdint>

#include "stats.hpp"
#include "types.hpp"

namespace mcopt {

struct CancelToken {
    std::atomic<bool> cancelled{false};
};

BlockPartial simulateBlock(const Contract& contract, std::uint64_t seed,
                           std::uint64_t firstPath, std::uint64_t pathCount,
                           const CancelToken& token = CancelToken{});

}  // namespace mcopt
