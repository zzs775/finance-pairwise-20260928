#include "config.hpp"

#include <algorithm>
#include <cctype>
#include <charconv>
#include <fstream>
#include "stats.hpp"
#include <sstream>
#include <string>
#include <string_view>
#include <unordered_map>

namespace mcopt {

namespace {

std::string trim(std::string text) {
    auto begin = std::find_if_not(text.begin(), text.end(),
                                  [](unsigned char ch) {
                                      return std::isspace(ch) != 0;
                                  });
    auto end = std::find_if_not(text.rbegin(), text.rend(),
                                [](unsigned char ch) {
                                    return std::isspace(ch) != 0;
                                }).base();
    return begin < end ? std::string(begin, end) : std::string();
}

std::string toLower(std::string text) {
    for (char& ch : text) {
        ch = static_cast<char>(std::tolower(static_cast<unsigned char>(ch)));
    }
    return text;
}

void configError(const std::string& message) {
    throw McError(ErrorKind::InvalidConfig, message);
}

double requireDouble(const std::unordered_map<std::string, std::string>& kv,
                     const std::string& key) {
    const auto it = kv.find(key);
    if (it == kv.end()) {
        configError("missing required parameter: " + key);
    }
    try {
        std::size_t consumed = 0;
        const double value = std::stod(it->second, &consumed);
        if (consumed != it->second.size()) {
            configError("unparsed trailing characters in " + key);
        }
        return value;
    } catch (const std::exception&) {
        configError("invalid numeric value for " + key);
    }
    return 0.0;
}

double optionalDouble(
    const std::unordered_map<std::string, std::string>& kv,
    const std::string& key, double fallback) {
    if (kv.find(key) == kv.end()) {
        return fallback;
    }
    return requireDouble(kv, key);
}

std::uint64_t requireUint(
    const std::unordered_map<std::string, std::string>& kv,
    const std::string& key) {
    const auto it = kv.find(key);
    if (it == kv.end()) {
        configError("missing required parameter: " + key);
    }
    std::uint64_t value = 0;
    const auto* first = it->second.data();
    const auto result = std::from_chars(first, first + it->second.size(), value);
    if (result.ec != std::errc{} || result.ptr != first + it->second.size()) {
        configError("invalid unsigned integer for " + key);
    }
    return value;
}

std::uint64_t optionalUint(
    const std::unordered_map<std::string, std::string>& kv,
    const std::string& key, std::uint64_t fallback) {
    if (kv.find(key) == kv.end()) {
        return fallback;
    }
    return requireUint(kv, key);
}

std::string requireString(
    const std::unordered_map<std::string, std::string>& kv,
    const std::string& key) {
    const auto it = kv.find(key);
    if (it == kv.end()) {
        configError("missing required parameter: " + key);
    }
    return it->second;
}

std::string optionalString(
    const std::unordered_map<std::string, std::string>& kv,
    const std::string& key, const std::string& fallback) {
    const auto it = kv.find(key);
    return it == kv.end() ? fallback : it->second;
}

ContractType parseContractType(const std::string& value) {
    const std::string lowered = toLower(value);
    if (lowered == "european" || lowered == "euro") {
        return ContractType::European;
    }
    if (lowered == "asian" || lowered == "asian_arithmetic" ||
        lowered == "arithmetic_asian") {
        return ContractType::AsianArithmetic;
    }
    if (lowered == "barrier" || lowered == "single_barrier") {
        return ContractType::SingleBarrier;
    }
    configError("unknown contract type: " + value);
    return ContractType::European;
}

OptionRight parseRight(const std::string& value) {
    const std::string lowered = toLower(value);
    if (lowered == "call" || lowered == "c") {
        return OptionRight::Call;
    }
    if (lowered == "put" || lowered == "p") {
        return OptionRight::Put;
    }
    configError("option right must be call or put: " + value);
    return OptionRight::Call;
}

BarrierKind parseBarrierKind(const std::string& value) {
    const std::string lowered = toLower(value);
    if (lowered == "up_and_out" || lowered == "upout" || lowered == "up-out") {
        return BarrierKind::UpAndOut;
    }
    if (lowered == "down_and_out" || lowered == "downout" ||
        lowered == "down-out") {
        return BarrierKind::DownAndOut;
    }
    if (lowered == "up_and_in" || lowered == "upin" || lowered == "up-in") {
        return BarrierKind::UpAndIn;
    }
    if (lowered == "down_and_in" || lowered == "downin" ||
        lowered == "down-in") {
        return BarrierKind::DownAndIn;
    }
    configError("unknown barrier kind: " + value);
    return BarrierKind::UpAndOut;
}

}  // namespace

SimConfig loadConfig(const std::string& path) {
    std::ifstream input(path);
    if (!input) {
        throw McError(ErrorKind::IoError,
                      "cannot open configuration file: " + path);
    }

    std::unordered_map<std::string, std::string> kv;
    std::string line;
    std::uint64_t lineNumber = 0;
    while (std::getline(input, line)) {
        ++lineNumber;
        const std::string trimmed = trim(line);
        if (trimmed.empty() || trimmed[0] == '#' || trimmed[0] == ';' ||
            trimmed[0] == '[') {
            continue;
        }
        const auto equals = trimmed.find('=');
        if (equals == std::string::npos) {
            std::ostringstream out;
            out << "malformed line " << lineNumber << ": " << trimmed;
            configError(out.str());
        }
        const std::string key = trim(trimmed.substr(0, equals));
        const std::string value = trim(trimmed.substr(equals + 1));
        if (key.empty()) {
            std::ostringstream out;
            out << "empty key on line " << lineNumber;
            configError(out.str());
        }
        kv[key] = value;
    }

    SimConfig config;
    config.contract.type = parseContractType(requireString(kv, "type"));
    config.contract.right = parseRight(requireString(kv, "right"));
    config.contract.spot = requireDouble(kv, "spot");
    config.contract.strike = requireDouble(kv, "strike");
    config.contract.rate = optionalDouble(kv, "rate", 0.0);
    config.contract.dividend = optionalDouble(kv, "dividend", 0.0);
    config.contract.volatility = requireDouble(kv, "volatility");
    config.contract.maturity = requireDouble(kv, "maturity");
    config.contract.timeSteps = requireUint(kv, "steps");

    if (config.contract.type == ContractType::SingleBarrier) {
        config.contract.barrierKind =
            parseBarrierKind(requireString(kv, "barrier"));
        config.contract.barrierLevel = requireDouble(kv, "barrier_level");
        config.contract.rebate = optionalDouble(kv, "rebate", 0.0);
    } else {
        if (kv.find("barrier") != kv.end()) {
            config.contract.barrierKind =
                parseBarrierKind(requireString(kv, "barrier"));
        }
        config.contract.barrierLevel =
            optionalDouble(kv, "barrier_level", 0.0);
        config.contract.rebate = optionalDouble(kv, "rebate", 0.0);
    }

    config.seed = optionalUint(kv, "seed", 0);
    config.paths = optionalUint(kv, "paths", 0);
    config.threads = static_cast<unsigned>(optionalUint(kv, "threads", 1));
    if (config.threads == 0) {
        configError("threads must be >= 1");
    }
    config.chunkPaths = optionalUint(kv, "chunk_paths", kPathsPerBlock);
    if (config.chunkPaths == 0) {
        configError("chunk_paths must be >= 1");
    }
    config.memoryBudgetBytes =
        optionalUint(kv, "memory_budget_bytes", 512ULL * 1024ULL * 1024ULL);
    config.confidenceLevel = optionalDouble(kv, "confidence", 0.95);
    if (!(config.confidenceLevel > 0.0 && config.confidenceLevel < 1.0)) {
        configError("confidence level must lie strictly between 0 and 1");
    }
    config.checkpointPath =
        optionalString(kv, "checkpoint", "");
    config.outputPath = optionalString(kv, "output", "");

    constexpr std::uint64_t kMaxPaths =
        (static_cast<std::uint64_t>(-1) / kPathsPerBlock - 1) *
            kPathsPerBlock;
    if (config.paths > kMaxPaths) {
        throw McError(ErrorKind::SampleOverflow,
                      "requested path count exceeds the supported maximum");
    }
    return config;
}

}  // namespace mcopt
