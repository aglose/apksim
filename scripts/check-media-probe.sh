#!/bin/bash
# Checks MediaProbe (the sound durations ShadowSimMediaPlayer gives MediaPlayer, issue 488) on a plain JVM
# (dev.apksim.video.MediaProbeCheck). Exits 1 if any check fails. Needs work/apksim.jar (scripts/build.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
CP="$ROOT/work/apksim.jar:$(ls "$ROOT"/work/lib/*.jar | tr "\n" ":")"
exec "$JDK/bin/java" -cp "$CP" dev.apksim.video.MediaProbeCheck
