#include "contract.hpp"

#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <initializer_list>
#include <string>

namespace mcopt {

namespace {

bool finiteAll(std::initializer_list<double> values) {
    for (double value : values) {
        if (!std::isfinite(value)) {
            return false;
        }
    }
    return true;
}

std::string hexDouble(double value) {
    static_assert(sizeof(double) == sizeof(std::uint64_t),
                  "double must be 64-bit");
    std::uint64_t bits;
    std::memcpy(&bits, &value, sizeof(bits));
    char buffer[19];
    std::snprintf(buffer, sizeof(buffer), "%016llx",
                  static_cast<unsigned long long>(bits));
    return std::string(buffer);
}

const char* typeName(ContractType type) {
    switch (type) {
        case ContractType::European:
            return "european";
        case ContractType::AsianArithmetic:
            return "asian_arithmetic";
        case ContractType::SingleBarrier:
            return "single_barrier";
    }
    return "unknown";
}

const char* rightName(OptionRight right) {
    return right == OptionRight::Call ? "call" : "put";
}

const char* barrierName(BarrierKind kind) {
    switch (kind) {
        case BarrierKind::UpAndOut:
            return "up_and_out";
        case BarrierKind::DownAndOut:
            return "down_and_out";
        case BarrierKind::UpAndIn:
            return "up_and_in";
        case BarrierKind::DownAndIn:
            return "down_and_in";
    }
    return "unknown";
}

void fail(ErrorKind kind, const std::string& message) {
    throw McError(kind, message);
}

}  // namespace

void validateContract(const Contract& contract) {
    if (!finiteAll({contract.spot, contract.strike, contract.rate,
                    contract.dividend, contract.volatility,
                    contract.maturity, contract.barrierLevel,
                    contract.rebate})) {
        fail(ErrorKind::NonFiniteParameter,
             "contract contains a non-finite numeric parameter");
    }
    if (contract.spot <= 0.0 || contract.strike <= 0.0) {
        fail(ErrorKind::NonFiniteParameter,
             "spot and strike must be strictly positive");
    }
    if (contract.volatility < 0.0) {
        fail(ErrorKind::NonFiniteParameter, "volatility must be >= 0");
    }
    if (contract.maturity <= 0.0) {
        fail(ErrorKind::DegenerateMaturity,
             "maturity must be strictly positive");
    }
    if (contract.timeSteps == 0) {
        fail(ErrorKind::DegenerateMaturity,
             "time steps must be strictly positive");
    }
    if (contract.type == ContractType::SingleBarrier) {
        const bool up = contract.barrierKind == BarrierKind::UpAndOut ||
                        contract.barrierKind == BarrierKind::UpAndIn;
        if (contract.barrierLevel <= 0.0) {
            fail(ErrorKind::IllegalBarrier,
                 "barrier level must be strictly positive");
        }
        if (up) {
            if (!(contract.barrierLevel > contract.spot)) {
                fail(ErrorKind::IllegalBarrier,
                     "up barrier must lie strictly above spot");
            }
        } else {
            if (!(contract.barrierLevel < contract.spot)) {
                fail(ErrorKind::IllegalBarrier,
                     "down barrier must lie strictly below spot");
            }
        }
    }
}

std::string contractSummary(const Contract& contract) {
    char line[1024];
    const int written = std::snprintf(
        line, sizeof(line),
        "type=%s right=%s spot=%s strike=%s rate=%s dividend=%s vol=%s "
        "maturity=%s steps=%llu barrier=%s level=%s rebate=%s",
        typeName(contract.type), rightName(contract.right),
        hexDouble(contract.spot).c_str(), hexDouble(contract.strike).c_str(),
        hexDouble(contract.rate).c_str(),
        hexDouble(contract.dividend).c_str(),
        hexDouble(contract.volatility).c_str(),
        hexDouble(contract.maturity).c_str(),
        static_cast<unsigned long long>(contract.timeSteps),
        barrierName(contract.barrierKind),
        hexDouble(contract.barrierLevel).c_str(),
        hexDouble(contract.rebate).c_str());
    (void)written;
    return line;
}

}  // namespace mcopt
