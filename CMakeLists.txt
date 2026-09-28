cmake_minimum_required(VERSION 3.20)
project(mc_option_pricer LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(Threads REQUIRED)

add_library(mcopt STATIC
    src/prng.cpp
    src/gaussian.cpp
    src/contract.cpp
    src/config.cpp
    src/stats.cpp
    src/blackscholes.cpp
    src/engine.cpp
    src/checkpoint.cpp
    src/scheduler.cpp
    src/output.cpp
)
target_include_directories(mcopt PUBLIC src)
target_link_libraries(mcopt PUBLIC Threads::Threads)

add_executable(mcopt_cli src/main.cpp)
target_link_libraries(mcopt_cli PRIVATE mcopt)
set_target_properties(mcopt_cli PROPERTIES OUTPUT_NAME mcopt)

enable_testing()
add_executable(mcopt_tests tests/test_main.cpp)
target_link_libraries(mcopt_tests PRIVATE mcopt)
add_test(NAME mcopt_tests COMMAND mcopt_tests)
