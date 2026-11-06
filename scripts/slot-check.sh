#!/bin/bash
# What a data slot keeps of the device across launches (SlotDevice.java, issues 82 and 84), against the test app's
# apksim://slot: a first --fresh launch with --setting sees the seeded Settings value and nothing else from
# Application.onCreate on, writes a Settings.Global value and a /sdcard file; the next launch of the slot sees both
# (and the seed) in onCreate; a --fresh launch sees none of them. Uses its own data slots (--data-slots slotcheck), never
# the app's default one.
#   scripts/slot-check.sh APK OUT [IMAGE]
# Without IMAGE: ./apksim on this Mac (warm launches when a standby is ready). With IMAGE: the Linux image, a container
# per launch, with the app data folder on a volume in OUT, as containers sharing a cache volume have it.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$1" OUT="$(mkdir -p "$2" && cd "$2" && pwd)" IMAGE="${3:-}"
[[ -n "$IMAGE" ]] && PLATFORM="linux/$(docker image inspect -f '{{.Architecture}}' "$IMAGE")"
PORT="${SLOTCHECK_PORT:-7480}" NAME="apksim-slotcheck-$$"
cp "$APK" "$OUT/app.apk"
mkdir -p "$OUT/data"
drive() { python3 "$ROOT/scripts/drive.py" "$PORT" "$@"; }
LAUNCHED=""
cleanup() {
  if [[ -n "$IMAGE" ]]; then docker rm -f "$NAME" >/dev/null 2>&1 || true; fi
  if [[ -n "$LAUNCHED" ]]; then drive quit >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

# The text of the first node whose text starts with $1 (from `nodes`).
text() {
  drive nodes | python3 -c '
import re, sys
for m in re.finditer(r"\[(\d+),(\d+),(\d+),(\d+)\] text=\"((?:[^\"\\]|\\.)*)\"", sys.stdin.read()):
    if m.group(5).startswith(sys.argv[1]):
        print(m.group(5)); break' "$1"
}
tap_text() {
  local xy
  xy="$(drive nodes | python3 -c '
import re, sys
for m in re.finditer(r"\[(\d+),(\d+),(\d+),(\d+)\] text=\"((?:[^\"\\]|\\.)*)\"", sys.stdin.read()):
    if m.group(5) == sys.argv[1]:
        x1, y1, x2, y2 = map(int, m.groups()[:4]); print((x1 + x2) // 2, (y1 + y2) // 2); break' "$1")"
  [[ -n "$xy" ]] || { echo "no \"$1\" on screen" >&2; exit 1; }
  drive "tap $xy" >/dev/null
}
expect() {  # expect WHAT ACTUAL PATTERN
  if [[ "$2" =~ $3 ]]; then echo "ok: $1: $2"; else echo "FAILED: $1: got \"$2\", wanted /$3/" >&2; exit 1; fi
}

# start LOG APKSIM_ARGS...: the app on its slot, then the slot screen.
start() {
  local log="$1"; shift
  if drive ping 2>/dev/null | grep -q pong; then
    echo "port $PORT already has an apksim instance on it; set SLOTCHECK_PORT to a free one" >&2
    exit 1
  fi
  if [[ -z "$IMAGE" ]]; then
    (cd "$OUT" && "$ROOT/apksim" --headless --control "$PORT" --data-slots slotcheck "$@" app.apk \
      > "$OUT/$log" 2>&1 &)
  else
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    docker run -d --name "$NAME" --platform "$PLATFORM" -p "127.0.0.1:$PORT:$PORT" -v "$OUT:/work" \
      -v "$OUT/data:/opt/apksim/work/cache/data" "$IMAGE" \
      --control "$PORT" --control-bind 0.0.0.0 --data-slots slotcheck "$@" app.apk >/dev/null
  fi
  LAUNCHED=1
  for _ in $(seq 1 120); do drive ping 2>/dev/null | grep -q pong && break; sleep 1; done
  drive ping | grep -q pong || { echo "the app never answered on the control channel" >&2; tail -20 "$OUT/$log" >&2; exit 1; }
  drive 'wait 1500' 'open apksim://slot' 'wait 2000' >/dev/null
}
stop() {
  drive quit >/dev/null 2>&1 || true
  LAUNCHED=""
  if [[ -n "$IMAGE" ]]; then docker wait "$NAME" >/dev/null 2>&1 || true; docker logs "$NAME" > "$OUT/container-$1.log" 2>&1 || true; docker rm -f "$NAME" >/dev/null 2>&1 || true
  else for _ in $(seq 1 20); do drive ping >/dev/null 2>&1 || break; sleep 0.5; done; fi
}

echo "== a fresh slot, seeded"
start fresh.log --fresh --setting global:apksim_slot_seed=seeded
expect "onCreate saw" "$(text 'at app start:')" '^at app start: setting=null seed=seeded sdcard=missing$'
expect "external storage" "$(text 'storage:')" '^storage: mounted, Download inside /sdcard$'
tap_text Write
sleep 1
expect "written" "$(text 'now:')" '^now: setting=written seed=seeded sdcard=written$'
stop fresh

echo "== the same slot again"
start again.log
expect "onCreate saw" "$(text 'at app start:')" '^at app start: setting=written seed=seeded sdcard=written$'
stop again

echo "== --fresh"
start erased.log --fresh
expect "onCreate saw" "$(text 'at app start:')" '^at app start: setting=null seed=null sdcard=missing$'
stop erased
echo "slot check passed"
