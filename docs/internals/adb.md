# The adb device

Agent reference notes, moved out of AGENTS.md so they are read only when needed. Quoted section names
("Video", "Warm launches") are sections of these files: AGENTS.md's index says which file has each.

## adb device
`./apksim --adb PORT ...` (or `--adb auto`; the port is written to `work/tmp/<pid>/adb.port`), then
`adb connect localhost:PORT`: the instance shows in `adb devices` as `apksim_phone` and standard adb tooling drives
it. `AdbServer` is the device side of the adb transport (CNXN without AUTH or TLS, OPEN/OKAY/WRTE/CLSE with one
WRTE in flight per stream) plus `sync:` v1 (STAT/LIST/SEND/RECV). `AdbShell` is a small shell (`;` `&&` `||`,
quotes, `>`/`>>`; no pipes: pipe on the Mac) whose commands go through `Control.QUEUE`, i.e. the same
`Host.runCommand`/`DeviceControls.run` code as `--control`; anything needing the main thread otherwise is posted to
the main looper (`AdbShell.onMain`). The port answers before the sandbox exists, so `shell:`/`exec:` requests are held
until the framework is ready (`AdbServer.frameworkReady`, from `Host.run`, or `SimRunner` in test runs; after 60 s:
`apksim: still starting, retry`): one that ran in that window initialised `android.os.Looper` on an adb thread outside
the sandbox and the app died with exit 10 (issue 189). Checked with adb 36 against the test app:
`adb shell input tap 733 1533` (FAB count goes up), `input swipe|text|keyevent` (BACK/HOME/POWER/MENU map to
back/home/lock/unlock; key codes by number too), `input tap|swipe` return once the Host has dispatched them, a swipe
after its duration, as on a device (they returned at once, and a 1.5 s hold lifted seconds later on a slow screen), `adb shell screenrecord [--time-limit N] FILE` (the control channel's `record`, run until the limit (180 s default and cap) or until the client goes; FILE is a device path such as /sdcard/x.mp4 for `adb pull`; the movie is the recorder's own format, AVI headless and QuickTime in a window, whatever the name says, at the display's size: `--size` and `--bit-rate` are accepted and not applied; checked by `adb-check.py`), `adb exec-out screencap -p > shot.png` (the display: `shot --display`, the app's pixels with the drawn soft keyboard, bars and banner over them when there is a window; headless it is the app's pixels, as `shot`; without `-p` raw RGBA),
`adb shell uiautomator dump [--compressed] [--windows] [FILE]` (`UiDump`: the stock tool's XML and its "UI hierchary
dumped to: FILE" line, AOSP's AccessibilityNodeInfoDumper attribute for attribute, NAF included, from the active
window's tree as UiAutomation reads it here (`--windows`: every window it reports, so none a dialog covers), so
Compose nodes and testTagsAsResourceId ids show; default
/sdcard/window_dump.xml, `/dev/tty` prints it, so `adb exec-out uiautomator dump /dev/tty`; `--compressed` drops views
not important for accessibility, lifting their children; it waits up to 1 s for the main thread to go idle first),
`adb shell am start -a android.intent.action.VIEW -d apksim://composeapp/trips` (= `open URL`), `am start [-W] -n
PKG/.Act` (an already running activity is brought to the front, not stacked), `am broadcast|startservice`, `monkey -p
PKG 1`, `pm list packages|features|instrumentation`, `pm path|grant|revoke`, `wm size|density`, `settings get|put
global|system|secure` (`animator_duration_scale` = `animscale`, `font_scale` = `fontscale`, global `bluetooth_on` =
`bluetooth`), `svc wifi|data enable|disable`, `cmd wifi set-wifi-enabled enabled|disabled` and `cmd phone data enable|disable` (= `network wifi|cellular`), `svc bluetooth enable|disable` and `cmd bluetooth_manager enable|disable|wait-for-state:STATE_X` (=
`bluetooth`), `dumpsys bluetooth_manager` (enabled, state, address, name), `cmd uimode night yes|no`, `getprop`
(`sys.boot_completed=1` once the app is up), `run-as PKG`, `ls/cat/rm/mkdir`, `dumpsys activity top`, and
`adb shell apksim CMD...` for any control command. `adb logcat` reads `AdbLog`: ShadowLog.stream is replaced by a
tee that timestamps lines into a 20k-line ring and still passes them on (`logcat on` keeps working; a watcher puts the
tee back when the stream is reassigned); supports `-d -c -t N -T N -s -e`, `TAG:LEVEL` and `*:S`.
`adb push/pull` (and `ls`) see `/data/data/PKG` = `/data/user/0/PKG` (the app's persistent data folder),
`/sdcard` = `/storage/emulated/0` (the data slot's external storage, kept across launches: `SlotDevice`; per test in
test runs) and `/data/local/tmp` (this
instance's scratch); anything else is refused. `pm clear PKG` fails with a pointer to `--fresh` / `ctl.py erase`
(here the instance is both the app's process and the device, and the app's open databases would outlive a delete).
Tools with an on-device server work (checked with the Android CLI: `android layout`, `input tap|text|scroll|key`,
`screen capture`): `adb install` (push + `pm install`, or `pm install-create/-write/-commit` as adblib streams it)
takes an APK that only brings an instrumentation (`Packages`; the running app's own APK again is a no-op, any other
app is refused with the reason), `am instrument [-w] [-r] [-e K V] PKG/CLASS [&]` runs an instrumentation that
targets its own package live in this process (`LiveInstrumentation`: its classes in a loader of their own, since such
servers bring their own Kotlin and AndroidX Test; its own package name and assets; sendStatus/finish print as `am
instrument` does; it outlives the adb stream that started it, and its receivers go when it finishes), `am broadcast`
is ordered and prints the result code and data, `adb forward tcp:N localabstract:NAME` (or `tcp:PORT`) reaches an
`android.net.LocalServerSocket` (`LocalSockets`: names on loopback ports, `Os.poll` on the server's descriptor, adb's
connections from the shell uid 2000), and UiAutomation in a running instance reads the windows as in test runs and
injects through the Host's input queue (`touch down|move|up X Y`) without moving the clock. `scripts/adb-check.py`
checks all of it with the test app project's `uiserver` module (CI: `ci-smoke.sh`); `-Dapksim.adbTrace=true` logs
every service a client opens. Test APKs that target the app still run with `--instrument`, not over adb: they need
Robolectric's test environment. The server runs in the app's process, so a crash in it ends the app too.
Not supported: other apps beside the running one, split APKs (`install-multiple`), `am force-stop`
(it is this process; `adb shell apksim quit`), `jdwp`, shell_v2 (so no exit codes or separate stderr) and
`logcat -B`. Bound to loopback, or to the `--control-bind` address (containers). The adb server only discovers emulator ports at its own start, so use `adb connect`, not port 5555.

## Code notes
- `AdbServer`, `AdbShell`, `AdbLog`, `UiDump`: the adb device (`--adb PORT`, see "adb device").
