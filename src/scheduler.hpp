#pragma once
#include <cstdint>
#include <functional>
#include <vector>

#include "stats.hpp"

namespace mcopt {

class BlockScheduler {
public:
    BlockScheduler(unsigned threads, std::uint64_t memoryBudgetBytes);

    using BlockFn = std::function<BlockPartial(std::uint64_t blockIndex)>;
    std::vector<std::pair<std::uint64_t, BlockPartial>>
    run(std::uint64_t firstBlock, std::uint64_t blockCount,
        const BlockFn& fn);

private:
    unsigned threads_;
    std::uint64_t memoryBudgetBytes_;
};

}  // namespace mcopt
