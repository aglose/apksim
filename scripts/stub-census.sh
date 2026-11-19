#!/bin/bash
# The stub census (scripts/stub-census.java): every native method of the framework jar and whether a shadow or the
# native runtime covers it, docs/stub-census.tsv. No argument: checks the committed table against the jars (exit 1
# when stale; CI's quick checks). --update rewrites it and prints the summary per package; --summary prints that
# alone. Needs work/lib and work/apksim.jar (scripts/build.sh); a change to a shadow in runtime/ changes rows, so run
# --update after one. --update-registered APK (Mac): launches APK headless once with -Xlog:jni+resolve=debug and writes
# docs/stub-census-registered.txt, the framework natives libandroid_runtime's registration binds (the check reads that
# file, not the library, so CI needs no Mac); run it when work/runtime's libraries change, then --update.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
TABLE="$ROOT/docs/stub-census.tsv"
run() { "$JDK/bin/java" -cp "$ROOT/work/lib/asm-9.10.1.jar" "$ROOT/scripts/stub-census.java" "$ROOT" "$@"; }
case "${1:-}" in
  --update) run --out "$TABLE" ;;
  --summary) run ;;
  --update-registered)
    [[ -n "${2:-}" ]] || { echo "usage: $0 --update-registered APK" >&2; exit 2; }
    LOG="$(mktemp "${TMPDIR:-/tmp}/stub-census-jni.XXXXXX")"
    trap 'rm -f "$LOG"' EXIT
    APKSIM_STANDBY=0 "$ROOT/apksim" --headless --exit-after-first-frame --jvm "-Xlog:jni+resolve=debug:file=$LOG" "$2" >/dev/null 2>&1 || true
    run --registered-from "$LOG" ;;
  "") run --check "$TABLE" --quiet ;;
  *) echo "usage: $0 [--update|--summary|--update-registered APK]" >&2; exit 2 ;;
esac
