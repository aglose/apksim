#!/bin/bash
# A launch whose main thread is slow to name the APK still runs the app as its manifest describes. Main builds Host's
# runner on a thread of its own while the main thread indexes the APK, and Robolectric reads the app's manifest while it
# builds that runner; one that got there before AppInfo.load kept the standby warm-up's manifest, with no app in it, so
# the app ran with Robolectric's default Application, theme and data folder and none of its own resources ("No package
# ID 7f found", a crawl seeing no controls). Seen in 0.58.0 on first launches into a new state folder while the startup
# cache trained beside them, under load. Here the main thread is held back the way that happened: another process holds
# the APK cache's lock (a training's first sweep of a new state folder holds it), so the runner is built first.
# The test app's Application logs "launch overlay added" with this feature: it runs only if the app's manifest was used.
#   scripts/late-manifest-check.sh STATE APK [APKSIM]
# STATE: the state folder the launch uses (APKSIM_HOME; a new folder on the Mac, the image's own in CI).
set -euo pipefail
STATE="$1" APK="$2" APKSIM="${3:-apksim}"
mkdir -p "$STATE/cache"
READY="$STATE/late-manifest.$$.held" LOG="$STATE/late-manifest.$$.log"
rm -f "$READY"
python3 - "$STATE/cache/apk-cache.lock" "$READY" <<'EOF' &
import fcntl, sys, time
f = open(sys.argv[1], "a+")
fcntl.lockf(f, fcntl.LOCK_EX)  # the same POSIX record lock Java's FileChannel.lock takes
open(sys.argv[2], "w").close()
time.sleep(20)  # longer than building the runner takes, also in an emulated CI image
EOF
holder=$!
until [[ -e "$READY" ]]; do kill -0 "$holder" 2>/dev/null || { echo "could not hold the APK cache's lock" >&2; exit 1; }; sleep 0.1; done
status=0
APKSIM_HOME="$STATE" APKSIM_LOGCAT=1 APKSIM_STANDBY=0 "$APKSIM" --headless --exit-after-first-frame \
  --feature dev.apksim.testapp.launch_overlay "$APK" > "$LOG" 2>&1 || status=$?
wait "$holder" || true
rm -f "$READY"
fail() { tail -30 "$LOG" >&2; echo "late-manifest-check: $1" >&2; exit 1; }
[[ $status == 0 ]] || fail "the launch ended with status $status"
grep -q first-frame "$LOG" || fail "no first frame"
grep -q "launch overlay added" "$LOG" || fail "the app's own Application did not run: the runner kept a manifest with no APK"
! grep -q "No package ID 7f" "$LOG" || fail "the app's resources were missing"
grep -q "built again" "$LOG" || fail "the runner was not built before the APK was known: the check reproduced nothing"
echo "late-manifest-check: the runner built ahead was built again, and the app ran with its own Application and resources"
rm -f "$LOG"
