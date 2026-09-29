#include "report.h"

#include <sstream>

#include "engine.h"

namespace blockalloc {

namespace {

const char* status_name(Status s) {
    switch (s) {
        case Status::Optimal: return "OPTIMAL";
        case Status::BudgetExhausted: return "BUDGET_EXHAUSTED_INCUMBENT";
        case Status::Infeasible: return "INFEASIBLE";
    }
    return "UNKNOWN";
}

}  // namespace

std::string render_report(const Instance& inst,
                          const AllocationResult& result,
                          const ConflictReport& conflict,
                          const OracleResult& oracle) {
    std::ostringstream os;
    const std::vector<size_t> order = canonical_order(inst);
    const qty_t F = target_total(inst);
    const qty_t T = inst.total_qty;

    os << "BLOCK ALLOCATION REPORT (canonical)\n";
    os << "====================================\n";
    os << "instance: " << inst.name << "\n";
    os << "total_quantity: " << T << "\n";
    os << "account_count: " << inst.accounts.size() << "\n";
    os << "status: " << status_name(result.status) << "\n";
    os << "nodes_visited: " << result.nodes_visited << "\n";
    os << "nodes_budget: " << result.nodes_budget << "\n";

    if (!conflict.feasible) {
        os << "\nFEASIBILITY\n";
        os << "feasible: no\n";
        for (const auto& r : conflict.reasons) os << "reason: " << r << "\n";
        os << "minimum_conflict_account_set:";
        if (conflict.conflict_ids.empty()) {
            os << " (none)\n";
        } else {
            os << "\n";
            for (const auto& id : conflict.conflict_ids) os << "  - " << id << "\n";
        }
        os << "note: relaxing min/eligibility constraints of that set restores feasibility\n";
        return os.str();
    }

    os << "\nFEASIBILITY\nfeasible: yes\n";

    os << "\nOBJECTIVES (lexicographic, no weighted sum)\n";
    os << "scaling: fractional deviation |q_i/T - target_i/F| with F=sum(targets)="
       << F << " is reported as integer numerator |q_i*F - target_i*T|\n";
    os << "d1_sum_abs_proportional_deviation: " << result.obj.d1 << "\n";
    os << "d2_max_account_deviation: " << result.obj.d2 << "\n";
    os << "d3_reallocation_churn: " << result.obj.d3 << "\n";

    os << "\nBOUNDS (reproducible from prefix + suffix constraints)\n";
    os << "lower_bound: d1=" << result.lower_bound.d1
       << " d2=" << result.lower_bound.d2
       << " d3=" << result.lower_bound.d3 << "\n";
    os << "upper_bound: d1=" << result.upper_bound.d1
       << " d2=" << result.upper_bound.d2
       << " d3=" << result.upper_bound.d3 << "\n";
    os << "optimality: "
       << (result.status == Status::Optimal
               ? std::string("proven by exhaustive search over bounded branches")
               : std::string("NOT proven: search stopped at budget"))
       << "\n";

    qty_t check_sum = 0;
    os << "\nPER-ACCOUNT ALLOCATION (canonical id order)\n";
    os << "id  eligible  lot  target  prior  alloc  min  max  "
          "abs_dev_units  min_slack  max_headroom  priority\n";
    for (size_t oi : order) {
        const Account& a = inst.accounts[oi];
        qty_t q = result.incumbent_found ? result.allocs[oi] : 0;
        check_sum += q;
        qty_t dev = (q * F) - a.target * T;
        if (dev < 0) dev = -dev;
        qty_t min_slack = q - a.min_qty;
        qty_t max_head = a.max_qty - q;
        os << a.id
           << "  " << (a.eligible ? "yes" : "no")
           << "  " << a.lot_multiple
           << "  " << a.target
           << "  " << a.prior_qty
           << "  " << q
           << "  " << a.min_qty
           << "  " << a.max_qty
           << "  " << dev
           << "  " << min_slack
           << "  " << max_head
           << "  " << a.priority << "\n";
    }
    os << "check_sum: " << check_sum << " == total_quantity " << T
       << " : " << (check_sum == T ? "OK" : "MISMATCH") << "\n";

    os << "\nTIE-BREAKING\n";
    os << "rule: equal (d1,d2,d3) -> smallest allocation vector in ascending id order\n";

    os << "\nDECISION PATH (bounded log)\n";
    if (result.path.empty()) {
        os << "(no events)\n";
    } else {
        for (const auto& e : result.path)
            os << "depth=" << e.depth << "  " << e.kind
               << "  " << e.detail << "\n";
    }

    os << "\nORACLE (small-scale full enumeration)\n";
    os << "oracle_feasible: " << (oracle.feasible ? "yes" : "no") << "\n";
    os << "oracle_combinations_enumerated: " << oracle.combinations << "\n";
    if (oracle.budget_exhausted) {
        os << "oracle_status: BUDGET_EXHAUSTED (enumeration stopped at budget; "
              "no cross-check for this size)\n";
    } else if (oracle.feasible) {
        os << "oracle_status: COMPLETE\n";
        os << "oracle_objectives: d1=" << oracle.obj.d1
           << " d2=" << oracle.obj.d2
           << " d3=" << oracle.obj.d3 << "\n";
        bool alloc_match =
            result.incumbent_found && result.allocs == oracle.allocs;
        bool obj_match = result.obj.d1 == oracle.obj.d1 &&
                         result.obj.d2 == oracle.obj.d2 &&
                         result.obj.d3 == oracle.obj.d3;
        os << "oracle_cross_check: "
           << (alloc_match && obj_match ? "MATCH" : "MISMATCH") << "\n";
    }

    os << "\nREPRODUCE\n";
    os << "run: block_alloc --input <file> --report report.txt\n";
    return os.str();
}

}  // namespace blockalloc
