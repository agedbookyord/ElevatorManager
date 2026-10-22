#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
source scripts/java-env.sh
./build.sh
exec java -Djava.awt.headless=true -cp out/main:out/test elevatormanager.SimulatorTest
