#pragma once

#include <cstdint>
#include <string>

#include "varbt/types.hpp"

namespace varbt {

// Parses a decimal string into fixed-point minor units.
// Rejects malformed input (InvalidNumeric) and magnitude overflow.
ErrorCode parseFixed(const std::string& text, int frac_digits, int64_t& out);

// Formats fixed-point minor units back to a canonical decimal string.
std::string formatFixed(int64_t value, int frac_digits);

} // namespace varbt
