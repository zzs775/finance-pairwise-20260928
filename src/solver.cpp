#include "solver.h"

#include <algorithm>
#include <functional>

#include "engine.h"

namespace blockalloc {

bool Objectives::better_than(const Objectives& o) const {
    // Strict lexicographic order: proportional deviation sum first, then the
    // largest single-account deviation, then reallocation churn. Weighted sums
    // are intentionally never used across these three objectives.
    if (d1 != o.d1) return d1 < o.d1;
    if (d2 != o.d2) return d2 < o.d2;
    return d3 < o.d3;
}

namespace {

struct CandidateAccount {
    size_t orig_index;
    qty_t lo;       // smallest legal lot-aligned quantity
    qty_t hi;       // largest legal lot-aligned quantity
    qty_t lot;
    qty_t target;
    qty_t prior;
    std::string id;
};

qty_t deviation_unit(const CandidateAccount& c, qty_t v, qty_t F, qty_t T) {
    qty_t d = (v * F) - c.target * T;
    return d < 0 ? -d : d;
}

qty_t churn_unit(const CandidateAccount& c, qty_t v) {
    qty_t d = v - c.prior;
    return d < 0 ? -d : d;
}

// Smallest |v*F - target*T| achievable by a lot-aligned v in [lo,hi].
// Computed by snapping the continuous minimizer down to lot alignment and
// checking that point and its two neighbours. Every value is reproducible
// from the account constraints alone.
qty_t min_deviation_in_range(const CandidateAccount& c, qty_t F, qty_t T) {
    qty_t numerator = c.target * T;
    qty_t v0 = numerator / F;
    qty_t snap = floor_align(v0, c.lot);
    qty_t best = 0;
    bool have = false;
    for (int k = -1; k <= 1; ++k) {
        qty_t v = snap + static_cast<qty_t>(k) * c.lot;
        if (v < c.lo || v > c.hi) continue;
        qty_t d = deviation_unit(c, v, F, T);
        if (!have || d < best) { best = d; have = true; }
    }
    return have ? best : deviation_unit(c, c.lo, F, T);
}

}  // namespace

AllocationResult solve(const Instance& inst, const SolveOptions& opts) {
    AllocationResult result;
    result.nodes_budget = opts.node_budget;
    result.status = Status::Infeasible;

    const std::vector<size_t> order = canonical_order(inst);
    std::vector<CandidateAccount> accts;
    for (size_t oi : order) {
        const Account& a = inst.accounts[oi];
        if (!a.eligible) continue;
        CandidateAccount c;
        c.orig_index = oi;
        c.lot = a.lot_multiple;
        c.lo = ceil_align(a.min_qty, c.lot);
        c.hi = floor_align(a.max_qty, c.lot);
        c.target = a.target;
        c.prior = a.prior_qty;
        c.id = a.id;
        if (c.lo > c.hi) {
            result.path.push_back({0, "precheck", "no legal lot for " + c.id});
            return result;
        }
        accts.push_back(c);
    }

    const int n = static_cast<int>(accts.size());
    const qty_t F = target_total(inst);
    const qty_t T = inst.total_qty;

    std::vector<qty_t> x(n, 0);
    std::vector<qty_t> best(n, 0);

    // Additive prefix objectives maintained across recursion. d1 and d3 are
    // true sums, updated incrementally and exactly rolled back. d2 is a
    // maximum and is recomputed deterministically from the fixed prefix vector
    // rather than carried across sibling branches (carrying it left stale
    // maxima after backtracking).
    Objectives cur_obj{0, 0, 0};
    Objectives best_obj{0, 0, 0};
    bool have_best = false;

    // Reproducible suffix table for the d1 bound: tail_min_dev[i] is the sum of
    // independent minimum deviations for accounts i..n-1 ignoring the sum
    // coupling. It is a valid (loose) lower bound on every completion's d1.
    std::vector<qty_t> tail_min_dev(n + 1, 0);
    for (int j = n - 1; j >= 0; --j) {
        tail_min_dev[j] = tail_min_dev[j + 1] + min_deviation_in_range(accts[j], F, T);
    }

    uint64_t nodes = 0;
    bool budget_out = false;

    // Deterministic greedy incumbent (restricted-account remainder
    // redistribution): start every account at its lot-aligned minimum, then
    // hand out the remainder one lot at a time to a non-capped account. The
    // chosen account is the one whose marginal increase in proportional
    // deviation d1 is smallest; ties break by canonical index. Accounts
    // already at their maximum receive nothing (they are restricted).
    auto build_greedy = [&]() -> bool {
        std::vector<qty_t> gx(n, 0);
        qty_t used = 0;
        for (int i = 0; i < n; ++i) {
            gx[i] = accts[i].lo;
            used += gx[i];
        }
        if (used > T) return false;
        qty_t remainder = T - used;
        while (remainder > 0) {
            int pick = -1;
            qty_t best_marginal = 0;
            for (int i = 0; i < n; ++i) {
                if (gx[i] + accts[i].lot > accts[i].hi) continue;
                if (remainder < accts[i].lot) continue;
                qty_t before = deviation_unit(accts[i], gx[i], F, T);
                qty_t after = deviation_unit(accts[i], gx[i] + accts[i].lot, F, T);
                qty_t marginal = after - before;
                if (pick == -1 || marginal < best_marginal) {
                    pick = i;
                    best_marginal = marginal;
                }
            }
            if (pick == -1) return false;
            gx[pick] += accts[pick].lot;
            remainder -= accts[pick].lot;
        }
        Objectives go{0, 0, 0};
        for (int i = 0; i < n; ++i) {
            qty_t d = deviation_unit(accts[i], gx[i], F, T);
            go.d1 += d;
            go.d2 = std::max(go.d2, d);
            go.d3 += churn_unit(accts[i], gx[i]);
        }
        best_obj = go;
        best = gx;
        have_best = true;
        result.path.push_back(
            {0, "greedy-incumbent",
             "restricted-account remainder redistribution gives initial upper bound"});
        return true;
    };

    build_greedy();

    auto prune_by_bound = [&](int i) -> bool {
        if (!have_best) return false;
        // Every bound below is reproducible from the fixed prefix (0..i-1) and
        // the suffix accounts' independent constraint ranges.
        //
        // d1: sum of each suffix account's smallest possible deviation
        // (ignoring the sum coupling) plus the fixed prefix d1 is a valid, if
        // loose, LOWER bound on any completion's d1.
        qty_t lb1 = cur_obj.d1 + tail_min_dev[i];
        if (lb1 > best_obj.d1) return true;
        // If the fixed prefix alone already exceeds the incumbent on d1, no
        // suffix can repair it (all terms are non-negative).
        if (cur_obj.d1 > best_obj.d1) return true;
        // Only when d1 can still TIE the incumbent do we inspect d2/d3. The
        // suffix-independent minima of d2/d3 ignore the total-sum coupling, so
        // they do not bound the d2/d3 of a completion that also achieves the
        // best d1; hence we prune only on the fixed PREFIX values, which are
        // deterministic lower bounds on every completion of this node.
        if (lb1 == best_obj.d1) {
            if (cur_obj.d2 > best_obj.d2) return true;
            if (cur_obj.d2 == best_obj.d2 && cur_obj.d3 > best_obj.d3)
                return true;
        }
        return false;
    };

    std::function<void(int, qty_t)> dfs = [&](int i, qty_t remaining) {
        if (budget_out) return;
        ++nodes;
        if (nodes > opts.node_budget) {
            budget_out = true;
            result.path.push_back(
                {i, "budget-exhausted",
                 "node budget " + std::to_string(opts.node_budget) +
                     " reached; current best not proven optimal"});
            return;
        }
        if (prune_by_bound(i)) {
            if (result.path.size() < 200)
                result.path.push_back(
                    {i, "prune-bound",
                     "reproducible lexicographic lower bound dominates incumbent"});
            return;
        }
        if (i == n) {
            if (remaining == 0) {
                // Final objectives are recomputed from the COMPLETE vector.
                // The incrementally maintained prefix values are valid for
                // pruning bounds but must not adjudicate ties, since d2 is a
                // running maximum and incremental state across branches is
                // easy to mis-track.
                Objectives leaf{0, 0, 0};
                for (int k = 0; k < n; ++k) {
                    qty_t dk = (x[k] * F) - accts[k].target * T;
                    if (dk < 0) dk = -dk;
                    leaf.d1 += dk;
                    leaf.d2 = std::max(leaf.d2, dk);
                    qty_t ck = x[k] - accts[k].prior;
                    if (ck < 0) ck = -ck;
                    leaf.d3 += ck;
                }
                bool take = !have_best || leaf.better_than(best_obj);
                if (!take && !(best_obj.better_than(leaf))) {
                    // Three-way objective tie: lexicographically smallest
                    // allocation vector in canonical (ascending id) order.
                    take = x < best;
                }
                if (take) {
                    best_obj = leaf;
                    best = x;
                    have_best = true;
                    if (result.path.size() < 200)
                        result.path.push_back(
                            {n, "new-best",
                             "d1=" + std::to_string(leaf.d1) +
                                 " d2=" + std::to_string(leaf.d2) +
                                 " d3=" + std::to_string(leaf.d3)});
                }
            }
            return;
        }

        const CandidateAccount& c = accts[i];
        qty_t base_lo = c.lo;
        qty_t base_hi = c.hi;
        qty_t tail_lo = 0, tail_hi = 0, gcd_tail = 0;
        for (int j = i + 1; j < n; ++j) {
            tail_lo += accts[j].lo;
            tail_hi += accts[j].hi;
            gcd_tail = std::gcd(gcd_tail, accts[j].lot);
        }

        qty_t v = base_lo;
        for (;;) {
            if (budget_out) return;
            if (v > base_hi) break;
            qty_t rem = remaining - v;
            bool range_ok = (rem >= tail_lo && rem <= tail_hi);
            bool gcd_ok = (gcd_tail == 0) ? (rem == 0)
                                          : (rem >= 0 && rem % gcd_tail == 0);
            if (range_ok && gcd_ok) {
                qty_t dev = (v * F) - c.target * T;
                if (dev < 0) dev = -dev;
                qty_t churn = v - c.prior;
                if (churn < 0) churn = -churn;
                cur_obj.d1 += dev;
                cur_obj.d3 += churn;
                x[i] = v;
                // Deterministic prefix maximum over the fixed prefix 0..i.
                qty_t pref_max = 0;
                for (int k = 0; k <= i; ++k) {
                    qty_t dk = (x[k] * F) - accts[k].target * T;
                    if (dk < 0) dk = -dk;
                    pref_max = std::max(pref_max, dk);
                }
                cur_obj.d2 = pref_max;
                dfs(i + 1, rem);
                cur_obj.d3 -= churn;
                cur_obj.d1 -= dev;
                x[i] = 0;
                // Restore prefix maximum for the (shorter) fixed prefix 0..i-1.
                qty_t back_max = 0;
                for (int k = 0; k < i; ++k) {
                    qty_t dk = (x[k] * F) - accts[k].target * T;
                    if (dk < 0) dk = -dk;
                    back_max = std::max(back_max, dk);
                }
                cur_obj.d2 = back_max;
            }
            v += c.lot;
        }
    };

    // Root-level reproducible lower bound on d1 only. Independent per-account
    // minima of d2/d3 ignore the total-sum coupling and therefore do not bound
    // the d2/d3 attainable together with the best d1, so they are reported as
    // 0 (no information) rather than a misleading value.
    Objectives root_lb{tail_min_dev[0], 0, 0};

    dfs(0, T);

    result.lower_bound = root_lb;
    if (have_best) result.upper_bound = best_obj;

    result.nodes_visited = nodes;

    if (have_best) {
        result.allocs.assign(inst.accounts.size(), 0);
        for (int i = 0; i < n; ++i)
            result.allocs[accts[i].orig_index] = best[i];
        result.obj = best_obj;
        result.incumbent_found = true;
    } else {
        result.allocs.assign(inst.accounts.size(), 0);
    }
    if (budget_out) {
        result.status = Status::BudgetExhausted;
    } else {
        result.status = have_best ? Status::Optimal : Status::Infeasible;
    }
    return result;
}

}  // namespace blockalloc



