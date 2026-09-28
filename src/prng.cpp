#include "prng.hpp"

namespace mcopt {

namespace {

// SplitMix64 (Staffa/Vigna): bijective 64->64 mixing function.
std::uint64_t splitMix64(std::uint64_t& state) {
    std::uint64_t z = (state += 0x9E3779B97F4A7C15ULL);
    z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
    z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
    return z ^ (z >> 31);
}

}  // namespace

JumpRng::JumpRng(std::uint64_t seed) : seed_(seed) {}

void JumpRng::seek(std::uint64_t pathIndex) {
    // Each (seed, path) pair is hashed to an independent counter start.
    // Seeking is O(1): no draws from earlier paths are consumed.
    std::uint64_t mixer = seed_;
    splitMix64(mixer);
    mixer ^= pathIndex;
    splitMix64(mixer);
    path_ = pathIndex;
    step_ = 0;
    state0_ = mixer;
    state1_ = splitMix64(mixer);
}

double JumpRng::nextUniform() {
    std::uint64_t bits;
    if ((step_ & 1ULL) == 0) {
        bits = splitMix64(state0_);
    } else {
        bits = splitMix64(state1_);
    }
    ++step_;
    // 53-bit mantissa uniform in [0, 1).
    return static_cast<double>(bits >> 11) * (1.0 / 9007199254740992.0);
}

}  // namespace mcopt
