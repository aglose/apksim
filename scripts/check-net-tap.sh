#!/bin/bash
# Checks the network tap (dev.apksim.net.TapCheck, docs/network.md "The tap"): connections made through Net's hooks, as
# translated app code makes them, against servers on this machine; HTTP/1.1, HTTP/2 (header blocks written by the JDK's
# own HPACK encoder, hence the --add-opens), WebSocket, failures, HttpsURLConnection, rule routing, and that a stuck
# decoder never makes the app wait. Needs work/apksim.jar (scripts/build.sh). Exits 1 on any failure.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
exec "$JDK/bin/java" --add-opens java.net.http/jdk.internal.net.http.hpack=ALL-UNNAMED -Dapksim.netCheckLoopback=true \
  -cp "$ROOT/work/apksim.jar" dev.apksim.net.TapCheck
