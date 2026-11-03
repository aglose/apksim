#!/bin/bash
# Restarts the apksim instance on a control port with the current build, and waits until the app is up.
#   scripts/relaunch.sh PORT APK [LOG]
# Quits the old instance first (only the one on PORT) and fails if the port stays busy, rather than leaving a new
# instance that exited at once while the old one keeps running the previous build.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PORT="$1" APK="$2" LOG="${3:-$ROOT/work/run-$1.log}"
python3 "$ROOT/scripts/drive.py" "$PORT" quit >/dev/null 2>&1 || true
for _ in $(seq 1 40); do nc -z 127.0.0.1 "$PORT" 2>/dev/null || break; sleep 0.25; done
if nc -z 127.0.0.1 "$PORT" 2>/dev/null; then echo "port $PORT is still in use" >&2; exit 1; fi
nohup "$ROOT/apksim" --control "$PORT" --window-x 900 "$APK" > "$LOG" 2>&1 &
for _ in $(seq 1 240); do
  grep -q "first-frame" "$LOG" 2>/dev/null && { echo "up: $(grep -m1 first-frame "$LOG")"; exit 0; }
  grep -qE "Address already in use|fatal error" "$LOG" 2>/dev/null && { echo "failed to start, see $LOG" >&2; exit 1; }
  sleep 0.25
done
echo "no first frame within 60 s, see $LOG" >&2
exit 1
