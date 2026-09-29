#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/classes build/test-classes
echo "compiling main sources..."
find src/main -name '*.java' > build/main-sources.txt
javac -d build/classes @build/main-sources.txt
echo "compiling test sources..."
find src/test -name '*.java' > build/test-sources.txt
javac -cp build/classes -d build/test-classes @build/test-sources.txt
echo "build OK"
