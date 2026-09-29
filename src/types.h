#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace blockalloc {

using qty_t = int64_t;

struct Account {
    std::string id;
    qty_t target = 0;
    qty_t min_qty = 0;
    qty_t max_qty = 0;
    qty_t lot_multiple = 1;
    bool eligible = true;
    int priority = 0;
    qty_t prior_qty = 0;
    bool exempt = false;
};

struct Instance {
    std::string name = "unnamed";
    qty_t total_qty = 0;
    std::vector<Account> accounts;
};

struct Objectives {
    qty_t d1 = 0;
    qty_t d2 = 0;
    qty_t d3 = 0;

    bool better_than(const Objectives& o) const;
};

enum class Status {
    Optimal,
    BudgetExhausted,
    Infeasible
};

struct DecisionEvent {
    int depth = 0;
    std::string kind;
    std::string detail;
};

struct AllocationResult {
    Status status = Status::Infeasible;
    std::vector<qty_t> allocs;
    Objectives obj{};
    std::vector<DecisionEvent> path;
    uint64_t nodes_visited = 0;
    uint64_t nodes_budget = 0;
    bool incumbent_found = false;
    Objectives lower_bound{};
    Objectives upper_bound{};
};

struct ConflictReport {
    bool feasible = true;
    std::vector<std::string> reasons;
    std::vector<std::string> conflict_ids;
};

struct OracleResult {
    bool feasible = false;
    bool budget_exhausted = false;
    std::vector<qty_t> allocs;
    Objectives obj{};
    uint64_t combinations = 0;
};

struct SolveOptions {
    uint64_t node_budget = 1'000'000ULL;
    uint64_t oracle_budget = 5'000'000ULL;
};

}  // namespace blockalloc
