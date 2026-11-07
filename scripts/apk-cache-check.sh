#!/bin/bash
# The per-APK cache folder stays bounded (ApkCache.java, issue 125): the same bytes under a new mtime reuse their entry,
# and eviction (here with a 0 MB cap) removes entries nobody uses, but never one a running instance holds. The sweep
# runs as it does by default: at most once an hour (so the check removes cache/apk-cache.swept first), and on the way
# out of a launch shorter than its 3 s delay, which every launch here is.
#   scripts/apk-cache-check.sh APK OUT [IMAGE]
# Without IMAGE: ./apksim on this Mac, with its own state folder (APKSIM_HOME=OUT/state, cold launches). With IMAGE: the
# Linux image, a container per launch, the cache on a volume in OUT shared by them, as containers sharing a cache
# volume have it.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$1" OUT="$(mkdir -p "$2" && cd "$2" && pwd)" IMAGE="${3:-}"
[[ -n "$IMAGE" ]] && PLATFORM="linux/$(docker image inspect -f '{{.Architecture}}' "$IMAGE")"
PORT="${APKCACHECHECK_PORT:-7490}" NAME="apksim-apkcachecheck-$$"
rm -rf "$OUT/a" "$OUT/b" "$OUT/state"
mkdir -p "$OUT/a" "$OUT/b" "$OUT/state/cache"
CACHE="$OUT/state/cache"
cp "$APK" "$OUT/a/app.apk"
# The same app with one more entry: other bytes, so another entry.
python3 - "$APK" "$OUT/b/app.apk" <<'EOF'
import shutil, sys, zipfile
shutil.copy(sys.argv[1], sys.argv[2])
with zipfile.ZipFile(sys.argv[2], "a") as z:
    z.writestr("assets/apk-cache-check.txt", "b\n")
EOF
drive() { python3 "$ROOT/scripts/drive.py" "$PORT" "$@"; }
LAUNCHED=""
cleanup() {
  if [[ -n "$IMAGE" ]]; then docker rm -f "$NAME" >/dev/null 2>&1 || true; fi
  if [[ -n "$LAUNCHED" ]]; then drive quit >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

# once DIR LOG ARGS...: one launch of DIR/app.apk to its first frame.
once() {
  local dir="$1" log="$2"; shift 2
  if [[ -z "$IMAGE" ]]; then
    (cd "$OUT/$dir" && APKSIM_HOME="$OUT/state" APKSIM_STANDBY=0 "$ROOT/apksim" --headless --exit-after-first-frame "$@" app.apk \
      > "$OUT/$log" 2>&1) || { tail -20 "$OUT/$log"; exit 1; }
  else
    docker run --rm --platform "$PLATFORM" -v "$OUT/$dir:/work" -v "$CACHE:/opt/apksim/work/cache" "$IMAGE" \
      --exit-after-first-frame "$@" app.apk > "$OUT/$log" 2>&1 || { tail -20 "$OUT/$log"; exit 1; }
  fi
}
entries() { (cd "$CACHE" && ls -d app.apk-* 2>/dev/null) | sort | tr '\n' ' '; }
# age SECONDS FILE: FILE's mtime that long ago (in the image as root: its files there are root's).
age() {
  local t=$(($(date +%s) - $1))
  if [[ -n "$IMAGE" ]]; then
    docker run --rm --platform "$PLATFORM" --entrypoint touch -v "$CACHE:/opt/apksim/work/cache" "$IMAGE" -d "@$t" "/opt/apksim/work/cache/${2#$CACHE/}"
  else
    touch -t "$(date -r "$t" +%Y%m%d%H%M.%S)" "$2"
  fi
}
fail() { echo "FAILED: $*" >&2; ls -la "$CACHE" >&2; exit 1; }

echo "== the same APK twice, touched in between"
once a first.log
A="$(entries)"
[[ "$A" =~ ^app\.apk-[0-9a-f]{16}\ $ ]] || fail "one content-keyed entry after the first launch, got: $A"
touch "$OUT/a/app.apk"
once a second.log
[[ "$(entries)" == "$A" ]] || fail "a touched APK made another entry: $(entries)"
echo "ok: one entry, $A"

echo "== eviction keeps the entry a running instance holds"
if [[ -z "$IMAGE" ]]; then
  (cd "$OUT/a" && APKSIM_HOME="$OUT/state" APKSIM_STANDBY=0 "$ROOT/apksim" --headless --control "$PORT" app.apk > "$OUT/held.log" 2>&1 &)
else
  docker run -d --name "$NAME" --platform "$PLATFORM" -p "127.0.0.1:$PORT:$PORT" -v "$OUT/a:/work" \
    -v "$CACHE:/opt/apksim/work/cache" "$IMAGE" --control "$PORT" --control-bind 0.0.0.0 app.apk >/dev/null
fi
LAUNCHED=1
for _ in $(seq 1 120); do drive ping 2>/dev/null | grep -q pong && break; sleep 1; done
drive ping | grep -q pong || { echo "the held instance never answered" >&2; exit 1; }
sleep 4  # past its own sweep (skipped: the earlier launches swept within the hour)
# Old enough to go, but in use; and an entry from an older apksim (no markers) and a half-deleted one, both to go.
age 7200 "$CACHE/${A% }/last-used"
mkdir -p "$CACHE/old.apk-1700000000000/lib" "$CACHE/.apk-cache-trash/left-over/dex"
echo '<manifest/>' > "$CACHE/old.apk-1700000000000/AndroidManifest.xml"
age 7200 "$CACHE/old.apk-1700000000000/AndroidManifest.xml"
rm -f "$CACHE/apk-cache.swept"
once b third.log --jvm -Dapksim.apkCacheMB=0
NOW="$(entries)"
[[ "$NOW" == *"${A% }"* ]] || fail "the running instance's entry was evicted: $NOW"
[[ "$(echo $NOW | wc -w)" -eq 2 ]] || fail "wanted the held entry and the new one, got: $NOW"
[[ ! -e "$CACHE/old.apk-1700000000000" ]] || fail "an unused old-format entry was kept"
[[ ! -e "$CACHE/.apk-cache-trash/left-over" ]] || fail "a left-over in the trash was kept"
echo "ok: kept $NOW, evicted the unused ones"
drive quit >/dev/null 2>&1 || true
LAUNCHED=""
if [[ -n "$IMAGE" ]]; then docker wait "$NAME" >/dev/null 2>&1 || true; docker rm -f "$NAME" >/dev/null 2>&1 || true
else for _ in $(seq 1 20); do drive ping >/dev/null 2>&1 || break; sleep 0.5; done; fi

echo "== once it has quit, its entry goes"
B="${NOW/${A% }/}"; B="${B// /}"
age 7200 "$CACHE/${A% }/last-used"
rm -f "$CACHE/apk-cache.swept"
once b fourth.log --jvm -Dapksim.apkCacheMB=0
[[ "$(entries)" == "$B " ]] || fail "wanted only $B, got: $(entries)"
echo "ok: only $B left"
echo "apk cache check passed"
