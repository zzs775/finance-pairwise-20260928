#include "rebalancer/model.hpp"

#include <algorithm>

#include "rebalancer/fees.hpp"

namespace rebal {

namespace {

using wide = __int128;

wide to_wide(i64 v) {
    return static_cast<wide>(v);
}

} // namespace

EvalResult evaluate_trade(const Problem& problem, const std::vector<i64>& trade) {
    EvalResult result;
    const i64 n = static_cast<i64>(problem.assets.size());
    if (static_cast<i64>(trade.size()) != n) {
        result.overflow = true;
        return result;
    }

    std::vector<i64> post_holding(static_cast<std::size_t>(n));
    i64 buy = 0;
    i64 sell = 0;
    for (i64 i = 0; i < n; ++i) {
        const AssetInput& asset = problem.assets[static_cast<std::size_t>(i)];
        i64 delta = trade[static_cast<std::size_t>(i)];
        if (asset.lot != 0 && delta % asset.lot != 0) {
            return result;
        }
        i64 new_holding = 0;
        if (!add_checked(asset.holding, delta, new_holding) || new_holding < 0) {
            return result;
        }
        post_holding[static_cast<std::size_t>(i)] = new_holding;
        i64 notional = 0;
        if (!mul_checked(delta, asset.price, notional)) {
            result.overflow = true;
            return result;
        }
        if (notional > 0) {
            if (!add_checked(buy, notional, buy)) {
                result.overflow = true;
                return result;
            }
        } else if (notional < 0) {
            if (!add_checked(sell, -notional, sell)) {
                result.overflow = true;
                return result;
            }
        }
    }

    std::optional<i64> buy_fee = fee_for_notional(buy, problem.fee_tiers);
    std::optional<i64> sell_fee = fee_for_notional(sell, problem.fee_tiers);
    if (!buy_fee || !sell_fee) {
        result.overflow = true;
        return result;
    }
    i64 fee = 0;
    if (!add_checked(*buy_fee, *sell_fee, fee)) {
        result.overflow = true;
        return result;
    }

    i64 post_cash = 0;
    i64 interim = 0;
    if (!add_checked(problem.initial_cash, sell, interim) ||
        !add_checked(interim, -buy, interim) ||
        !add_checked(interim, -fee, post_cash)) {
        result.overflow = true;
        return result;
    }
    if (post_cash < 0) {
        result.slack.post_cash = post_cash;
        result.slack.cash_slack = post_cash;
        result.slack.funding_slack = post_cash;
        return result;
    }

    std::vector<wide> values(static_cast<std::size_t>(n));
    wide total = to_wide(post_cash);
    for (i64 i = 0; i < n; ++i) {
        values[static_cast<std::size_t>(i)] =
            to_wide(post_holding[static_cast<std::size_t>(i)]) *
            to_wide(problem.assets[static_cast<std::size_t>(i)].price);
        total += values[static_cast<std::size_t>(i)];
    }
    if (total <= 0) {
        return result;
    }

    const wide wscale = to_wide(kWeightScale);
    bool feasible = true;
    i64 deviation = 0;
    result.slack.assets.reserve(static_cast<std::size_t>(n));
    for (i64 i = 0; i < n; ++i) {
        const AssetInput& asset = problem.assets[static_cast<std::size_t>(i)];
        wide value = values[static_cast<std::size_t>(i)];
        wide weight_wide = value * wscale / total;
        i64 weight = static_cast<i64>(weight_wide);
        wide min_product = to_wide(asset.min_weight) * total;
        wide max_product = to_wide(asset.max_weight) * total;
        if (value * wscale < min_product || value * wscale > max_product) {
            feasible = false;
        }
        i64 diff = 0;
        if (weight > asset.target_weight) {
            diff = weight - asset.target_weight;
        } else {
            diff = asset.target_weight - weight;
        }
        deviation += diff;
        result.slack.assets.push_back(AssetSlack{
            asset.id, weight, weight - asset.min_weight, asset.max_weight - weight});
    }

    result.slack.groups.reserve(problem.groups.size());
    for (const GroupLimit& group : problem.groups) {
        wide group_value = 0;
        for (i64 i = 0; i < n; ++i) {
            if (problem.assets[static_cast<std::size_t>(i)].group == group.name) {
                group_value += values[static_cast<std::size_t>(i)];
            }
        }
        i64 group_weight = static_cast<i64>(group_value * wscale / total);
        if (group_value * wscale < to_wide(group.min_weight) * total ||
            group_value * wscale > to_wide(group.max_weight) * total) {
            feasible = false;
        }
        result.slack.groups.push_back(GroupSlack{
            group.name, group_weight, group_weight - group.min_weight,
            group.max_weight - group_weight});
    }

    i64 turnover = 0;
    if (!add_checked(buy, sell, turnover)) {
        result.overflow = true;
        return result;
    }

    result.feasible = feasible;
    result.objective = Objective{deviation, fee, turnover};
    result.slack.post_cash = post_cash;
    result.slack.cash_slack = post_cash;
    result.slack.funding_slack = interim;
    return result;
}

} // namespace rebal
