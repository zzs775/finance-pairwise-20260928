#include "validator.h"

#include <algorithm>
#include <numeric>

namespace blockalloc {

namespace {

qty_t ceil_align(qty_t v, qty_t m) {
    if (v <= 0) return 0;
    return ((v + m - 1) / m) * m;
}

qty_t floor_align(qty_t v, qty_t m) {
    if (v < 0) return 0;
    return (v / m) * m;
}

// Relaxed feasibility used both for the strict check and the conflict search.
// Members of `exempt_idx` are treated as fully unconstrained (min 0, unlimited
// capacity, lot 1, eligibility ignored). Every other account either takes a
// lot-aligned quantity inside [eff_min, eff_max] or, when ineligible, must be
// 0. Relaxing a minimum-cardinality set therefore covers every failure mode:
// excessive minimums, insufficient caps, eligibility and lot divisibility.
bool relaxed_feasible(const Instance& inst,
                      const std::vector<size_t>& exempt_idx) {
    const qty_t T = inst.total_qty;
    std::vector<char> exempt(inst.accounts.size(), 0);
    for (size_t i : exempt_idx) exempt[i] = 1;

    qty_t lo = 0;
    qty_t hi = 0;
    qty_t g = 0;
    bool unlimited = false;
    for (size_t i = 0; i < inst.accounts.size(); ++i) {
        const Account& a = inst.accounts[i];
        if (exempt[i]) {
            unlimited = true;  // unconstrained slack account, lot divides all
            g = 1;
            continue;
        }
        if (!a.eligible) continue;
        qty_t eff_lo = ceil_align(a.min_qty, a.lot_multiple);
        qty_t eff_hi = floor_align(a.max_qty, a.lot_multiple);
        if (eff_lo > eff_hi) return false;
        lo += eff_lo;
        hi += eff_hi;
        g = std::gcd(g, a.lot_multiple);
        if (lo > T) return false;
        if (hi < 0) return false;
    }
    if (!unlimited && hi < T) return false;
    if (lo > T) return false;
    if (g > 0 && (T % g != 0)) return false;
    return true;
}

}  // namespace

ConflictReport check_feasibility(const Instance& inst) {
    ConflictReport report;

    // Global, account-independent sanity.
    if (inst.accounts.size() > 12) {
        report.feasible = false;
        report.reasons.push_back("more than 12 accounts declared (" +
                                 std::to_string(inst.accounts.size()) + ")");
        return report;
    }

    // Strict feasibility: no exemptions.
    if (relaxed_feasible(inst, {})) {
        report.feasible = true;
        return report;
    }

    report.feasible = false;

    // Explain the aggregate bounds of the strict problem.
    qty_t lo = 0, hi = 0, g = 0;
    for (const Account& a : inst.accounts) {
        if (!a.eligible) continue;
        lo += ceil_align(a.min_qty, a.lot_multiple);
        hi += floor_align(a.max_qty, a.lot_multiple);
        g = std::gcd(g, a.lot_multiple);
    }
    if (lo > inst.total_qty)
        report.reasons.push_back(
            "sum of lot-aligned minimums " + std::to_string(lo) +
            " exceeds total quantity " + std::to_string(inst.total_qty));
    if (hi < inst.total_qty)
        report.reasons.push_back(
            "sum of lot-aligned maximums " + std::to_string(hi) +
            " is below total quantity " + std::to_string(inst.total_qty));
    if (lo <= inst.total_qty && hi >= inst.total_qty && g > 0 &&
        inst.total_qty % g != 0)
        report.reasons.push_back(
            "total quantity " + std::to_string(inst.total_qty) +
            " is not divisible by gcd of lot multiples " + std::to_string(g));

    // Find a minimum-cardinality set of accounts whose relaxation restores
    // feasibility (lower bounds and eligibility of these accounts ignored).
    // Enumerate subsets in increasing size, members ordered by account index;
    // index order is canonical (input order is validated by the caller and the
    // report sorts IDs, which are unique, so output is order-independent).
    const size_t n = inst.accounts.size();
    std::vector<size_t> order(n);
    std::iota(order.begin(), order.end(), size_t{0});

    bool found = false;
    std::vector<size_t> best;
    for (size_t k = 1; k <= n && !found; ++k) {
        std::vector<bool> mask(n, false);
        std::fill(mask.end() - static_cast<ptrdiff_t>(k), mask.end(), true);
        do {
            std::vector<size_t> sub;
            for (size_t i = 0; i < n; ++i)
                if (mask[i]) sub.push_back(i);
            if (relaxed_feasible(inst, sub)) {
                best = sub;
                found = true;
                break;
            }
        } while (std::next_permutation(mask.begin(), mask.end()));
    }

    if (found) {
        for (size_t i : best) report.conflict_ids.push_back(inst.accounts[i].id);
        std::sort(report.conflict_ids.begin(), report.conflict_ids.end());
        report.reasons.push_back(
            "relaxing constraints of minimum-cardinality account set restores feasibility");
    } else {
        report.reasons.push_back("no relaxation subset restores feasibility");
    }
    return report;
}

}  // namespace blockalloc
