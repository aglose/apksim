#!/bin/bash
# Checks the beam tab relay inside an instance (dev.apksim.net.TabRelay, docs/beam.md "Tab relay") against a fake tab:
# the request the tab sees, the answer the app gets, the refusals and limits, the JDK's HttpURLConnection path, timeouts
# and the tab going (dev.apksim.net.TabRelayCheck). Exits 1 if any check fails. Needs work/apksim.jar (scripts/build.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
# java.net open, as the launcher has it: the relay hands an app socket its in-memory connection (Socket.setConnectedImpl).
exec "$JDK/bin/java" --add-opens=java.base/java.net=ALL-UNNAMED -cp "$ROOT/work/apksim.jar" dev.apksim.net.TabRelayCheck
