#!/bin/bash
# UI smoke test against the Linux image, for CI: the first frame, then a scripted session over the control
# channel (switch to Trips, rename a trip in SQLite, check the list changed). With UISERVER_APK (the test app
# project's uiserver module) and adb on the PATH, also the adb device as a tool's on-device server uses it
# (scripts/adb-check.py). Then the network check (scripts/network-check.sh), what a data slot keeps across launches
# (scripts/slot-check.sh), screenshot tests on a small matrix
# (scripts/snapshot.py): goldens recorded, matched on a rerun, and a changed screen caught, and `apksim serve` in the
# image, driven as a browser tab would (scripts/serve-check.py). Screens, logs and snapshot reports go to OUT.
#   scripts/ci-smoke.sh IMAGE APK OUT [UISERVER_APK]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="$1" APK="$2" OUT="$(mkdir -p "$3" && cd "$3" && pwd)" UISERVER="${4:-}"
PORT=7400 ADB_PORT=7401 NAME=apksim-smoke-$$
# The image's own platform (linux/amd64 or linux/arm64), so Docker never pulls or emulates another.
PLATFORM="linux/$(docker image inspect -f '{{.Architecture}}' "$IMAGE")"
cp "$APK" "$OUT/app.apk"
drive() { python3 "$ROOT/scripts/drive.py" "$PORT" "$@"; }

docker run --rm --platform "$PLATFORM" -v "$OUT:/work" "$IMAGE" --exit-after-first-frame --shot first.png app.apk \
  > "$OUT/first.log" 2>&1 || { cat "$OUT/first.log"; exit 1; }
grep first-frame "$OUT/first.log"
test -s "$OUT/first.png" || { echo "no screenshot after the first frame" >&2; exit 1; }

docker run -d --name "$NAME" --platform "$PLATFORM" -p "127.0.0.1:$PORT:$PORT" -p "127.0.0.1:$ADB_PORT:$ADB_PORT" \
  -v "$OUT:/work" "$IMAGE" --control "$PORT" --adb "$ADB_PORT" --control-bind 0.0.0.0 app.apk >/dev/null
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

# A Trace async section begun twice is a no-op, as on an untraced device, and a tap outside a non-focusable popup (a
# slider's value tooltip) reaches the window behind it (the test app's apksim://popup-trace).
# "Tap me" (behind the popup) counts the tap and closes the tooltip.
popup() { drive nodes | grep -oE 'text="(trace: begun twice [0-9]+ times|tooltip: [a-z]+|taps: [0-9]+)"' | tr '\n' ' '; }
drive 'open apksim://popup-trace' 'wait 2000' 'tap 168 152' 'wait 500' 'tap 168 152' 'wait 500' \
  'swipe 200 392 500 392 300' 'wait 1000' >/dev/null
shown="$(popup)"
drive 'tap 143 632' 'wait 1000' >/dev/null
tapped="$(popup)"
echo "Popup and Trace: $shown-> $tapped"
[[ "$shown" == 'text="trace: begun twice 2 times" text="tooltip: shown" text="taps: 0" ' \
   && "$tapped" == 'text="trace: begun twice 2 times" text="tooltip: hidden" text="taps: 1" ' ]] \
  || { echo "a Trace section begun twice or a tap outside a non-focusable popup went wrong" >&2; exit 1; }
drive back 'wait 1000' >/dev/null
if [[ -n "$UISERVER" ]]; then
  python3 "$ROOT/scripts/adb-check.py" "localhost:$ADB_PORT" "$UISERVER"
  adb disconnect "localhost:$ADB_PORT" >/dev/null 2>&1 || true
fi
drive quit >/dev/null || true
# The app's network recorded against a fixture server, replayed without it, offline and slow (docs/network.md).
NETCHECK_PORT=7470 "$ROOT/scripts/network-check.sh" "$APK" "$OUT/network" "$IMAGE"
# A data slot's /sdcard and Settings values across launches, a container each, the app data on a shared volume.
SLOTCHECK_PORT=7480 "$ROOT/scripts/slot-check.sh" "$APK" "$OUT/slot" "$IMAGE"

# Screenshot tests in the image (scripts/snapshot.py, docs/snapshots.md), on a small matrix: record goldens, run again
# against them (the image must give the same pixels), then a spec whose home screen shows something else (one FAB tap)
# must fail on exactly those captures. Reports in OUT/snapshots/*/report.html.
SNAP="$OUT/snapshots"
rm -rf "$SNAP" && mkdir -p "$SNAP"
cp "$APK" "$SNAP/app.apk"
cp "$ROOT/testapks/composeapp/snapshots/ci.json" "$SNAP/ci.json"
python3 - "$SNAP/ci.json" "$SNAP/changed.json" <<'EOF'
import json, sys
spec = json.load(open(sys.argv[1]))
for s in spec["screens"]:
    if s["name"] == "home":
        s["steps"] = ["tap-text Add"]
json.dump(spec, open(sys.argv[2], "w"), indent=1)
EOF
snapshot() { docker run --rm --platform "$PLATFORM" -v "$SNAP:/work" --entrypoint snapshot.py "$IMAGE" "$@" --apk app.apk --goldens goldens; }
snapshot ci.json --update --out record > "$SNAP/record.log" 2>&1 || { cat "$SNAP/record.log"; exit 1; }
tail -2 "$SNAP/record.log" | head -1
snapshot ci.json --out rerun > "$SNAP/rerun.log" 2>&1 || { cat "$SNAP/rerun.log"; echo "a rerun did not match its goldens" >&2; exit 1; }
tail -2 "$SNAP/rerun.log" | head -1
status=0
snapshot changed.json --out changed > "$SNAP/changed.log" 2>&1 || status=$?
python3 - "$SNAP/changed/results.json" "$status" <<'EOF'
import json, sys
results = json.load(open(sys.argv[1]))["results"]
assert sys.argv[2] == "1", f"a changed screen should exit 1, not {sys.argv[2]}"
home = [r for r in results if r["screen"] == "home"]
rest = [r for r in results if r["screen"] != "home"]
assert home and all(r["status"] == "mismatch" for r in home), \
    f"every home capture should be a mismatch: {[(r['combo'], r['status'], r['detail']) for r in home]}"
assert rest and all(r["status"] == "match" for r in rest), \
    f"every other capture should match: {[(r['screen'], r['combo'], r['status']) for r in rest if r['status'] != 'match']}"
print(f"changed screen caught: all {len(home)} home captures differ, all {len(rest)} others match")
EOF

# apksim serve: a token (any address but loopback needs one), only serve's port published, the test app listed.
SERVE_NAME=apksim-serve-smoke-$$ SERVE_PORT=7402
head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$OUT/serve.token"
docker run -d --name "$SERVE_NAME" --platform "$PLATFORM" -p "127.0.0.1:$SERVE_PORT:8080" -v "$OUT:/work" "$IMAGE" \
  serve --bind 0.0.0.0 --token /work/serve.token --apps /work/app.apk --max-upload 64 >/dev/null
trap 'docker logs "$NAME" > "$OUT/run.log" 2>&1 || true; docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker logs "$SERVE_NAME" > "$OUT/serve.log" 2>&1 || true; docker rm -f "$SERVE_NAME" >/dev/null 2>&1 || true' EXIT
for _ in $(seq 1 60); do curl -sf -H "Authorization: Bearer $(cat "$OUT/serve.token")" "http://127.0.0.1:$SERVE_PORT/api/health" >/dev/null && break; sleep 1; done
python3 "$ROOT/scripts/serve-check.py" "http://127.0.0.1:$SERVE_PORT" --token "$OUT/serve.token" --max-upload-mb 64 \
  | tee "$OUT/serve-check.log"
echo "smoke test passed"
