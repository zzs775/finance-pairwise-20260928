#pragma once

#include <algorithm>
#include <numeric>
#include <vector>

#include "types.h"

namespace blockalloc {

inline qty_t ceil_align(qty_t v, qty_t m) {
    if (v <= 0) return 0;
    return ((v + m - 1) / m) * m;
}

inline qty_t floor_align(qty_t v, qty_t m) {
    if (v < 0) return 0;
    return (v / m) * m;
}

// Canonical account order: ascending account id. Every algorithm traverses
// accounts in this order so results are byte-for-byte independent of the input
// ordering. The returned indices map canonical position -> original index.
inline std::vector<size_t> canonical_order(const Instance& inst) {
    std::vector<size_t> idx(inst.accounts.size());
    std::iota(idx.begin(), idx.end(), size_t{0});
    std::sort(idx.begin(), idx.end(), [&](size_t a, size_t b) {
        if (inst.accounts[a].id != inst.accounts[b].id)
            return inst.accounts[a].id < inst.accounts[b].id;
        return a < b;
    });
    return idx;
}

// Common denominator for proportional deviations. Fractional deviation of
// account i is |q_i/T - target_i/F|; its integer numerator is
// |q_i*F - target_i*T|. F is the sum of target quantities of accounts that
// participate in the allocation (eligible accounts); ineligible accounts are
// fixed at 0 and excluded. Falls back to 1 so the arithmetic stays valid.
inline qty_t target_total(const Instance& inst) {
    qty_t sum = 0;
    for (const Account& a : inst.accounts)
        if (a.eligible) sum += a.target;
    return sum > 0 ? sum : 1;
}

}  // namespace blockalloc
