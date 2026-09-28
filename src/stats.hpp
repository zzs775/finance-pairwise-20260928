#pragma once

#include <cstdint>
#include <map>
#include <vector>

namespace mcopt {

inline constexpr std::uint64_t kPathsPerBlock = 1024;

struct BlockPartial {
    double sum = 0.0;
    double cSum = 0.0;
    double sumSq = 0.0;
    double cSq = 0.0;
    std::uint64_t count = 0;
    std::uint64_t barrierHits = 0;
};

struct PriceEstimate {
    double price = 0.0;
    double standardError = 0.0;
    double ciLow = 0.0;
    double ciHigh = 0.0;
    std::uint64_t paths = 0;
    std::uint64_t barrierHits = 0;
};

// Aggregates fixed-size blocks in block-index order so the result is
// independent of thread count, chunk size and completion order.
class BlockAggregator {
public:
    BlockAggregator() = default;

    void submitBlock(std::uint64_t blockIndex, const BlockPartial& partial);
    PriceEstimate finalize(double confidenceLevel) const;

    struct State {
        std::uint64_t nextBlock = 0;
        double sum = 0.0;
        double cSum = 0.0;
        double sumSq = 0.0;
        double cSq = 0.0;
        std::uint64_t paths = 0;
        std::uint64_t barrierHits = 0;
        std::vector<std::pair<std::uint64_t, BlockPartial>> pending;
    };

    explicit BlockAggregator(const State& state);
    State state() const;

private:
    std::uint64_t nextBlock_ = 0;
    double sum_ = 0.0;
    double cSum_ = 0.0;
    double sumSq_ = 0.0;
    double cSq_ = 0.0;
    std::uint64_t paths_ = 0;
    std::uint64_t barrierHits_ = 0;
    std::map<std::uint64_t, BlockPartial> pending_;

    void drainPending();

    static void kahanAdd(double& sum, double& compensation, double value);
};

}  // namespace mcopt
