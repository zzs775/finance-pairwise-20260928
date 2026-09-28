#pragma once

#include <cstdio>
#include <cstdlib>
#include <string>

namespace rebal_test {

inline int g_failures = 0;
inline int g_checks = 0;

#define CHECK(cond)                                                           \
    do {                                                                      \
        ++rebal_test::g_checks;                                               \
        if (!(cond)) {                                                        \
            ++rebal_test::g_failures;                                         \
            std::printf("CHECK failed: %s @ %s:%d\n", #cond, __FILE__,        \
                        __LINE__);                                            \
        }                                                                     \
    } while (0)

#define CHECK_EQ(actual, expected)                                            \
    do {                                                                      \
        ++rebal_test::g_checks;                                               \
        auto actual_v_ = (actual);                                            \
        auto expected_v_ = (expected);                                        \
        if (!(actual_v_ == expected_v_)) {                                    \
            ++rebal_test::g_failures;                                         \
            std::printf("CHECK_EQ failed: %s == %s @ %s:%d\n", #actual,       \
                        #expected, __FILE__, __LINE__);                        \
        }                                                                     \
    } while (0)

inline int finish(const char* suite) {
    std::printf("%s: %d checks, %d failures\n", suite, g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}

} // namespace rebal_test
