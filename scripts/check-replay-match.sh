#!/bin/bash
# Checks how a replay matches requests that cannot be what was recorded byte for byte (dev.apksim.net.ReplayMatchCheck,
# docs/replay.md "A session with a sign-in"): typed input the recorder left out is taken out of the requests that carried
# it and such an entry answers a body that differs; a query value made at run time is named, and matched with --loose; a
# failed connect is recorded and fails again; the report counts every entry and every unmatched request.
# Needs work/apksim.jar (scripts/build.sh). Exits 1 on any failure.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
exec "$JDK/bin/java" -cp "$ROOT/work/apksim.jar" dev.apksim.net.ReplayMatchCheck
