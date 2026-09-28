#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
out="$root/target/classes"
test_out="$root/target/test-classes"
mkdir -p "$out" "$test_out"
find "$root/src/main/java" -name '*.java' > /tmp/fix-main.txt
javac -d "$out" @/tmp/fix-main.txt
if [[ "${1:-}" == "test" ]]; then
  find "$root/src/test/java" -name '*.java' > /tmp/fix-test.txt
  javac -cp "$out" -d "$test_out" @/tmp/fix-test.txt
  java -cp "$out:$test_out" fix.recovery.test.TestRunner
fi
