#!/bin/bash
# Checks that a client offering h2 gets the server's ALPN choice while the network engine captures
# (dev.apksim.net.H2TunnelCheck, issues 367, 387). Runs four times: loopback sockets and in memory
# (-Dapksim.netInMemory=true, the beam sandbox's path: the app's socket is a RelaySocket joined to the engine by pipes),
# each with the JDK's TLS and with Conscrypt's (a gRPC app's provider factory, which rejects cipher suites it lacks).
# Needs work/apksim.jar (scripts/build.sh). Exits 1 on any failure.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
CONSCRYPT="$(ls "$ROOT"/work/lib/conscrypt-openjdk-uber-*.jar 2>/dev/null | head -1 || true)"
for mem in false true; do
  echo "== in memory: $mem, JDK TLS"
  "$JDK/bin/java" -Dapksim.netInMemory=$mem -cp "$ROOT/work/apksim.jar" dev.apksim.net.H2TunnelCheck
  if [ -z "$CONSCRYPT" ]; then
    echo "no Conscrypt jar in work/lib: its passes cannot run" >&2
    [ -z "${CI:-}" ] || exit 1
  else
    echo "== in memory: $mem, Conscrypt"
    "$JDK/bin/java" --enable-native-access=ALL-UNNAMED -Dapksim.check.conscrypt=true -Dapksim.netInMemory=$mem \
      -cp "$ROOT/work/apksim.jar:$CONSCRYPT" dev.apksim.net.H2TunnelCheck
  fi
done
