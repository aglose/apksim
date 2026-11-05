#!/bin/bash
# Network record, replay and conditions against the test app's NetworkActivity (docs/network.md "Checks"):
# records its HTTPS fetches (OkHttp and HttpURLConnection) against scripts/network-fixture.py, checks the HAR
# (bodies kept, Authorization redacted), then with the server gone replays them, and checks an unmatched request,
# a slow network, and offline (lookups fail, ConnectivityManager has no network) and back.
#   scripts/network-check.sh APK OUT [IMAGE]
# Without IMAGE: ./apksim on this Mac. With IMAGE: the Linux image, the fixture server inside its container.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$1" OUT="$(mkdir -p "$2" && cd "$2" && pwd)" IMAGE="${3:-}"
PORT="${NETCHECK_PORT:-7470}" NAME="apksim-netcheck-$$"
cp "$APK" "$OUT/app.apk"
cp "$ROOT/scripts/network-fixture.py" "$OUT/"
rm -f "$OUT/rec.har" "$OUT/fixture.log"
mkdir -p "$OUT/fixture"
python3 "$OUT/network-fixture.py" "$OUT/fixture" --cert-only
drive() { python3 "$ROOT/scripts/drive.py" "$PORT" "$@"; }
FIXTURE_PID=""
cleanup() {
  if [[ -n "$FIXTURE_PID" ]]; then kill "$FIXTURE_PID" 2>/dev/null || true; fi
  if [[ -n "$IMAGE" ]]; then docker logs "$NAME" >> "$OUT/container.log" 2>&1 || true; docker rm -f "$NAME" >/dev/null 2>&1 || true; fi
  drive quit >/dev/null 2>&1 || true
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
# Taps $1, then waits for the line starting with $2 to show a result; prints it.
fetch() {
  tap_text "$1"
  local t
  for _ in $(seq 1 60); do
    sleep 0.5
    t="$(text "$2")"
    [[ -n "$t" && "$t" != *idle && "$t" != *fetching ]] && break
  done
  echo "$t"
}
expect() {  # expect WHAT ACTUAL PATTERN
  if [[ "$2" =~ $3 ]]; then echo "ok: $1: $2"; else echo "FAILED: $1: got \"$2\", wanted /$3/" >&2; exit 1; fi
}

# start LOG [fixture] -- APKSIM_ARGS...: the app (and the fixture), then the network screen.
start() {
  local log="$1" fixture="$2"; shift 3
  if [[ -z "$IMAGE" ]]; then
    if [[ -n "$fixture" ]]; then
      python3 "$OUT/network-fixture.py" "$OUT/fixture" > "$OUT/fixture.log" 2>&1 &
      FIXTURE_PID=$!
      for _ in $(seq 1 50); do grep -q ready "$OUT/fixture.log" 2>/dev/null && break; sleep 0.2; done
      FPORT="$(awk '/^ready/ {print $2}' "$OUT/fixture.log")"
    fi
    local args=("$@")
    args=("${args[@]//@FPORT@/$FPORT}")
    args=("${args[@]//@OUT@/$OUT}")
    (cd "$OUT" && "$ROOT/apksim" --window hidden --control "$PORT" "${args[@]}" app.apk > "$OUT/$log" 2>&1 &)
  else
    local pre=""
    [[ -n "$fixture" ]] && pre="python3 /work/network-fixture.py /work/fixture 8443 > /work/fixture.log 2>&1 & until grep -q ready /work/fixture.log 2>/dev/null; do sleep 0.2; done; "
    local args=("$@")
    args=("${args[@]//@FPORT@/8443}")
    args=("${args[@]//@OUT@//work}")
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    docker run -d --name "$NAME" --platform linux/amd64 -p "127.0.0.1:$PORT:$PORT" -v "$OUT:/work" --entrypoint bash "$IMAGE" \
      -c "${pre}exec apksim --control $PORT --control-bind 0.0.0.0 ${args[*]} app.apk > /work/$log 2>&1" >/dev/null
  fi
  for _ in $(seq 1 120); do drive ping 2>/dev/null | grep -q pong && break; sleep 1; done
  drive ping | grep -q pong || { echo "the app never answered on the control channel" >&2; tail -20 "$OUT/$log" >&2; exit 1; }
  drive 'wait 1500' 'open apksim://network' 'wait 2500' >/dev/null
}
stop() {
  drive quit >/dev/null 2>&1 || true
  if [[ -n "$IMAGE" ]]; then docker wait "$NAME" >/dev/null 2>&1 || true; docker logs "$NAME" >> "$OUT/container.log" 2>&1 || true; docker rm -f "$NAME" >/dev/null 2>&1 || true
  else for _ in $(seq 1 20); do drive ping >/dev/null 2>&1 || break; sleep 0.5; done; fi
  if [[ -n "$FIXTURE_PID" ]]; then kill "$FIXTURE_PID" 2>/dev/null || true; wait "$FIXTURE_PID" 2>/dev/null || true; FIXTURE_PID=""; fi
}

echo "== record against the fixture server"
start record.log fixture -- --network record @OUT@/rec.har --network-host "api.apksim.test=127.0.0.1:@FPORT@" \
  --network-trust @OUT@/fixture/cert.pem
expect "recorded OkHttp fetch" "$(fetch Fetch okhttp:)" '^okhttp: 200 Hello, apksim! in'
expect "recorded HttpURLConnection fetch" "$(fetch URL urlconnection:)" '^urlconnection: 200 Plain hello in'
stop
python3 - "$OUT/rec.har" <<'EOF'
import json, sys
text = open(sys.argv[1]).read()
log = json.loads(text)["log"]
entries = log["entries"]
urls = sorted(e["request"]["url"] for e in entries)
assert urls == ["https://api.apksim.test/v1/greeting", "https://api.apksim.test/v1/plain"], urls
for e in entries:
    auth = [h["value"] for h in e["request"]["headers"] if h["name"].lower() == "authorization"]
    assert auth == ["REDACTED"], auth
    assert e["response"]["status"] == 200 and "greeting" in e["response"]["content"]["text"], e["response"]
assert "test-token-123" not in text and "fixture-secret" not in text, "a secret reached the recording"
assert log.get("_apksimCertificates", {}).get("api.apksim.test"), "no certificate chain recorded"
print(f"ok: recording has {len(entries)} entries, bodies kept, Authorization and Set-Cookie redacted")
EOF

echo "== replay with the server gone"
start replay.log "" -- --network replay @OUT@/rec.har
expect "connectivity at launch" "$(text connectivity:)" '^connectivity: (wifi|cellular|vpn|other)'
expect "replayed OkHttp fetch" "$(fetch Fetch okhttp:)" '^okhttp: 200 Hello, apksim! \[recorded\] in'
expect "replayed HttpURLConnection fetch" "$(fetch URL urlconnection:)" '^urlconnection: 200 Plain hello \[recorded\] in'
expect "unmatched request" "$(fetch 'Fetch other' okhttp:)" '^okhttp: 599 network replay: no recorded response for POST .*\[unmatched\] in'
expect "unmatched list" "$(drive 'network unmatched')" 'POST https://api.apksim.test/v1/missing'
drive 'network latency 1500' >/dev/null
slow="$(fetch Fetch okhttp:)"
ms="$(sed -E 's/.* in ([0-9]+) ms$/\1/' <<<"$slow")"
expect "slow network" "$slow" '^okhttp: 200 Hello, apksim!'
(( ms >= 1500 )) || { echo "FAILED: a fetch with 1500 ms latency took $ms ms" >&2; exit 1; }
drive 'network offline' 'wait 1000' >/dev/null
# OkHttp may retry its last route (an address, no lookup) once its pooled connection drops: ConnectException then,
# as on a device. A fresh client looks the name up and fails there.
expect "offline fetch" "$(fetch Fetch okhttp:)" '^okhttp: (UnknownHostException: Unable to resolve host "api.apksim.test"|ConnectException: Failed to connect)'
expect "offline lookup" "$(fetch URL urlconnection:)" '^urlconnection: UnknownHostException: '
expect "offline connectivity" "$(text connectivity:)" '^connectivity: none$'
drive 'network online' 'wait 1000' >/dev/null
expect "back online" "$(fetch Fetch okhttp:)" '^okhttp: 200 Hello, apksim! \[recorded\]'
expect "online connectivity" "$(text connectivity:)" '^connectivity: (wifi|cellular|vpn|other)'
stop
echo "network check passed"
