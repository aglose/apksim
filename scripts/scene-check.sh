#!/bin/bash
# Scenes of linked instances (scripts/scene.py, docs/scenes.md) in the Linux image, for CI: the test app's board scene
# (two users of one backend: what one posts through the shared fixture, the other sees), its phone-and-watch scene (a
# BLE heart rate read on the phone reaches the watch), and a variant of the board scene that expects text nobody
# posts, which must fail at that step with the role's screen saved. scene.py runs inside the image with the repo's
# scripts mounted, so every instance and the fixture share the container's loopback.
#   scripts/scene-check.sh IMAGE APK OUT
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="$1" APK="$2" OUT="$(mkdir -p "$3" && cd "$3" && pwd)"
PLATFORM="linux/$(docker image inspect -f '{{.Architecture}}' "$IMAGE")"
cp "$APK" "$OUT/app.apk"
scene() {  # NAME SCENE_FILE (as the container sees it)
  rm -rf "${OUT:?}/$1"
  python3 "$ROOT/scripts/network-fixture.py" "$OUT/$1/fixture" --cert-only  # the image has no openssl
  docker run --rm --platform "$PLATFORM" -v "$OUT:/work" -v "$ROOT/scripts:/scene-scripts:ro" \
    -v "$ROOT/testapks/composeapp/scenes:/scenes:ro" --entrypoint python3 "$IMAGE" \
    /scene-scripts/scene.py "$2" --out "/work/$1" --apk /work/app.apk > "$OUT/$1.log" 2>&1
}
for s in board phone-watch; do
  scene "$s" "/scenes/$s.json" || { cat "$OUT/$s.log"; echo "the $s scene failed" >&2; exit 1; }
  grep -E '^scene ' "$OUT/$s.log"
done
python3 - "$ROOT/testapks/composeapp/scenes/board.json" "$OUT/wrong.json" <<'PY'
import json, sys
spec = json.load(open(sys.argv[1]))
spec["name"] = "wrong"
spec["steps"] = spec["steps"][:4] + [{"name": "bob waits for a post nobody made", "bob": "until 3000 hello from carol"}]
json.dump(spec, open(sys.argv[2], "w"), indent=1)
PY
status=0
scene wrong /work/wrong.json || status=$?
grep -E '^(FAILED:|scene) ' "$OUT/wrong.log" || true
[[ "$status" == 1 ]] && grep -q "^FAILED: step 5: bob: 'until 3000 hello from carol'" "$OUT/wrong.log" \
  && test -s "$OUT/wrong/failed-bob.png" \
  || { cat "$OUT/wrong.log"; echo "a scene step that cannot pass did not fail as it should (exit $status)" >&2; exit 1; }
echo "scene check: passed"
