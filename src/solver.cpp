#include "rebalancer/solver.hpp"

#include <algorithm>
#include <array>

#include "rebalancer/fees.hpp"
#include "rebalancer/model.hpp"

namespace rebal {

namespace {

using wide = __int128;

constexpr i64 kMaxAssets = 7;

struct Node {
    i64 depth = 0;
    std::array<i64, kMaxAssets> lots{};
    i64 buy_notional = 0;
    i64 sell_notional = 0;
    Objective lb{};
};

bool strictly_better(const SolveResult& result, const std::vector<i64>& trade,
                     const std::vector<i64>& lots, const Objective& obj) {
    int cmp = compare_objective(obj, result.incumbent_obj);
    if (cmp != 0) {
        return cmp < 0;
    }
    cmp = compare_canonical(lots, result.canonical_key);
    if (cmp != 0) {
        return cmp < 0;
    }
    return compare_canonical(trade, result.trade) < 0;
}

Objective lexicographic_min(Objective a, const Objective& b) {
    if (compare_objective(b, a) < 0) {
        return b;
    }
    return a;
}

} // namespace

SolveResult Rebalancer::solve(const Problem& problem) {
    SolveResult result;
    result.status = SolveStatus::Infeasible;
    result.proven = false;
    result.budget = problem.node_budget;

    const std::size_t n = problem.assets.size();
    if (n > kMaxAssets) {
        return result;
    }

    // Constant zero-fee portfolio value: initial_cash + holdings notional.
    wide t0 = static_cast<wide>(problem.initial_cash);
    wide sellable = 0;
    for (const AssetInput& asset : problem.assets) {
        t0 += static_cast<wide>(asset.holding) * asset.price;
        sellable += static_cast<wide>(asset.holding) * asset.price;
    }
    if (t0 <= 0) {
        return result;
    }

    std::array<i64, kMaxAssets> lo_lots{};
    std::array<i64, kMaxAssets> hi_lots{};
    wide funding_cap = static_cast<wide>(problem.initial_cash) + sellable;
    for (std::size_t i = 0; i < n; ++i) {
        const AssetInput& asset = problem.assets[i];
        i64 lo = -(asset.holding / asset.lot);
        wide denom = static_cast<wide>(asset.price) * asset.lot;
        i64 by_cash = static_cast<i64>(funding_cap / denom);
        i64 by_value = static_cast<i64>(t0 / denom);
        i64 hi = std::min(by_cash, by_value);
        if (problem.max_lot_delta > 0) {
            lo = std::max(lo, -problem.max_lot_delta);
            hi = std::min(hi, problem.max_lot_delta);
        }
        if (lo > hi) {
            result.proven = true;
            return result;
        }
        lo_lots[i] = lo;
        hi_lots[i] = hi;
    }

    const wide wscale = static_cast<wide>(kWeightScale);
    i64 max_rate = 0;
    for (const FeeTier& tier : problem.fee_tiers) {
        max_rate = std::max(max_rate, tier.rate);
    }
    // Conservative post-fee denominator: fee(total sides) <= rmax*2*T0/1e6.
    wide denom_min = t0 - t0 * 2 * max_rate / kFeeRateScale;

    auto make_node = [&](i64 depth, const std::array<i64, kMaxAssets>& lots,
                         i64 buy_notional, i64 sell_notional)
        -> std::optional<Node> {
        // Bounds the partial node; returns nullopt if it is provably
        // infeasible at this stage.
        Node node;
        node.depth = depth;
        node.lots = lots;
        node.buy_notional = buy_notional;
        node.sell_notional = sell_notional;

        std::array<i64, kMaxAssets> post{};
        std::array<wide, kMaxAssets> value{};
        for (std::size_t i = 0; i < n; ++i) {
            if (static_cast<i64>(i) < depth) {
                post[i] = problem.assets[i].holding + node.lots[i] * problem.assets[i].lot;
            } else {
                post[i] = problem.assets[i].holding;
            }
            value[i] = static_cast<wide>(post[i]) * problem.assets[i].price;
        }

        // Max-weight of fixed assets: final total is T0 - fee <= T0, so a
        // violation measured against T0 cannot be repaired later.
        for (std::size_t i = 0; i < static_cast<std::size_t>(node.depth); ++i) {
            if (value[i] * wscale >
                static_cast<wide>(problem.assets[i].max_weight) * t0) {
                return std::nullopt;
            }
        }

        // Min-weight of fixed assets: below minimum measured against the
        // largest possible denominator t0 can never recover later.
        for (std::size_t i = 0; i < static_cast<std::size_t>(depth); ++i) {
            if (value[i] * wscale <
                static_cast<wide>(problem.assets[i].min_weight) * t0) {
                return std::nullopt;
            }
        }

        // Same fixed-prefix relaxation for group limits.
        for (const GroupLimit& group : problem.groups) {
            wide group_value = 0;
            wide group_max_extra = 0;
            for (std::size_t i = 0; i < static_cast<std::size_t>(depth); ++i) {
                if (problem.assets[i].group == group.name) {
                    group_value += value[i];
                }
            }
            for (std::size_t i = static_cast<std::size_t>(depth); i < n; ++i) {
                if (problem.assets[i].group == group.name) {
                    wide lot_value =
                        static_cast<wide>(problem.assets[i].lot) * problem.assets[i].price;
                    group_max_extra +=
                        value[i] + static_cast<wide>(hi_lots[i]) * lot_value;
                }
            }
            if (group_value * wscale >
                    static_cast<wide>(group.max_weight) * t0 ||
                (group_value + group_max_extra) * wscale <
                    static_cast<wide>(group.min_weight) * t0) {
                return std::nullopt;
            }
        }

        // Cash relaxation: even selling every remaining lot and paying no
        // further fee, fixed buys must be fundable.
        wide remaining_sellable = 0;
        for (std::size_t i = static_cast<std::size_t>(depth); i < n; ++i) {
            remaining_sellable += value[i];
        }
        wide min_cash = static_cast<wide>(problem.initial_cash) + sell_notional +
                        remaining_sellable - buy_notional;
        if (min_cash < 0) {
            return std::nullopt;
        }

        i64 deviation = 0;
        for (std::size_t i = 0; i < n; ++i) {
            const AssetInput& asset = problem.assets[i];
            wide v_lo;
            wide v_hi;
            if (static_cast<i64>(i) < depth) {
                v_lo = v_hi = value[i];
            } else {
                wide lot_value = static_cast<wide>(asset.lot) * asset.price;
                v_lo = value[i] + static_cast<wide>(lo_lots[i]) * lot_value;
                v_hi = value[i] + static_cast<wide>(hi_lots[i]) * lot_value;
            }
            i64 w_lo = static_cast<i64>(v_lo * wscale / t0);
            // Fees shrink the denominator, so the upper weight must use the
            // smallest possible post-fee denominator (rounding up).
            i64 w_hi = kWeightScale;
            if (denom_min > 0) {
                w_hi = static_cast<i64>((v_hi * wscale + denom_min - 1) / denom_min);
            }
            i64 dist = 0;
            if (asset.target_weight < w_lo) {
                dist = w_lo - asset.target_weight;
            } else if (asset.target_weight > w_hi) {
                dist = asset.target_weight - w_hi;
            }
            deviation += dist;
        }

        i64 fee_lb = 0;
        if (buy_notional > 0) {
            std::optional<i64> fee = fee_for_notional(buy_notional, problem.fee_tiers);
            if (!fee) {
                return std::nullopt;
            }
            fee_lb += *fee;
        }
        if (sell_notional > 0) {
            std::optional<i64> fee =
                fee_for_notional(sell_notional, problem.fee_tiers);
            if (!fee) {
                return std::nullopt;
            }
            fee_lb += *fee;
        }

        i64 turnover_lb = buy_notional + sell_notional;
        node.lb = Objective{deviation, fee_lb, turnover_lb};
        return node;
    };

    std::vector<Node> stack;
    stack.reserve(64);
    std::array<i64, kMaxAssets> zero{};
    std::optional<Node> root = make_node(0, zero, 0, 0);
    if (!root) {
        result.proven = true;
        return result;
    }
    stack.push_back(*root);

    bool budget_hit = false;
    while (!stack.empty()) {
        if (problem.node_budget > 0 && result.stats.nodes >= problem.node_budget) {
            budget_hit = true;
            break;
        }
        Node node = stack.back();
        stack.pop_back();
        ++result.stats.nodes;

        std::vector<i64> partial(node.lots.begin(),
                                 node.lots.begin() + node.depth);

        if (result.has_incumbent) {
            int cmp = compare_objective(node.lb, result.incumbent_obj);
            if (cmp > 0) {
                ++result.stats.pruned_bound;
                result.node_log.push_back(
                    NodeReport{result.stats.nodes, node.depth, partial, node.lb,
                               "pruned-bound"});
                continue;
            }
            if (cmp == 0) {
                // State dominance via the stable tie-break: the smallest
                // canonical key reachable below this node is its prefix
                // followed by the minimum remaining lot counts.
                std::vector<i64> min_key(node.lots.begin(), node.lots.begin() + n);
                for (std::size_t i = static_cast<std::size_t>(node.depth); i < n;
                     ++i) {
                    min_key[i] = lo_lots[i];
                }
                if (compare_canonical(min_key, result.canonical_key) >= 0) {
                    ++result.stats.dominated;
                    result.node_log.push_back(
                        NodeReport{result.stats.nodes, node.depth, partial, node.lb,
                                   "dominated"});
                    continue;
                }
            }
        }

        if (node.depth == static_cast<i64>(n)) {
            ++result.stats.leaves;
            std::vector<i64> trade(n);
            std::vector<i64> lots(node.lots.begin(), node.lots.begin() + n);
            for (std::size_t i = 0; i < n; ++i) {
                trade[i] = lots[i] * problem.assets[i].lot;
            }
            EvalResult eval = evaluate_trade(problem, trade);
            const char* fate = "leaf";
            if (!eval.overflow && eval.feasible &&
                (!result.has_incumbent ||
                 strictly_better(result, trade, lots, eval.objective))) {
                result.has_incumbent = true;
                result.trade = trade;
                result.canonical_key = lots;
                result.incumbent_obj = eval.objective;
                result.slack = eval.slack;
                ++result.stats.incumbent_updates;
            }
            result.node_log.push_back(
                NodeReport{result.stats.nodes, node.depth,
                           std::vector<i64>(node.lots.begin(),
                                            node.lots.begin() + node.depth),
                           node.lb, fate});
            continue;
        }

        std::size_t idx = static_cast<std::size_t>(node.depth);
        // Expand in canonical ascending lot order; push reversed so the
        // smallest child is popped first.
        for (i64 k = hi_lots[idx]; k >= lo_lots[idx]; --k) {
            wide notional_wide = static_cast<wide>(k) * problem.assets[idx].lot *
                                 problem.assets[idx].price;
            if (notional_wide > INT64_MAX || notional_wide < INT64_MIN) {
                continue;
            }
            i64 notional = static_cast<i64>(notional_wide);
            i64 child_buy = node.buy_notional;
            i64 child_sell = node.sell_notional;
            if (notional > 0) {
                if (static_cast<wide>(child_buy) + notional > INT64_MAX) {
                    continue;
                }
                child_buy += notional;
            } else if (notional < 0) {
                if (static_cast<wide>(child_sell) + (-notional) > INT64_MAX) {
                    continue;
                }
                child_sell += -notional;
            }
            std::array<i64, kMaxAssets> child_lots = node.lots;
            child_lots[idx] = k;
            std::optional<Node> child =
                make_node(node.depth + 1, child_lots, child_buy, child_sell);
            if (!child) {
                ++result.stats.pruned_infeasible;
            } else {
                stack.push_back(*child);
            }
            if (k == lo_lots[idx]) {
                break;
            }
        }
        result.node_log.push_back(
            NodeReport{result.stats.nodes, node.depth, partial, node.lb,
                       "branched"});
    }

    if (budget_hit) {
        result.status = SolveStatus::BudgetExhausted;
        result.lower_bound = result.has_incumbent ? result.incumbent_obj : Objective{};
        for (const Node& pending : stack) {
            result.lower_bound =
                lexicographic_min(result.lower_bound, pending.lb);
        }
        // A budget-exhausted run never certifies or replaces a full result.
        return result;
    }

    result.proven = true;
    result.lower_bound = result.incumbent_obj;
    if (result.has_incumbent) {
        result.status = SolveStatus::ProvedOptimal;
    }
    last_proven_ = result;
    return result;
}

} // namespace rebal
