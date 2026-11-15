#!/bin/bash
# Checks HeapTrim (the heap's unused pages given back while idle, native/heaptrim.c) on a plain JVM with the serial
# collector (dev.apksim.HeapTrimCheck): that it finds the heap's spaces on this JDK, that a trim returns eden's pages,
# and that checksummed objects survive trims at every young and full collection while three threads allocate; once
# with room in the old generation and once tight, where promotions fail and full collections follow. Builds the
# native side itself (cc and the JDK's headers). Exits 1 if any check fails. Needs work/apksim.jar (scripts/build.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
if [[ "$(uname -s)" == Darwin ]]; then
  cc -O2 -dynamiclib -I"$JDK/include" -I"$JDK/include/darwin" -o "$OUT/libheaptrim.dylib" "$ROOT/native/heaptrim.c"
else
  cc -O2 -shared -fPIC -I"$JDK/include" -I"$JDK/include/linux" -o "$OUT/libheaptrim.so" "$ROOT/native/heaptrim.c" -ldl
fi
for HEAP in "-Xmn32m -Xms64m -Xmx192m" "-Xmn16m -Xms32m -Xmx72m"; do
  # shellcheck disable=SC2086
  "$JDK/bin/java" -XX:+UseSerialGC $HEAP --enable-native-access=ALL-UNNAMED -Dapksim.runtime="$OUT" \
    -Dapksim.heapTrim.log="${HEAP_TRIM_LOG:-false}" -cp "$ROOT/work/apksim.jar" dev.apksim.HeapTrimCheck "${1:-6}"
done
