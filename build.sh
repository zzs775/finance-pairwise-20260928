#!/bin/sh
# Zero-dependency build for the loan covenant monitoring engine (JDK 21).
set -eu
cd "$(dirname "$0")"
mkdir -p out
find src -name '*.java' > .cache_srcs
javac -d out @.cache_srcs
find test -name '*.java' > .cache_tests
javac -cp out -d out @.cache_tests
rm -f .cache_srcs .cache_tests
echo "BUILD OK -> out"
