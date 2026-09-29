#include "tests.h"

#include <algorithm>
#include <functional>
#include <iostream>
#include <string>
#include <utility>
#include <vector>

#include "engine.h"
#include "oracle.h"
#include "parser.h"
#include "report.h"
#include "solver.h"
#include "synthetic.h"
#include "validator.h"

namespace blockalloc {

namespace {

int g_failures = 0;
int g_checks = 0;

void check(bool cond, const std::string& name) {
    ++g_checks;
    if (!cond) {
        ++g_failures;
        std::cout << "FAIL: " << name << std::endl;
    }
}

Account make_account(std::string id, qty_t target, qty_t lo, qty_t hi,
                     qty_t lot = 1, bool eligible = true,
                     qty_t prior = -1, int prio = 0) {
    Account a;
    a.id = std::move(id);
    a.target = target;
    a.min_qty = lo;
    a.max_qty = hi;
    a.lot_multiple = lot;
    a.eligible = eligible;
    a.priority = prio;
    a.prior_qty = prior < 0 ? target : prior;
    return a;
}

Instance make_instance(std::string name, qty_t total,
                       std::vector<Account> accounts) {
    Instance inst;
    inst.name = std::move(name);
    inst.total_qty = total;
    inst.accounts = std::move(accounts);
    return inst;
}

bool sums_to_total(const Instance& inst, const std::vector<qty_t>& q) {
    qty_t s = 0;
    for (qty_t v : q) s += v;
    return s == inst.total_qty;
}

bool respects_constraints(const Instance& inst, const std::vector<qty_t>& q) {
    for (size_t i = 0; i < inst.accounts.size(); ++i) {
        const Account& a = inst.accounts[i];
        if (!a.eligible) {
            if (q[i] != 0) return false;
            continue;
        }
        if (q[i] < a.min_qty || q[i] > a.max_qty) return false;
        if (q[i] % a.lot_multiple != 0) return false;
    }
    return true;
}

Objectives compute_objectives(const Instance& inst, const std::vector<qty_t>& q) {
    const qty_t F = target_total(inst);
    Objectives o{0, 0, 0};
    for (size_t i = 0; i < inst.accounts.size(); ++i) {
        // Objectives are defined only over participating (eligible) accounts;
        // ineligible accounts are fixed at 0 and excluded.
        if (!inst.accounts[i].eligible) continue;
        qty_t d = q[i] * F - inst.accounts[i].target * inst.total_qty;
        if (d < 0) d = -d;
        o.d1 += d;
        o.d2 = std::max(o.d2, d);
        qty_t c = q[i] - inst.accounts[i].prior_qty;
        if (c < 0) c = -c;
        o.d3 += c;
    }
    return o;
}

void run_case(const std::string& name, const Instance& inst,
              const std::vector<qty_t>& expected, bool expect_feasible) {
    ConflictReport cf = check_feasibility(inst);
    check(cf.feasible == expect_feasible, name + ": feasibility flag");
    if (!expect_feasible) return;

    SolveOptions opts;
    opts.node_budget = 1'000'000;
    AllocationResult r = solve(inst, opts);
    check(r.status == Status::Optimal, name + ": optimal status");
    check(r.incumbent_found, name + ": incumbent found");
    check(sums_to_total(inst, r.allocs), name + ": sum equals total");
    check(respects_constraints(inst, r.allocs), name + ": constraints respected");
    check(r.allocs == expected, name + ": expected allocation vector");

    Objectives recomputed = compute_objectives(inst, r.allocs);
    check(recomputed.d1 == r.obj.d1 && recomputed.d2 == r.obj.d2 &&
              recomputed.d3 == r.obj.d3,
          name + ": objectives recomputable");

    OracleResult o = brute_force_oracle(inst, 5'000'000);
    check(o.feasible, name + ": oracle feasible");
    check(o.allocs == r.allocs, name + ": oracle allocation match");
    check(o.obj.d1 == r.obj.d1 && o.obj.d2 == r.obj.d2 &&
              o.obj.d3 == r.obj.d3,
          name + ": oracle objective match");
}

}  // namespace

int run_tests() {
    // 1. Non-divisible total vs proportional targets. T=10, targets 3/7:
    // (3,7) hits the targets exactly (they already sum to 10), lot 1, so it
    // is the integer optimum even though it is not "divisible" into halves.
    // To exercise rounding genuinely we use lot 2, where quantities must be
    // even: best lot-2 vector is (4,6).
    run_case("non_divisible",
             make_instance("non_divisible", 10,
                           {make_account("A", 3, 0, 10, 2, true, 4),
                            make_account("B", 7, 0, 10, 2, true, 6)}),
             {4, 6}, true);

    // 2. Account cap forces deviation.
    // T=10 targets 3/7, B capped at 2 -> (8,2).
    run_case("capped",
             make_instance("capped", 10,
                           {make_account("A", 3, 0, 10),
                            make_account("B", 7, 0, 2)}),
             {8, 2}, true);

    // 3. Minimum quantity forces deviation.
    // T=10 targets 3/7, A min 6 -> (6,4).
    run_case("minimum",
             make_instance("minimum", 10,
                           {make_account("A", 3, 6, 10),
                            make_account("B", 7, 0, 10)}),
             {6, 4}, true);

    // 4. Ineligible account excluded entirely.
    run_case("eligible_exclusion",
             make_instance("eligible", 50,
                           {make_account("A", 50, 0, 50, 1, false),
                            make_account("B", 30, 0, 50),
                            make_account("C", 20, 0, 50)}),
             {0, 30, 20}, true);

    // 5. Tied three-way objectives: identical accounts with lot 2 and an even
    // total. (4,6) and (6,4) have identical d1,d2,d3 (with prior = target for
    // both); tie-break takes the smallest vector in ascending id order.
    run_case("tie_break",
             make_instance("tie", 10,
                           {make_account("A", 5, 0, 10, 2, true, 5),
                            make_account("B", 5, 0, 10, 2, true, 5)}),
             {4, 6}, true);

    // 6. Budget exhaustion: a deliberately tiny node budget forces an early
    // stop. The solver must report an incumbent (if any) plus bounds and must
    // NOT label the result optimal.
    {
        Instance inst = make_instance(
            "budget", 200,
            {make_account("A", 100, 0, 200),
             make_account("B", 100, 0, 200),
             make_account("C", 0, 0, 200)});
        SolveOptions opts;
        opts.node_budget = 3;
        AllocationResult r = solve(inst, opts);
        check(r.status == Status::BudgetExhausted, "budget: status");
        check(!r.incumbent_found || sums_to_total(inst, r.allocs),
              "budget: any incumbent sums to total");
        check(!r.incumbent_found || respects_constraints(inst, r.allocs),
              "budget: any incumbent respects constraints");
        check(r.nodes_visited >= r.nodes_budget, "budget: budget consumed");
        // Oracle with ample budget still proves the true optimum.
        OracleResult o = brute_force_oracle(inst, 20'000'000);
        check(o.feasible, "budget: oracle feasible");
        check(!r.incumbent_found || o.obj.d1 <= r.obj.d1,
              "budget: oracle at least as good");
    }

    // 7. Infeasible: sum of minimums exceeds total -> minimum conflict set.
    {
        Instance inst = make_instance(
            "mins_too_high", 10,
            {make_account("A", 4, 8, 10), make_account("B", 3, 8, 10),
             make_account("C", 3, 0, 10)});
        ConflictReport cf = check_feasibility(inst);
        check(!cf.feasible, "mins: infeasible");
        check(cf.conflict_ids.size() == 1, "mins: one-account conflict set");
        bool has_heavy =
            std::find(cf.conflict_ids.begin(), cf.conflict_ids.end(), "A") !=
                cf.conflict_ids.end() ||
            std::find(cf.conflict_ids.begin(), cf.conflict_ids.end(), "B") !=
                cf.conflict_ids.end();
        check(has_heavy, "mins: conflict set identifies a heavy account");
    }

    // 8. Infeasible: total not divisible by gcd of lot multiples.
    {
        Instance inst = make_instance(
            "gcd", 11,
            {make_account("A", 5, 0, 20, 10),
             make_account("B", 5, 0, 20, 5)});
        ConflictReport cf = check_feasibility(inst);
        check(!cf.feasible, "gcd: infeasible");
        bool mentions_gcd = false;
        for (const auto& r : cf.reasons)
            if (r.find("gcd") != std::string::npos) mentions_gcd = true;
        check(mentions_gcd, "gcd: reason reported");
    }

    // 9. Infeasible: insufficient aggregate capacity.
    {
        Instance inst = make_instance(
            "capacity", 100,
            {make_account("A", 50, 0, 30), make_account("B", 50, 0, 30)});
        ConflictReport cf = check_feasibility(inst);
        check(!cf.feasible, "capacity: infeasible");
        check(cf.conflict_ids.size() >= 1, "capacity: conflict set non-empty");
    }

    // 10. Determinism: a shuffled input order (same fields otherwise) yields a
    // byte-identical canonical report.
    {
        Instance a = make_instance(
            "determ", 100,
            {make_account("B", 30, 0, 60, 5),
             make_account("A", 40, 10, 60, 10),
             make_account("C", 20, 0, 40, 2)});
        Instance b = a;
        std::reverse(b.accounts.begin(), b.accounts.end());

        SolveOptions opts;
        AllocationResult ra = solve(a, opts);
        AllocationResult rb = solve(b, opts);
        const auto oa_order = canonical_order(a);
        const auto ob_order = canonical_order(b);
        std::vector<qty_t> va(oa_order.size());
        std::vector<qty_t> vb(ob_order.size());
        for (size_t i = 0; i < oa_order.size(); ++i) {
            va[i] = ra.allocs[oa_order[i]];
            vb[i] = rb.allocs[ob_order[i]];
        }
        check(va == vb, "determ: canonical allocation equal");

        ConflictReport ca = check_feasibility(a), cb = check_feasibility(b);
        OracleResult oa = brute_force_oracle(a, 5'000'000);
        OracleResult ob = brute_force_oracle(b, 5'000'000);
        std::string ta = render_report(a, ra, ca, oa);
        std::string tb = render_report(b, rb, cb, ob);
        check(ta == tb, "determ: report byte-identical");
    }

    // 11. Parser round trip + synthetic instance sanity.
    {
        const char* text =
            "name: parsed\n"
            "total: 10\n"
            "account: A\n"
            "target: 6\n"
            "min: 0\n"
            "max: 10\n"
            "lot: 2\n"
            "account: B\n"
            "target: 4\n"
            "max: 10\n"
            "eligible: no\n";
        ParseReport pr = parse_instance(text);
        check(pr.ok, "parser: ok");
        check(pr.instance.accounts.size() == 2, "parser: two accounts");
        check(pr.instance.accounts[1].prior_qty == 4, "parser: prior defaults to target");
        AllocationResult r = solve(pr.instance);
        check(r.status == Status::Optimal, "parser: optimal");
        check(r.allocs[0] == 10 && r.allocs[1] == 0, "parser: all to eligible lot-2 A");

        ParseReport bad = parse_instance("name: x\ntotal: 5\naccount: A\ntarget: 5\n");
        check(!bad.ok, "parser: missing max rejected");

        Instance syn = make_synthetic_instance();
        ConflictReport cf = check_feasibility(syn);
        check(cf.feasible, "synthetic: feasible");
        AllocationResult rs = solve(syn);
        check(rs.status == Status::Optimal, "synthetic: optimal");
    }

    // 12. Oracle budget flag on a deliberately tiny enumeration budget.
    {
        Instance inst = make_instance(
            "oracle_budget", 20,
            {make_account("A", 10, 0, 20), make_account("B", 10, 0, 20)});
        OracleResult o = brute_force_oracle(inst, 3);
        check(o.budget_exhausted, "oracle: budget exhausted flagged");
        check(!o.feasible, "oracle: no answer when budgeted out");
        OracleResult full = brute_force_oracle(inst, 5'000'000);
        check(full.feasible && !full.budget_exhausted, "oracle: completes with ample budget");
    }

    // 13. Twelve accounts, coarse lots, exact proportional fit: solver must be
    // optimal and match a within-budget full enumeration oracle.
    {
        std::vector<Account> accs;
        for (int k = 1; k <= 12; ++k) {
            std::string id = std::string("A") + (k < 10 ? "0" : "") +
                             std::to_string(k);
            accs.push_back(make_account(id, 10, 0, 120, 10));
        }
        Instance inst = make_instance("twelve", 120, std::move(accs));
        ConflictReport cf = check_feasibility(inst);
        check(cf.feasible, "twelve: feasible");
        AllocationResult r = solve(inst);
        check(r.status == Status::Optimal, "twelve: optimal");
        check(r.obj.d1 == 0 && r.obj.d2 == 0, "twelve: zero proportional deviation");
        check(sums_to_total(inst, r.allocs), "twelve: sum correct");
        check(respects_constraints(inst, r.allocs), "twelve: constraints ok");
        OracleResult o = brute_force_oracle(inst, 5'000'000);
        check(o.feasible && !o.budget_exhausted, "twelve: oracle completes");
        check(o.allocs == r.allocs, "twelve: oracle vector match");
    }

    std::cout << "checks: " << g_checks << " failures: " << g_failures << std::endl;
    return g_failures == 0 ? 0 : 1;
}


}  // namespace blockalloc
