#!/bin/bash
# Checks recording-to-journey on a plain JVM (dev.apksim.JourneyCheck, a stand-in screen): which texts a journey may
# expect (times, durations, dates and sizes on any line left out) and how taps and presses become steps (double taps,
# taps on two controls, a crash with a finger down). Exits 1 if any check fails. Needs work/apksim.jar
# (scripts/build.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
CP="$ROOT/work/apksim.jar:$(ls "$ROOT"/work/lib/*.jar | tr '\n' ':')"
exec "$JDK/bin/java" -cp "$CP" dev.apksim.JourneyCheck
