#!/bin/bash
# Checks which texts `apksim replay --journey` may expect on screen (dev.apksim.JourneyCheck): times, durations, dates
# and sizes anywhere in a text, a multi-line one too, are left out. Exits 1 if any check fails. Needs work/apksim.jar
# (scripts/build.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
CP="$ROOT/work/apksim.jar:$(ls "$ROOT"/work/lib/*.jar | tr '\n' ':')"
exec "$JDK/bin/java" -cp "$CP" dev.apksim.JourneyCheck
