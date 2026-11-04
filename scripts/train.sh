#!/bin/bash
# Records which classes a launch touches and builds the JVM ahead-of-time cache from them.
# The cache holds the framework only, so it works for every APK. Rebuild it after changing the runtime.
# Usage: train.sh [--headless] [APK]. --headless trains without opening a window (an installed release trains in the
# background on its first launch, see the launcher).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HEADLESS=()
if [[ "${1:-}" == --headless ]]; then HEADLESS=(--headless); shift; fi
# Where the launcher keeps its state (see "STATE" there): work/ in a checkout, ~/.apksim for an installed release.
if [[ -n "${APKSIM_HOME:-}" ]]; then STATE="$APKSIM_HOME"
elif [[ -f "$ROOT/work/release" ]]; then STATE="$HOME/.apksim"
else STATE="$ROOT/work"; fi
mkdir -p "$STATE"
# Default: the test app as built here, else the released one (scripts/setup.sh fetches it; a release ships it).
APK="${1:-$ROOT/testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk}"
[[ $# -eq 0 && ! -f "$APK" && -f "$ROOT/work/testapp.apk" ]] && APK="$ROOT/work/testapp.apk"
# One training at a time per state folder: the launcher reads train.pid before starting one.
if ! (set -C; echo $$ > "$STATE/train.pid") 2>/dev/null; then
  OTHER="$(cat "$STATE/train.pid" 2>/dev/null || true)"
  if [[ -n "$OTHER" ]] && kill -0 "$OTHER" 2>/dev/null; then
    echo "train.sh: already training (pid $OTHER)" >&2; exit 1
  fi
  echo $$ > "$STATE/train.pid"
fi
# This trainer's own files, so that two trainers (a stale train.pid taken over by two launches at once) cannot
# clobber or delete each other's output: each builds a whole cache and renames it into place. The pid file is
# removed only by its owner.
CONF="$STATE/app.aotconf.$$"
NEW="$STATE/app.aot.new.$$"
trap 'rm -f "$CONF" "$NEW"; [[ "$(cat "$STATE/train.pid" 2>/dev/null)" == "$$" ]] && rm -f "$STATE/train.pid"; true' EXIT
# Each stage keeps its output, and a failure says which stage and where to look, rather than ending silently.
stage() {
  local name="$1"; shift
  if ! APKSIM_NOCACHE=1 "$ROOT/apksim" "$@" > "$STATE/train-$name.log" 2>&1 || [[ "$name" == create && ! -s "$NEW" ]]; then
    echo "train.sh: the $name stage failed; see $STATE/train-$name.log" >&2
    tail -5 "$STATE/train-$name.log" >&2
    exit 1
  fi
}
stage record --jvm -XX:AOTMode=record --jvm "-XX:AOTConfiguration=$CONF" "${HEADLESS[@]+"${HEADLESS[@]}"}" --exit-after-first-frame "$APK"
stage create --jvm -XX:AOTMode=create --jvm "-XX:AOTConfiguration=$CONF" --jvm "-XX:AOTCache=$NEW" "${HEADLESS[@]+"${HEADLESS[@]}"}" --exit-after-first-frame "$APK"
# Into place by rename, so running instances keep the cache they mapped; the root last, as launches check it.
mv -f "$NEW" "$STATE/app.aot"
printf '%s' "$ROOT" > "$STATE/app.aot.root"
ls -la "$STATE/app.aot"
