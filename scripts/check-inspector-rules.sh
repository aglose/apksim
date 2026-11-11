#!/bin/bash
# Checks the network inspector's URL patterns (dev.apksim.net.InspectorRuleCheck, docs/pr-preview.md): stars, parts in order,
# and no backtracking whatever pattern a tab sends. Exits 1 if any check fails. Needs work/apksim.jar (scripts/build.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
exec "$JDK/bin/java" -cp "$ROOT/work/apksim.jar" dev.apksim.net.InspectorRuleCheck
