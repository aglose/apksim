# apksim

[![ci](https://github.com/aglose/apksim/actions/workflows/ci.yml/badge.svg)](https://github.com/aglose/apksim/actions/workflows/ci.yml)
[![release](https://img.shields.io/github/v/release/aglose/apksim)](https://github.com/aglose/apksim/releases)
[![license](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

**apksim runs unmodified Android APKs on an Apple Silicon Mac as one ordinary process: no emulator, no virtual
machine, no install step.** The app is on screen about a quarter of a second after you press Enter, renders at up to
120 fps in a resizable native window, and can be driven like the iOS Simulator: by hand, from scripts, or with
standard `adb` tooling. The same runtime runs an app's instrumented test suite, on the Mac or headless in a Linux
container, several times faster than an emulator.

```bash
brew tap aglose/apksim && brew trust aglose/apksim && brew install apksim
apksim app-debug.apk
```

## Contents

- [What it offers](#what-it-offers)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Devices and launch options](#devices-and-launch-options)
- [The simulator window](#the-simulator-window)
- [Automation](#automation)
- [adb device](#adb-device)
- [In a browser tab](#in-a-browser-tab)
- [Instrumented tests and CI](#instrumented-tests-and-ci)
- [Screenshot tests](#screenshot-tests)
- [Network: record, replay and conditions](#network-record-replay-and-conditions)
- [Linux and Docker](#linux-and-docker)
- [Debugging](#debugging)
- [Configuration reference](#configuration-reference)
- [Performance](#performance)
- [How it works](#how-it-works)
- [Limitations](#limitations)
- [Building from source and contributing](#building-from-source-and-contributing)
- [License](#license)

## What it offers

| | |
|---|---|
| **Fast launches** | Warm launches put the app's first frame on screen in ~0.2-0.5 s, and the window in ~10 ms. A stock emulator takes 8-18 s from a snapshot or boot. |
| **Real Android** | The AOSP Android 16 (API 36) framework, Skia and HWUI rendering, SQLite, the app's own DEX code and its arm64 native libraries. |
| **A simulator, not just a runner** | Device types and live window resizing, rotation, dark mode, locale, text size, location, biometrics, permissions, notifications, deep links, a shared clipboard, screenshots and screen recording. |
| **In a browser tab** | `apksim serve` runs an APK per browser tab and streams it, so QA, design and product people can try a build without Android tools: first frame in ~0.3 s on a Mac, a tap shows in ~30 ms. |
| **Scriptable** | A line-based control channel, `ctl.py` for `simctl`-style control of running instances, and an adb device that standard tools (`adb`, the Android CLI) can drive. |
| **Instrumented tests** | Compose UI tests, Espresso, ActivityScenario and UiAutomator suites run as `am instrument` would. JUnit XML output, one process per test if you want it, and a Linux image for CI. |
| **CI triage and speed** | A failure bundle for every failing test (its screen, accessibility tree, log, threads and a script that reruns it), only the tests a change can reach, and shards balanced by measured time. |
| **Hermetic network** | Record an app's HTTP(S) traffic and replay it with no backend, or give it an offline, 3G, lossy or slow network, in a running instance or a test run. |
| **Screenshot tests** | Golden screenshots of the real APK across devices, dark mode, locales and text sizes, pixel-identical run to run, with an HTML diff report. |
| **Debuggable** | Breakpoints in the app's own Kotlin and Java sources from Android Studio or IntelliJ, logcat, and a per-stage frame profile. |
| **GPU where it counts** | AGSL shaders, shader effects on Compose layers, and scaled bitmaps run on Metal; video decodes on VideoToolbox. |

It is a fast UI and logic check, not a compatibility guarantee: system services are stand-ins (see
[Limitations](#limitations)).

## Installation

**Requirements:** an Apple Silicon Mac. For CI, a Linux host with Docker, x86_64 or arm64 (see [Linux and Docker](#linux-and-docker)).

### Homebrew

```bash
brew tap aglose/apksim
brew trust aglose/apksim      # once: Homebrew 7 loads casks only from taps you trust
brew install apksim
```

`brew upgrade apksim` updates it, and `brew uninstall --zap apksim` removes it along with its data.

### Install script

```bash
curl -fsSL https://raw.githubusercontent.com/aglose/apksim/main/install.sh | bash
```

This installs the release into `~/.local/share/apksim` and links `~/.local/bin/apksim`.

### What gets installed

A release is self-contained: its own JDK 24 runtime, libraries and native code, a 449 MB download. The first launch
builds a startup cache in the background (1-2 minutes, once per version). Launches are faster once it's built, and
`apksim --train` builds it right away. An installed apksim keeps everything it writes in `~/.apksim`, or in
`$APKSIM_HOME` if set: the startup cache, translated classes, app data and screenshots. `apksim --version` prints
the installed version.

To build apksim from a source checkout instead, see [Building from source](#building-from-source-and-contributing).

## Quick start

```bash
apksim app.apk                               # run an APK in a phone-sized window
apksim --device tablet app.apk               # as a tablet
apksim --appearance dark --locale de-DE app.apk
apksim --control auto app.apk &              # with a control port, for scripting
scripts/ctl.py list                          # running instances
scripts/ctl.py rotate landscape ';' shot /tmp/landscape.png
apksim --instrument app-debug-androidTest.apk app-debug.apk   # run a test suite
```

In a release, the scripts live in the install folder's `scripts/` (Homebrew:
`$(brew --prefix)/Caskroom/apksim/<version>/apksim-<version>-macos-arm64/scripts`).

## Devices and launch options

### Devices

| `--device` | Display (dp) | Notes |
|---|---|---|
| `phone` (default) | 411 × 891 | System bars and gesture navigation |
| `foldable` | 841 × 701 | System bars and gesture navigation |
| `tablet` | 1280 × 800 | 2560 × 1600 px, the Nexus 10 profile |
| `desktop` | 1440 × 900 | No system bars |
| `tv` | 960 × 540 | 1080p; television UI mode, D-pad, no touchscreen |
| `WxH[@density]` | custom | For example `1024x768@1.5` |

Density is 2 (xhdpi) unless given. You can also switch devices while the app runs, from the Device menu or with the
`device` command.

### Launch options

`apksim [OPTIONS] app.apk`

| Area | Option | Effect |
|---|---|---|
| Start | `--activity PKG/.Act\|.Act\|CLASS` | Start this activity instead of the manifest's first launcher one, as `am start -n` would: exported or not, by package and class, `.Act`, a full class name, or a simple name when one activity has it. For a home app whose debug manifest lists a test launcher first, or a screen deep inside the app. `restart` starts it again. An activity the manifest lacks stops the launch with the list of those it has. (An app with no launcher activity starts its MAIN/HOME one.) |
| Display | `--device NAME\|WxH[@density]\|WxHpx@Ndpi` | The device to simulate (table above); `1920x1080px@240dpi` takes `wm size`/`wm density` output |
| Device profile | `--device-profile FILE` | Display, `Build` fields, features and settings of a real device, captured with `scripts/device-profile.py [-s SERIAL] > FILE` over adb; options after it win |
| | `--headless` | No window; the app still runs and renders |
| | `--window-x N` | The window's horizontal position on screen |
| | `--window front\|nofocus\|background\|minimized\|hidden` | How the window shows itself. `front` (default) takes focus like any app; the others never take focus from the app you are in: `nofocus` shows it on top, `background` behind your other windows, `minimized` in the Dock, `hidden` not at all. The app runs and renders the same; click the Dock icon to bring the window forward. `APKSIM_WINDOW=MODE` sets a default for every launch |
| Look and locale | `--appearance dark\|light` | Dark or light mode |
| | `--locale TAG` | For example `fr-FR`; `ar-XB` is the right-to-left pseudo-locale |
| | `--timezone ZONE` | For example `America/New_York` (JDK, `android.icu`, `user.timezone` for Joda-Time) |
| | `--font-scale N` | For example `1.3` |
| Device state | `--permissions ask\|grant\|deny` | Runtime permission prompts; `grant` is like `adb install -g` |
| | `--biometrics enrolled` | Start with a fingerprint enrolled |
| | `--soft-keyboard` | Draw a soft keyboard for text fields, with real IME insets |
| | `--status-bar on\|off`, `--nav-bar gesture\|off` | Whether the device has a status bar and a gesture navigation bar (default: phone, foldable and tablet have both, desktop and TV neither). A bar that is off is not drawn and gives the app no inset |
| | `--fresh` | Erase the app's data first, with the data slot's `/sdcard` and Settings values: a new device with the app just installed |
| | `--setting NS:KEY=VALUE` | A `Settings.Global`, `Secure` or `System` value (`global:my_flag=1`) set before `Application.onCreate`, as `adb shell settings put` before a first launch would. Repeatable |
| Device identity | `--serial VALUE` | `Build.getSerial()` and `Build.SERIAL`. By default 16 letters and digits, the same every launch of one data slot (and in test runs), not Robolectric's `unknown` |
| | `--feature NAME[=false]` | A system feature `PackageManager.hasSystemFeature` (and `pm list features`) reports, for example `android.hardware.camera.any`; `=false` takes one away, a default too. Repeatable. By default the device has what an emulator of its kind lists for what apksim simulates: Bluetooth and BLE, Wi-Fi, location, multitouch, a fingerprint sensor, audio output (a TV: leanback, no touchscreen); no camera |
| | `--bluetooth on\|off` | The Bluetooth adapter at launch (default on, as an emulator boots) |
| Per-instance costs | `--max-fps N` | At most N frames a second (1-120), not the display's rate. Animations keep their speed in fewer frames, and the app still sees the display's refresh rate. The biggest saving for many headless instances: the test app's Shader tab costs about 0.7 of a core at 120 fps and 0.2 at 30 |
| | `--video on\|blank\|off` | `blank`: players play at their pace but nothing is decoded or drawn (TextureViews stay empty). `off`: no video decoders are listed, as on a device without them. Launch only for `off` |
| | `--audio on\|mute\|off` | `mute` keeps the timing and plays silence; `off` lists no AAC or MP3 decoders |
| | `--anim-scale N` | The animator duration scale from the start: 0 jumps animations to their end, 10 slows them. In test runs it is set again at each test's setup |
| | `--gles on\|off` | Offscreen GLES (on in test runs by default; off falls back to Robolectric's stand-ins) |
| | `--build FIELD=VALUE` | Any `Build` or `Build.VERSION` field, for example `MODEL=Pixel 9` or `VERSION.SECURITY_PATCH=2026-10-05` (not `VERSION.SDK_INT`: the framework is API 36). Repeatable. In test runs leave `FINGERPRINT` alone: with a device's, Compose's test rule waits for draw passes that never come |
| Network and safety | `--no-network` | Only localhost (127.0.0.1 and ::1) is reachable. macOS only, enforced by the sandbox (not with `--no-sandbox`); in a Linux container use `docker run --network none` |
| | `--no-sandbox` | Run without the `sandbox-exec` profile (macOS) |
| | `--network record\|replay FILE` | Record the app's HTTP(S) into a HAR file, or answer from one with no backend ([below](#network-record-replay-and-conditions)) |
| | `--network-profile NAME` | Start with `offline`, `3g`, `lossy`, `latency:MS` or `bandwidth:KBPS` |
| | `--network-host H=ADDR[:PORT]` | Send connections for host H to a fixture server instead (`--network-trust CERT` for its certificate) |
| Automation | `--control PORT\|auto` | Open the [control channel](#automation) |
| | `--control-bind ADDR` | The address the control channel and adb listen on (containers) |
| | `--adb PORT\|auto` | Make the instance an [adb device](#adb-device) |
| | `--shot FILE` | Save a screenshot of the first frame |
| | `--exit-after-first-frame` | Quit once the first frame is shown (benchmarks, smoke tests) |
| Testing | `--instrument TEST.apk` | Run a test APK against the app ([below](#instrumented-tests-and-ci)) |
| | `-e KEY VALUE` | An instrumentation argument (`class`, `package`, or any your tests read) |
| | `--realtime-clock` | In test runs, the clock follows real time instead of skipping idle time |
| | `--start-activities` | In test runs, an activity the app starts comes up, as on a device, instead of only being recorded |
| | `--test-outputs DIR` | In test runs, the Test Storage service's files (what a report library such as Allure writes) go under DIR, laid out as a device's `/sdcard/googletest` |
| | `--mock-maker auto\|keep\|subclass\|inline\|NAME` | Mockito's mock maker for the tests. `auto` (default) swaps the ART makers a test APK names (mockito-android, dexmaker) for Mockito's subclass maker; `keep` leaves the APK's; `subclass`/`inline` force `mock-maker-subclass`/`mock-maker-inline` (`inline` needs Byte Buddy's agent and mocks JDK types only, not the APK's translated classes); any other value is used as given |
| | `--bundles DIR` | In test runs, a [failure bundle](#failure-bundles) in DIR for each failing test. `--bundles-on all` for passing tests too, `--bundles-max N` (default 50 a process), `--bundles-redact REGEX` |
| | `--clock ISO\|epoch-ms\|real` | The wall clock the app sees starts there and runs on (screenshot tests) |
| | `--snapshot` | Deterministic drawing for [screenshot tests](#screenshot-tests): CPU only, carets that don't blink |
| Debugging | `--debug PORT` | A JDWP port for "Remote JVM Debug", with source-level debug info |
| | `--jvm ARG` | Pass a JVM option, for example `--jvm -Dapksim.timing=verbose` |

Other commands: `apksim --version`, `apksim --train` (build the startup cache now), and `apksim --stop-standbys`
(stop this install's warm-launch standbys).

## The simulator window

The window is resizable. Dragging an edge resizes the app's display in dp, as a freeform window does, so adaptive
layouts (window size classes, `BoxWithConstraints`) respond live. Activities get the configuration change they would
on a device: they're recreated, or resized in place if they declare `screenSize` in `configChanges`.

The menus follow the iOS Simulator wherever Android has the same thing:

| Menu | Items |
|---|---|
| File | Save Screen (Cmd+S), Record Screen (Cmd+R) |
| Device | Device Type: Phone, Foldable, Tablet, Desktop, TV (Cmd+Shift+1-5); Back; Home (Cmd+Shift+H); Lock (Cmd+L); Volume (Cmd+Opt+= / -); Rotate (Cmd+Left / Right); Software Keyboard (Cmd+K) |
| Features | Dark Appearance (Cmd+Shift+A); Larger, Smaller and Default Text (Cmd+Shift+= / - / 0); Language and Region; Location (None, Custom, City Run, Bicycle, Freeway); Fingerprint (Enrolled, Matching Cmd+Opt+M, Non-matching Cmd+Opt+N); Open URL (Cmd+Shift+O) |
| Window | Actual Size (Cmd+1), Fit Screen (Cmd+3), Zoom In / Out (Cmd+= / -) |
| Debug | Slow Animations (Cmd+T), Simulate Memory Warning (Cmd+Shift+M), Show Layout Bounds, Accessibility Audit |

**Also in the window:**
- **Input:** Option-drag pinches around the centre, and Option-Shift-drag pans with two fingers. Trackpad pinch and rotate map to two fingers, and the keyboard sends Android key events, Control shortcuts included.
- **System UI:** a status bar and gesture handle are drawn over the app, each only while the device has it (`--status-bar`, `--nav-bar`, `bars`) and the app has not hidden it (`WindowInsetsController.hide`, the legacy fullscreen and immersive flags); a swipe in from the edge shows hidden bars as on a device, transiently in immersive-sticky mode. Permission requests and biometric prompts appear as sheets, and notifications as banners.
- **The Mac:** the clipboard is shared with the Mac both ways, http(s) and mailto links open in the Mac's browser and mail app, sharing goes to the Mac's share menu, and document pickers open the Mac's file panel.

Screenshots and recordings are saved to `work/screenshots` (`~/.apksim/screenshots` in a release). Gaps relative
to the iOS Simulator are tracked in [docs/ios-simulator-parity.md](docs/ios-simulator-parity.md).

## Automation

Start an instance with `--control PORT` (or `auto`). It then accepts one command per line, and answers each with
one line. There are three ways to send commands:

```bash
scripts/drive.py 7400 'tap 720 1690' 'wait 1000' 'shot /tmp/home.png' 'quit'    # by port
scripts/ctl.py list                                                       # find instances
scripts/ctl.py @myapp appearance dark ';' wait 500 ';' shot /tmp/dark.png  # by PID, port or APK name
```

`ctl.py` also manages app data: `ctl.py apps` lists the apps that have data, `ctl.py container PKG` shows a
package's data folder, and `ctl.py erase PKG` deletes it, refusing while an instance is using it. Coordinates are
device pixels: the phone is 822 × 1782. File paths in commands resolve against the launch's working directory. On
macOS, the sandbox only lets apksim write inside your home folder to its state folder, so save to `/tmp` or the
state folder.

| Group | Commands |
|---|---|
| Input | `tap X Y`, `swipe X1 Y1 X2 Y2 [ms]`, `pinch CX CY FROM TO [ms]`, `wheel X Y AMOUNT`, `type TEXT`, `key NAME [count] [ctrl\|shift\|alt\|meta]`, `back`, `paste` |
| Device | `device [NAME\|WxH[@d]]`, `size WxH`, `window WxH`, `window front\|show\|background\|minimize\|hide` (`window` alone: its state), `rotate left\|right\|portrait\|landscape`, `zoom actual\|fit\|in\|out`, `home` / `resume`, `lock` / `unlock` |
| Settings | `appearance dark\|light\|toggle`, `locale TAG`, `fontscale N\|+\|-`, `timezone [ZONE]`, `animscale 10\|1\|0\|toggle`, `keyboard soft on\|off`, `bars [status on\|off] [nav gesture\|off]`, `config` |
| Network | `network` (mode and counts), `network online\|offline\|3g\|lossy`, `network latency MS`, `network bandwidth KBPS`, `network unmatched [clear]`, `network save` |
| Device state | `permission [allow\|deny\|grant NAME\|revoke NAME\|reset]`, `biometrics [enroll\|unenroll\|match\|nomatch\|cancel]`, `bluetooth [on\|off\|toggle]`, `location LAT LNG\|run\|bike\|drive\|gpx FILE\|none`, `memory [moderate\|low\|critical]` |
| App lifecycle | `open URL` (deep link), `recreate` (a configuration change), `process-death` (save state, then rebuild from it), `pick FILE...\|cancel` |
| Background work | `jobs [run ID]`, `services`, `alarms`, `push key=value...` (an FCM message), `notifications [open N\|last\|clear]` |
| Capture | `shot FILE.png` (app pixels), `wshot FILE.ppm` (the whole window), `record start [FILE]\|stop`, `clipboard [set TEXT]` |
| Inspection | `activity`, `windows` (each window, its activity and lifecycle state), `viewtree [FILE]` (every view of every window, with ids and screen bounds), `nodes` (the accessibility tree, with bounds), `insets`, `display`, `a11y [show]`, `debug layout on\|off` |
| Screenshot tests | `settle [MAX_MS] [QUIET_MS] [HORIZON_MS]` (answers once the UI is idle), `restart [URL]` (a fresh activity, no saved state), `clock [ISO\|epoch-ms\|real]`, `diff GOLDEN ACTUAL [MAP] [DELTA]` |
| Performance | `frames` (frame-interval percentiles), `fps` (cost per stage), `shaders` (GPU or CPU), `video [blank\|on]` (decode counts; sets the mode for the next codec), `maxfps [N]` (the frame rate cap, 1-120), `stats` |
| Other | `logcat on\|off`, `selftest [FILE]`, `ping`, `quit` |

A command that fails answers `error: ...`, and the app keeps running.

Many instances at once: `scripts/fleet.py N APP.apk -- CMD...` starts N headless instances and drives each through
the same commands at the same time (`until MS TEXT` waits for text on screen, `expect TEXT` checks the last reply),
then reports each one's result and the memory they used. On a 12-core Mac, 32 instances of the test app all passed a
tap-and-check script at once, in about 7 GB. See [docs/parallel.md](docs/parallel.md).

## adb device

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
- **Input and screen:** `input tap|swipe|text|keyevent`, `screencap`, `uiautomator dump` (the stock XML, Compose
  nodes and test tags included).
- **App control:** `am start` (including deep links), `am broadcast` (with its result), `am instrument` of an instrumentation that targets its own package, `monkey`.
- **Packages and settings:** `pm list packages|features|instrumentation`, `pm path|grant|revoke`, `pm install` and `adb install` of an instrumentation-only APK, `wm size|density`, `settings get|put`, `cmd uimode night`, `getprop`.
- **Bluetooth:** `svc bluetooth enable|disable`, `cmd bluetooth_manager enable|disable|wait-for-state:STATE_ON`, `settings put global bluetooth_on 0|1`, `dumpsys bluetooth_manager`; the app gets `ACTION_STATE_CHANGED` as on a device.
- **Files:** `ls/cat/rm/mkdir`, `run-as`, and `adb push/pull` of the app's data folder, `/sdcard` and `/data/local/tmp`. `/sdcard` and what `settings put` sets are kept with the app's data slot across launches, as a device keeps them across app restarts.
- **Logs and forwarding:** `logcat` (with filters), `adb forward` to a `LocalServerSocket`.
- **Anything else:** `adb shell apksim CMD` runs any [control command](#automation).

Tools that run an on-device server, such as the Android CLI's `android layout`, `input` and `screen capture`, work too.
`scripts/adb-check.py` checks all of this.

**Not supported:** other apps alongside the running one, split APKs, `am force-stop`, `jdwp` (use `--debug`), and
shell_v2 exit codes.

## In a browser tab

```bash
apksim serve --apps builds/            # http://localhost:8080/: pick an APK (or upload one) and use it in the tab
apksim serve --bind 0.0.0.0 --token token.txt --apps builds/   # for others: they open /?token=... once
```

For people without Android tools: each tab runs the APK in its own instance on the server and streams its screen
(JPEG frames, sent only when the screen changes); clicks, drags, the wheel and the keyboard go back as touches and
keys. Back, Home, rotation, dark mode, device type, text size, locale and deep links are buttons, and "Copy link"
gives a URL that opens the same app in the same settings. On a Mac a session's first frame comes in 0.2-0.4 s, a tap
shows in ~30 ms on the same machine, and animations stream at up to 120 fps. Loopback only unless a token is set;
sessions end when the tab closes or after 10 minutes idle, and their app data is erased. See
[docs/serve.md](docs/serve.md).

## Instrumented tests and CI

```bash
apksim --instrument app-debug-androidTest.apk app-debug.apk
apksim --instrument app-debug-androidTest.apk -e class com.example.LoginTest app-debug.apk
scripts/instrument.py app-debug.apk app-debug-androidTest.apk --xml junit.xml
scripts/instrument.py app-debug.apk app-debug-androidTest.apk --isolate -j 4 --xml junit.xml -- --device tablet
```

`--instrument` runs a test APK against the app the way `am instrument -w -r` does, and prints the same output. A
library's device-test APK, such as a KMP `androidDeviceTest`, instruments itself: pass it as both APKs.

`scripts/instrument.py` wraps it for CI:
- **Output:** it writes JUnit XML and exits 1 if any test failed.
- **`--isolate`:** gives each test a fresh process, N at a time, as the AndroidX Test Orchestrator with `clearPackageData` does.
- **`--shards N`:** splits the suite over N processes at once, with AndroidJUnitRunner's `-e numShards`/`shardIndex` (which `--instrument` takes too), into one report. Prefer it, and running suites side by side, to `--isolate`: every process pays a JVM and app start. On a large app's 4,134 tests, the whole set went from 167 s to 58 s on a Mac and from 670 s to about 135 s on 4 CPUs in the Linux image; in the app's own CI, on a GitHub runner, from a median 310 s to 140 s ([docs/parallel.md](docs/parallel.md)).
- **Crash recovery:** without `--isolate`, if the process dies mid-run (a native crash, or a test that ignores its timeout), the test that was running is reported failed and the rest run in a new process.
- **`--test-timeout SEC`:** a process that goes SEC seconds (default 900, 0 for none) without a test starting or ending, such as a hang in teardown, gets a thread dump and is killed. The running test fails with the dump's JUnit stacks, and the rest run in a new process.
- **Arguments:** `-e` passes instrumentation arguments: `class`, `notClass`, `package`, `notPackage`, `log`, and anything your tests read. Options after `--` go to apksim.
- **Failure bundles (`--bundles DIR`):** what each failing test left, so a red check can be read without a rerun ([below](#failure-bundles)).
- **Only the affected tests (`--record-deps`, `--affected-by`):** record once on main which classes each test reaches, then on a pull request run only the tests the branch's changes can reach ([below](#affected-tests-and-balanced-shards)).
- **Balanced shards (`--shards N --timings XML`):** split by each test's time in an earlier run instead of by name hash; `--shard I` runs one shard, for a CI matrix on separate machines.

**What works:**
- **Test frameworks:** Compose UI tests (`createComposeRule`, `createAndroidComposeRule`), Espresso, ActivityScenario, UiAutomator (window tree, clicks, gestures, `setOrientation`, `waitForIdle`) and AndroidX Test rules.
- **Runner and rules:** a custom runner's `newApplication`, and JUnit's `Timeout` rule.
- **Shell commands:** UiAutomation shell commands: `settings` (animation scales included), `getprop`, `logcat -c`/`-d`, `touch`, `ls`, `am broadcast`.
- **Rendering:** offscreen GLES20/30 and EGL14 on a real GLES 3 (ANGLE on Metal, Mesa in the Linux image).
- **Device behaviour suites rely on:** before the app starts, an `ANDROID_ID`, normal permissions granted and content providers created (AndroidX Startup runs); a connected, validated network; the test APK's own context and assets; `Parameterized` inside the test environment; views drawn after each layout, dialogs taking the window focus, and Espresso waiting for Compose; `--start-activities` for flows that go through a second activity.
- **Running on the main thread:** tests run as Robolectric runs them: the test body on the main thread, with a simulated clock that skips idle time. `--realtime-clock` makes the clock follow real time instead, for suites whose timed UI must last, such as a banner that closes itself after a few seconds. `--timezone` and `--device tablet` match a CI emulator's configuration.

On a large app's suite, the same APKs its CI runs on two API 34 emulators, the results were:

| Run | Tests | Time | Emulators |
|---|---|---|---|
| Mac | 4,107 | ~4.7 min | ~19 min |
| Linux image, under Rosetta | 4,107 | 8.3 min | ~19 min |
| The app's own CI, released image | 4,111 | ~6 min (a 15.6 min job with the build) | agreed on every test |

With two fixes to the app's own tests, every test passed, and none was flaky in the app's CI. Methods and costs:
[docs/ci-testbed.md](docs/ci-testbed.md).

A GitHub Actions job using the released image:

```yaml
- name: Device tests on apksim
  run: |
    mkdir -p out && cp app/build/outputs/apk/debug/*.apk app/build/outputs/apk/androidTest/debug/*.apk out/
    docker run --rm -v "$PWD/out:/work" --entrypoint instrument.py ghcr.io/aglose/apksim:0.14.0 \
      app-debug.apk app-debug-androidTest.apk --xml junit.xml --bundles bundles
- uses: actions/upload-artifact@v4
  if: always()
  with:
    name: apksim-tests
    path: |
      out/junit.xml
      out/bundles
```

### Failure bundles

```bash
scripts/instrument.py app-debug.apk app-debug-androidTest.apk --xml out/junit.xml --bundles out/bundles
```

Every failing test leaves `out/bundles/<class>/<method>-<hash>/`:
- `screen.png` and `nodes.txt`: the screen and its accessibility tree when the test failed, for a failure in the test body or in Espresso's failure handler, which are captured before the test's teardown closes the activity. A failure in `@After`, a rule or a class with its own runner is captured after teardown, so its screen may be gone, and a test the timeout watchdog ends has no screen at all. `failure.txt` says which capture point wrote the bundle;
- `failure.txt`: the stack trace, the device and settings, and how many tests ran before it in the same process;
- `logcat.txt`: the app's log during that test only. `threads.txt`, `looper.txt`: every thread, and what the main thread still had queued;
- `repro.sh`: reruns that one test with the same options;
- `process.txt`: when the process died during the test, its exit status and the end of its stderr.

`out/bundles/index.html` shows every bundle with its screenshot, and the JUnit XML links each test to its folder (GitLab and Jenkins show the screenshot with the test). Capturing costs nothing until a test fails, and its time never counts against a test's timeout. A bundle holds what the app logged and showed, and secrets can end up in it. `-- --bundles-redact REGEX` blanks matches in `logcat.txt`, `nodes.txt`, `failure.txt`, `process.txt` and the index page's previews, but not in `repro.sh` (it keeps the run's arguments), `threads.txt`, `looper.txt`, `windows.txt` or the screenshots. Check those before uploading bundles anywhere public. Reading one: [docs/ci-testbed.md](docs/ci-testbed.md#failure-bundles).

### Affected tests and balanced shards

```bash
# on main, after each merge or nightly: record which classes each test reaches, and how long each took
scripts/instrument.py app.apk app-androidTest.apk --record-deps deps.json.gz --xml timings.xml
# on a pull request: only the tests the branch's changes reach, in 4 shards balanced by measured time
scripts/instrument.py app.apk app-androidTest.apk --affected-by deps.json.gz --diff origin/main...HEAD \
  --ignore '*.md' --ignore '*/src/test/*' --shards 4 --timings timings.xml --xml out.xml
```

apksim translates every class itself, so recording which classes each test runs costs nothing measurable. A change to a
Kotlin or Java file selects the tests that reached a class compiled from it; a change the class map can't see (resources,
manifests, build files) runs the whole suite and says why. How it works, its limits and measurements on a large app:
[docs/affected-tests.md](docs/affected-tests.md).

**Not supported yet:** accessibility events for UiAutomator and `adb shell am instrument` of a test APK. Tests that wait for another process won't work either. See AGENTS.md,
"Instrumentation tests", for the details, and [docs/plan.md](docs/plan.md) for what's planned.

## Screenshot tests

```bash
# record goldens in the Linux image, where CI checks them (spec, APK and goldens in the mounted folder; --out keeps
# the report there too, outside the goldens folder: without it, it stays in the container)
docker run --rm -v "$PWD:/work" --entrypoint snapshot.py ghcr.io/aglose/apksim:0.14.0 spec.json --update --out snapshots/record
# compare every screen in every combination; exit 1 on any difference (in CI: the same image, without --update)
docker run --rm -v "$PWD:/work" --entrypoint snapshot.py ghcr.io/aglose/apksim:0.14.0 spec.json --out snapshots/check
# a quick local check on a Mac before pushing, against the image's goldens with a one-level tolerance in the spec
scripts/snapshot.py spec.json -j 4
```

`scripts/snapshot.py` captures a list of screens (deep links, or a few control commands each) across a matrix of
devices, dark and light mode, locales and font scales, compares each capture with its golden image, and writes
`report.html` with the actual, golden and difference images side by side. Each screen's tolerance is set in the spec.
It runs the real APK, so there is no screenshot test to write per screen. Captures are deterministic: animations off,
a fixed clock and time zone, the UI settled before each shot (`settle`), and CPU-only drawing (`--snapshot`). 80
captures of the test app came out byte-identical across five runs, both on the Mac and in the Linux image, and a 1 dp
change was caught. Make goldens in the Linux image, where CI checks them. Spec format and measurements:
[docs/snapshots.md](docs/snapshots.md).

## Network: record, replay and conditions

```bash
apksim --network record api.har app.apk                  # use the app against its real backend; every exchange is saved
apksim --network replay api.har app.apk                  # the same answers, with nothing leaving the process
apksim --network replay api.har --instrument app-androidTest.apk app.apk   # hermetic test runs
apksim --network-profile 3g app.apk                      # or offline, lossy, latency:MS, bandwidth:KBPS
```

The recording is a standard HAR 1.2 file, so browser dev tools and proxies can read it, and it can be edited by hand.
`Authorization`, `Proxy-Authorization`, `Cookie` and `Set-Cookie` are redacted by default (`--network-redact` adds
names). A replayed request must match on method, URL and body (`--network-match` adds headers, `--network-ignore-query`
and `--network-ignore-header` for cache busters). One that doesn't gets a 599, is logged with the closest recorded
entry, and in a test run fails the test.

It works on the app's own HTTP stack, through the JDK calls R8 never renames, so minified builds record too, and the
app's TLS stays the app's: its trust manager (pins in it included) decides when recording, and the server's real
certificate chain is replayed. To record against a local fixture server for an address compiled into the app, use
`--network-host api.example.com=127.0.0.1:8443` and, for its throwaway certificate, `--network-trust cert.pem`.

Offline is offline the whole way down. DNS fails, connections fail, open ones close, and `ConnectivityManager` loses
its network, so the app's network callbacks fire as on a device. `network offline`, `network 3g` and the others switch
conditions while the app runs (also from the Features > Network menu, `adb shell apksim network ...` or a test's shell
command).

Recorded and replayed: OkHttp 3-5 and everything on it (Retrofit, Ktor's OkHttp engine, Coil), `HttpURLConnection`,
Ktor's Android engine and Volley, including requests through the app's own proxy. WebSockets are relayed while
recording but not replayed. Not recorded: Cronet's native stack and Ktor CIO, which bring their own TLS and sockets
(offline still applies to them), and NIO channels. Details and measurements: [docs/network.md](docs/network.md).

## Linux and Docker

Each release publishes a headless Linux image, `ghcr.io/aglose/apksim:VERSION`, for x86_64 and arm64 under the
one tag (Docker pulls the host's), with `:latest` following the newest release. It's about 565 MB and holds:
- the launcher and a trained startup cache;
- Mesa, for offscreen GLES;
- `instrument.py`, `snapshot.py` and `drive.py` on the `PATH`.

Pin a version in CI: a new release can change how apps are translated. On Linux there is no window, Metal or
VideoToolbox: rendering and shaders run on the CPU. APKs need native libraries for the host's CPU there: x86_64, or
arm64-v8a on arm64 (4 KB-aligned ones load). On an Apple Silicon Mac or an arm64 runner the arm64 image runs without
emulation, so it is the faster choice there. There is no sandbox inside the container,
so use `docker run --network none` to keep an app off the network. Building the image and
running it on a Mac are covered in [docs/linux-docker.md](docs/linux-docker.md).

## Debugging

- **Breakpoints in app code.** Launch with `--debug 5005`, then attach Android Studio or IntelliJ ("Remote JVM Debug", localhost:5005). Breakpoints in the app's Kotlin and Java sources stop, step and show locals. R8-minified apps have no locals, and their line numbers need retracing with the mapping file.
- **Logs.** Send `logcat on`, or set `APKSIM_LOGCAT=1` at launch, to copy `android.util.Log` output to stderr. With `--adb`, `adb logcat` reads it with filters.
- **Crashes.** An uncaught exception ends the app as on a device: `FATAL EXCEPTION` with the stack goes to the log (`adb logcat`, and stderr), and the exit status is 10. An app's own default exception handler replaces apksim's. `--jvm -Dapksim.crashes=log` logs it and keeps running.
- **Startup timing.** `--jvm -Dapksim.timing=verbose` prints each startup stage's time.
- **Frames.** `frames`, `fps` and `shaders` show where frame time goes, and which shaders run on the GPU.
- **Layout and accessibility.** `debug layout on` shows layout bounds for Views and Compose. `a11y show` marks unlabeled controls and touch targets under 48 dp.

## Configuration reference

### Environment variables

| Variable | Effect |
|---|---|
| `APKSIM_HOME` | Where apksim keeps its state: caches, app data, screenshots (default `~/.apksim` for a release, `work/` for a checkout) |
| `APKSIM_STANDBY=0` | Launch cold and start no warm-launch standby |
| `APKSIM_LOGCAT=1` | Copy the app's log output to stderr from the start |
| `APKSIM_JDK` | The JDK 24 that build scripts use (from-source builds) |

### JVM switches

Pass these with `--jvm`, for example `--jvm -Dapksim.vsync=grid`.

| Switch | Effect |
|---|---|
| `-Dapksim.timing=verbose` | Finer startup timing marks |
| `-Dapksim.vsync=grid` | Pace frames on a fixed 120 Hz grid instead of the display's vsync |
| `-Dapksim.classCache=false` | Don't reuse translated classes from earlier launches |
| `-Dapksim.cpuLayerEffects=true` | Run layer shader effects on the CPU, as HWUI does (for comparisons) |
| `-Dapksim.gpuBitmaps=false` | Resample scaled bitmaps on the CPU |
| `-Dapksim.videoAtViewSize=false` | Keep decoded video at the picture's size when the view is bigger, instead of scaling it on VideoToolbox |
| `-Dapksim.mute=true` | Keep audio timing, but play silence |
| `-Dapksim.audio=false` | Turn off sound and the audio decoders |
| `-Dapksim.runnerApplication=false` | In test runs, ignore the runner's `newApplication` |
| `-Dapksim.crashes=log` | Log an uncaught exception and keep running, instead of ending the app (to look at a broken screen) |
| `-Dapksim.providerFailures=fail` | In running instances, a content provider that throws at startup crashes the launch, as on a device (by default it is logged) |

A launch is warm when it's handed to a pre-booted standby. `--debug`, test runs and most JVM options launch cold,
because the standby read them when it booted. Of the switches above, only `timing`, `vsync`, `classCache`, `crashes` and
`providerFailures` still launch warm.

### Files and folders

| Path, under the state folder | Contents |
|---|---|
| `app.aot` | The JVM startup cache (`apksim --train` builds it) |
| `cache/classes/` | Translated app classes, per DEX file |
| `cache/data/PKG/` | Each app's persistent data (`default`, or `instance-N` for extra instances) |
| `cache/data/PKG/SLOT.device/` | What that data slot keeps of the device: `sdcard/` (the app's `/sdcard`) and `settings.properties` (Settings values that differ from the defaults) |
| `tmp/PID/` | Per-instance scratch, including `control.port` and `adb.port` |
| `screenshots/` | Saved screenshots and recordings |

## Performance

Measured on an M4 Pro (24 GB), from pressing Enter to the app's first frame, as the median of 5 runs
(`scripts/bench.py`). A launch is normally *warm*: it's handed to a standby process that has already booted the
framework. A *cold* launch starts a JVM.

| | Window | First frame, warm | Cold | Memory |
|---|---|---|---|---|
| apksim, small Compose app | 0.01 s | 0.22 s | 1.35 s | ~550-780 MB |
| apksim, a large app (R8 release build, 12 MB DEX, SQLite and WebRTC native code) | 0.01 s | 0.36 s | 1.5 s | |
| apksim, the same app's debug build (179 MB APK) | 0.01 s | 0.48 s | 1.6 s | |
| Stock emulator, Quick Boot snapshot (small app) | - | | 8.3-9.0 s | 3-4 GB |
| Stock emulator, full boot | - | | 18 s | 3.3-4 GB |

**Frame rate.** Frames follow the display's vsync. On a 120 Hz display, normal UI, a full-screen noise shader and the
large app's sheet animation all average 117-120 fps. Frames that miss are typically 9-12 ms, not dropped. What's
still CPU-bound: HWUI rasterises on one core, which takes 6-7 ms for a busy full-screen frame.

## How it works

- **Framework:** real AOSP Android 16 code (API 36) from Robolectric's `android-all`, with its native Skia, HWUI and SQLite for macOS. Robolectric's shadows stand in for system services.
- **App code:** a from-scratch DEX-to-JVM bytecode translator translates classes lazily, as they're first loaded: about 5,400 classes in about 0.6 s. Methods over the JVM's 64 KB limit are split. Translated classes are cached per DEX file, so a rebuilt APK retranslates only the DEX files that changed.
- **Native code:** Android arm64 ELF libraries load directly; it's the same CPU, so nothing is emulated. A bionic-to-macOS shim covers structs, constants, errno and pthreads, and a bridge handles the varargs ABI difference.
- **Pixels:** Android's own render pipeline draws into a buffer that goes straight to an IOSurface on a native Cocoa window, which takes about 0.5 ms a frame to present. AGSL shaders and layer effects are translated to Metal, and H.264 and HEVC video decode on VideoToolbox.
- **Startup:** the JVM's AOT cache covers the framework for any APK. After each launch, a standby JVM boots the framework in the background, as Android's zygote does, and waits. The next launch hands it the APK and the terminal, and it becomes that instance in ~10 ms.

## Limitations

- **It isn't a device.**
  - **System services** are stand-ins: no Play Services, Health Connect, real camera or sensors.
  - **Other apps** don't exist. Intents that leave the app go to the Mac (links, sharing) or nowhere.
  - **Background work** does run: started and bound services, JobScheduler and WorkManager, alarms, and FCM pushes delivered with `push`.
- **One API level:** everything runs as API 36, whatever the app targets.
- **Native libraries:** they must be arm64-v8a (x86_64 on x86_64 Linux). On macOS they must also be 16 KB-aligned: macOS forbids memory that is writable and executable at once, so 4 KB-aligned libraries such as Flipper's fail to load there. Linux loads 4 KB-aligned libraries too. libjnigraphics's `AndroidBitmap_*` calls work (getInfo, lock and unlock pixels, compress; no hardware bitmaps). Native GLES, OpenSL ES and AAudio are stubs: Java's AudioTrack plays, but nothing presents native GLES in the window.
- **Rasterising runs on the CPU,** on one core, which limits busy full-screen frames.
- **Test runs are Robolectric-style:** the test body runs on the main thread. A `runBlocking` or `Thread.sleep` there runs the main queue while it waits, as the device's main thread would beside the test thread, but accessibility events aren't sent.
- **The C library shim is partial.** It covers what real APKs import, sockets included, and an unsupported call prints its name.
- **Security:** an APK runs as code in this process. `sandbox-exec` confines it: inside your home folder it can write only to apksim's state folder, and it can't read `~/.ssh`, `~/Documents`, `~/Desktop`, keychains, browser data or similar. Apple has deprecated `sandbox-exec`, and the sandbox is a safeguard for your own apps, not a boundary for hostile ones.

## Building from source and contributing

```bash
git clone https://github.com/aglose/apksim && cd apksim
scripts/setup.sh          # once: finds or fetches JDK 24, builds the runtime and the test app, trains the cache
./apksim app.apk
```

This needs the Xcode command-line tools (`xcode-select --install`).
- **JDK:** `setup.sh` uses a JDK 24 it finds, or downloads Eclipse Temurin 24 (checksum verified). Later JDKs run apksim but can't build its startup cache yet.
- **Android SDK:** needed only to build the test app (`testapks/composeapp`); without one, setup downloads the released test app.
- **Rebuilding:**
  - After a Java change: `scripts/build.sh && scripts/train.sh`.
  - After a native change: `scripts/build-native.sh`.
  - After moving the folder: `scripts/train.sh`, because the cache is tied to the folder's location.

Changes reach `main` through pull requests, and each needs the `linux-smoke` check to pass. That check builds the
Linux image and runs the test app headless, its androidTest suite in both clock modes, the DEX translator check and a
privacy check; `linux-smoke-arm64` runs the smoke test and the suite in the arm64 image. On an Apple Silicon runner, `startup-gate`
fails a pull request whose launches are slower than their budget against a fixed release
([docs/startup-gate.md](docs/startup-gate.md)). CI can't cover the other Mac-only parts (the window, Metal,
VideoToolbox), so say in the PR what you checked locally. [AGENTS.md](AGENTS.md) is the full guide to building,
running and measuring.

`scripts/package-release.sh VERSION` packages a release. Pushing a `v*` tag builds it and makes a draft release, and
publishing the draft updates the Homebrew tap and the image.

## License

Apache License 2.0: see [LICENSE](LICENSE). Running an APK doesn't change its own license. Robolectric and the
Android framework jars that `scripts/setup.sh` downloads keep theirs.
