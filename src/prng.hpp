#pragma once

#include <cstdint>

namespace mcopt {

// Deterministic, jump-capable PRNG. A path index maps directly to an
// independent random substream; seeking is O(1).
class JumpRng {
public:
    explicit JumpRng(std::uint64_t seed = 0);

    void seek(std::uint64_t pathIndex);
    double nextUniform();

private:
    std::uint64_t seed_ = 0;
    std::uint64_t path_ = 0;
    std::uint64_t step_ = 0;
    std::uint64_t state0_ = 0;
    std::uint64_t state1_ = 0;
};

}  // namespace mcopt
