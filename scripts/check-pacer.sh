#!/bin/bash
# Checks serve's H.264 pacing for a tab slower than the app (dev.apksim.serve.Pacer, issue 232) on a simulated clock:
# when a pace is set and raised, easing, a failed trial's backoff, the one-frame window, idle time kept out of the ack
# gap (dev.apksim.serve.PacerCheck). Exits 1 if any check fails. Needs work/apksim.jar (scripts/build.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
exec "$JDK/bin/java" -cp "$ROOT/work/apksim.jar" dev.apksim.serve.PacerCheck
