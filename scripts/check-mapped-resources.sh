#!/bin/bash
# Checks resource tables mapped from a file (dev.apksim.MappedResources) against the same tables read into an array,
# on a plain JVM (dev.apksim.MappedResourcesCheck): string decoding from a buffer with no array, then the framework
# jar's table (deflated: inflated once into a cache folder, then mapped from there) and each APK's given (stored:
# mapped in place), whose every string must be the same either way. Exits 1 if any check fails.
# Usage: check-mapped-resources.sh [APK...]. Needs work/apksim.jar (scripts/build.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
CP="$ROOT/work/apksim.jar:$(ls "$ROOT"/work/lib/*.jar | tr "\n" ":")"
ALL="$(ls "$ROOT"/work/lib/android-all-instrumented-*.jar | sort -V | tail -1)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/cache" "$TMP/apk"
run() { "$JDK/bin/java" -cp "$CP" -Dapksim.cacheRoot="$TMP/cache" -Dapksim.cache="$TMP/apk" "$@" dev.apksim.MappedResourcesCheck "system:$ALL" "${APKS[@]+"${APKS[@]}"}"; }
APKS=("$@")
# The first APK's table again, deflated, as APKs built for older Android versions have it: inflated into the APK's
# cache entry and mapped from there, with the same strings.
if [[ $# -gt 0 ]]; then
  python3 - "$1" "$TMP/deflated.apk" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as src, zipfile.ZipFile(sys.argv[2], "w", zipfile.ZIP_DEFLATED) as out:
    out.writestr("resources.arsc", src.read("resources.arsc"))
PY
  APKS+=("$TMP/deflated.apk")
fi
FIRST="$(run)"; echo "$FIRST"
ls "$TMP"/cache/framework/resources-*.arsc > /dev/null || { echo "FAIL: the framework's table was not inflated into the cache"; exit 1; }
SECOND="$(run)"
OFF="$(run -Dapksim.mappedResources=false)"
FAIL=0
[[ "$FIRST" == "$SECOND" ]] || { echo "FAIL: a second run (table already in the cache) differs:"; echo "$SECOND"; FAIL=1; }
if grep -v '^decodeString' <<<"$FIRST" | grep -q 'mapped=false'; then echo "FAIL: a table was not mapped"; FAIL=1; fi
if grep -q 'mapped=true' <<<"$OFF"; then echo "FAIL: -Dapksim.mappedResources=false still mapped a table"; FAIL=1; fi
[[ "$(sed 's/mapped=true/mapped=false/' <<<"$FIRST")" == "$OFF" ]] || { echo "FAIL: strings differ with the table in an array:"; echo "$OFF"; FAIL=1; }
if [[ $# -gt 0 ]]; then
  ls "$TMP"/apk/arsc/resources-*.arsc > /dev/null || { echo "FAIL: the deflated APK table was not inflated into the APK's cache entry"; FAIL=1; }
  STORED="$(grep "^$(basename "$1") " <<<"$FIRST" | sed 's/.* strings=/strings=/')"
  DEFLATED="$(grep '^deflated.apk ' <<<"$FIRST" | sed 's/.* strings=/strings=/')"
  [[ -n "$STORED" && "$STORED" == "$DEFLATED" ]] || { echo "FAIL: the deflated copy of $(basename "$1")'s table reads differently"; FAIL=1; }
fi
[[ $FAIL == 0 ]] && echo "mapped resource tables: ok"
exit $FAIL
