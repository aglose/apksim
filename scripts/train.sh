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
# Default: the test app as built here, else the released one (scripts/setup.sh fetches it into a checkout; an
# installed release downloads it below, once).
APK="${1:-$ROOT/testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk}"
[[ $# -eq 0 && ! -f "$APK" && -f "$ROOT/work/testapp.apk" ]] && APK="$ROOT/work/testapp.apk"
RELEASE_APK=""
if [[ $# -eq 0 && ! -f "$APK" && -f "$ROOT/work/release" && -f "$ROOT/work/testapp.sha256" ]]; then
  VERSION="$(cat "$ROOT/work/release")"
  RELEASE_APK="$STATE/testapp-$VERSION.apk"
  APK="$RELEASE_APK"
fi
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
DL="$STATE/testapp.download.$$"
trap 'rm -f "$CONF" "$NEW" "$DL"; [[ "$(cat "$STATE/train.pid" 2>/dev/null)" == "$$" ]] && rm -f "$STATE/train.pid"; true' EXIT
# An installed release does not ship the test app (22 MB of its download): the first training fetches it from the
# release, checked against the hash the release carries (work/testapp.sha256), and keeps it in STATE.
# APKSIM_DOWNLOAD_URL points at a mirror or a local folder (file://), as for install.sh.
if [[ -n "$RELEASE_APK" && ! -f "$RELEASE_APK" ]]; then
  URL="${APKSIM_DOWNLOAD_URL:-https://github.com/aglose/apksim/releases/download/v$VERSION}/apksim-testapp-$VERSION.apk"
  WANT="$(tr -d ' \n' < "$ROOT/work/testapp.sha256")"
  echo "train.sh: downloading the test app the startup cache is trained on (once): $URL" >&2
  if ! curl -fsSL --connect-timeout 10 --max-time 600 -o "$DL" "$URL"; then
    echo "train.sh: could not download the test app; the startup cache is not built. apksim works without it." >&2
    echo "train.sh: the next launch tries again, or run apksim --train (apksim --train APP.apk trains offline on any APK)" >&2
    exit 1
  fi
  GOT="$(shasum -a 256 "$DL" | cut -d' ' -f1)"
  if [[ "$GOT" != "$WANT" ]]; then
    echo "train.sh: checksum mismatch for the downloaded test app (got $GOT, expected $WANT); not used" >&2
    exit 1
  fi
  for old in "$STATE"/testapp-*.apk; do [[ -e "$old" ]] && rm -f "$old"; done
  mv -f "$DL" "$RELEASE_APK"
fi
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
