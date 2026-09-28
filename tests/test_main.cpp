#include "test_util.hpp"

// Suites (defined in separate translation units):
void run_fees();
void run_validation();
void run_model();
void run_oracle_match();
void run_solver();

int main() {
    run_fees();
    run_validation();
    run_model();
    run_oracle_match();
    run_solver();
    return rebal_test::finish("all");
}
