#include "parser.h"

#include <cctype>
#include <set>
#include <sstream>
#include <stdexcept>

namespace blockalloc {

namespace {

std::string trim(const std::string& s) {
    size_t b = 0;
    size_t e = s.size();
    while (b < e && std::isspace(static_cast<unsigned char>(s[b]))) ++b;
    while (e > b && std::isspace(static_cast<unsigned char>(s[e - 1]))) --e;
    return s.substr(b, e - b);
}

std::string lower(std::string s) {
    for (char& c : s) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return s;
}

bool parse_int(const std::string& token, qty_t& out) {
    std::string s = trim(token);
    if (s.empty()) return false;
    size_t i = 0;
    if (s[0] == '+' || s[0] == '-') i = 1;
    if (i == s.size()) return false;
    for (; i < s.size(); ++i) {
        if (!std::isdigit(static_cast<unsigned char>(s[i]))) return false;
    }
    try {
        out = static_cast<qty_t>(std::stoll(s));
    } catch (...) {
        return false;
    }
    return true;
}

bool parse_bool(const std::string& token, bool& out) {
    std::string s = lower(trim(token));
    if (s == "yes" || s == "true" || s == "1" || s == "on") { out = true; return true; }
    if (s == "no" || s == "false" || s == "0" || s == "off") { out = false; return true; }
    return false;
}

qty_t round_up(qty_t v, qty_t m) {
    return ((v + m - 1) / m) * m;
}

}  // namespace

ParseReport parse_instance(const std::string& text) {
    ParseReport report;
    Instance& inst = report.instance;
    inst.total_qty = 0;

    std::set<std::string> seen_ids;
    Account cur;
    bool have_account = false;
    bool target_set = false;
    bool max_set = false;
    bool prior_set = false;

    auto finalize_account = [&]() -> std::string {
        if (!have_account) return {};
        if (cur.id.empty()) return "account declared without id";
        if (!target_set) return "account " + cur.id + ": target is required";
        if (!max_set) return "account " + cur.id + ": max is required";
        if (cur.lot_multiple < 1) return "account " + cur.id + ": lot must be >= 1";
        if (cur.min_qty < 0) return "account " + cur.id + ": min must be >= 0";
        if (cur.max_qty < 0) return "account " + cur.id + ": max must be >= 0";
        if (cur.target < 0) return "account " + cur.id + ": target must be >= 0";
        if (cur.min_qty > cur.max_qty)
            return "account " + cur.id + ": min exceeds max";
        if (round_up(cur.min_qty, cur.lot_multiple) > cur.max_qty)
            return "account " + cur.id + ": no lot-aligned quantity within [min,max]";
        if (seen_ids.count(cur.id)) return "duplicate account id: " + cur.id;
        if (!prior_set) cur.prior_qty = cur.target;
        if (cur.prior_qty < 0) return "account " + cur.id + ": prior must be >= 0";
        seen_ids.insert(cur.id);
        inst.accounts.push_back(cur);
        return {};
    };

    std::istringstream ss(text);
    std::string line;
    int lineno = 0;
    while (std::getline(ss, line)) {
        ++lineno;
        std::string raw = trim(line);
        if (raw.empty() || raw[0] == '#') continue;
        size_t colon = raw.find(':');
        if (colon == std::string::npos) {
            report.error = "line " + std::to_string(lineno) + ": expected 'key: value'";
            return report;
        }
        std::string key = lower(trim(raw.substr(0, colon)));
        std::string val = trim(raw.substr(colon + 1));

        if (key == "account" || key == "id") {
            std::string err = finalize_account();
            if (!err.empty()) { report.error = "line " + std::to_string(lineno) + ": " + err; return report; }
            cur = Account{};
            cur.id = trim(val);
            have_account = true;
            target_set = false;
            max_set = false;
            prior_set = false;
            continue;
        }

        if (!have_account) {
            if (key == "name") {
                if (val.empty()) { report.error = "line " + std::to_string(lineno) + ": empty name"; return report; }
                inst.name = val;
            } else if (key == "total") {
                qty_t v;
                if (!parse_int(val, v) || v < 0) {
                    report.error = "line " + std::to_string(lineno) + ": invalid total";
                    return report;
                }
                inst.total_qty = v;
            } else {
                report.error = "line " + std::to_string(lineno) + ": unknown header key '" + key + "'";
                return report;
            }
            continue;
        }

        auto fail = [&](const std::string& what) {
            report.error = "line " + std::to_string(lineno) + ": account " + cur.id + ": " + what;
        };

        qty_t iv;
        bool bv;
        if (key == "target") {
            if (!parse_int(val, iv)) { fail("invalid target"); return report; }
            cur.target = iv; target_set = true;
        } else if (key == "min") {
            if (!parse_int(val, iv)) { fail("invalid min"); return report; }
            cur.min_qty = iv;
        } else if (key == "max") {
            if (!parse_int(val, iv)) { fail("invalid max"); return report; }
            cur.max_qty = iv; max_set = true;
        } else if (key == "lot" || key == "lot_multiple") {
            if (!parse_int(val, iv)) { fail("invalid lot"); return report; }
            cur.lot_multiple = iv;
        } else if (key == "eligible") {
            if (!parse_bool(val, bv)) { fail("invalid eligible (yes/no)"); return report; }
            cur.eligible = bv;
        } else if (key == "priority") {
            if (!parse_int(val, iv)) { fail("invalid priority"); return report; }
            cur.priority = static_cast<int>(iv);
        } else if (key == "prior") {
            if (!parse_int(val, iv)) { fail("invalid prior"); return report; }
            cur.prior_qty = iv; prior_set = true;
        } else {
            fail("unknown key '" + key + "'");
            return report;
        }
    }

    std::string err = finalize_account();
    if (!err.empty()) { report.error = err; return report; }

    if (inst.accounts.empty()) {
        report.error = "no accounts declared";
        return report;
    }

    report.ok = true;
    return report;
}

}  // namespace blockalloc
