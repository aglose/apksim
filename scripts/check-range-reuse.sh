#!/bin/bash
# Checks HTTP Range ZIP entry reuse and verification (dev.apksim.ZipRangeFetchCheck, issue 674). Exits 1 if any fails.
# Needs work/apksim.jar (scripts/build.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
exec "$JDK/bin/java" -cp "$ROOT/work/apksim.jar" dev.apksim.ZipRangeFetchCheck
