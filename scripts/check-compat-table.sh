#!/bin/bash
# Checks CompatTable (compat changes by the app's targetSdk) on a plain JVM against the committed table
# (dev.apksim.CompatTableCheck). Exits 1 if any check fails. Needs work/apksim.jar (scripts/build.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
CP="$ROOT/work/apksim.jar:$(ls "$ROOT"/work/lib/*.jar | tr "\n" ":")"
exec "$JDK/bin/java" -cp "$CP" dev.apksim.CompatTableCheck
