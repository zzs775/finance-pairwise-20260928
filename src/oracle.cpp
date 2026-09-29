#include "oracle.h"

#include <algorithm>
#include <functional>

#include "engine.h"

namespace blockalloc {

OracleResult brute_force_oracle(const Instance& inst, uint64_t combination_budget) {
    OracleResult out;
    out.feasible = false;

    const std::vector<size_t> order = canonical_order(inst);
    struct O {
        size_t orig;
        qty_t lo, hi, lot, target, prior;
    };
    std::vector<O> accts;
    for (size_t oi : order) {
        const Account& a = inst.accounts[oi];
        if (!a.eligible) continue;
        O o;
        o.orig = oi;
        o.lot = a.lot_multiple;
        o.lo = ceil_align(a.min_qty, o.lot);
        o.hi = floor_align(a.max_qty, o.lot);
        o.target = a.target;
        o.prior = a.prior_qty;
        if (o.lo > o.hi) return out;
        accts.push_back(o);
    }

    const int n = static_cast<int>(accts.size());
    const qty_t F = target_total(inst);
    const qty_t T = inst.total_qty;

    std::vector<qty_t> x(n, 0);
    std::vector<qty_t> best(n, 0);
    Objectives best_obj{};
    bool have = false;
    uint64_t combos = 0;
    bool budget_out = false;

    auto dev = [&](int i, qty_t v) {
        qty_t d = (v * F) - accts[i].target * T;
        return d < 0 ? -d : d;
    };
    auto churn = [&](int i, qty_t v) {
        qty_t d = v - accts[i].prior;
        return d < 0 ? -d : d;
    };

    std::function<void(int, qty_t, Objectives)> en =
        [&](int i, qty_t remaining, Objectives running) {
            if (budget_out) return;
            if (i == n) {
                if (remaining != 0) return;
                ++combos;
                if (combos > combination_budget) {
                    budget_out = true;
                    return;
                }
                bool take = !have || running.better_than(best_obj);
                if (!take && have && !(best_obj.better_than(running))) take = x < best;
                if (take) {
                    have = true;
                    best_obj = running;
                    best = x;
                }
                return;
            }
            // Tail range prune identical in spirit to the solver's.
            qty_t tlo = 0, thi = 0, g = 0;
            for (int j = i + 1; j < n; ++j) {
                tlo += accts[j].lo;
                thi += accts[j].hi;
                g = std::gcd(g, accts[j].lot);
            }
            for (qty_t v = accts[i].lo; v <= accts[i].hi; v += accts[i].lot) {
                if (budget_out) return;
                qty_t rem = remaining - v;
                if (rem < tlo || rem > thi) continue;
                if (g > 0 && (rem < 0 || rem % g != 0)) continue;
                if (g == 0 && rem != 0) continue;
                Objectives nx = running;
                qty_t d = dev(i, v);
                nx.d1 += d;
                nx.d2 = std::max(nx.d2, d);
                nx.d3 += churn(i, v);
                x[i] = v;
                en(i + 1, rem, nx);
            }
        };

    en(0, T, Objectives{});

    out.combinations = combos;
    out.budget_exhausted = budget_out;
    if (have && !budget_out) {
        out.feasible = true;
        out.allocs.assign(inst.accounts.size(), 0);
        for (int i = 0; i < n; ++i) out.allocs[accts[i].orig] = best[i];
        out.obj = best_obj;
    } else {
        out.allocs.assign(inst.accounts.size(), 0);
    }
    return out;
}

}  // namespace blockalloc
