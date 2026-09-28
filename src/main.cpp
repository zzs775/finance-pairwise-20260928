#include <cstdint>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

#include "varbt/checkpoint.hpp"
#include "varbt/engine.hpp"
#include "varbt/fixed_point.hpp"
#include "varbt/report.hpp"

using namespace varbt;

namespace {

const char* kUsage =
    "usage:\n"
    "  varbt-cli init    <db> <window> <level_num> <level_den>\n"
    "  varbt-cli add     <db> <day> <version> <var> <pnl> <seq>\n"
    "  varbt-cli correct <db> <day> <version> <var> <pnl> <seq>\n"
    "  varbt-cli erase   <db> <day> <version>\n"
    "  varbt-cli report  <db>\n"
    "values are decimals with up to 6 fractional digits (fixed point);\n"
    "VaR is a non-negative loss amount, PnL is signed.\n";

bool parseInt64(const std::string& text, int64_t& out) {
    try {
        size_t used = 0;
        long long parsed = std::stoll(text, &used);
        if (used != text.size()) {
            return false;
        }
        out = static_cast<int64_t>(parsed);
        return true;
    } catch (...) {
        return false;
    }
}

bool parseUint64(const std::string& text, uint64_t& out) {
    try {
        size_t used = 0;
        unsigned long long parsed = std::stoull(text, &used);
        if (used != text.size()) {
            return false;
        }
        out = static_cast<uint64_t>(parsed);
        return true;
    } catch (...) {
        return false;
    }
}

bool parseObservation(const std::vector<std::string>& args, size_t at,
                      Observation& obs, ErrorCode& numeric_error) {
    if (args.size() < at + 5) {
        return false;
    }
    if (!parseInt64(args[at], obs.day)) {
        numeric_error = ErrorCode::InvalidNumeric;
        return false;
    }
    obs.version = args[at + 1];
    ErrorCode var_error = parseFixed(args[at + 2], kFracDigits, obs.var);
    ErrorCode pnl_error = parseFixed(args[at + 3], kFracDigits, obs.pnl);
    if (var_error != ErrorCode::Ok) {
        numeric_error = var_error;
        return false;
    }
    if (pnl_error != ErrorCode::Ok) {
        numeric_error = pnl_error;
        return false;
    }
    if (!parseUint64(args[at + 4], obs.seq)) {
        numeric_error = ErrorCode::InvalidNumeric;
        return false;
    }
    return true;
}

int fail(ErrorCode code, const std::string& detail = {}) {
    std::cerr << errorName(code);
    if (!detail.empty()) {
        std::cerr << ": " << detail;
    }
    std::cerr << "\n";
    return 1;
}

int loadOrFail(const std::string& db, std::unique_ptr<Engine>& engine) {
    std::string message;
    ErrorCode error = loadCheckpoint(db, engine, message);
    if (error != ErrorCode::Ok) {
        std::cerr << errorName(error) << ": " << message << "\n";
        return 1;
    }
    return 0;
}

int saveOrFail(const Engine& engine, const std::string& db) {
    std::string message;
    ErrorCode error = saveCheckpoint(engine, db, message);
    if (error != ErrorCode::Ok) {
        std::cerr << errorName(error) << ": " << message << "\n";
        return 1;
    }
    return 0;
}

} // namespace

int main(int argc, char** argv) {
    std::vector<std::string> args;
    args.reserve(static_cast<size_t>(argc));
    for (int i = 0; i < argc; ++i) {
        args.emplace_back(argv[i]);
    }
    if (args.size() < 2) {
        std::cerr << kUsage;
        return 2;
    }
    const std::string& command = args[1];

    if (command == "init" && args.size() == 6) {
        int64_t window = 0;
        int64_t num = 0;
        int64_t den = 0;
        if (!parseInt64(args[3], window) || !parseInt64(args[4], num) ||
            !parseInt64(args[5], den)) {
            return fail(ErrorCode::InvalidNumeric);
        }
        Params params{window, num, den};
        if (validateParams(params) != ErrorCode::Ok) {
            return fail(ErrorCode::IllegalWindow);
        }
        Engine engine(params);
        return saveOrFail(engine, args[2]);
    }

    if ((command == "add" && args.size() == 8) ||
        (command == "correct" && args.size() == 8)) {
        std::unique_ptr<Engine> engine;
        int load_result = loadOrFail(args[2], engine);
        if (load_result != 0) {
            return load_result;
        }
        Observation observation;
        ErrorCode numeric_error = ErrorCode::Ok;
        if (!parseObservation(args, 3, observation, numeric_error)) {
            return fail(numeric_error);
        }
        ErrorCode result = command == "add"
                               ? engine->insert(observation)
                               : engine->correct(observation);
        if (result != ErrorCode::Ok) {
            return fail(result);
        }
        return saveOrFail(*engine, args[2]);
    }

    if (command == "erase" && args.size() == 5) {
        std::unique_ptr<Engine> engine;
        int load_result = loadOrFail(args[2], engine);
        if (load_result != 0) {
            return load_result;
        }
        int64_t day = 0;
        if (!parseInt64(args[3], day)) {
            return fail(ErrorCode::InvalidNumeric);
        }
        ErrorCode result = engine->erase(day, args[4]);
        if (result != ErrorCode::Ok) {
            return fail(result);
        }
        return saveOrFail(*engine, args[2]);
    }

    if (command == "report" && args.size() == 3) {
        std::unique_ptr<Engine> engine;
        int load_result = loadOrFail(args[2], engine);
        if (load_result != 0) {
            return load_result;
        }
        std::string report;
        std::string message;
        ErrorCode error = renderReport(*engine, report, message);
        if (error != ErrorCode::Ok) {
            return fail(error, message);
        }
        std::cout << report;
        return 0;
    }

    std::cerr << kUsage;
    return 2;
}
