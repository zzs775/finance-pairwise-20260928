#include <iostream>

#include "tests.h"

int main() {
    int rc = blockalloc::run_tests();
    std::cout << (rc == 0 ? "ALL TESTS PASSED" : "TESTS FAILED") << std::endl;
    return rc;
}
