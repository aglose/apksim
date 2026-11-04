#!/bin/bash
# Records which classes a launch touches and builds the JVM ahead-of-time cache from them.
# The cache holds the framework only, so it works for every APK. Rebuild it after changing the runtime.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Default: the test app as built here, else the released one scripts/setup.sh fetched (no Android SDK).
APK="${1:-$ROOT/testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk}"
[[ $# -eq 0 && ! -f "$APK" && -f "$ROOT/work/testapp.apk" ]] && APK="$ROOT/work/testapp.apk"
rm -f "$ROOT/work/app.aot" "$ROOT/work/app.aotconf"
# Each stage keeps its output, and a failure says which stage and where to look, rather than ending silently.
stage() {
  local name="$1"; shift
  if ! APKSIM_NOCACHE=1 "$ROOT/apksim" "$@" > "$ROOT/work/train-$name.log" 2>&1 || [[ "$name" == create && ! -s "$ROOT/work/app.aot" ]]; then
    echo "train.sh: the $name stage failed; see work/train-$name.log" >&2
    tail -5 "$ROOT/work/train-$name.log" >&2
    exit 1
  fi
}
stage record --jvm -XX:AOTMode=record --jvm "-XX:AOTConfiguration=$ROOT/work/app.aotconf" --exit-after-first-frame "$APK"
stage create --jvm -XX:AOTMode=create --jvm "-XX:AOTConfiguration=$ROOT/work/app.aotconf" --jvm "-XX:AOTCache=$ROOT/work/app.aot" --exit-after-first-frame "$APK"
printf '%s' "$ROOT" > "$ROOT/work/app.aot.root"
ls -la "$ROOT/work/app.aot"
