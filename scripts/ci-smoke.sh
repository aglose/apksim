#!/bin/bash
# UI smoke test against the Linux image, for CI: the first frame, then a scripted session over the control
# channel (switch to Trips, rename a trip in SQLite, check the list changed). With UISERVER_APK (the test app
# project's uiserver module) and adb on the PATH, also the adb device as a tool's on-device server uses it
# (scripts/adb-check.py). Then screenshot tests on a small matrix (scripts/snapshot.py): goldens recorded, matched on a
# rerun, and a changed screen caught. Screens, logs and snapshot reports go to OUT.
#   scripts/ci-smoke.sh IMAGE APK OUT [UISERVER_APK]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="$1" APK="$2" OUT="$(mkdir -p "$3" && cd "$3" && pwd)" UISERVER="${4:-}"
PORT=7400 ADB_PORT=7401 NAME=apksim-smoke-$$
cp "$APK" "$OUT/app.apk"
drive() { python3 "$ROOT/scripts/drive.py" "$PORT" "$@"; }

docker run --rm --platform linux/amd64 -v "$OUT:/work" "$IMAGE" --exit-after-first-frame --shot first.png app.apk \
  > "$OUT/first.log" 2>&1 || { cat "$OUT/first.log"; exit 1; }
grep first-frame "$OUT/first.log"
test -s "$OUT/first.png" || { echo "no screenshot after the first frame" >&2; exit 1; }

docker run -d --name "$NAME" --platform linux/amd64 -p "127.0.0.1:$PORT:$PORT" -p "127.0.0.1:$ADB_PORT:$ADB_PORT" \
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
if [[ -n "$UISERVER" ]]; then
  python3 "$ROOT/scripts/adb-check.py" "localhost:$ADB_PORT" "$UISERVER"
  adb disconnect "localhost:$ADB_PORT" >/dev/null 2>&1 || true
fi
drive quit >/dev/null || true

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
snapshot() { docker run --rm --platform linux/amd64 -v "$SNAP:/work" --entrypoint snapshot.py "$IMAGE" "$@" --apk app.apk --goldens goldens; }
snapshot ci.json --update --out record > "$SNAP/record.log" 2>&1 || { cat "$SNAP/record.log"; exit 1; }
tail -2 "$SNAP/record.log" | head -1
snapshot ci.json --out rerun > "$SNAP/rerun.log" 2>&1 || { cat "$SNAP/rerun.log"; echo "a rerun did not match its goldens" >&2; exit 1; }
tail -2 "$SNAP/rerun.log" | head -1
status=0
snapshot changed.json --out changed > "$SNAP/changed.log" 2>&1 || status=$?
python3 - "$SNAP/changed/results.json" "$status" <<'EOF'
import json, sys
results = json.load(open(sys.argv[1]))["results"]
failed = sorted({r["screen"] for r in results if r["status"] != "match"})
assert sys.argv[2] == "1", f"a changed screen should exit 1, not {sys.argv[2]}"
assert failed == ["home"], f"only the changed screen should fail: {failed}"
print(f"changed screen caught: {sum(r['screen'] == 'home' for r in results)} home captures differ, the rest match")
EOF
echo "smoke test passed"
