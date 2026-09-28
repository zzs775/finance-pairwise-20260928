#include "rebalancer/types.hpp"

#include <algorithm>

namespace rebal {

bool mul_checked(i64 a, i64 b, i64& out) noexcept {
    __int128 wide = static_cast<__int128>(a) * static_cast<__int128>(b);
    if (wide < static_cast<__int128>(INT64_MIN) ||
        wide > static_cast<__int128>(INT64_MAX)) {
        out = 0;
        return false;
    }
    out = static_cast<i64>(wide);
    return true;
}

bool add_checked(i64 a, i64 b, i64& out) noexcept {
    __int128 wide = static_cast<__int128>(a) + static_cast<__int128>(b);
    if (wide < static_cast<__int128>(INT64_MIN) ||
        wide > static_cast<__int128>(INT64_MAX)) {
        out = 0;
        return false;
    }
    out = static_cast<i64>(wide);
    return true;
}

int compare_objective(const Objective& a, const Objective& b) {
    if (a.weight_deviation != b.weight_deviation) {
        return a.weight_deviation < b.weight_deviation ? -1 : 1;
    }
    if (a.total_fee != b.total_fee) {
        return a.total_fee < b.total_fee ? -1 : 1;
    }
    if (a.turnover != b.turnover) {
        return a.turnover < b.turnover ? -1 : 1;
    }
    return 0;
}

int compare_canonical(const std::vector<i64>& a, const std::vector<i64>& b) {
    std::size_t n = std::min(a.size(), b.size());
    for (std::size_t i = 0; i < n; ++i) {
        if (a[i] != b[i]) {
            return a[i] < b[i] ? -1 : 1;
        }
    }
    if (a.size() != b.size()) {
        return a.size() < b.size() ? -1 : 1;
    }
    return 0;
}

} // namespace rebal
