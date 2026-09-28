#include "stats.hpp"

#include <algorithm>
#include <cmath>

#include "gaussian.hpp"

namespace mcopt {

void BlockAggregator::kahanAdd(double& sum, double& compensation,
                               double value) {
    const double adjusted = value - compensation;
    const double updated = sum + adjusted;
    compensation = (updated - sum) - adjusted;
    sum = updated;
}

void BlockAggregator::drainPending() {
    for (auto it = pending_.find(nextBlock_); it != pending_.end();
         it = pending_.find(nextBlock_)) {
        const BlockPartial& partial = it->second;
        kahanAdd(sum_, cSum_, partial.sum);
        kahanAdd(sumSq_, cSq_, partial.sumSq);
        paths_ += partial.count;
        barrierHits_ += partial.barrierHits;
        ++nextBlock_;
        pending_.erase(it);
    }
}

BlockAggregator::BlockAggregator(const State& state)
    : nextBlock_(state.nextBlock),
      sum_(state.sum),
      cSum_(state.cSum),
      sumSq_(state.sumSq),
      cSq_(state.cSq),
      paths_(state.paths),
      barrierHits_(state.barrierHits) {
    for (const auto& entry : state.pending) {
        pending_.emplace(entry.first, entry.second);
    }
    drainPending();
}

BlockAggregator::State BlockAggregator::state() const {
    State state;
    state.nextBlock = nextBlock_;
    state.sum = sum_;
    state.cSum = cSum_;
    state.sumSq = sumSq_;
    state.cSq = cSq_;
    state.paths = paths_;
    state.barrierHits = barrierHits_;
    state.pending.assign(pending_.begin(), pending_.end());
    return state;
}

void BlockAggregator::submitBlock(std::uint64_t blockIndex,
                                  const BlockPartial& partial) {
    if (partial.count == 0) {
        return;
    }
    if (blockIndex < nextBlock_) {
        return;
    }
    pending_.insert_or_assign(blockIndex, partial);
    drainPending();
}

PriceEstimate BlockAggregator::finalize(double confidenceLevel) const {
    PriceEstimate estimate;
    estimate.paths = paths_;
    estimate.barrierHits = barrierHits_;
    if (paths_ == 0) {
        return estimate;
    }
    const double n = static_cast<double>(paths_);
    estimate.price = sum_ / n;
    double variance = sumSq_ / n - estimate.price * estimate.price;
    if (!(variance > 0.0)) {
        variance = 0.0;
    }
    if (paths_ > 1) {
        estimate.standardError =
            std::sqrt(variance * (n / (n - 1.0)) / n);
    }
    const double tailProbability = 0.5 + 0.5 * confidenceLevel;
    const double z = inverseNormalCdf(tailProbability);
    estimate.ciLow = estimate.price - z * estimate.standardError;
    estimate.ciHigh = estimate.price + z * estimate.standardError;
    return estimate;
}

}  // namespace mcopt
