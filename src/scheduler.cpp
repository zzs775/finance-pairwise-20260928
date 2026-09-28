#include "scheduler.hpp"

namespace mcopt {

BlockScheduler::BlockScheduler(unsigned threads,
                               std::uint64_t memoryBudgetBytes)
    : threads_(threads), memoryBudgetBytes_(memoryBudgetBytes) {}

std::vector<std::pair<std::uint64_t, BlockPartial>>
BlockScheduler::run(std::uint64_t firstBlock, std::uint64_t blockCount,
                    const BlockFn& fn) {
    (void)firstBlock;
    (void)blockCount;
    (void)fn;
    return {};
}

}  // namespace mcopt
