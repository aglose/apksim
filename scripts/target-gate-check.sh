#!/usr/bin/env bash
# The notification gate of a running instance (Notifications.allowed(): shown below target 33, else only with
# POST_NOTIFICATIONS) against the test app built for target 36 (default) and for 28 (-Papksim.targetSdk=28).
# Usage: scripts/target-gate-check.sh DEFAULT.apk T28.apk
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
[[ $# -eq 2 ]] || { echo "usage: $0 DEFAULT.apk T28.apk" >&2; exit 2; }
run() {  # APK LOG: taps Notify on the Settings tab, leaves the instance's stderr in LOG
  local log="$2" port
  "$ROOT/apksim" --headless --control auto "$1" > "$log" 2>&1 &
  local pid=$!
  for _ in $(seq 1 60); do grep -q "control port" "$log" && break; sleep 1; done
  port=$(grep -o "control port [0-9]*" "$log" | awk '{print $3}')
  python3 "$ROOT/scripts/drive.py" "$port" 'wait 2500' 'open apksim://composeapp/settings' 'wait 1500' 'tap-text Notify' 'wait 1500' 'quit' > /dev/null
  wait "$pid" 2>/dev/null || true
}
T="$(mktemp -d)"
run "$1" "$T/default.log"
run "$2" "$T/t28.log"
grep -q "notification not shown, POST_NOTIFICATIONS is not granted" "$T/default.log" || { echo "FAIL: the target-36 app's notification was shown without the permission"; exit 1; }
grep -q "^\[apksim\] notification: Trip reminder" "$T/t28.log" || { echo "FAIL: the target-28 app's notification was not shown"; exit 1; }
grep -q "not shown" "$T/t28.log" && { echo "FAIL: the target-28 app's notification was held back"; exit 1; }
echo "target-gate-check: ok (target 36 held back, target 28 shown)"
rm -rf "$T"
