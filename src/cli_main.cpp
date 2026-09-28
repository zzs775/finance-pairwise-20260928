#include <iostream>
#include <fstream>
#include <optional>
#include <sstream>
#include <string>

#include "rebalancer/io.hpp"
#include "rebalancer/oracle.hpp"
#include "rebalancer/solver.hpp"
#include "rebalancer/validation.hpp"

int main(int argc, char** argv) {
    std::string path = "-";
    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--help" || arg == "-h") {
            std::cout
                << "usage: rebalancer_cli [input-file] (default: stdin)\n"
                << "See README.md for the line-oriented input format.\n";
            return 0;
        }
        path = arg;
    }

    std::string text;
    if (path == "-") {
        std::ostringstream buffer;
        buffer << std::cin.rdbuf();
        text = buffer.str();
    } else {
        std::ifstream file(path);
        if (!file) {
            std::cerr << "error: cannot open " << path << "\n";
            return 2;
        }
        std::ostringstream buffer;
        buffer << file.rdbuf();
        text = buffer.str();
    }

    rebal::ParseResult parsed = rebal::parse_problem_text(text);
    if (!parsed.ok) {
        std::cerr << "input error: " << parsed.error << "\n";
        return 2;
    }

    rebal::Problem normalized;
    std::optional<rebal::InputError> error =
        rebal::validate_and_normalize(parsed.problem, normalized);
    if (error) {
        std::cerr << "input error (" << static_cast<int>(error->kind)
                  << "): " << error->detail << "\n";
        return 2;
    }

    rebal::Rebalancer solver;
    rebal::SolveResult result = solver.solve(normalized);

    if (parsed.oracle_window > 0) {
        rebal::SolveResult oracle_result =
            rebal::enumerate_oracle(normalized, parsed.oracle_window);
        std::cout << rebal::render_result(normalized, oracle_result);
        return 0;
    }
    std::cout << rebal::render_result(normalized, result);
    return 0;
}
