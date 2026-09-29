#include "synthetic.h"

namespace blockalloc {

Instance make_synthetic_instance() {
    Instance inst;
    inst.name = "synthetic-builtin";
    inst.total_qty = 100;

    // Mix of lot multiples, caps, minimums and an ineligible account.
    auto add = [&](std::string id, qty_t target, qty_t lo, qty_t hi,
                   qty_t lot, bool eligible, int prio, qty_t prior) {
        Account a;
        a.id = std::move(id);
        a.target = target;
        a.min_qty = lo;
        a.max_qty = hi;
        a.lot_multiple = lot;
        a.eligible = eligible;
        a.priority = prio;
        a.prior_qty = prior;
        inst.accounts.push_back(std::move(a));
    };

    add("A", 40, 10, 60, 10, true, 1, 30);
    add("B", 30, 0, 50, 5, true, 2, 30);
    add("C", 20, 0, 40, 2, true, 3, 25);
    add("D", 10, 0, 20, 1, false, 4, 15);
    return inst;
}

}  // namespace blockalloc
