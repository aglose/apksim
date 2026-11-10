#!/bin/bash
# Checks apksim's InvokeDynamicSupport and ShadowInvalidator (docs/perf-plan.md, B1) on a plain JVM: instance, static
# and constructor sites re-bound after their class is invalidated, other classes' left alone, and a binding that races
# an invalidation (dev.apksim.CallSiteCheck). Exits 1 if any check fails. Needs work/apksim.jar (scripts/build.sh).
# Robolectric's own binding (-Dapksim.directCallSites=false) fails the race check: it takes its SwitchPoint after the
# lookup, so a binding that looked up before an invalidation keeps the old target.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
CP="$ROOT/work/apksim.jar:$(ls "$ROOT"/work/lib/*.jar | tr '\n' ':')"
exec "$JDK/bin/java" -cp "$CP" dev.apksim.CallSiteCheck
