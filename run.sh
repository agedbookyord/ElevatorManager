#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
source scripts/java-env.sh
./build.sh
exec java -cp out/main elevatormanager.ElevatorManager
