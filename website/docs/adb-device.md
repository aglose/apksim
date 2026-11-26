# adb device

```bash
apksim --adb 5600 app.apk &
adb connect localhost:5600
adb shell input tap 733 1533
adb exec-out screencap -p > shot.png
adb exec-out uiautomator dump /dev/tty
adb logcat -s MyTag
```

The instance appears in `adb devices` as `apksim_phone`. It speaks adb's transport and sync protocols, so standard
tools work against it.

**Supported:**

- **Input and screen:** `input tap|swipe|text|keyevent`, `screencap`, `screenrecord --time-limit N FILE` (to `/sdcard`), `uiautomator dump` (the stock XML, Compose
    nodes and test tags included).
- **App control:** `am start` (including deep links), `am broadcast` (with its result), `am instrument` of an instrumentation that targets its own package, `monkey`.
- **Packages and settings:** `pm list packages|features|instrumentation`, `pm path|grant|revoke`, `pm install` and `adb install` of an instrumentation-only APK, `wm size|density`, `settings get|put`, `cmd uimode night`, `getprop`.
- **Bluetooth:** `svc bluetooth enable|disable`, `cmd bluetooth_manager enable|disable|wait-for-state:STATE_ON`, `settings put global bluetooth_on 0|1`, `dumpsys bluetooth_manager`; the app gets `ACTION_STATE_CHANGED` as on a device.
- **Files:** `ls/cat/rm/mkdir`, `run-as`, and `adb push/pull` of the app's data folder, `/sdcard` and `/data/local/tmp`. `/sdcard` and what `settings put` sets are kept with the app's data slot across launches, as a device keeps them across app restarts.
- **Logs and forwarding:** `logcat` (with filters), `adb forward` to a `LocalServerSocket`.
- **Anything else:** `adb shell apksim CMD` runs any [control command](automation.md).

Tools that run an on-device server, such as the Android CLI's `android layout`, `input` and `screen capture`, work too.
`scripts/adb-check.py` checks all of this.

**Not supported:** other apps alongside the running one, split APKs, `am force-stop`, `jdwp` (use `--debug`), and
shell_v2 exit codes.
