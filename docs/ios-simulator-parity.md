# iOS Simulator parity: feature gap and plan

What Xcode's iOS Simulator (plus `xcrun simctl`) offers, what apksim does today, and roughly how to close each
gap. Sizes: **S** = a day or less, **M** = a few days, **L** = a week or more. Status was read from the code on
2026-11-02 (`Host.runCommand`, `native/simwindow.m`, `Device`, `SimRunner.getExtraShadows`).

## Status (2026-11-03)

Done, with a control command for each (see AGENTS.md "Simulator features") and a Mac menu item where it fits:
runtime configuration changes (dark/light, locale, text size), rotation, menu bar with Simulator shortcuts, `ctl.py`
(instances found without ports, `--control auto`), Home and Lock (with home/lock screens, click to return), volume
keys, slow animations, memory warnings, deep links (`open URL`), web/mail links handed to the Mac, two-way clipboard,
screenshots and screen recording (Mac), drawn status bar and gesture handle, two-finger gestures (Option-drag,
trackpad, `pinch`), runtime permission prompts (`permission`, `--permissions`), notifications (banner, `notifications`),
location (custom, run/bike/drive routes, GPX), fingerprint enrollment and prompt answers, window zoom, keyboard
shortcuts and modifiers, app data commands (`ctl.py apps|container|erase`), `recreate` and `process-death`,
activity results (in-app setResult, document/photo pickers via the Mac file panel, cancel for the rest), the share
menu for ACTION_SEND, Show Layout Bounds and an accessibility audit, background work (services, jobs, alarms),
FCM push (`push`), recording on Linux/headless (Motion-JPEG AVI), the Android Keystore (test keystore), and the
software keyboard with IME insets, and an adb device (`--adb PORT`, then `adb connect`: shell, push/pull, logcat,
screencap).
Everything except the window-only parts also works in the Linux image.

Not yet: device bezels, external display, other API levels, CREATE_DOCUMENT (save) pickers, sharing files from Linux, audio, camera, binding to app services in general (only FCM's
is wired), widgets, installing other apps and `jdwp` on the adb device, a view inspector beyond `nodes`/`a11y`.

## Shared groundwork (do these first)

Most rows below depend on four pieces. Building them once makes the rest mostly S-sized.

1. **Configuration changes at runtime** (M). Rotation, dark mode, locale, font scale, display size and window
   resizing are all an Android `Configuration` change. Apply new qualifiers with `RuntimeEnvironment.setQualifiers`,
   call `ActivityController.configurationChange(config)` on every activity in `Host.stack` (it recreates or
   calls `onConfigurationChanged` per the manifest's `configChanges`), and resize the window, IOSurface and
   `HwRenderer` surface when the size changes. Today the size is fixed at launch (`Device` → `apksim.qualifiers`).
2. **A real menu bar and command registry** (S). `simwindow.m` has only *Quit*. Add Device / Features / I/O /
   Debug menus whose items post commands into the same queue `--control` uses, so every feature is reachable from
   the menu, a shortcut, `drive.py` and (later) adb with one implementation. Keyboard shortcuts should match
   Simulator's where they make sense (Cmd+← / Cmd+→ rotate, Cmd+Shift+A appearance, Cmd+S screenshot, Cmd+K
   keyboard, Cmd+Shift+H home, Cmd+L lock, Ctrl+Cmd+Z shake).
3. **A `simctl`-style CLI** (S). `apksim ctl <instance> <command…>` that finds running instances (write
   `work/tmp/<pid>/control.port` at startup) so nobody has to remember ports. Thin wrapper over `drive.py`.
4. **Starting app components other than activities** (M). Services, broadcast receivers, `JobScheduler`/
   `WorkManager` and `AlarmManager` are not run today. Robolectric has `ServiceController` and shadows that
   record scheduled jobs/alarms; `Host` needs to start/bind services the app asks for, deliver broadcasts to
   manifest receivers, and fire alarms/jobs on the simulated clock. Push notifications, deep-link receivers,
   widgets and background work all need this.

## Device and display

| Feature | iOS Simulator | apksim today | Plan | Size |
|---|---|---|---|---|
| Choice of device models | Dozens, plus `simctl create` | Done: 5 presets (phone, foldable, tablet, desktop, TV) and custom `WxH[@density]`, at launch (`--device`) or live (`device`, Device > Device Type) | Profiles with cutout and corner radius | S |
| Device bezel / frame | Show Device Bezels | Bare window | Draw a bezel layer (rounded corners, cutout/camera hole) around `frameLayer` in `simwindow.m`; report the cutout to the app via `DisplayCutout` in the qualifiers/`SystemUi` shadow | M |
| Status bar contents | Real status bar; `simctl status_bar override` | 48 px inset reported, nothing drawn | Draw a status bar overlay (time, battery, Wi-Fi) as a Cocoa layer above the app, honouring the app's light/dark status-bar appearance flags (`WindowInsetsController.setAppearanceLightStatusBars`); `statusbar` command overrides time/battery, like Android's demo mode | M |
| Navigation bar / home indicator | Home indicator | Gesture inset only | Draw the gesture handle; bottom-edge swipe up = Home, side-edge swipe = Back (predictive back already exists in `dispatchPredictiveBack`) | S |
| Rotation | Cmd+← / Cmd+→ | None | Swap width/height through groundwork 1; resize window; `Surface.ROTATION_*` in the display shadow | M |
| Light/dark appearance | Cmd+Shift+A | None (always the system default, light) | `night`/`notnight` qualifier + configuration change; `--appearance dark` flag | S |
| Window zoom | Physical / point / pixel accurate, fit | Fixed scale | Window menu that changes the scale passed to `openWindow`; the IOSurface stays device-sized | S |
| Free resizing | iPad windows | Done: dragging the window's edge resizes the display (new qualifiers every 100 ms while dragging, and at the end); `size WxH` does it by command | | M |
| Several devices at once | Yes | Yes (several processes) | Done; add `instance` names to the CLI | - |
| External display | Yes | No | `DisplayManager` second display + `Presentation` support, rendered into a second window. Low priority | L |
| OS versions | Install runtimes | API 36 only | Robolectric ships `android-all` for API 21-36; add `--api N`, an AOT cache per API, and fix shadows that differ | L |

## Input

| Feature | iOS Simulator | apksim today | Plan | Size |
|---|---|---|---|---|
| Single touch with mouse | Yes | Yes | Done | - |
| Pinch / rotate / two-finger pan | Option-drag, Option+Shift-drag | No | In `SimView`: Option-drag mirrors a second pointer around the window centre, Option+Shift moves both; send `ACTION_POINTER_DOWN/UP` with two pointers. Also map trackpad `magnifyWithEvent:`/`rotateWithEvent:` to pinch | M |
| Trackpad / mouse wheel | Yes | Wheel only | Map `NSEventTypeGesture` swipes to scroll with fling velocity; hover (`ACTION_HOVER_MOVE`) for desktop/tablet profiles; right click = `BUTTON_SECONDARY` | S |
| Hardware keyboard | Yes, with layouts | Characters, arrows, Return, Tab, Delete; Esc = Back | Translate Mac key codes fully (`KeyCharacterMap`), modifiers, key-up events, so shortcuts in apps and Compose `onKeyEvent` work | S |
| Software keyboard | Cmd+K toggles | None | Draw a simple on-screen keyboard as an overlay that posts the same key events, and report IME insets (`WindowInsets.ime`) so layouts move as on a device. Needed to catch "keyboard hides the field" bugs | L |
| Clipboard sync | Both directions, automatic | Mac → app as typed text (Cmd+V) | Make `ClipboardManager` real: shadow it onto `NSPasteboard` both ways, so the app's own paste menus and `copy` work | S |
| Hardware buttons | Home, Lock, Side, Volume, Siri, Apple Pay | Back only | Home: pause/stop the activity, show a home screen (see Lifecycle), click to resume. Lock: screen off (`onPause/onStop`, `Display.STATE_OFF`). Volume: `KEYCODE_VOLUME_*` + `AudioManager` stream volume. Power long-press: ignore | S |
| Shake | Ctrl+Cmd+Z | None | Feed an accelerometer burst through Robolectric's `ShadowSensorManager` (see Sensors) | S |
| Game controller | Yes | No | `GCController` → `InputDevice` with `SOURCE_GAMEPAD` + `KEYCODE_BUTTON_*`/axis `MotionEvent`s. Low priority | M |
| Drag and drop onto the device | APK/app install, photos, URLs | None | Drop an `.apk`: launch it in a new instance. Drop images/videos: add to the app-visible MediaStore (see Files). Drop a URL: deep link (see Lifecycle) | M |

## Environment and sensors

| Feature | iOS Simulator | apksim today | Plan | Size |
|---|---|---|---|---|
| Location | Custom, City Run, Freeway Drive, GPX | None | `ShadowLocationManager.simulateLocation`; Fused Location (Play Services) is absent, so also shadow `FusedLocationProviderClient` when the APK has it. Menu: custom lat/long, and GPX/route playback on the simulated clock | M |
| Motion sensors | Shake only | None | `ShadowSensorManager` events for accelerometer/gyro/rotation vector; a small "device tilt" panel; tie to rotation | M |
| Memory warning | Debug → Simulate Memory Warning | None | `memory` command: call `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)` and `onLowMemory()` on the Application and activities | S |
| Locale, region, 24-hour time | Settings, scheme options | Host defaults | `locale` qualifier + `Locale.setDefault` + config change; `--locale fr-FR`; RTL pseudo-locale (`ar-XB`) for layout testing | S |
| Time zone and clock | Scheme options | Host zone | `TimeZone.setDefault` + `ACTION_TIMEZONE_CHANGED`; the clock is already simulated, so "jump the clock" is cheap | S |
| Text size | Dynamic Type overrides | Fixed | `fontScale` in the config; slider in the menu | S |
| Accessibility settings (bold text, reduce motion, contrast) | Environment overrides | None | Font weight adjustment (API 31+ config field), `Settings.Global.ANIMATOR_DURATION_SCALE = 0` for remove animations, high-contrast text | S |
| Slow animations | Cmd+T | None | `ValueAnimator.setDurationScale(10)` and the same setting Compose reads | S |
| Network conditions | Network Link Conditioner (Mac-wide) | Done, per instance: offline down to DNS, sockets and `ConnectivityManager` callbacks; 3G, lossy, latency, bandwidth (`--network-profile`, `network`, Features > Network), plus HAR record and replay of the app's HTTP(S) (docs/network.md). The Mac's Link Conditioner still applies on top | - | - |
| Biometrics | Face ID / Touch ID enrolled, matching / non-matching | Disabled (`ShadowNoBiometrics`) | Replace with an enrolled/not-enrolled toggle; when `BiometricPrompt` shows, draw a Cocoa sheet with Match / No match / Cancel and call the callback | M |
| Permissions | Real prompts; `simctl privacy grant/revoke/reset` | Robolectric default | When `requestPermissions` is called, show a Cocoa alert (Allow / Deny / Only this time) and answer through `onRequestPermissionsResult`; `permission grant/revoke/reset` commands | M |
| Keychain / secure storage | `simctl keychain` | Unknown: `AndroidKeyStore` is not provided by Robolectric | Provide a JCA `AndroidKeyStore` backed by an in-memory or file keystore under app data, so EncryptedSharedPreferences and token storage work; `keystore reset` | M |
| Audio out / microphone | Mac audio | Stubbed | `AudioTrack`/`MediaPlayer`/`SoundPool` → AVAudioEngine; AAudio/OpenSL in `bionic`-side stubs → CoreAudio; `AudioRecord` → Mac mic (asks the macOS permission) | L |
| Camera | Not supported in iOS Simulator | Not supported | Beyond parity, but cheap relative to value: Camera2/CameraX shadow fed by AVFoundation from the Mac camera, or a still-image/video file source | L |

## App lifecycle and system integration

| Feature | iOS Simulator | apksim today | Plan | Size |
|---|---|---|---|---|
| Home screen / app switcher | Yes | One app per process | Home: show a launcher overlay with the app's icon (from `AppInfo`) and the recent-apps card (a `shot` of the last frame); tapping restores. Not a multi-app OS, just enough to test background/foreground | M |
| Background / foreground | Via Home | None | Home + return drives `onPause/onStop/onRestart`, `ProcessLifecycleOwner`, `onUserLeaveHint`, `onTrimMemory(UI_HIDDEN)` | S |
| Process death and restore | No equivalent | None | Android-specific and worth more than parity: `kill-restore` saves instance state (`onSaveInstanceState` bundles + `SavedStateHandle`), restarts the process and relaunches with the saved bundle. Catches the classic Compose/ViewModel bugs | M |
| Deep links / URLs | `simctl openurl`, drop a URL | None | `open <url>` command and Mac `apksim open`: resolve intent filters from the manifest (Robolectric `PackageManager` already parses them), deliver to `onNewIntent` or a new activity per launch mode | S |
| Intents leaving the app | Opens Safari, Mail, Maps… | Logged and ignored (`Host.updateActivityStack`) | Map the common ones to the Mac: `VIEW http(s)` → default browser (`NSWorkspace`); `mailto`/`tel`/`geo` → Mail/FaceTime/Maps; `ACTION_SEND` → `NSSharingServicePicker`; return `RESULT_CANCELED` to `startActivityForResult`/activity result APIs instead of hanging | M |
| Document and photo pickers | Files and Photos apps | None | `ACTION_OPEN_DOCUMENT`/`GET_CONTENT`/`PickVisualMedia`/`CREATE_DOCUMENT` → `NSOpenPanel`/`NSSavePanel`, returning a `content://` URI served by a small `ContentProvider` over the chosen file | M |
| Notifications | Notification Center; `simctl push` | Ignored | Read posted notifications from `ShadowNotificationManager` and show them as macOS notifications (`UNUserNotificationCenter`); clicking sends the `contentIntent`. Permission prompt for `POST_NOTIFICATIONS` | M |
| Push notifications | `simctl push`, drop an `.apns` file | None | Needs groundwork 4. `push <json>` builds the FCM `com.google.firebase.MESSAGING_EVENT` intent and delivers it to the app's `FirebaseMessagingService`, bypassing Play Services | M |
| Widgets / Live Activities | Yes | None | App widgets: host `AppWidgetProvider` `RemoteViews` in a second small window, updating via broadcasts. Low priority | L |
| Install / uninstall / list apps | `simctl install/uninstall/listapps` | Run an APK; data under `work/cache/data/<pkg>` | `apksim apps` lists cached packages; `apksim uninstall <pkg>` removes cache + data | S |
| Erase content and settings | Yes | Delete `work/cache/data` by hand | `apksim erase <pkg>` / menu item: clears app data, keystore and granted permissions | S |
| App container access | `simctl get_app_container` | Paths are known | `apksim container <pkg>` prints the data dir; open it in Finder from the menu | S |
| Clone / snapshot a device | `simctl clone` | None | Copy the data dir to a named instance (`instance-N` already exists in `Storage`); snapshot/restore = copy dirs | S |

## Developer tooling

| Feature | iOS Simulator | apksim today | Plan | Size |
|---|---|---|---|---|
| Screenshot | Cmd+S, `simctl io screenshot` | `shot`, `wshot` commands | Menu item + Cmd+S saving a PNG to the Desktop, with and without bezel | S |
| Screen recording | `simctl io recordVideo` | None | Frames already land in an IOSurface: feed them to `AVAssetWriter` (H.264/HEVC via VideoToolbox, already linked) at present time; `record start/stop` command and menu | M |
| Logs | `simctl spawn log stream`, Console | stdout/stderr | `logcat` command and CLI: stream `ShadowLog`/`android.util.Log` with tag/level filters; also forward to `os_log` so Console.app shows them | S |
| Debugger | Xcode LLDB | **Done**: `--debug PORT`, then Android Studio/IntelliJ "Remote JVM Debug" | `--debug` adds `-agentlib:jdwp` and translates app classes with `LineNumberTable`/`LocalVariableTable` from DEX debug info and Kotlin's SMAP (own class cache; off otherwise, so normal launches are unchanged). Breakpoints in app source stop, step and show locals. Not yet: locals in split (>64 KB) methods; attaching through the adb device's `jdwp` | M |
| View hierarchy debugger | Xcode view debugger | `windows` command | `views` command dumping the View tree and Compose semantics tree as JSON (bounds, ids, text, test tags); a "pick element" mode in the window that highlights and prints the node under the cursor | M |
| Profiling (Instruments) | Time Profiler, Allocations, Leaks | `Sampler`, `prof.py` | Expose JFR (`--profile`) and async-profiler; `heapdump` command (`HotSpotDiagnosticMXBean`). JFR output opens in IntelliJ/JMC | S |
| Rendering debug overlays | Color blended layers, offscreen-rendered, misaligned | `fps`, `frames` | Android equivalents: show layout bounds (`debug.layout`), GPU overdraw, profile HWUI rendering bars, show taps / pointer location. Mostly system properties HWUI already reads, plus an overlay for taps | M |
| Accessibility Inspector / VoiceOver | Works on the simulator | None | Export the `AccessibilityNodeInfo` tree as `NSAccessibility` elements on `SimView`, so Accessibility Inspector and VoiceOver can read and drive the app. An `a11y` audit command (missing labels, small touch targets, contrast) is cheaper and covers most of it | L |
| UI test automation | XCUITest, `simctl` | `--control` + `drive.py` | Add selectors to the control channel (`tap text "Save"`, `tap tag "fab"`, `wait-for text …`) using the semantics/view dump; optionally run the APK's own Espresso/Compose UI tests from `androidTest` | M |
| Studio / tool integration | Xcode Run button | **Done (minimal adb device)**: `--adb PORT`, then `adb connect localhost:PORT` | `AdbServer` speaks the device side of the adb transport (insecure: no AUTH/TLS) with `shell:`/`exec:` and `sync:` v1. `AdbShell` maps `getprop`, `input tap|swipe|text|keyevent`, `am start|broadcast|startservice`, `monkey -p`, `pm list|path|grant|revoke`, `wm size|density`, `settings get|put`, `cmd uimode night`, `logcat` (AdbLog: a timestamped ring of `ShadowLog` lines, `-d -c -t -T -s -e`, TAG:LEVEL), `screencap [-p]`, `ls/cat/rm/mkdir`, `run-as`, `dumpsys activity top|window` onto the control commands; `adb shell apksim CMD` sends any control command. push/pull/ls cover `/data/data/PKG`, `/sdcard` and `/data/local/tmp`. `adb install` of an instrumentation-only APK, `am instrument` of one that targets itself (run live in the process), `adb forward` to local sockets: the Android CLI's `layout`, `input` and `screen` work. Not yet: installing other apps (one app per instance), `jdwp`/`track-jdwp` (so Studio's debugger and Layout Inspector), shell_v2 (exit codes, separate stderr), `logcat -B` (Studio's binary logcat), `am force-stop` | L |
| In-app purchases test | StoreKit testing | None | Fake `BillingClient` returning configurable products/purchases. Low priority | M |

## Already ahead of or equal to the iOS Simulator

- Launch to first frame in ~2.5 s, no boot.
- Several devices at once in separate processes; persistent app data per instance.
- Live network, SQLite, WebRTC video (H.264 on VideoToolbox); Mac's Network Link Conditioner applies for free.
- Screenshot (golden) tests across devices and settings of the real APK (`scripts/snapshot.py`, docs/snapshots.md),
  and an APK in a browser tab (`apksim serve`, docs/serve.md): no counterpart in the iOS Simulator.
- GPU-translated AGSL shaders.
- Scripted input over `--control` (`tap`, `swipe`, `type`, `key`, `wheel`, `back`), frame-time stats.

## Out of scope (or a separate project)

- Other system apps (Settings, Photos, Maps, Messages), iCloud/Google account sync, Play Services in general
  beyond the specific shadows above, CarPlay / Android Auto, paired watch.

## Rough order

1. **Simulator chrome (2-3 weeks):** groundwork 1-3; rotation, dark mode, zoom, locale, font scale, slow
   animations; bezel, status bar and nav handle; Home/Lock/Volume buttons; clipboard both ways; screenshot menu
   and screen recording; `logcat`; pinch gestures and full keyboard mapping.
2. **App environment (2-3 weeks):** deep links and intents to the Mac; permission prompts; biometrics; location and
   sensors; memory warning; background/foreground and process-death restore; document/photo pickers; Keystore;
   erase/uninstall/container CLI.
3. **Tooling (3-4 weeks):** line numbers in the DEX translator + JDWP; `views`/semantics dump and element picker;
   selector-based UI automation; rendering overlays; JFR; the adb device (minimal version done: shell, sync,
   logcat, screencap) still needs `jdwp` and install for Android Studio's Run and Debug.
4. **Platform depth (open-ended):** groundwork 4 (services, receivers, jobs, alarms) → notifications and push;
   on-screen keyboard with IME insets; audio in/out; camera; other API levels; widgets; external display;
   accessibility bridge to macOS. GLES and GPU raster stay on the existing "Open work" list in AGENTS.md.

Each item should land with a control command (so it is scriptable and testable through `drive.py`) and, where it
changes what the app sees, a `scripts/both.py` comparison against the `ApksimCmp36` emulator.
