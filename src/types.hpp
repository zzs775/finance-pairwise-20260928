#pragma once

#include <cstdint>
#include <stdexcept>
#include <string>
#include <utility>

namespace mcopt {

enum class ContractType { European, AsianArithmetic, SingleBarrier };

enum class OptionRight { Call, Put };

enum class BarrierKind { UpAndOut, DownAndOut, UpAndIn, DownAndIn };

struct Contract {
    ContractType type = ContractType::European;
    OptionRight right = OptionRight::Call;
    double spot = 0.0;
    double strike = 0.0;
    double rate = 0.0;
    double dividend = 0.0;
    double volatility = 0.0;
    double maturity = 0.0;
    std::uint64_t timeSteps = 0;
    BarrierKind barrierKind = BarrierKind::UpAndOut;
    double barrierLevel = 0.0;
    double rebate = 0.0;
};

enum class ErrorKind {
    NonFiniteParameter,
    DegenerateMaturity,
    IllegalBarrier,
    SampleOverflow,
    MemoryBudgetExceeded,
    InvalidConfig,
    CheckpointSummaryMismatch,
    CheckpointCorrupt,
    IoError,
    Cancelled,
};

struct McError : std::runtime_error {
    ErrorKind kind;
    McError(ErrorKind k, std::string message)
        : std::runtime_error(std::move(message)), kind(k) {}
};

}  // namespace mcopt
