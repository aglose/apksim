#!/bin/bash
# UI smoke test against the Linux image, for CI: the first frame, then a scripted session over the control
# channel (switch to Trips, rename a trip in SQLite, check the list changed). Screens and logs go to OUT.
#   scripts/ci-smoke.sh IMAGE APK OUT
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="$1" APK="$2" OUT="$(mkdir -p "$3" && cd "$3" && pwd)"
PORT=7400 NAME=apksim-smoke-$$
cp "$APK" "$OUT/app.apk"
drive() { python3 "$ROOT/scripts/drive.py" "$PORT" "$@"; }

docker run --rm --platform linux/amd64 -v "$OUT:/work" "$IMAGE" --exit-after-first-frame --shot first.png app.apk \
  > "$OUT/first.log" 2>&1 || { cat "$OUT/first.log"; exit 1; }
grep first-frame "$OUT/first.log"
test -s "$OUT/first.png" || { echo "no screenshot after the first frame" >&2; exit 1; }

docker run -d --name "$NAME" --platform linux/amd64 -p "127.0.0.1:$PORT:$PORT" -v "$OUT:/work" "$IMAGE" \
  --control "$PORT" --control-bind 0.0.0.0 app.apk >/dev/null
trap 'docker logs "$NAME" > "$OUT/run.log" 2>&1 || true; docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT
# Docker's port proxy accepts before the app listens, so wait for an answer rather than a connection.
for _ in $(seq 1 120); do drive ping 2>/dev/null | grep -q pong && break; sleep 1; done
drive ping | grep -q pong || { echo "the app never answered on the control channel" >&2; exit 1; }

trip() { drive nodes | grep -oE 'text="(Italian|Roman) vacation"' | head -1; }
drive 'tap 350 1690' 'wait 1500' >/dev/null
before="$(trip)"
drive 'tap 300 256' 'wait 1500' 'shot /work/trips.png' >/dev/null
after="$(trip)"
echo "Trips: $before -> $after"
[[ -n "$before" && -n "$after" && "$before" != "$after" ]] || { echo "the trip rename did not show" >&2; exit 1; }

# Adaptive layout: the test app shows its window size class in the top bar. Another device, then a custom size.
sizeclass() { drive nodes | grep -oE 'text="(compact|medium|expanded) [0-9]+×[0-9]+"' | head -1; }
drive 'device tablet' 'wait 2000' >/dev/null
tablet="$(sizeclass)"
drive 'size 700x900' 'wait 2000' >/dev/null
medium="$(sizeclass)"
drive 'device phone' 'wait 2000' >/dev/null
echo "Size classes: $tablet, $medium, $(sizeclass)"
[[ "$tablet" == 'text="expanded 1280×800"' && "$medium" == 'text="medium 700×900"' ]] \
  || { echo "the app did not follow the display's new size" >&2; exit 1; }
drive quit >/dev/null || true
echo "smoke test passed"
