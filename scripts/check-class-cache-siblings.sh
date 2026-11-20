#!/bin/bash
# Checks that a beamed instance's translated-class lookup by content reads the packs of earlier builds the agent names
# (-Dapksim.beamSiblingCaches, issue 578) and nothing else: a class saved by one build is found by a second build
# that names the first's cache, is not found when none is named, and the sibling's folder is never written.
# Needs work/apksim.jar (scripts/build.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
CP="$ROOT/work/apksim.jar:$(ls "$ROOT"/work/lib/*.jar | tr "\n" ":")"
S="$(mktemp -d)"
trap 'rm -rf "$S"' EXIT
mkdir -p "$S/cache" "$S/beam/cache/a" "$S/beam/cache/b"
run() {  # MODE KEY [SIBLINGS]
  local props=(-Dapksim.beamSandbox=true -Dapksim.cacheRoot="$S/cache")
  [[ -n "${3:-}" ]] && props+=(-Dapksim.beamSiblingCaches="$3")
  "$JDK/bin/java" "${props[@]}" -cp "$CP" dev.apksim.ClassCacheSiblingsCheck "$1" "$2"
}
# Build A's instance writes to its out folder, which the agent moves into the build's cache.
run write aaaa
OUT="$(ls -d "$S"/beam/out/*)"
mv "$OUT/classes" "$S/beam/cache/a/classes"
rm -rf "$OUT"
run read-none bbbb
rm -rf "$S"/beam/out
BEFORE="$(find "$S/beam/cache/a" -type f -exec shasum {} + | sort)"
run read-found bbbb "$S/beam/cache/a"
[[ "$(find "$S/beam/cache/a" -type f -exec shasum {} + | sort)" == "$BEFORE" ]] || { echo "FAIL: the sibling's folder changed"; exit 1; }
OUT="$(ls -d "$S"/beam/out/*)"
ls "$OUT"/classes/*/bbbb.pack >/dev/null || { echo "FAIL: the class found by content was not saved into the new build's own pack"; exit 1; }
echo "class cache siblings: ok"
