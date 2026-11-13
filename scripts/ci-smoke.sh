#!/bin/bash
# UI smoke test against the Linux image, for CI: the first frame, then a scripted session over the control
# channel (switch to Trips, rename a trip in SQLite, check the list changed), and `--activity`. With UISERVER_APK (the test app
# project's uiserver module) and adb on the PATH, also the adb device as a tool's on-device server uses it
# (scripts/adb-check.py). Then the network check (scripts/network-check.sh), what a data slot keeps across launches
# (scripts/slot-check.sh), the per-APK cache folder staying bounded (scripts/apk-cache-check.sh), screenshot tests on a small matrix
# (scripts/snapshot.py): goldens recorded, matched on a rerun, and a changed screen caught, and `apksim serve` in the
# image, driven as a browser tab would (scripts/serve-check.py). The crawler (scripts/crawl-check.py) explores the test app's screens. Screens, logs and snapshot reports go to OUT.
#   scripts/ci-smoke.sh IMAGE APK OUT [UISERVER_APK]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMAGE="$1" APK="$2" OUT="$(mkdir -p "$3" && cd "$3" && pwd)" UISERVER="${4:-}"
PORT=7400 ADB_PORT=7401 NAME=apksim-smoke-$$
# The image's own platform (linux/amd64 or linux/arm64), so Docker never pulls or emulates another.
PLATFORM="linux/$(docker image inspect -f '{{.Architecture}}' "$IMAGE")"
cp "$APK" "$OUT/app.apk"
drive() { python3 "$ROOT/scripts/drive.py" "$PORT" "$@"; }
# SMOKE_PARTS (space-separated; default all) runs only some parts, as CI's parallel legs do: app (one instance driven
# over the control channel, the adb device, --activity, the camera, crashes and exits), device (network, data slot, APK
# cache), replay (replay, bisect, journeys, coverage, screenshot tests), crawl and serve.
part() { [[ " ${SMOKE_PARTS:-all} " == *" all "* || " ${SMOKE_PARTS:-all} " == *" $1 "* ]]; }

if part app; then
# scripts/conformance.py reads `am instrument` output and tables two runs (docs/conformance.md); no device needed.
python3 "$ROOT/scripts/conformance-check.py"
docker run --rm --platform "$PLATFORM" -v "$OUT:/work" "$IMAGE" --exit-after-first-frame --shot first.png app.apk \
  > "$OUT/first.log" 2>&1 || { cat "$OUT/first.log"; exit 1; }
grep first-frame "$OUT/first.log"
test -s "$OUT/first.png" || { echo "no screenshot after the first frame" >&2; exit 1; }
# An overlay window the app adds before its activity and never sizes (0x0) is the bottom window: the first frame must
# not wait for it (issue 83, the screen stayed black). The test app adds one with this feature. Without the fix the run
# never ends, hence the timeout (where there is one: macOS has no timeout).
OVERLAY_NAME=apksim-overlay-smoke-$$
limit() { if command -v timeout >/dev/null; then timeout "$@"; else shift; "$@"; fi; }
if ! limit 300 docker run --rm --name "$OVERLAY_NAME" --platform "$PLATFORM" -e APKSIM_LOGCAT=1 -v "$OUT:/work" "$IMAGE" \
    --exit-after-first-frame --feature dev.apksim.testapp.launch_overlay app.apk > "$OUT/overlay.log" 2>&1; then
  docker rm -f "$OVERLAY_NAME" >/dev/null 2>&1 || true
  tail -20 "$OUT/overlay.log"; echo "no first frame with an unsized overlay window at the bottom" >&2; exit 1
fi
grep -q "launch overlay added" "$OUT/overlay.log" || { echo "the test app added no launch overlay" >&2; exit 1; }
grep first-frame "$OUT/overlay.log"

docker run -d --name "$NAME" --platform "$PLATFORM" -p "127.0.0.1:$PORT:$PORT" -p "127.0.0.1:$ADB_PORT:$ADB_PORT" \
  -v "$OUT:/work" "$IMAGE" --control "$PORT" --adb "$ADB_PORT" --control-bind 0.0.0.0 \
  --timezone America/New_York --sensor light=75 app.apk >/dev/null
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
# A full-screen FLAG_NOT_TOUCHABLE overlay above the activity takes no touch (issue 323): a tap on Settings lands.
drive 'open apksim://composeapp/trips?overlay=full' 'wait 2500' 'tap 550 1690' 'wait 1000' >/dev/null
drive nodes | grep -q 'text="Notifications"' || { echo "a tap did not pass an untouchable overlay" >&2; exit 1; }
echo "Popup and Trace: $shown-> $tapped"
# A browser sign-in's way back (issue 336): the redirect activity hands the callback to the waiting singleTask activity
# with CLEAR_TOP | SINGLE_TOP, which gets it in onNewIntent; stacked as a second copy, it showed "not started".
drive 'open apksim://signin' 'wait 1500' 'tap-text Sign in' 'wait 500' 'open apksim://signin-callback?code=smoke42' 'wait 1500' >/dev/null
signin="$(drive nodes | grep -oE 'text="signin: [^"]*"')"
echo "Sign-in: $signin"
[[ "$signin" == 'text="signin: code=smoke42 (instances 1, new intents 1)"' ]] \
  || { echo "a sign-in's redirect did not reach the waiting activity" >&2; exit 1; }
drive back 'wait 800' >/dev/null
[[ "$shown" == 'text="trace: begun twice 2 times" text="tooltip: shown" text="taps: 0" ' \
   && "$tapped" == 'text="trace: begun twice 2 times" text="tooltip: hidden" text="taps: 1" ' ]] \
  || { echo "a Trace section begun twice or a tap outside a non-focusable popup went wrong" >&2; exit 1; }
drive back 'wait 1000' >/dev/null
# A ValueAnimator set to repeat INFINITE times repeats for ever: apksim://bitmaps' square, turning once a second, still
# turns after 3.5 s (Robolectric's shadow made INFINITE 1, so it stopped at 360 after two turns).
spin() { drive viewtree | tr ';' '\n' | grep -oE '48x48 rotation=[0-9.]+' || echo 'not turned'; }
drive 'open apksim://bitmaps' 'wait 3500' >/dev/null
turned="$(spin)"
drive 'wait 300' >/dev/null
later="$(spin)"
echo "Endless animator: $turned -> $later"
[[ "$turned" != "$later" ]] || { echo "a ValueAnimator repeating INFINITE times stopped" >&2; exit 1; }
drive back 'wait 1000' >/dev/null
# An accessibility provider that lists nodes under several parents and an ancestor as a child must not hang `nodes`
# (issue 114: the walk repeated every listing down to its depth cap). Each node is listed once.
drive 'open apksim://cyclic-nodes' 'wait 4000' >/dev/null
raw="$(perl -e 'alarm 20; exec @ARGV' python3 "$ROOT/scripts/drive.py" "$PORT" nodes)" \
  || { echo "nodes hung on a provider with repeated nodes" >&2; exit 1; }
cyc="$(echo "$raw" | grep -oE 'text="cyclic: [a-z0-9 ]+"' | tr '\n' ' ' || true)"
echo "Cyclic nodes: $cyc"
[[ "$cyc" == 'text="cyclic: root" text="cyclic: node 1" text="cyclic: node 2" text="cyclic: node 3" ' ]] \
  || { echo "$raw" | cut -c1-400; echo "nodes did not list each node of a repeating provider once" >&2; exit 1; }
drive back 'wait 1000' >/dev/null
# System state as apps read it and await it in coroutines (issue 96): Wi-Fi's signal level bucketed as
# level / (max / 4) (getMaxSignalLevel() was 0: a division by zero), Wi-Fi's, the network's and the battery's sticky
# broadcasts, network
# callbacks (one registered in Application.onCreate), VOLUME_CHANGED_ACTION, a settings observer
# (apksim://system-sources, 3 s each). Bluetooth's profile proxy waits for Bluetooth to be on, as on a device.
drive 'open apksim://system-sources' 'wait 5000' >/dev/null
sources="$(drive nodes | grep -oE 'text="[a-z ]+: [^"]*"' | grep -v 'bluetooth profile' || true)"
echo "System sources: $(echo "$sources" | wc -l | tr -d ' ') answered or timed out"
if [[ "$(echo "$sources" | grep -c ': ')" -lt 12 ]] || echo "$sources" | grep -qE 'timed out|: error'; then
  echo "$sources"; echo "a system source never answered" >&2; exit 1
fi
drive back 'wait 1000' >/dev/null

# Scripted BLE peripherals (docs/ble.md): apksim://ble scans, connects and shows a heart-rate preset's notifications.
python3 "$ROOT/scripts/ble-check.py" "$PORT" > "$OUT/ble.log" 2>&1 && tail -1 "$OUT/ble.log" || { cat "$OUT/ble.log"; exit 1; }
# Health Connect (docs/health-connect.md): a seeded week, the permissions granted, and apksim://health reads the week's
# steps, the latest heart rate and last night's sleep through AndroidX's client; its insert adds 100 to today's steps.
health() { drive nodes | grep -oE 'text="(health|permissions|steps today|steps this week|heart rate|sleep|inserted): [^"]*"' | tr '\n' ' '; }
drive 'health add week' 'permission grant health.READ_STEPS health.WRITE_STEPS health.READ_HEART_RATE health.WRITE_HEART_RATE health.READ_SLEEP' >/dev/null
drive 'open apksim://health' 'wait 4000' >/dev/null
seen="$(health)"
drive 'tap-text INSERT 100 STEPS' 'wait 3000' >/dev/null
inserted="$(health)"
echo "Health Connect: $seen-> $inserted"
num() { echo "$1" | grep -oE "$2: [0-9]+" | grep -oE '[0-9]+$'; }
[[ "$seen" == *'health: available'* && "$seen" == *'permissions: 5 of 5 granted'* && "$seen" =~ 'heart rate: '[0-9]+' bpm at' \
   && "$seen" =~ 'sleep: '[0-9]+' h' && "$(num "$seen" 'steps this week')" -gt 20000 \
   && "$(num "$inserted" 'steps today')" == "$(( $(num "$seen" 'steps today') + 100 ))" ]] \
  || { drive health; echo "apksim://health did not read the seeded week or its insert" >&2; exit 1; }
drive back 'wait 1000' >/dev/null
# The week preset is the same for the same days: seeded at two clocks a day apart, the records of the days both cover
# have the same ids (a seed's id comes from its content).
python3 - "$ROOT/scripts/drive.py" "$PORT" <<'EOF'
import subprocess, sys
drive, port = sys.argv[1], sys.argv[2]
def run(*cmds):
    return subprocess.run(["python3", drive, port, *cmds], capture_output=True, text=True).stdout
def ids(clock):
    out = run("health clear", f"clock {clock}", "health add week", "health list steps").splitlines()[-1]
    return {e.split()[1] for e in out.split(" -> ", 1)[1].split("; ")[1:] if "2026-01-06" <= e[:10] <= "2026-01-08"}
a, b = ids("2026-01-10T12:00:00Z"), ids("2026-01-11T12:00:00Z")
run("health clear", "clock real")
print(f"Health week preset: {len(a)} steps records on 6-8 Jan (UTC) at both clocks, {len(a ^ b)} differing")
assert a and a == b, f"the week preset changed between launches: {sorted(a ^ b)[:5]}"
EOF
# Every zone source an app reads follows --timezone, Joda-Time's (it reads user.timezone) included, and `timezone ZONE`
# changes them all and broadcasts ACTION_TIMEZONE_CHANGED, which the screen hears as joda-time-android does (the test
# app's apksim://timezone, issue 91).
zones() { drive nodes | grep -oE 'text="(jdk zone|icu zone|user.timezone|joda zone|tz changes): [^"]*"' | tr '\n' ' '; }
expect() { printf 'text="%s zone: %s" ' jdk "$1" icu "$1"; printf 'text="user.timezone: %s" text="joda zone: %s" text="tz changes: %s" ' "$1" "$1" "$2"; }
drive 'open apksim://timezone' 'wait 2000' >/dev/null
launched="$(zones)"
drive 'timezone Asia/Tokyo' 'wait 1000' >/dev/null
changed="$(zones)"
echo "Time zones: $launched-> $changed"
[[ "$launched" == "$(expect America/New_York 0)" && "$changed" == "$(expect Asia/Tokyo '1 to Asia/Tokyo')" ]] \
  || { echo "the app's time zones did not follow --timezone or the timezone command" >&2; exit 1; }
drive back 'wait 1000' >/dev/null

# ACTION_TIME_TICK (issue 109): a receiver registered at runtime hears one tick when the app's clock crosses a minute,
# and none more within that minute (the test app's apksim://timezone counts them as "ticks: N").
ticks() { drive nodes | grep -oE 'text="ticks: [0-9]+"'; }
drive 'clock 2026-01-01T09:41:30Z' 'wait 1000' >/dev/null  # the clock change settles before the receiver registers
drive 'open apksim://timezone' 'wait 2000' >/dev/null
before="$(ticks)"
drive 'clock 2026-01-01T09:42:05Z' 'wait 1000' >/dev/null
crossed="$(ticks)"
drive 'clock 2026-01-01T09:42:20Z' 'wait 1000' >/dev/null
same="$(ticks)"
echo "Time ticks: $before -> $crossed -> $same"
[[ "$before" == 'text="ticks: 0"' && "$crossed" == 'text="ticks: 1"' && "$same" == 'text="ticks: 1"' ]] \
  || { echo "the app did not hear one ACTION_TIME_TICK at the minute boundary" >&2; exit 1; }
drive back 'wait 1000' >/dev/null

# Sensors (Sensors.java, the test app's apksim://sensors): the device lists an emulator's sensors, each registered
# listener gets a first event at once (an app awaiting one must not hang), at rest the accelerometer reads gravity on the
# axis the rotation gives, and `sensor ...` changes what the listeners hear: light and proximity (on-change), the pose.
sensor_line() { drive nodes | grep -oE "text=\"$1 \(type [0-9]+[^\"]*\"" | grep -oE ': [-0-9. ]+, n=[0-9]+' | head -1; }
drive 'open apksim://sensors' 'wait 2500' >/dev/null
count="$(drive nodes | grep -oE 'text="sensors: [0-9]+"')"
rest="$(sensor_line 'Goldfish 3-axis Accelerometer')"
light0="$(sensor_line 'Goldfish Light sensor')"
drive 'sensor light 300' 'sensor proximity near' 'wait 600' >/dev/null
light1="$(sensor_line 'Goldfish Light sensor')"
prox="$(sensor_line 'Goldfish Proximity sensor')"
drive 'rotate right' 'wait 800' >/dev/null
turned="$(sensor_line 'Goldfish 3-axis Accelerometer')"
drive 'rotate left' 'sensor tilt 0 0' 'wait 800' >/dev/null
flat="$(sensor_line 'Goldfish 3-axis Accelerometer')"
drive 'sensor light off' 'sensor' | grep -q 'removed: light' || { echo "sensor light off did not take the light sensor away" >&2; exit 1; }
echo "Sensors: $count; at rest$rest; light$light0 -> $light1; proximity$prox; rotated$turned; flat$flat"
[[ "$count" == 'text="sensors: 18"' && "$rest" == ': 0.00 9.81 0.00, n='* && "$light0" == ': 75.00, n=1' && "$light1" == ': 300.00, n=2' \
   && "$prox" == ': 0.00, n=2' && "$turned" == ': 9.81 0.00 0.00, n='* && "$flat" == ': 0.00 0.00 9.81, n='* ]] \
  || { echo "the sensors did not deliver, follow the rotation or follow the sensor command" >&2; exit 1; }
# The rate is the one asked for (UI, 15 Hz, on the main looper; GAME, 50 Hz, on a HandlerThread while the screen is still),
# not a few events a second (a background looper's tick only runs when Host's idle sleep lets the clock reach it).
acc_n() { sensor_line 'Goldfish 3-axis Accelerometer' | grep -oE 'n=[0-9]+' | cut -d= -f2; }
n0="$(acc_n)"; drive 'wait 2000' >/dev/null; n1="$(acc_n)"
drive back 'open apksim://sensors?rate=game&thread=bg' 'wait 1500' >/dev/null
b0="$(acc_n)"; drive 'wait 2000' >/dev/null; b1="$(acc_n)"
echo "Sensor rates: UI $((n1 - n0)) in 2 s (15 Hz: 30), GAME on a HandlerThread $((b1 - b0)) in 2 s (50 Hz: 100)"
(( n1 - n0 >= 24 && n1 - n0 <= 36 && b1 - b0 >= 85 && b1 - b0 <= 115 )) \
  || { echo "the sensors did not run at the rate asked" >&2; exit 1; }
# A screen that registers listeners but does not redraw settles (the sensors' ticks are not work that changes the screen).
drive 'back' 'open apksim://sensors?redraw=0' 'wait 1500' 'settle 5000 300' | tail -1 | grep -q 'settled in' \
  || { echo "a screen with sensor listeners never settled" >&2; exit 1; }
# Device kinds: what an emulator of each lists (a TV none, a watch adds heart rate, a desktop has no proximity or steps).
kinds=""
drive 'sensor light on' >/dev/null
for k in watch tv desktop phone; do kinds="$kinds $k=$(drive "device $k" 'wait 800' 'sensor' | grep -oE 'sensors: [0-9]+' | tail -1 | cut -d' ' -f2)"; done
echo "Sensors by device:$kinds"
[[ "$kinds" == " watch=18 tv=0 desktop=14 phone=20" ]] || { echo "a device kind did not list its sensors" >&2; exit 1; }
drive 'sensor reset' 'back' 'wait 1000' >/dev/null

# What installing gives the app before Application.onCreate (a normal permission, ANDROID_ID, its providers), and a bind
# to a service nothing provides: false and no callbacks, so the screen's Kotlin connection does not end the app
# (the test app's apksim://install, issues 76 and 81).
install() { drive nodes | grep -oE 'text="(install|android id|provider|bind missing): [^"]*"' | tr '\n' ' '; }
drive 'open apksim://install' 'wait 3000' >/dev/null
installed="$(install)"
echo "Install state: $installed"
[[ "$installed" == 'text="install: vibrate granted at create" text="android id: set at create" text="provider: before create" text="bind missing: false, unbound, 0 callbacks" ' ]] \
  || { echo "the app did not start with what installing gives it, or a bind to nothing called back" >&2; exit 1; }
drive back 'wait 1000' >/dev/null

# A frame rate cap (maxfps, as --max-fps): the Shader tab animates without end, and draws no faster than the cap.
# Counted from `stats` over a timed window, not `frames`: that leaves out intervals of 100 ms or more, and under
# Rosetta a CPU-drawn Shader frame can take that long.
cap=20
drive "maxfps $cap" 'tap 720 1690' 'wait 1500' >/dev/null
capped="$(python3 - "$ROOT/scripts/drive.py" "$PORT" "$cap" <<'EOF'
import re, subprocess, sys, time
drive, port, cap = sys.argv[1], sys.argv[2], int(sys.argv[3])
def frames():
    out = subprocess.run(["python3", drive, port, "stats"], capture_output=True, text=True).stdout
    return int(re.search(r"frames=(\d+)", out).group(1))
f0, t0 = frames(), time.monotonic()
time.sleep(4)
n, secs = frames() - f0, time.monotonic() - t0
print(f"{n} frames in {secs:.1f} s ({n / secs:.1f} fps)")
assert 0 < n <= cap * secs + 2, f"not capped at {cap} fps: {n} frames in {secs:.1f} s"
EOF
)" || { echo "$capped" >&2; exit 1; }
drive 'maxfps 120' 'tap 130 1690' 'wait 1000' >/dev/null
echo "Capped at $cap fps: $capped"
# Recorded video on Linux (issue 229, libavcodec behind MediaCodec): selftest on the H.264 fixture clip, the 1080p60
# H.264 clip of apksim://video drawing pictures without decode errors, and no HEVC decoder listed (the image is built
# with H.264 alone; for one built with VIDEO_DECODERS=h264,hevc set VIDEO_CHECK_ARGS=--hevc, which plays the Settings
# tab's HEVC clip instead). scripts/video-check.py. Without libvtdecoder.so the image lists no video decoders and this fails.
cp "$ROOT/testapks/media-fixture/clip.h264" "$ROOT/testapks/media-fixture/clip.h265" "$OUT/"
python3 "$ROOT/scripts/video-check.py" "$PORT" --clips /work ${VIDEO_CHECK_ARGS:-} | tee "$OUT/video-check.log"
drive 'tap 130 1690' 'wait 500' >/dev/null
if [[ -n "$UISERVER" ]]; then
  python3 "$ROOT/scripts/adb-check.py" "localhost:$ADB_PORT" "$UISERVER"
  adb disconnect "localhost:$ADB_PORT" >/dev/null 2>&1 || true
fi
# An app's own default uncaught-exception handler gets a coroutine's crash and decides: this one keeps the app running
# (issue 104, the test app's apksim://crash?kind=handled). Last: the handler stays the app's for the rest of the run.
handled="$(drive 'open apksim://crash?kind=handled' 'wait 2500' nodes | grep -oE 'text="crash: handled by the app [^"]*"' || true)"
echo "Crash with the app's own handler: $handled"
[[ -n "$handled" ]] && drive ping | grep -q pong || { echo "the app's own uncaught-exception handler was not called" >&2; exit 1; }
drive quit >/dev/null || true

# --activity: a non-exported activity started instead of the launcher one, kept by restart and process-death; back on
# it ends the app. One the manifest lacks stops the launch with exit 2 and the manifest's activities.
ACT_PORT=7403 ACT_NAME=$NAME-activity
# Its log saved and the container removed once checked (act_done), and by the trap meanwhile: later stages replace it.
act_done() { docker logs "$ACT_NAME" > "$OUT/activity.log" 2>&1 || true; docker rm -f "$ACT_NAME" >/dev/null 2>&1 || true; }
trap 'docker logs "$NAME" > "$OUT/run.log" 2>&1 || true; docker rm -f "$NAME" >/dev/null 2>&1 || true; act_done' EXIT
docker run -d --name "$ACT_NAME" --platform "$PLATFORM" -p "127.0.0.1:$ACT_PORT:$ACT_PORT" -v "$OUT:/work" "$IMAGE" \
  --activity .TripChooserActivity --control "$ACT_PORT" --control-bind 0.0.0.0 app.apk >/dev/null
act() { python3 "$ROOT/scripts/drive.py" "$ACT_PORT" "$@"; }
for _ in $(seq 1 120); do act ping 2>/dev/null | grep -q pong && break; sleep 1; done
started="$(act activity restart 'wait 1500' activity process-death 'wait 1500' activity | grep -c '^activity -> dev.apksim.composeapp.TripChooserActivity$' || true)"
act back >/dev/null || true
for _ in $(seq 1 30); do [[ "$(docker inspect -f '{{.State.Running}}' "$ACT_NAME")" == false ]] && break; sleep 1; done
running="$(docker inspect -f '{{.State.Running}}' "$ACT_NAME")"
act_done
trap 'docker logs "$NAME" > "$OUT/run.log" 2>&1 || true; docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT
code=0
docker run --rm --platform "$PLATFORM" -v "$OUT:/work" "$IMAGE" --headless --activity .NoSuchActivity app.apk \
  > "$OUT/activity-missing.log" 2>&1 || code=$?
echo "--activity: started and kept $started of 3 times, running after back: $running, missing one: exit $code"
[[ "$started" == 3 && "$running" == false && "$code" == 2 ]] && grep -q 'no activity .*NoSuchActivity' "$OUT/activity-missing.log" \
  || { echo "--activity did not start, keep or refuse the activity asked for" >&2; cat "$OUT/activity-missing.log" >&2; exit 1; }

# The simulated cameras (issue 219): with --camera fake CameraX binds the test app's apksim://camera (a Preview in a
# SurfaceView and an ImageAnalysis on the front camera), and both get the test pattern's frames.
CAM_PORT=7405 CAM_NAME=$NAME-camera
trap 'docker logs "$NAME" > "$OUT/run.log" 2>&1 || true; docker rm -f "$NAME" "$CAM_NAME" >/dev/null 2>&1 || true' EXIT
docker run -d --name "$CAM_NAME" --platform "$PLATFORM" -p "127.0.0.1:$CAM_PORT:$CAM_PORT" -v "$OUT:/work" "$IMAGE" \
  --camera fake --permissions grant --activity .CameraActivity --control "$CAM_PORT" --control-bind 0.0.0.0 app.apk >/dev/null
cam() { python3 "$ROOT/scripts/drive.py" "$CAM_PORT" "$@"; }
for _ in $(seq 1 120); do cam ping 2>/dev/null | grep -q pong && break; sleep 1; done
analysed=""
for _ in $(seq 1 30); do
  analysed="$(cam nodes | grep -oE 'text="camera: [1-9][0-9]* frames, 1280x720 YUV, rotation 270"' || true)"
  [[ -n "$analysed" ]] && break
  sleep 1
done
previews="$(cam camera | grep -oE 'previews [0-9]+' || true)"
cam quit >/dev/null 2>&1 || true
docker logs "$CAM_NAME" > "$OUT/camera.log" 2>&1 || true
docker rm -f "$CAM_NAME" >/dev/null 2>&1 || true
trap 'docker logs "$NAME" > "$OUT/run.log" 2>&1 || true; docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT
echo "Camera: $analysed, $previews"
[[ -n "$analysed" && "$previews" =~ previews\ [1-9] ]] || { echo "CameraX got no frames from --camera fake" >&2; exit 1; }

# Uncaught exceptions crash the app as on a device (issue 104): FATAL EXCEPTION in the log with the stack, and the
# process ends with RuntimeInit's exit status 10. One escaping a coroutine on the main thread (viewModelScope.launch),
# and one thrown in the Application's attachBaseContext, before onCreate. Named containers, removed after each timed
# run and by the trap meanwhile: `limit` ends only the docker client if the handler hangs, and --rm acts only on exit.
CRASH_NAME=$NAME-crash
trap 'docker logs "$NAME" > "$OUT/run.log" 2>&1 || true; docker rm -f "$NAME" "$CRASH_NAME" >/dev/null 2>&1 || true' EXIT
crash_run() {  # crash_run LOG APKSIM_ARGS...: the exit status in $code
  local log="$1"; shift
  code=0
  limit 300 docker run --rm --name "$CRASH_NAME" --platform "$PLATFORM" -v "$OUT:/work" "$IMAGE" --headless "$@" app.apk \
    > "$OUT/$log" 2>&1 || code=$?
  docker rm -f "$CRASH_NAME" >/dev/null 2>&1 || true
}
crashed() {  # crashed LOG EXCEPTION_LINE: exit 10 with the device's FATAL EXCEPTION lines and the exception
  [[ "$code" == 10 ]] && grep -q 'E/AndroidRuntime: FATAL EXCEPTION: main' "$OUT/$1" \
    && grep -q 'Process: dev.apksim.composeapp, PID: ' "$OUT/$1" && grep -qF "$2" "$OUT/$1"
}
crash_run crash.log --activity .CrashActivity
echo "Uncaught coroutine exception: exit $code, $(grep -c 'FATAL EXCEPTION: main' "$OUT/crash.log" || true) FATAL EXCEPTION line(s)"
crashed crash.log 'java.lang.ArithmeticException: / by zero' \
  || { echo "an uncaught coroutine exception did not crash the app" >&2; tail -40 "$OUT/crash.log" >&2; exit 1; }
crash_run crash-attach.log --jvm -Ddev.apksim.testapp.crash_in_attach=true
echo "Exception in Application.attachBaseContext: exit $code"
crashed crash-attach.log 'thrown in attachBaseContext' \
  || { echo "an exception in attachBaseContext did not crash the app" >&2; tail -40 "$OUT/crash-attach.log" >&2; exit 1; }
# Every way the app ends itself says so in one `[apksim] exit N: ...` line (issue 116: a process ended with status 0
# and nothing in the log). The test app's ExitActivity: finishAffinity() on the only activity names the call and
# exits 0; finish() then a start 200 ms later from a posted message lands, as a device's process lingers (that
# ExitActivity calls System.exit(3)); Runtime.halt(4); Process.killProcess(myPid()) ends the process (137).
exit_run() {  # exit_run KIND STATUS LINE
  crash_run "exit-$1.log" --activity .ExitActivity --jvm "-Ddev.apksim.testapp.exit_kind=$1"
  echo "ExitActivity $1: exit $code"
  [[ "$code" == "$2" ]] && grep -qF "[apksim] exit $2: $3" "$OUT/exit-$1.log" \
    || { echo "ExitActivity $1 did not exit $2 saying '$3'" >&2; tail -40 "$OUT/exit-$1.log" >&2; exit 1; }
}
exit_run affinity 0 'the last activity, dev.apksim.composeapp.ExitActivity, finished (Activity.finishAffinity() from dev.apksim.composeapp.ExitActivity'
exit_run handoff 3 'the app called System.exit(3) on thread'
exit_run halt 4 'the app called Runtime.halt(4) on thread'
exit_run kill 137 'the app called Process.killProcess(myPid()) on thread'
trap 'docker logs "$NAME" > "$OUT/run.log" 2>&1 || true; docker rm -f "$NAME" >/dev/null 2>&1 || true' EXIT
fi  # app

if part device; then
# The app's network recorded against a fixture server, replayed without it, offline and slow (docs/network.md).
NETCHECK_PORT=7470 "$ROOT/scripts/network-check.sh" "$APK" "$OUT/network" "$IMAGE"
# A data slot's /sdcard and Settings values across launches, a container each, the app data on a shared volume.
SLOTCHECK_PORT=7480 "$ROOT/scripts/slot-check.sh" "$APK" "$OUT/slot" "$IMAGE"
APKCACHECHECK_PORT=7490 "$ROOT/scripts/apk-cache-check.sh" "$APK" "$OUT/apk-cache" "$IMAGE"
fi  # device

if part replay; then
REPLAYCHECK_PORT=7495 python3 "$ROOT/scripts/replay-check.py" "$APK" "$OUT/replay" --image "$IMAGE"  # docs/replay.md
BISECTCHECK_PORT=7496 python3 "$ROOT/scripts/bisect-rec-check.py" "$APK" "$OUT/bisect" --image "$IMAGE"  # docs/replay.md
JOURNEYCHECK_PORT=7497 python3 "$ROOT/scripts/journey-check.py" "$APK" "$OUT/journey" --image "$IMAGE"  # docs/replay.md
# Coverage of test runs (docs/coverage.md), when the androidTest APK was built beside the APK.
TESTAPK="$(dirname "$APK")/../androidTest/debug/app-debug-androidTest.apk"; [[ -f "$TESTAPK" ]] && { mkdir -p "$OUT/cov" && cp "$APK" "$OUT/cov/app.apk" && cp "$TESTAPK" "$OUT/cov/test.apk" && python3 "$ROOT/scripts/coverage-check.py" app.apk test.apk out --apksim "docker run --rm --platform $PLATFORM -v $OUT/cov:/work $IMAGE" --local "$OUT/cov"; }
# Scenes of linked instances sharing one backend, a phone with a watch, and a failing step caught (docs/scenes.md).
"$ROOT/scripts/scene-check.sh" "$IMAGE" "$APK" "$OUT/scenes"

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
fi  # replay

if part crawl; then
# Automated exploration (scripts/crawl.py, docs/crawl.md) in the image, to depth 2: the tabs, Trips, SecondActivity and the dialogs found,
# every path replayed and captured, a second walk giving the same graph, and a changed screen reported against a base launch.
python3 "$ROOT/scripts/crawl-check.py" --docker "$IMAGE" --apk "$APK" --out "$OUT/crawl" --quick | tee "$OUT/crawl-check.log"

# A skin drawn around the screen (docs/skins.md "Drawing"): the cutout and rounded corners the app is told, shot --skin's
# pixels, a rotation turning both, skin off and an unknown skin refused (the window's clicks are Mac only: --window there).
python3 "$ROOT/scripts/skin-window-check.py" "$APK" --docker "$IMAGE" --out "$OUT/skin" --port 7498 | tee "$OUT/skin-check.log"
fi  # crawl

if part serve; then
# apksim serve: a token (any address but loopback needs one), only serve's port published, the test app listed.
SERVE_NAME=apksim-serve-smoke-$$ SERVE_PORT=7402
head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$OUT/serve.token"
docker run -d --name "$SERVE_NAME" --platform "$PLATFORM" -p "127.0.0.1:$SERVE_PORT:8080" -v "$OUT:/work" "$IMAGE" \
  serve --bind 0.0.0.0 --token /work/serve.token --apps /work/app.apk --max-upload 64 \
  --allow-origin https://dash.example --device-size hd720=1280x720@1.5 >/dev/null
trap 'docker logs "$NAME" > "$OUT/run.log" 2>&1 || true; docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker logs "$SERVE_NAME" > "$OUT/serve.log" 2>&1 || true
  docker cp "$SERVE_NAME:/opt/apksim/work/serve/logs" "$OUT/serve-sessions" >/dev/null 2>&1 || true
  docker rm -f "$SERVE_NAME" >/dev/null 2>&1 || true' EXIT
for _ in $(seq 1 60); do curl -sf -H "Authorization: Bearer $(cat "$OUT/serve.token")" "http://127.0.0.1:$SERVE_PORT/api/health" >/dev/null && break; sleep 1; done
python3 "$ROOT/scripts/serve-check.py" "http://127.0.0.1:$SERVE_PORT" --token "$OUT/serve.token" --max-upload-mb 64 \
  --allow-origin https://dash.example --device-size hd720=1280x720@1.5 --h264 required | tee "$OUT/serve-check.log"
# Issue 336: a web link the app opens (a browser sign-in) reaches the tab, and its final address comes back as `open URL`.
python3 "$ROOT/scripts/serve-link-check.py" "http://127.0.0.1:$SERVE_PORT" --token "$OUT/serve.token" | tee "$OUT/serve-link-check.log"
# Issue 307: a --broadcast launch option is validated on serve's own JVM (no framework classes there): accepted, and a bad one is a 400.
tok="$(cat "$OUT/serve.token")"
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $tok" -H 'content-type: application/json' \
  -d '{"url":"http://127.0.0.1:9/b.apk","name":"b.apk","options":["--broadcast","com.example.ACTION;ez:flag=true"]}' "http://127.0.0.1:$SERVE_PORT/api/apps")
[ "$code" = 200 ] || { echo "serve: --broadcast option answered $code, wanted 200" >&2; exit 1; }
code=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $tok" -H 'content-type: application/json' \
  -d '{"url":"http://127.0.0.1:9/b.apk","name":"b.apk","options":["--broadcast","com.example.ACTION;ez:flag=maybe"]}' "http://127.0.0.1:$SERVE_PORT/api/apps")
[ "$code" = 400 ] || { echo "serve: bad --broadcast option answered $code, wanted 400" >&2; exit 1; }
# Compare mode and PR previews (docs/pr-preview.md): two mirrored sessions, a build added with a time to live, pr-preview.py.
python3 "$ROOT/scripts/compare-check.py" "http://127.0.0.1:$SERVE_PORT" --token "$OUT/serve.token" --apk "$OUT/app.apk" | tee "$OUT/compare-check.log"
# This server runs without --allow-rec and --inspect-network: the tab's rec and inspect commands are refused (the rest of that check needs both).
python3 "$ROOT/scripts/inspector-check.py" "http://127.0.0.1:$SERVE_PORT" --token "$OUT/serve.token" | tee "$OUT/inspector-check.log"
# A slow link's window: acks and pongs held 200 ms against a second serve with a cap of 4 (the check looks at the
# stats' window right after its drag: it must be above 2 and within the cap).
SERVE2_NAME=apksim-serve-slow-$$ SERVE2_PORT=7403
# It also hands out a fixture skin (--skins, docs/serve.md "Skins"): /api/skins and its images, checked below.
rm -rf "$OUT/skins" && python3 "$ROOT/scripts/serve-skin-check.py" --write-fixture "$OUT/skins"
docker run -d --name "$SERVE2_NAME" --platform "$PLATFORM" -p "127.0.0.1:$SERVE2_PORT:8080" -v "$OUT:/work" "$IMAGE" \
  serve --bind 0.0.0.0 --token /work/serve.token --apps /work/app.apk --in-flight 4 --skins /work/skins >/dev/null
# One trap for every container: both servers' logs are copied before either is removed.
trap 'docker logs "$NAME" > "$OUT/run.log" 2>&1 || true; docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker logs "$SERVE_NAME" > "$OUT/serve.log" 2>&1 || true
  docker cp "$SERVE_NAME:/opt/apksim/work/serve/logs" "$OUT/serve-sessions" >/dev/null 2>&1 || true
  docker logs "$SERVE2_NAME" > "$OUT/serve-slow.log" 2>&1 || true
  docker cp "$SERVE2_NAME:/opt/apksim/work/serve/logs" "$OUT/serve-slow-sessions" >/dev/null 2>&1 || true
  docker rm -f "$SERVE_NAME" "$SERVE2_NAME" >/dev/null 2>&1 || true' EXIT
for _ in $(seq 1 60); do curl -sf -H "Authorization: Bearer $(cat "$OUT/serve.token")" "http://127.0.0.1:$SERVE2_PORT/api/health" >/dev/null && break; sleep 1; done
python3 "$ROOT/scripts/serve-skin-check.py" --api "http://127.0.0.1:$SERVE2_PORT" --token "$OUT/serve.token" | tee "$OUT/serve-skin-check.log"
python3 "$ROOT/scripts/serve-check.py" "http://127.0.0.1:$SERVE2_PORT" --token "$OUT/serve.token" --ack-delay 200 --in-flight 4 \
  | tee "$OUT/serve-check-slow.log"
# Kept sessions (issue 221): a third serve that keeps them; the check reads the running app's slot inside the container.
SERVE3_NAME=apksim-serve-kept-$$ SERVE3_PORT=7404
docker run -d --name "$SERVE3_NAME" --platform "$PLATFORM" -p "127.0.0.1:$SERVE3_PORT:8080" -v "$OUT:/work" "$IMAGE" \
  serve --bind 0.0.0.0 --token /work/serve.token --apps /work/app.apk --max 3 --keep-max 10 >/dev/null
trap 'docker logs "$NAME" > "$OUT/run.log" 2>&1 || true; docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker logs "$SERVE_NAME" > "$OUT/serve.log" 2>&1 || true
  docker cp "$SERVE_NAME:/opt/apksim/work/serve/logs" "$OUT/serve-sessions" >/dev/null 2>&1 || true
  docker logs "$SERVE2_NAME" > "$OUT/serve-slow.log" 2>&1 || true
  docker cp "$SERVE2_NAME:/opt/apksim/work/serve/logs" "$OUT/serve-slow-sessions" >/dev/null 2>&1 || true
  docker logs "$SERVE3_NAME" > "$OUT/serve-kept.log" 2>&1 || true
  docker cp "$SERVE3_NAME:/opt/apksim/work/serve/logs" "$OUT/serve-kept-sessions" >/dev/null 2>&1 || true
  docker rm -f "$SERVE_NAME" "$SERVE2_NAME" "$SERVE3_NAME" >/dev/null 2>&1 || true' EXIT
for _ in $(seq 1 60); do curl -sf -H "Authorization: Bearer $(cat "$OUT/serve.token")" "http://127.0.0.1:$SERVE3_PORT/api/health" >/dev/null && break; sleep 1; done
python3 "$ROOT/scripts/kept-check.py" "http://127.0.0.1:$SERVE3_PORT" --token "$OUT/serve.token" --state /opt/apksim/work \
  --exec "docker exec $SERVE3_NAME" | tee "$OUT/kept-check.log"
python3 "$ROOT/scripts/trace-viewer-check.py" --csp-only
fi  # serve
echo "smoke test passed (parts: ${SMOKE_PARTS:-all})"
