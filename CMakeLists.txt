cmake_minimum_required(VERSION 3.20)
project(discrete_rebalancer LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(rebalancer
    src/types.cpp
    src/validation.cpp
    src/fees.cpp
    src/model.cpp
    src/oracle.cpp
    src/solver.cpp
    src/io.cpp
)
target_include_directories(rebalancer PUBLIC include)

if(MSVC)
    target_compile_options(rebalancer PRIVATE /W4)
else()
    target_compile_options(rebalancer PRIVATE -Wall -Wextra)
endif()

add_executable(rebalancer_cli src/cli_main.cpp)
target_link_libraries(rebalancer_cli PRIVATE rebalancer)

enable_testing()
add_executable(rebalancer_tests
    tests/test_main.cpp
    tests/test_fees.cpp
    tests/test_validation.cpp
    tests/test_model.cpp
    tests/test_oracle_match.cpp
    tests/test_solver.cpp
)
target_link_libraries(rebalancer_tests PRIVATE rebalancer)
add_test(NAME rebalancer_tests COMMAND rebalancer_tests)

find_program(POWERSHELL_EXE NAMES pwsh powershell)
if(POWERSHELL_EXE)
    add_test(
        NAME cli_e2e
        COMMAND ${POWERSHELL_EXE} -ExecutionPolicy Bypass -File
                ${CMAKE_CURRENT_SOURCE_DIR}/tests/cli_e2e_test.ps1
        WORKING_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}
    )
endif()
