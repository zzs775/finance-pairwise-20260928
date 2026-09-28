#include "rebalancer/io.hpp"

#include <map>
#include <sstream>
#include <vector>

namespace rebal {

namespace {

std::vector<std::string> split_ws(const std::string& line) {
    std::vector<std::string> tokens;
    std::istringstream stream(line);
    std::string token;
    while (stream >> token) {
        tokens.push_back(token);
    }
    return tokens;
}

bool parse_i64(const std::string& token, i64& value, std::string& error) {
    try {
        std::size_t consumed = 0;
        value = std::stoll(token, &consumed);
        if (consumed != token.size()) {
            error = "not an integer: " + token;
            return false;
        }
    } catch (const std::exception&) {
        error = "not an integer: " + token;
        return false;
    }
    return true;
}

} // namespace

ParseResult parse_problem_text(const std::string& text) {
    ParseResult result;
    std::vector<AssetInput> assets;
    std::map<std::string, AssetInput> by_id;
    std::map<std::string, GroupLimit> groups;
    std::vector<FeeTier> tiers;

    std::istringstream stream(text);
    std::string line;
    std::size_t line_no = 0;
    while (std::getline(stream, line)) {
        ++line_no;
        if (!line.empty() && line.back() == '\r') {
            line.pop_back();
        }
        std::vector<std::string> tokens = split_ws(line);
        if (tokens.empty() || tokens[0].empty() || tokens[0][0] == '#') {
            continue;
        }
        const std::string& key = tokens[0];
        auto need = [&](std::size_t count) -> bool {
            if (tokens.size() != count) {
                result.error = "line " + std::to_string(line_no) +
                               ": expected " + std::to_string(count - 1) +
                               " arguments for " + key;
                return false;
            }
            return true;
        };
        auto integer = [&](std::size_t index, i64& value) -> bool {
            if (!parse_i64(tokens[index], value, result.error)) {
                result.error = "line " + std::to_string(line_no) + ": " + result.error;
                return false;
            }
            return true;
        };

        if (key == "cash") {
            if (!need(2) || !integer(1, result.problem.initial_cash)) {
                return result;
            }
        } else if (key == "budget") {
            if (!need(2) || !integer(1, result.problem.node_budget)) {
                return result;
            }
        } else if (key == "window") {
            if (!need(2) || !integer(1, result.problem.max_lot_delta)) {
                return result;
            }
        } else if (key == "oracle") {
            if (!need(2) || !integer(1, result.oracle_window)) {
                return result;
            }
        } else if (key == "asset") {
            if (!need(8)) {
                return result;
            }
            AssetInput asset;
            asset.id = tokens[1];
            if (by_id.count(asset.id)) {
                result.error = "line " + std::to_string(line_no) +
                               ": duplicate asset id: " + asset.id;
                return result;
            }
            if (!integer(2, asset.price) || !integer(3, asset.holding) ||
                !integer(4, asset.lot) || !integer(5, asset.target_weight) ||
                !integer(6, asset.min_weight) || !integer(7, asset.max_weight)) {
                return result;
            }
            by_id[asset.id] = asset;
            assets.push_back(asset);
        } else if (key == "groupof") {
            if (!need(3)) {
                return result;
            }
            auto found = by_id.find(tokens[1]);
            if (found == by_id.end()) {
                result.error = "line " + std::to_string(line_no) +
                               ": unknown asset id: " + tokens[1];
                return result;
            }
            for (AssetInput& asset : assets) {
                if (asset.id == tokens[1]) {
                    asset.group = tokens[2];
                }
            }
            if (!groups.count(tokens[2])) {
                groups[tokens[2]] = GroupLimit{tokens[2], 0, kWeightScale};
            }
        } else if (key == "grouplimit") {
            if (!need(4)) {
                return result;
            }
            i64 min_weight = 0;
            i64 max_weight = 0;
            if (!integer(2, min_weight) || !integer(3, max_weight)) {
                return result;
            }
            groups[tokens[1]] = GroupLimit{tokens[1], min_weight, max_weight};
        } else if (key == "fee") {
            if (!need(4)) {
                return result;
            }
            i64 lo = 0;
            i64 hi = 0;
            i64 rate = 0;
            if (!integer(1, lo) || !integer(3, rate)) {
                return result;
            }
            if (tokens[2] == "inf") {
                hi = kOpenEnd;
            } else if (!integer(2, hi)) {
                return result;
            }
            tiers.push_back(FeeTier{lo, hi, rate});
        } else {
            result.error = "line " + std::to_string(line_no) +
                           ": unknown directive: " + key;
            return result;
        }
    }

    result.problem.assets = std::move(assets);
    for (auto& entry : groups) {
        result.problem.groups.push_back(entry.second);
    }
    result.problem.fee_tiers = std::move(tiers);
    result.ok = true;
    return result;
}

namespace {

const char* status_name(SolveStatus status) {
    switch (status) {
    case SolveStatus::ProvedOptimal:
        return "proved_optimal";
    case SolveStatus::BudgetExhausted:
        return "budget_exhausted";
    case SolveStatus::Infeasible:
        return "infeasible";
    }
    return "unknown";
}

} // namespace

std::string render_result(const Problem& problem, const SolveResult& result) {
    std::ostringstream out;
    out << "status " << status_name(result.status) << "\n";
    out << "proven " << (result.proven ? 1 : 0) << "\n";
    out << "objective " << result.incumbent_obj.weight_deviation << " "
        << result.incumbent_obj.total_fee << " "
        << result.incumbent_obj.turnover << "\n";
    out << "lower_bound " << result.lower_bound.weight_deviation << " "
        << result.lower_bound.total_fee << " "
        << result.lower_bound.turnover << "\n";
    out << "nodes " << result.stats.nodes << " leaves " << result.stats.leaves
        << " pruned_bound " << result.stats.pruned_bound
        << " pruned_infeasible " << result.stats.pruned_infeasible
        << " dominated " << result.stats.dominated
        << " incumbent_updates " << result.stats.incumbent_updates << "\n";
    if (result.has_incumbent) {
        for (std::size_t i = 0; i < problem.assets.size(); ++i) {
            out << "trade " << problem.assets[i].id << " "
                << result.trade[i] << " weight "
                << result.slack.assets[i].post_weight
                << " slack_min " << result.slack.assets[i].min_slack
                << " slack_max " << result.slack.assets[i].max_slack << "\n";
        }
        for (const GroupSlack& slack : result.slack.groups) {
            out << "group " << slack.name << " weight " << slack.post_weight
                << " slack_min " << slack.min_slack
                << " slack_max " << slack.max_slack << "\n";
        }
        out << "cash " << result.slack.post_cash
            << " slack " << result.slack.cash_slack
            << " funding_slack " << result.slack.funding_slack << "\n";
    }
    return out.str();
}

} // namespace rebal
