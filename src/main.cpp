#include <cstring>
#include <fstream>
#include <iostream>
#include <sstream>
#include <string>

#include "oracle.h"
#include "parser.h"
#include "report.h"
#include "solver.h"
#include "synthetic.h"
#include "tests.h"
#include "validator.h"

int main(int argc, char** argv) {
    std::string input_path;
    std::string report_path;
    uint64_t node_budget = 1'000'000ULL;
    uint64_t oracle_budget = 5'000'000ULL;
    bool run_selftest = false;

    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        auto next = [&](const char* name) -> std::string {
            if (i + 1 >= argc) {
                std::cerr << "missing value for " << name << std::endl;
                std::exit(2);
            }
            return argv[++i];
        };
        if (arg == "--input") input_path = next("--input");
        else if (arg == "--report") report_path = next("--report");
        else if (arg == "--node-budget")
            node_budget = std::stoull(next("--node-budget"));
        else if (arg == "--oracle-budget")
            oracle_budget = std::stoull(next("--oracle-budget"));
        else if (arg == "--synthetic") { /* default input when no --input */ }
        else if (arg == "--selftest") run_selftest = true;
        else if (arg == "--help" || arg == "-h") {
            std::cout
                << "usage: block_alloc [--input file | --synthetic]\n"
                << "                    [--report file] [--node-budget n]\n"
                << "                    [--oracle-budget n] [--selftest]\n";
            return 0;
        } else {
            std::cerr << "unknown argument: " << arg << std::endl;
            return 2;
        }
    }

    if (run_selftest) return blockalloc::run_tests();

    blockalloc::Instance inst;
    if (!input_path.empty()) {
        std::ifstream f(input_path);
        if (!f) {
            std::cerr << "cannot open input file: " << input_path << std::endl;
            return 2;
        }
        std::stringstream ss;
        ss << f.rdbuf();
        blockalloc::ParseReport pr = blockalloc::parse_instance(ss.str());
        if (!pr.ok) {
            std::cerr << "parse error: " << pr.error << std::endl;
            return 2;
        }
        inst = std::move(pr.instance);
    } else {
        inst = blockalloc::make_synthetic_instance();
    }

    blockalloc::ConflictReport conflict = blockalloc::check_feasibility(inst);
    blockalloc::AllocationResult result;
    blockalloc::OracleResult oracle;

    if (conflict.feasible) {
        blockalloc::SolveOptions opts;
        opts.node_budget = node_budget;
        opts.oracle_budget = oracle_budget;
        result = blockalloc::solve(inst, opts);
        oracle = blockalloc::brute_force_oracle(inst, oracle_budget);
    } else {
        result.status = blockalloc::Status::Infeasible;
        result.nodes_budget = node_budget;
    }

    std::string text = blockalloc::render_report(inst, result, conflict, oracle);

    if (!report_path.empty()) {
        std::ofstream out(report_path, std::ios::binary);
        if (!out) {
            std::cerr << "cannot write report file: " << report_path << std::endl;
            return 2;
        }
        out << text;
    }
    std::cout << text;
    return 0;
}
