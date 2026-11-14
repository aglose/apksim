#!/bin/bash
# Checks that a client offering h2 gets the server's ALPN choice while the network engine captures
# (dev.apksim.net.H2TunnelCheck, issue 367). Needs work/apksim.jar (scripts/build.sh). Exits 1 on any failure.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
exec "$JDK/bin/java" -cp "$ROOT/work/apksim.jar" dev.apksim.net.H2TunnelCheck
