#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./build.sh
java -cp "build/classes:build/test-classes" csm.TestRunner
