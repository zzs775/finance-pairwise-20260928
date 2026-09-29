cmake_minimum_required(VERSION 3.16)
project(block_allocator LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    add_compile_options(-Wall -Wextra -Wpedantic)
endif()

add_executable(block_alloc
    src/main.cpp
    src/tests.cpp
    src/parser.cpp
    src/validator.cpp
    src/solver.cpp
    src/oracle.cpp
    src/report.cpp
    src/synthetic.cpp
)
target_include_directories(block_alloc PRIVATE src)

add_executable(selftest
    src/tests_main.cpp
    src/tests.cpp
    src/parser.cpp
    src/validator.cpp
    src/solver.cpp
    src/oracle.cpp
    src/report.cpp
    src/synthetic.cpp
)
target_include_directories(selftest PRIVATE src)
target_compile_definitions(selftest PRIVATE BLOCK_ALLOC_SELFTEST_MAIN=1)

enable_testing()
add_test(NAME selftest COMMAND selftest)
