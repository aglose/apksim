#!/bin/bash
# Checks who the control channel lets in (dev.apksim.ControlCheck, on a plain JVM): an HTTP request line is never read
# as commands (a web page's POST to the loopback port), and with --control-token-file a connection must send
# `auth TOKEN` first. Exits 1 if any check fails. Needs work/apksim.jar (scripts/build.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
exec "$JDK/bin/java" -cp "$ROOT/work/apksim.jar" dev.apksim.ControlCheck
