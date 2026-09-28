cmake_minimum_required(VERSION 3.20)
project(var_backtest CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(varbt
    src/fixed_point.cpp
    src/ordered_multiset.cpp
    src/engine.cpp
    src/oracle.cpp
    src/checkpoint.cpp
    src/report.cpp
)
target_include_directories(varbt PUBLIC include)

if(MSVC)
    target_compile_options(varbt PRIVATE /W4)
else()
    target_compile_options(varbt PRIVATE -Wall -Wextra -Wpedantic)
endif()

add_executable(varbt-cli src/main.cpp)
target_link_libraries(varbt-cli PRIVATE varbt)

enable_testing()
add_executable(varbt-tests tests/test_main.cpp)
target_link_libraries(varbt-tests PRIVATE varbt)
add_test(NAME varbt-tests COMMAND varbt-tests)
