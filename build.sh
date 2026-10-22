#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
source scripts/java-env.sh
mkdir -p out/main out/test
find src -name '*.java' -print > out/main-sources.txt
javac -encoding UTF-8 --release 21 -d out/main @out/main-sources.txt
find tests -name '*.java' -print > out/test-sources.txt
javac -encoding UTF-8 --release 21 -cp out/main -d out/test @out/test-sources.txt
