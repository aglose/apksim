#!/bin/bash
# Checks which activity a launch starts when intent-filter priorities are resources (issue 108): the fixture in
# testapks/launch-priority has an integer resource as its launcher's priority, a non-default value that must not win,
# and a disabled activity with a higher priority. Exits 1 if the launcher is not the one expected.
# Needs work/apksim.jar (scripts/build.sh). Usage: scripts/launch-priority-check.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
CP="$ROOT/work/apksim.jar:$(ls "$ROOT"/work/lib/*.jar | tr '\n' ':')"
CACHE="$(mktemp -d)"
trap 'rm -rf "$CACHE"' EXIT
"$JDK/bin/java" -cp "$CP" "$ROOT/scripts/LaunchPriorityCheck.java" \
  "$ROOT/testapks/launch-priority/launch-priority.apk" "$CACHE"
