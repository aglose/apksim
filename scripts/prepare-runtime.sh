#!/bin/bash
# One-time: unpacks native code and data so launches never touch the big jars for them.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/work/runtime"
ALL="$(ls "$ROOT"/work/lib/android-all-instrumented-*.jar | sort -V | tail -1)"
DIST="$(ls "$ROOT"/work/lib/nativeruntime-dist-compat-*.jar | sort -V | tail -1)"
rm -rf "$OUT"; mkdir -p "$OUT/icu"
for JAR in "$ALL" "$DIST"; do
  unzip -q -o "$JAR" 'fonts/*' 'hyphen-data/*' -d "$OUT" 2>/dev/null || true
  unzip -q -o -j "$JAR" 'native/mac/aarch64/*' -d "$OUT" 2>/dev/null || true
done
ICU="$(unzip -l "$ALL" | awk '{print $4}' | grep '^icu/icudt' | head -1)"
unzip -q -o -p "$ALL" "$ICU" > "$OUT/icu/icudt.dat"
ls -la "$OUT" "$OUT/icu"; du -sh "$OUT"
"$ROOT/scripts/build-native.sh"
