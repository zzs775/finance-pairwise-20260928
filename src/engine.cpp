#include "engine.hpp"

namespace mcopt {

BlockPartial simulateBlock(const Contract& contract, std::uint64_t seed,
                           std::uint64_t firstPath, std::uint64_t pathCount,
                           const CancelToken& token) {
    (void)contract;
    (void)seed;
    (void)firstPath;
    (void)pathCount;
    (void)token;
    return {};
}

}  // namespace mcopt
