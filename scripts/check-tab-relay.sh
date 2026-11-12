#!/bin/bash
# Checks the beam tab relay (dev.apksim.net.TabRelay, issue 276) with a stand-in for the tab: a plain socket factory's
# socket and a URL connection to page.apksim.invalid, bodies both ways, the limits (405, 413, 503, 504, 502), a read
# timeout, any other address, and the relay off (dev.apksim.net.TabRelayCheck). Exits 1 if any check fails. Needs
# work/apksim.jar (scripts/build.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
exec "$JDK/bin/java" -cp "$ROOT/work/apksim.jar" dev.apksim.net.TabRelayCheck
