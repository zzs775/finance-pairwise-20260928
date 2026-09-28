#include "varbt/fixed_point.hpp"

#include <array>
#include <limits>

namespace varbt {

namespace {

constexpr int64_t kIntMin = (std::numeric_limits<int64_t>::min)();
constexpr int64_t kIntMax = (std::numeric_limits<int64_t>::max)();

// Multiplies a non-negative accumulator by 10 and adds a digit, returning
// false on signed-overflow of the final (possibly negated) magnitude.
bool appendDigit(uint64_t& magnitude, int digit) {
    constexpr uint64_t limit =
        static_cast<uint64_t>(kIntMax) + 1ULL; // |INT64_MIN|
    if (magnitude > (limit - static_cast<uint64_t>(digit)) / 10ULL) {
        return false;
    }
    magnitude = magnitude * 10ULL + static_cast<uint64_t>(digit);
    return true;
}

} // namespace

ErrorCode parseFixed(const std::string& text, int frac_digits, int64_t& out) {
    out = 0;
    if (frac_digits < 0 || frac_digits > 18) {
        return ErrorCode::IllegalWindow;
    }
    size_t pos = 0;
    bool negative = false;
    if (pos < text.size() && (text[pos] == '+' || text[pos] == '-')) {
        negative = text[pos] == '-';
        ++pos;
    }
    if (pos >= text.size()) {
        return ErrorCode::InvalidNumeric;
    }

    uint64_t integer_part = 0;
    bool saw_integer_digit = false;
    while (pos < text.size() && text[pos] >= '0' && text[pos] <= '9') {
        saw_integer_digit = true;
        if (!appendDigit(integer_part, text[pos] - '0')) {
            return ErrorCode::Overflow;
        }
        ++pos;
    }

    uint64_t fraction = 0;
    int fraction_digits = 0;
    if (pos < text.size() && text[pos] == '.') {
        ++pos;
        while (pos < text.size() && text[pos] >= '0' && text[pos] <= '9') {
            if (fraction_digits < frac_digits) {
                if (!appendDigit(fraction, text[pos] - '0')) {
                    return ErrorCode::Overflow;
                }
                ++fraction_digits;
            } else {
                // Extra precision beyond the fixed-point scale is rejected so
                // that a correction can never silently round away value.
                if (text[pos] != '0') {
                    return ErrorCode::InvalidNumeric;
                }
            }
            ++pos;
        }
        if (fraction_digits == 0 && pos == text.size()) {
            return ErrorCode::InvalidNumeric; // dangling decimal point
        }
    }

    if (!saw_integer_digit && fraction_digits == 0) {
        return ErrorCode::InvalidNumeric;
    }
    if (pos != text.size()) {
        return ErrorCode::InvalidNumeric;
    }

    while (fraction_digits < frac_digits) {
        fraction *= 10ULL;
        ++fraction_digits;
    }

    uint64_t scale = 1;
    for (int i = 0; i < frac_digits; ++i) {
        scale *= 10ULL;
    }
    constexpr uint64_t limit = static_cast<uint64_t>(kIntMax) + 1ULL;
    if (integer_part > limit / scale) {
        return ErrorCode::Overflow;
    }
    uint64_t scaled_integer = integer_part * scale;
    if (fraction > limit - scaled_integer) {
        return ErrorCode::Overflow;
    }
    uint64_t scaled_units = scaled_integer + fraction;

    if (negative) {
        if (scaled_units == limit) {
            out = kIntMin;
        } else {
            out = -static_cast<int64_t>(scaled_units);
        }
    } else {
        if (scaled_units == limit) {
            return ErrorCode::Overflow;
        }
        out = static_cast<int64_t>(scaled_units);
    }
    return ErrorCode::Ok;
}

std::string formatFixed(int64_t value, int frac_digits) {
    bool negative = value < 0;
    uint64_t magnitude =
        negative && value == kIntMin
            ? static_cast<uint64_t>(kIntMax) + 1ULL
            : static_cast<uint64_t>(negative ? -value : value);

    uint64_t divisor = 1;
    for (int i = 0; i < frac_digits; ++i) {
        divisor *= 10;
    }
    uint64_t integer_part = magnitude / divisor;
    uint64_t fraction = magnitude % divisor;

    std::string result = negative ? "-" : "";
    result += std::to_string(integer_part);
    if (frac_digits > 0) {
        std::array<char, 20> buffer{};
        int written = 0;
        uint64_t remaining = fraction;
        for (int i = 0; i < frac_digits; ++i) {
            buffer[frac_digits - 1 - i] =
                static_cast<char>('0' + (remaining % 10));
            remaining /= 10;
            ++written;
        }
        result.push_back('.');
        result.append(buffer.data(), written);
    }
    return result;
}

} // namespace varbt
