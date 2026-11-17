#!/bin/bash
# Checks --network-host and --network-trust as serve and beam launch options (dev.apksim.serve.HostAllowCheck, issue 429):
# the allow list, the inline certificate and the counts. Exits 1 if any check fails. Needs work/apksim.jar (scripts/build.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
exec "$JDK/bin/java" -cp "$ROOT/work/apksim.jar" dev.apksim.serve.HostAllowCheck
