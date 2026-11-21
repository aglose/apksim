#!/bin/bash
# Checks that a beamed instance's translated-class lookup by content reads the packs of earlier builds the agent names
# (-Dapksim.beamSiblingCaches, issue 578) and nothing else: a class saved by one build is found by a second build
# that names the first's cache, is not found when none is named, and the sibling's folder is never written; a second
# launch of the first build keeps its first launch's index entries, and the new build takes the sibling's load hints.
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
# As the agent keeps it (Session.keepBeamCaches): regular .pack, .classes and .index files only.
mkdir -p "$S/beam/cache/a/classes"
for f in "$OUT"/classes/*/*; do
  case "$f" in *.pack|*.classes|*.index) mkdir -p "$S/beam/cache/a/classes/$(basename "$(dirname "$f")")"; cp "$f" "$S/beam/cache/a/classes/$(basename "$(dirname "$f")")/";; esac
done
rm -rf "$OUT"
# A second launch of build A, reading its cache as the seed, translates only a class of another DEX file; kept as
# before, its index replaces A's and must still name the first launch's pack (issue 583).
"$JDK/bin/java" -Dapksim.beamSandbox=true -Dapksim.cacheRoot="$S/cache" -Dapksim.beamBuildCache="$S/beam/cache/a" \
  -cp "$CP" dev.apksim.ClassCacheSiblingsCheck write-other aaaa cccc
OUT="$(ls -d "$S"/beam/out/*)"
for f in "$OUT"/classes/*/*; do
  case "$f" in *.pack|*.classes|*.index) cp "$f" "$S/beam/cache/a/classes/$(basename "$(dirname "$f")")/";; esac
done
rm -rf "$OUT"
run read-none bbbb
rm -rf "$S"/beam/out
BEFORE="$(find "$S/beam/cache/a" -type f -exec shasum {} + | sort)"
run read-found bbbb "$S/beam/cache/a"
[[ "$(find "$S/beam/cache/a" -type f -exec shasum {} + | sort)" == "$BEFORE" ]] || { echo "FAIL: the sibling's folder changed"; exit 1; }
OUT="$(ls -d "$S"/beam/out/*)"
ls "$OUT"/classes/*/bbbb.pack >/dev/null || { echo "FAIL: the class found by content was not saved into the new build's own pack"; exit 1; }
echo "class cache siblings: ok"
