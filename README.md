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

New to it? The [system design](#system-design) diagram shows what runs inside that process and every way to drive
it, and [How it was built](#how-it-was-built) covers the order it came together in and what was hard.

## Contents

- [What it offers](#what-it-offers)
- [System design](#system-design)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Devices and launch options](#devices-and-launch-options)
- [The simulator window](#the-simulator-window)
- [Skins](#skins)
- [Automation](#automation)
- [adb device](#adb-device)
- [In a browser tab](#in-a-browser-tab)
- [Instrumented tests and CI](#instrumented-tests-and-ci)
- [Screenshot tests](#screenshot-tests)
- [Network: record, replay and conditions](#network-record-replay-and-conditions)
- [Replay, trace viewer and crawler](#replay-trace-viewer-and-crawler)
- [Agents, Bluetooth LE and scenes](#agents-bluetooth-le-and-scenes)
- [Linux and Docker](#linux-and-docker)
- [Debugging](#debugging)
- [Configuration reference](#configuration-reference)
- [Performance](#performance)
- [How it was built](#how-it-was-built)
- [Limitations](#limitations)
- [Building from source and contributing](#building-from-source-and-contributing)
- [License](#license)

## What it offers

| | |
|---|---|
| **Fast launches** | Warm launches put the app's first frame on screen in ~0.2-0.5 s, and the window in ~10 ms. A stock emulator takes 8-18 s from a snapshot or boot. |
| **Real Android** | The AOSP Android 16 (API 36) framework, Skia and HWUI rendering, SQLite, the app's own DEX code and its arm64 native libraries. |
| **A simulator, not just a runner** | Device types and live window resizing, rotation, dark mode, locale, text size, location, biometrics, permissions, notifications, deep links, a shared clipboard, screenshots and screen recording. |
| **In a browser tab** | `apksim serve` runs an APK per browser tab and streams it, so QA, design and product people can try a build without Android tools: first frame in ~0.3 s on a Mac, a tap shows in ~30 ms. Compare mode puts two builds side by side under the same input, with the pixels that differ marked, and a GitHub Action comments preview links on pull requests. A report button and a network inspector are there when the server allows them. |
| **Scriptable** | An MCP server for AI agents (`apksim mcp`), a line-based control channel, `ctl.py` for `simctl`-style control of running instances, and an adb device that standard tools (`adb`, the Android CLI) can drive. |
| **Instrumented tests** | Compose UI tests, Espresso, ActivityScenario and UiAutomator suites run as `am instrument` would. JUnit XML output, one process per test if you want it, and a Linux image for CI. |
| **CI triage and speed** | A failure bundle for every failing test (its screen, accessibility tree, log, threads and a script that reruns it), only the tests a change can reach, shards balanced by measured time, `--isolate` processes booted ahead of their tests (29-47% less wall time on the test app's suite at `-j 3`), code coverage without rebuilding the app, and a flake hunter that says whether a flaky test depends on order or timing. |
| **Replay a session** | A flight recording plays again from its checkpoint, with the network answered from the recording, and says where the screen first differed; the same recording bisects a list of builds to the first that changed, becomes a test that finds elements by text or id, and opens in a static trace viewer with failure bundles. |
| **Explore without tests** | A crawler walks an APK's screens through the accessibility tree, maps them, captures each across a device, appearance and locale matrix, and compares two builds screen by screen; its resilience sweep puts every screen through process death, rotation, offline, permission denial, large text, right-to-left and dark mode. |
| **Devices that talk to each other** | Scripted Bluetooth LE peripherals the app scans for, connects to and reads, writes and subscribes to over GATT, and scenes that drive several instances (two users of one backend, a phone and its watch) step by step through one script. |
| **Hermetic network** | Record an app's HTTP(S) traffic and replay it with no backend, or give it an offline, 3G, lossy or slow network, in a running instance or a test run. |
| **Screenshot tests** | Golden screenshots of the real APK across devices, dark mode, locales and text sizes, pixel-identical run to run, with an HTML diff report. |
| **Debuggable** | Breakpoints in the app's own Kotlin and Java sources from Android Studio or IntelliJ, logcat, and a per-stage frame profile. |
| **GPU where it counts** | AGSL shaders, shader effects on Compose layers, and scaled bitmaps run on Metal; video decodes on VideoToolbox. |

It is a fast UI and logic check, not a compatibility guarantee: system services are stand-ins (see
[Limitations](#limitations)).

### Compared with the Android emulator

An emulator boots a whole virtual device and runs the app inside it. apksim runs the app's code and Android's own
framework as one process on the Mac's CPU, and replaces the parts of a device that are not the app with stand-ins.
That trade is where both the speed and the limits come from.

| | apksim | Android emulator |
|---|---|---|
| **What runs** | One process: AOSP Android 16 framework classes, the app's DEX translated to JVM bytecode, its native libraries for the host's ABI loaded directly | A full virtual device: Linux kernel, system server, ART, system apps |
| **To first frame** | ~0.2-0.5 s warm, ~1.1-1.6 s cold; the window opens in ~10 ms | 8-9 s from a Quick Boot snapshot, ~18 s for a full boot |
| **Memory** | ~550-780 MB per instance | 3-4 GB |
| **Install step** | None: `apksim app.apk` | `adb install` onto a booted device |
| **Many at once** | 32 headless instances of the test app ran at once in about 7 GB on a 12-core Mac ([docs/parallel.md](docs/parallel.md)) | A few, each with its own multi-GB VM |
| **Frame rate** | Up to 120 fps, paced to the display's vsync | Depends on the host's GPU emulation |
| **Device tests** | Same test APKs, JUnit XML; a large app's 4,107 device tests took ~4.7 min on a Mac and 8.3 min in the Linux image under Rosetta, against ~19 min on two CI emulators | Runs the tests as a device does |
| **CI cost** | No hardware acceleration or KVM needed: a container on an ordinary runner. In that app's CI, about 55% fewer runner-minutes per pull request | Needs a runner with KVM or a macOS runner |
| **Failure triage** | A bundle per failing test: screen, accessibility tree, log, threads, a rerun script | Screenshots and logcat you arrange to collect |
| **API levels and devices** | API 36 only, whatever the app targets; phone, foldable, tablet, desktop, TV and watch profiles | Any system image, many API levels |
| **System services** | Stand-ins: no Play Services, Health Connect or sensors, a simulated camera (test pattern or a still image, `--camera fake`); no other apps | The real ones, and Google Play images |
| **Native libraries** | arm64-v8a on the Mac and in the arm64 Linux image (16 KB-aligned on macOS; Linux also loads 4 KB-aligned ones); x86_64 in the x86_64 Linux image | arm64 or x86_64, matching the system image; 4 KB or 16 KB pages |
| **Fidelity** | The framework and rendering are Android's own, but behaviour that depends on the rest of a device is simulated | A real Android system |

In practice:
- **Use apksim** for the inner loop (launch, look, tap, rotate, repeat), for UI and logic tests in CI, for screenshot
  tests, for hermetic runs with recorded network, for trying a build in a browser tab, and wherever you want many
  instances at once.
- **Keep an emulator** for anything that depends on the real system or on timing a real device would show: Play
  Services, the camera and sensors, other apps, older API levels, measured startup time, or a final check of a
  release. On the large app's suite the two agreed on every test, but a pass here is not a promise about a device.
- **They coexist.** An apksim instance with `--adb` shows up in `adb devices`, so tools that expect a device work
  against either, and `scripts/both.py` sends the same input to both and diffs the screens.

## System design

[![apksim system design: ways in, the launch, what runs inside the one process, what comes out, and the hosts it runs on](docs/architecture.svg)](docs/architecture.svg)

The diagram is a vector image: click it to open it at full size. Colours say whose code each part is: the app's own,
Android's own (AOSP), apksim's, or the host's.

Reading it from the top:
1. **Ways in.** The Mac window, the control channel, the adb device, a browser tab, the MCP server and test runs are
   each a launch option or a subcommand. In a running instance they all end as the same commands on the Android main
   thread, so a tap from the mouse, from `adb shell input tap`, from a browser tab or from an agent takes one path.
   Test runs are the exception: they have no window and no frame loop, and run each test the way Robolectric does.
2. **Launch.** The launcher hands the APK to a standby JVM that has already booted the framework, as Android's zygote
   has, or starts a JVM from an AOT cache of the framework when no standby is ready.
3. **The process.** Three kinds of code share one address space. The app's DEX code is translated to JVM bytecode,
   class by class, and JIT-compiled by HotSpot like any Java program. The Android framework is AOSP's own, with
   stand-ins where a device would have a system server and other apps. The APK's native libraries are mapped by an
   ELF loader and run on the CPU as they are. `Host` is the Android main thread: it keeps the activity stack,
   dispatches input and draws a frame per display vsync through Android's own HWUI and Skia.
4. **What comes out.** Besides pixels: test reports and failure bundles, coverage, screenshots, flight recordings,
   network recordings and screen maps, most of which another apksim command takes as input.
5. **Runs on.** A Mac with Apple Silicon, with a window and the GPU, or a Linux container, headless, for CI.

### How it works

- **Framework:** real AOSP Android 16 code (API 36) from Robolectric's `android-all`, with its native Skia, HWUI and SQLite for macOS. Robolectric's shadows stand in for system services.
- **App code:** a from-scratch DEX-to-JVM bytecode translator translates classes lazily, as they're first loaded: about 5,400 classes in about 0.6 s. Methods over the JVM's 64 KB limit are split. Translated classes are cached per DEX file, so a rebuilt APK retranslates only the DEX files that changed.
- **Native code:** Android arm64 ELF libraries load directly; it's the same CPU, so nothing is emulated. A bionic-to-macOS shim covers structs, constants, errno and pthreads, and a bridge handles the varargs ABI difference.
- **Pixels:** Android's own render pipeline draws into a buffer that goes straight to an IOSurface on a native Cocoa window, which takes about 0.5 ms a frame to present. AGSL shaders and layer effects are translated to Metal, and H.264 and HEVC video decode on VideoToolbox.
- **Startup:** the JVM's AOT cache covers the framework for any APK. After each launch, a standby JVM boots the framework in the background, as Android's zygote does, and waits. The next launch hands it the APK and the terminal, and it becomes that instance in ~10 ms.

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

A release is self-contained: its own JDK 24 runtime, libraries and native code, a ~310 MB download. The first launch
builds a startup cache in the background (1-2 minutes, once per version), downloading the 22 MB test app it trains on.
Launches are faster once it's built, and `apksim --train` builds it right away (`apksim --train APP.apk` trains on
any APK, offline). An installed apksim keeps everything it writes in `~/.apksim`, or in
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
| `watch`, `watch-small`, `watch-large` | 227 × 227, 192 × 192, 240 × 240 | Wear OS: round screen (`isScreenRound`), watch UI mode and `android.hardware.type.watch`, no system bars. The wheel is the crown (rotary input; `rotary N`), `key STEM_1` / `STEM_2` / `STEM_PRIMARY` are the buttons, `ambient on\|off` enters ambient mode through a stand-in `com.google.android.wearable` library. `serve` streams the frame with the corners cleared (the Mac window shows a square) |
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
| | `--sdcard-file PATH=VALUE` | A file written to `/sdcard/PATH` in the data slot before the app starts, over any file the slot had, for apps that read a config file once in `Application.onCreate`. `VALUE` is `text:TEXT` or base64, never a host file; at most 64 KB a file and 72 KB in all. Repeatable; allowed in `serve` catalog lines and URL adds and in beam |
| | `--broadcast 'ACTION;es:K=V;ez:K=true'` | Sends one broadcast to the app's own package after `Application.onCreate` and before the first activity, for debug receivers that set flag overrides (what `adb shell am broadcast -a ACTION --es/--ez/--ei/--el/--ef` does). Extras are `es`, `ez`, `ei`, `el` or `ef:KEY=VALUE`, separated by `;` (no spaces in `serve` catalog lines). Reaches receivers registered at run time and manifest ones; logs `[apksim] broadcast ACTION: result=N`. Repeatable (8 at most); allowed in `serve` catalog lines and URL adds and in beam |
| Device identity | `--serial VALUE` | `Build.getSerial()` and `Build.SERIAL`. By default 16 letters and digits, the same every launch of one data slot (and in test runs), not Robolectric's `unknown` |
| | `--feature NAME[=false]` | A system feature `PackageManager.hasSystemFeature` (and `pm list features`) reports, for example `android.hardware.camera.any`; `=false` takes one away, a default too. Repeatable. By default the device has what an emulator of its kind lists for what apksim simulates: Bluetooth and BLE, Wi-Fi, location, multitouch, a fingerprint sensor, audio output (a TV: leanback, no touchscreen); no camera |
| | `--camera off\|fake\|fake=IMAGE` | Simulated cameras (default off): declares `android.hardware.camera`, `.any` and `.front` and lists a back camera (id `0`) and a front one (`1`) in `CameraManager`, with 1280x720 and 1920x1080 YUV_420_888/JPEG/PRIVATE streams. Opening one needs the `CAMERA` permission; Camera2 sessions and CameraX (`Preview`, `ImageAnalysis`, `ImageCapture`) get 30 fps frames of a moving test pattern, or of the still `IMAGE`. The `camera pattern\|image PATH\|off` command and Features > Camera switch it while the app runs (`off`: black frames, as Android's camera privacy toggle) |
| | `--bluetooth on\|off` | The Bluetooth adapter at launch (default on, as an emulator boots) |
| Per-instance costs | `--max-fps N` | At most N frames a second (1-120), not the display's rate. Animations keep their speed in fewer frames, and the app still sees the display's refresh rate. The biggest saving for many headless instances: the test app's Shader tab costs about 0.7 of a core at 120 fps and 0.2 at 30 |
| | `--video on\|blank\|off` | `blank`: players play at their pace but nothing is decoded or drawn (TextureViews stay empty). `off`: no video decoders are listed, as on a device without them. Launch only for `off` |
| | `--audio on\|mute\|off` | `mute` keeps the timing and plays silence; `off` lists no AAC or MP3 decoders |
| | `--anim-scale N` | The animator duration scale from the start: 0 jumps animations to their end, 10 slows them. In test runs it is set again at each test's setup |
| | `--gles on\|off` | Offscreen GLES (on in test runs by default; off falls back to Robolectric's stand-ins) |
| | `--skip-library NAME` | The app's `System.loadLibrary(NAME)` throws `UnsatisfiedLinkError`, as for a library the APK lacks, for an SDK whose native part is optional and that catches it. Repeatable |
| | `--move-shared-data` | macOS: load a 4 KB-aligned native library whose writable data shares a 16 KB page with its code by moving that data (experimental). Without it such a library fails with a catchable `UnsatisfiedLinkError` |
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
| | `--rec all\|CHANNEL,...` | The flight recorder: keep the last 2 minutes (`--rec-window SECONDS`) of input, logcat, network, SQLite calls and the screen, with checkpoints to start a replay from, saved as one shareable `.apkrec` file by `rec save`, File > Save Replay, or by itself when the app crashes. `scripts/replay-adb.py FILE.apkrec -s SERIAL --yes` plays one on a device over adb, to tell an app bug from a simulator one. It replaces the app's data, `/sdcard` files and Settings values on that device (hence `--yes`; the app's current data is backed up first), so look at the plan with `--dry-run` before ([docs/flight-recorder.md](docs/flight-recorder.md)) |
| Testing | `--instrument TEST.apk` | Run a test APK against the app ([below](#instrumented-tests-and-ci)) |
| | `-e KEY VALUE` | An instrumentation argument (`class`, `package`, or any your tests read) |
| | `--realtime-clock` | In test runs, the clock follows real time instead of skipping idle time |
| | `--precise-waits on\|off\|auto` | With `--realtime-clock`, spin the last part of short waits where the OS wakes sleeps late (VM CI runners); `auto` probes and does nothing on a desktop Mac |
| | `--uia engine\|classic` | In test runs, UiAutomator's idle waits, gesture time and accessibility events decided in-process (`engine`, experimental: docs/uiautomator-engine.md); `classic` is the default |
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
on a device: they're recreated, or resized in place if they declare `screenSize` in `configChanges`. With a
[skin](#skins) on, the window is the frame's size and only zooms (Window menu).

The menus follow the iOS Simulator wherever Android has the same thing:

| Menu | Items |
|---|---|
| File | Save Screen (Cmd+S), Save Screen with Skin (Cmd+Opt+S), Record Screen (Cmd+R), Save Replay (Cmd+Shift+S: the flight recorder's last minutes) |
| Device | Device Type: Phone, Foldable, Tablet, Desktop, TV (Cmd+Shift+1-5); Skin: None and every stored skin; Back; Home (Cmd+Shift+H); Lock (Cmd+L); Volume (Cmd+Opt+= / -); Rotate (Cmd+Left / Right); Software Keyboard (Cmd+K) |
| Features | Dark Appearance (Cmd+Shift+A); Larger, Smaller and Default Text (Cmd+Shift+= / - / 0); Language and Region; Location (None, Custom, City Run, Bicycle, Freeway); Fingerprint (Enrolled, Matching Cmd+Opt+M, Non-matching Cmd+Opt+N); Open URL (Cmd+Shift+O) |
| Window | Actual Size (Cmd+1), Fit Screen (Cmd+3), Zoom In / Out (Cmd+= / -) |
| Debug | Slow Animations (Cmd+T), Simulate Memory Warning (Cmd+Shift+M), Show Layout Bounds, Accessibility Audit |

**Also in the window:**
- **Input:** Option-drag pinches around the centre, and Option-Shift-drag pans with two fingers. Trackpad pinch and rotate map to two fingers, and the keyboard sends Android key events, Control shortcuts included.
- **System UI:** a status bar and gesture handle are drawn over the app, each only while the device has it (`--status-bar`, `--nav-bar`, `bars`) and the app has not hidden it (`WindowInsetsController.hide`, the legacy fullscreen and immersive flags); a swipe in from the edge shows hidden bars as on a device, transiently in immersive-sticky mode. Permission requests and biometric prompts appear as sheets, and notifications as banners.
- **The Mac:** the clipboard is shared with the Mac both ways, http(s) and mailto links open in the Mac's browser and mail app, sharing goes to the Mac's share menu, and document pickers open the Mac's file panel.

Screenshots and recordings are saved to `work/screenshots` (`~/.apksim/screenshots` in a release). Gaps relative
to the iOS Simulator are tracked in [docs/ios-simulator-parity.md](docs/ios-simulator-parity.md).

## Skins

A skin is a device frame image plus the device it frames, kept on your machine and added from the command line.
Skins are local data: apksim ships none, and a private one never leaves your machine. `apksim serve` hands its skins
to the page, which draws them around the screen (`&skin=NAME`, a Skin menu; [docs/serve.md](docs/serve.md) "Skins").

```bash
apksim skin add pixel --frame frame.png --screen auto --device phone   # the screen is the frame's transparent hole
apksim skin preview pixel                                               # pixel-preview.png: a test pattern in the screen
apksim skin list
apksim skin show pixel --json
apksim skin export pixel --out pixel.zip
apksim skin add --from pixel.zip                                        # or a folder, or an https:// URL
apksim skin remove pixel
```

`--screen auto` takes the largest fully transparent region the frame closes in, and reads its corner radius from the
hole's corners; `skin add` prints what it found and `skin preview` shows it. With no `--device` it prints sizes that
fit the hole and stops: the device is never guessed. A skin whose screen does not match its device's pixel aspect
within 1%, whose images are missing or whose hole is not found is refused with the reason. Skins live in
`~/.apksim/skins/NAME/`; `APKSIM_SKINS` (colon-separated folders) adds shared sets searched after it. The format is in
[docs/skins.md](docs/skins.md).

```bash
apksim --skin pixel app.apk           # the frame around the screen, and the skin's device unless --device names one
scripts/drive.py 7400 'skin off' 'skin pixel' 'shot --skin framed.png'
```

`--skin NAME` (Device > Skin, the `skin NAME|off` command) draws the frame around the screen in the window: the window
becomes the frame's size, the screen is clipped to its corners (or its mask), and only the screen takes clicks. It
turns with the display (`rotate`), using `frame-land.png` when there is one. A skin with another device switches to it;
a device switch to another shape turns the skin off. The app is told what the frame implies: its cutout as the
display's `DisplayCutout` and its corner radius as `RoundedCorner`s, in `WindowInsets` and from `Display`, so a layout
that pads for the camera hole does so as on the device. `shot --skin FILE` (File > Save Screen with Skin) saves the
screen in the frame, transparent outside the device; `shot` is still the app's pixels alone. Headless and in the Linux
image there is no window, but the device, the cutout and corners and `shot --skin` are the same.

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
| Device | `device [NAME\|WxH[@d]]`, `skin [NAME\|off]` (a stored skin around the screen), `size WxH`, `window WxH`, `window click X Y` (a click through the window, in points), `window front\|show\|background\|minimize\|hide` (`window` alone: its state), `rotate left\|right\|portrait\|landscape`, `zoom actual\|fit\|in\|out`, `home` / `resume`, `lock` / `unlock` |
| Settings | `appearance dark\|light\|toggle`, `locale TAG`, `fontscale N\|+\|-`, `timezone [ZONE]`, `animscale 10\|1\|0\|toggle`, `keyboard soft on\|off`, `bars [status on\|off] [nav gesture\|off]`, `config` |
| Network | `network` (mode and counts), `network online\|offline\|3g\|lossy`, `network latency MS`, `network bandwidth KBPS`, `network unmatched [clear]`, `network save` |
| Device state | `permission [allow\|deny\|grant NAME\|revoke NAME\|reset]`, `biometrics [enroll\|unenroll\|match\|nomatch\|cancel]`, `bluetooth [on\|off\|toggle]`, `location LAT LNG\|run\|bike\|drive\|gpx FILE\|none`, `memory [moderate\|low\|critical]` |
| App lifecycle | `open URL` (deep link), `recreate` (a configuration change), `process-death` (save state, then rebuild from it), `pick FILE...\|cancel` |
| Background work | `jobs [run ID]`, `services`, `alarms`, `push key=value...` (an FCM message), `notifications [open N\|last\|clear]` |
| Capture | `shot FILE.png` (app pixels), `shot --skin FILE.png` (in the skin's frame), `wshot FILE.ppm` (the whole window), `record start [FILE]\|stop`, `clipboard [set TEXT]`, `rec [on\|off [CHANNEL...]]`, `rec save [SECONDS] [FILE]`, `rec checkpoint now\|every SECONDS\|off`, `rec clear` (the flight recorder) |
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
(JPEG frames, or H.264 where the browser decodes it, sent only when the screen changes; a tab that decodes slower
than the app draws gets fewer frames, not freezes); clicks, drags, the wheel and the keyboard go back as touches and
keys. Back, Home, rotation, dark mode, device type, text size, locale and deep links are buttons, and "Copy link"
gives a URL that opens the same app in the same settings. A page that drives the stream itself can also mute audio,
blank video and change animation speed in a running session (`audio`, `video`, `animscale`; docs/serve.md), push a
file into the app's storage (`POST /api/sessions/ID/files?path=`), and, when the server runs with `--allow-rec`, save
a flight recording and download it. On a Mac a session's first frame comes in 0.2-0.4 s, a tap
shows in ~30 ms on the same machine, and animations stream at up to 120 fps. Loopback only unless a token is set;
sessions end when the tab closes or after 10 minutes idle, and their app data is erased, unless the server keeps
sessions (`--keep-max N`) and the session named a key (`keep=KEY`): then the next session with that key starts
signed in where the last one left off. See
[docs/serve.md](docs/serve.md).

Two builds side by side: `/?compare=BASE_ID,NEW_ID` starts a session for each, sends every tap, drag, key and device
control to both, and shows a third screen marking the pixels that differ. `scripts/pr-preview.py` and the
`.github/actions/preview` Action put a pull request's build on a server and keep one comment on the pull request with
its preview and compare links; added apps can expire (`ttl`). With `--allow-rec` a tab's report button packs a
recording, the device's settings and a note into one zip (and with `--issue-url` opens an issue in the reviewer's own
browser); with `--inspect-network` a panel lists the app's requests as they happen and can rewrite a response. Both are
off by default. See [docs/pr-preview.md](docs/pr-preview.md).

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
- **Start order (`--order`):** with `--isolate` and `--timings`, tests start interleaved by default, the longest and shortest remaining alternating, so the heaviest tests do not all run at once (`longest-first`, `suite` for the others).
- **Exit time (`--exit-marks`, `--reap-early SEC`):** with `--isolate`, see where each process's exit time goes, and free a process's slot as soon as its result is in, finishing its teardown in the background.
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
    docker run --rm -v "$PWD/out:/work" --entrypoint instrument.py ghcr.io/aglose/apksim:0.32.1 \
      app-debug.apk app-debug-androidTest.apk --xml junit.xml --bundles bundles
- uses: actions/upload-artifact@v4
  if: always()
  with:
    name: apksim-tests
    path: |
      out/junit.xml
      out/bundles
```

With `--isolate` (one process per test), `instrument.py` boots twice `-j` processes ahead of their tests and hands
each test to one that is ready, so a test no longer waits for its process's JVM, sandbox and native runtime; each
still runs in a process nothing else ran in. `--prewarm N` sets how many, `--prewarm off` starts each cold as before.
On the test app's suite at `-j 3` a run took 29-47% less time than cold, for ~10-15% more CPU. See
[docs/test-pool.md](docs/test-pool.md).

Code coverage needs no rebuild of the app: `instrument.py ... --coverage cov` counts the methods and lines each test
run reached and writes JaCoCo XML, LCOV and an HTML page, merged across processes and shards (`--mapping` for an R8
build). See [docs/coverage.md](docs/coverage.md).

`scripts/flake-hunt.py` runs chosen tests many times under shuffled order, isolation, CPU pressure and the real-time
clock, and says which are order-dependent (with the fewest earlier tests that make one fail) and which depend on
timing. See [docs/flake-hunt.md](docs/flake-hunt.md).

The test app's suite also checks AndroidX libraries' documented behaviour (activity, fragment, lifecycle, navigation,
Compose UI and foundation, material3, recyclerview) against an API 36 emulator with `scripts/conformance.py`; each
difference found is traced and fixed with its own repro. See [docs/conformance.md](docs/conformance.md).

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
docker run --rm -v "$PWD:/work" --entrypoint snapshot.py ghcr.io/aglose/apksim:0.32.1 spec.json --update --out snapshots/record
# compare every screen in every combination; exit 1 on any difference (in CI: the same image, without --update)
docker run --rm -v "$PWD:/work" --entrypoint snapshot.py ghcr.io/aglose/apksim:0.32.1 spec.json --out snapshots/check
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

## Replay, trace viewer and crawler

```bash
apksim replay --headless session.apkrec; echo $?          # 0 matched, 3 diverged, 10 crashed, 2 cannot replay
apksim replay --apk app-new.apk session.apkrec            # the same session on another build (same signer)
scripts/bisect-rec.py session.apkrec builds/              # the first build whose replay diverges, with an HTML report
apksim replay --journey --headless --out out session.apkrec && scripts/journey.py out/journey.json --xml results.xml
apksim rec view session.apkrec                            # the trace viewer, in the default browser
scripts/crawl.py app.apk --out crawl/ --base app-old.apk  # every screen it can reach, mapped, and what changed
```

- **Replay:** a flight recording (`--rec`, Save Replay) starts again from its checkpoint, its input is fed at the
  recorded offsets and its network answered from the recording, and the run says where the screen first differed
  from the recorded frames, or that it never did; with `--control` the app stays up afterwards for a person to carry
  on. See [docs/replay.md](docs/replay.md).
- **Bisect:** `scripts/bisect-rec.py` replays one recording against builds in order and reports the first that
  diverges or crashes, skipping builds it cannot replay, with the frames on each side and their difference.
- **Recording to test:** `apksim replay --journey` also writes the recording as `journey.json`, steps that find their
  element by text, content description or resource id rather than coordinates, each with the texts it should bring
  on screen; `scripts/journey.py` runs it on any build or device and writes JUnit XML.
- **Trace viewer:** one static page that opens a `.apkrec` or a zipped failure bundle by file picker or drag and drop,
  read in the browser with nothing sent anywhere: frames, input, network, logcat and SQLite on one timeline. See
  [docs/trace-viewer.md](docs/trace-viewer.md).
- **Crawler:** `scripts/crawl.py` walks an APK's screens through the accessibility tree within a budget, with no test
  code: a clickable map of screens and the actions between them, the shortest replayable path to each, each screen
  captured across a device, appearance, locale and font-scale matrix, and any crash with the path that caused it. With
  `--base` it walks a second build and says which screens are new, gone or changed. `--sweep all` then puts every
  screen through process death, recreation, rotation, offline, permission denial, large text, right-to-left and dark
  mode and reports what broke (lost state, crashes, endless spinners, cut-off text) as JUnit failures with the commands
  that reproduce each. See [docs/crawl.md](docs/crawl.md).

## Agents, Bluetooth LE and scenes

```bash
apksim mcp --apps builds/                               # an MCP server over stdio; agents may launch APKs from builds/
apksim --ble heart-rate app.apk                         # a scripted peripheral the app can scan for and connect to
scripts/scene.py testapks/composeapp/scenes/board.json  # several instances, one script, one shared backend
```

- **MCP server:** `apksim mcp` lets an agent launch an APK, tap, type and swipe, and read the screen as a short list
  of nodes; every action is settled and answered with what changed since the last answer, not the whole tree. See
  [docs/mcp.md](docs/mcp.md).
- **Bluetooth LE peripherals:** `--ble FILE|PRESET` (repeatable) and the `ble` command play the device's Bluetooth
  stack against scripted peripherals: advertisements the app's scanner finds, GATT services it discovers, reads,
  writes, notifications, bonding, MTU and PHY, in a running instance and in a test run. An emulator has no Bluetooth
  LE at all. See [docs/ble.md](docs/ble.md).
- **Scenes:** `scripts/scene.py` runs different roles, each its own instance with its own APK, device and options,
  through one ordered script whose steps wait on one another's state, against one shared server. See
  [docs/scenes.md](docs/scenes.md).

## Linux and Docker

Each release publishes a headless Linux image, `ghcr.io/aglose/apksim:VERSION`, for x86_64 and arm64 under the
one tag (Docker pulls the host's), with `:latest` following the newest release. It's about 565 MB and holds:
- the launcher and a trained startup cache;
- Mesa, for offscreen GLES;
- `instrument.py`, `coverage.py`, `flake-hunt.py`, `snapshot.py`, `crawl.py`, `journey.py` and `drive.py` on the `PATH`.

Pin a version in CI: a new release can change how apps are translated. On Linux there is no window, Metal or
VideoToolbox: rendering and shaders run on the CPU, and MediaCodec's H.264 decoder is libavcodec (the published image
has no HEVC decoder; an image you build can add it). APKs need native libraries for the host's CPU there: x86_64, or
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
| `cache/NAME.apk-KEY/` | Each APK's manifest, native libraries and inflated DEX files, keyed by the APK's contents (a copy or re-download of the same build reuses it). Entries unused for 14 days go, then the least recently used until they fit in 2 GB (`--jvm -Dapksim.apkCacheMB=N`); one a running instance uses is never removed. Entries from earlier versions (`NAME.apk-MTIME`, no `in-use.lock`) go only after 14 days unused. Safe to delete while nothing runs |
| `cache/data/PKG/` | Each app's persistent data (`default`, or `instance-N` for extra instances) |
| `cache/data/PKG/SLOT.device/` | What that data slot keeps of the device: `sdcard/` (the app's `/sdcard`) and `settings.properties` (Settings values that differ from the defaults) |
| `tmp/PID/` | Per-instance scratch, including `control.port` and `adb.port` |
| `screenshots/` | Saved screenshots, recordings and flight recorder bundles (`.apkrec`) |

## Performance

Measured on an M4 Pro (24 GB), from pressing Enter to the app's first frame, as the median of 5 runs
(`scripts/bench.py`). A launch is normally *warm*: it's handed to a standby process that has already booted the
framework. A *cold* launch starts a JVM.

| | Window | First frame, warm | Cold | Memory |
|---|---|---|---|---|
| apksim, small Compose app | 0.01 s | 0.20 s | 1.15 s | ~550-780 MB |
| apksim, a large app (R8 release build, 12 MB DEX, SQLite and WebRTC native code) | 0.01 s | 0.36 s | 1.5 s | |
| apksim, the same app's debug build (179 MB APK) | 0.01 s | 0.48 s | 1.6 s | |
| Stock emulator, Quick Boot snapshot (small app) | - | | 8.3-9.0 s | 3-4 GB |
| Stock emulator, full boot | - | | 18 s | 3.3-4 GB |

Since 0.24.0 framework calls are bound straight to their shadows, Bouncy Castle and Conscrypt load on threads of their
own, and an idle instance sleeps until there is work: on the small app, cold first frame 2.08 → 1.15 s, warm 317 →
198 ms, a one-class test process 1.64 → 0.90 s, its 157-test suite 24.0 → 19.4 s with half the CPU, and idle CPU
40-55% lower ([docs/perf-plan.md](docs/perf-plan.md)). The large app's rows were measured before that.

**Frame rate.** Frames follow the display's vsync. On a 120 Hz display, normal UI, a full-screen noise shader and the
large app's sheet animation all average 117-120 fps. Frames that miss are typically 9-12 ms, not dropped. What's
still CPU-bound: HWUI rasterises on one core, which takes 6-7 ms for a busy full-screen frame.

## How it was built

apksim came together one goal at a time, each measured before the next was started:

1. **Launch in seconds, then several times faster.** The runtime puts Robolectric's `android-all` framework and its
   native runtime on a plain JVM, opens the window before anything else, and loads the framework from the JVM's own
   class loader so that the JVM's AOT cache can hold it. The next goal was ten times faster: handing each launch to
   a pre-booted standby took the window from ~0.1 s to 12 ms and the small app's first frame from 1.49 s to ~0.22 s.
2. **Run real APKs.** dex2jar miscompiled code and took 30 s, so the DEX translator was written from scratch. An ELF
   loader and a bionic shim followed for native libraries, until a large app's R8 release build and its 179 MB debug
   build both ran.
3. **120 fps.** HWUI got a thread of its own, frames were paced to the display's vsync, and the per-pixel work
   (AGSL shaders, layer effects, bitmap scaling, video decoding) moved to Metal and VideoToolbox.
4. **A testbed for device tests.** A large app's 4,107 emulator tests were run here, on the Mac and in a headless
   Linux image, and fixed until none failed, so that CI needs no emulator.
5. **Everything around it.** The adb device, the browser tab, network record and replay, screenshot tests, the
   flight recorder and its replay, the crawler, the MCP server, Bluetooth LE and scenes are mostly combinations of
   those building blocks: the control channel, the accessibility tree, `settle`, data slots and the recorder's rings.

Today that is about 65,000 lines of Java (the runtime), 8,500 of C and Objective-C (the window, the ELF loader, the
bionic shims, Metal and the codecs), 20,000 of Python (tools and checks) and a Kotlin test app whose screens and
tests reproduce the behaviours that once went wrong.

Four habits did most of the work:
- **Use Android's own code wherever it exists.** The framework and its Skia, HWUI and SQLite are Android's own.
  apksim writes only what a device has around an app, and checks that against AOSP's sources.
- **Measure, then keep or drop.** `scripts/bench.py` and CI's `startup-gate` hold launch time, A/B runs are
  interleaved because timings swing with the machine's load, and ideas that measured no gain are written down so
  that they are not tried twice (partial redraw, linear textures, rendering into the window's IOSurface).
- **Trace before changing behaviour.** When a test passes on the emulator and fails here, the mechanism is found
  first, with breakpoints and return values over JDWP (`scripts/jdi/`). Two guesses broke hundreds of tests.
- **Every bug becomes a fixture.** A miscompiled shape goes into `testapks/translator-fixture`, a loader case into
  `testapks/loader-fixture`, a framework difference into a test that also passes on an API 36 emulator
  ([docs/conformance.md](docs/conformance.md)); `scripts/both.py` compares screens with the emulator pixel for pixel.

### The hard parts

- **Translating DEX to JVM bytecode.** Dalvik is a register machine whose registers carry no types: a zero can be an
  int, a float or null. The JVM's locals are typed, so the translator infers a type for every value (union-find over
  values) and gives each register a local. Its two worst bugs were both in what an exception handler sees: counting a
  return as an instruction that can throw, and merging a handler's live registers at every block of its range, typed
  ints and floats as references and dropped them. That showed up far away, as Compose animations that snapped to
  their end. Methods over the JVM's 64 KB limit have to be split, and R8 and D8's debug mode reuse registers
  differently, so the fixture is built with both. It now translates about 5,400 classes in about 0.6 s.
- **Loading Android's native libraries into a macOS process.** Same CPU, different everything else. The loader maps
  and relocates ELF files itself. bionic and libSystem disagree on struct layouts, constants and `errno`, and the two
  arm64 ABIs pass variadic arguments differently, so JNI's variadic calls go through a table chosen by the caller's
  address and each variadic libc function needs a thunk (`syslog` crashed in `strlen` without one). Apple's
  `pthread_cond_timedwait` reads a monotonic deadline as wall-clock time and returned at once, so two WebRTC threads
  spun a core each. macOS maps 16 KB pages and never makes one writable and executable, which libraries linked for
  4 KB pages assume they can have ([Limitations](#limitations)). On arm64 Linux, glibc owns the thread pointer, so the
  loader rewrites the instructions that read it.
- **Launch time.** The JVM's AOT cache skips classes from Robolectric's instrumenting class loader and from signed
  jars, so the framework is loaded another way and one jar is unsigned at setup. Robolectric's dispatch through
  `invokedynamic` was a quarter of a cold launch until call sites were bound directly. A standby has to become the
  instance without the terminal noticing: it receives the launcher's stdin, stdout and stderr over a Unix socket,
  moves to its working directory and returns its exit code. A CI job on a Mac fails a pull request that makes
  launches slower ([docs/startup-gate.md](docs/startup-gate.md)).
- **120 fps with a CPU rasteriser.** Robolectric's clock has 1 ms resolution, and a 120 Hz frame is 8.33 ms: a
  rounding slip between the two once spun a core for the rest of every frame. A 256 MB young generation paused
  12-50 ms in mid-scroll; at 64 MB a collection takes about 1 ms. The ripple effect's per-pixel shader cost ~15 ms a
  frame on the CPU. AGSL shaders are translated to Metal's shading language and rendered a frame ahead, and scaling
  bitmaps on the GPU took a tablet's raster time from ~36 ms to ~2.3 ms. What is left is HWUI rasterising on one
  core: 15-35% of frames still take 9-12 ms.
- **Being the system server.** Robolectric is a unit-test library: it records that an activity was started, and it
  has no activity stack, no window placement, no touch routing between windows, no network callbacks or sticky
  broadcasts, and an uncaught exception left the app running. Each was rebuilt to do what AOSP's code does (which
  windows accessibility reports, which window a touch goes to, when a network callback fires, how a crash ends the
  process). A typical bug: `WifiManager.getMaxSignalLevel()` answered 0, an app that buckets the signal level divided
  by zero inside a coroutine, nothing reported it, and the screen waited for ever.
- **Device tests without a device.** A device test runs on its own thread beside the app's main thread. Here the
  test body is on the main thread, so `Thread.sleep`, `CountDownLatch.await` and `runBlocking` must run the main
  queue while they wait, Compose's test clock must queue behind what is already posted, and an endless animation
  must not keep the looper from ever going idle. A second clock mode follows real time for UI that has to last.
  With all of that, a large app's 4,107 device tests pass, on a Mac in about a quarter of its emulators' time.
- **Video and audio.** A decoder returns pictures in presentation order with their own timestamps, and holds input
  buffers while it reorders. Pairing each input with one output made ExoPlayer drop 58 of 90 frames as late, and
  handing out one input buffer at a time showed a 1080p60 clip at ~9 pictures a second. An AudioTrack's playback head
  may move only as sound is played, or the player's clock races ahead of the pictures.
- **Time.** The uptime clock is simulated, and only the main loop may move it. A worker thread polling with
  `SystemClock.sleep` once pushed it minutes ahead, and timers fired early: most likely why an app's 10-minute
  inactivity timer went off after about 30 seconds.
- **Network record and replay in minified apps.** R8 renames OkHttp, so there is no class to hook. The translator
  instead redirects the JDK calls every HTTP stack ends in, which R8 never renames, and a stand-in TLS socket hands
  the app the server's real certificate chain, so hostname checks and pins still pass
  ([docs/network.md](docs/network.md)).
- **The host has opinions.** macOS's `setlocale` swaps the process locale under running threads, which crashed a JIT
  compiler thread while a standby booted. App Nap throttles the timers of a hidden window. The window server brings a
  newly launched app to the front whether asked or not, so windows that must not take focus start as accessory apps.

What is still open is under [Limitations](#limitations) and in [AGENTS.md](AGENTS.md), "Open work".

## Limitations

- **It isn't a device.**
  - **System services** are stand-ins: no Play Services, Health Connect or sensors; the camera (`--camera fake`) shows
    a test pattern or a still image, not the Mac's webcam.
  - **Other apps** don't exist. Intents that leave the app go to the Mac (links, sharing) or nowhere. A stand-in
    browser is installed so that SDKs that look for one before signing in (OAuth) find it.
  - **Background work** does run: started and bound services, JobScheduler and WorkManager, alarms, and FCM pushes delivered with `push`.
- **One API level:** everything runs as API 36, whatever the app targets.
- **Native libraries:** they must be arm64-v8a (x86_64 on x86_64 Linux). On macOS, which maps 16 KB pages and never makes one writable and executable at once, a 4 KB-aligned library (an NDK before r27) loads when what shares a page with its code is read-only data or RELRO (the GOT, vtables: read-only once relocated, as on a device), which is how lld lays out most. One whose writable `.data` or `.bss` shares a page with code (a small RELRO, such as libevent's in Flipper's debug build) fails with an `UnsatisfiedLinkError` naming its APK entry, which the app's `System.loadLibrary` can catch: rebuild it for 16 KB pages. `--move-shared-data` (experimental) loads such a library by moving that data to pages of its own and repointing what reaches it; it is off by default because some references to data carry no relocation (relative lookup tables, for one) and cannot all be found. `--skip-library NAME` makes a `System.loadLibrary(NAME)` throw without trying. libjnigraphics's `AndroidBitmap_*` calls work (getInfo, lock and unlock pixels, compress; no hardware bitmaps). Native GLES, OpenSL ES and AAudio are stubs: Java's AudioTrack plays, but nothing presents native GLES in the window.
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

Changes reach `main` through pull requests, and each needs the `linux-smoke` check to pass. CI builds the Linux image
once and runs, in parallel jobs, the test app headless, its androidTest suite in both clock modes, the DEX translator
check and a privacy check; `linux-smoke` passes when all of them did. `linux-smoke-arm64` runs the smoke test and the
suite in the arm64 image. On an Apple Silicon runner, `startup-gate`
fails a pull request whose launches are slower than their budget against a fixed release
([docs/startup-gate.md](docs/startup-gate.md)). CI can't cover the other Mac-only parts (the window, Metal,
VideoToolbox), so say in the PR what you checked locally. [AGENTS.md](AGENTS.md) is the full guide to building,
running and measuring.

`scripts/package-release.sh VERSION` packages a release. Pushing a `v*` tag builds it and makes a draft release, and
publishing the draft updates the Homebrew tap and the image.

## License

Apache License 2.0: see [LICENSE](LICENSE) and [NOTICE](NOTICE). Running an APK doesn't change its own license.
Robolectric, the Android framework jars that `scripts/setup.sh` downloads, and the other components release tarballs
and the Linux image bundle keep their own licenses: see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

apksim is free to use, change and redistribute, commercially or not, under those terms. It is a hobby project
provided as is, with no warranty and no liability for what it does (sections 7 and 8 of the license), and no promise
of support. You are responsible for having the right to run, test and inspect the APKs you give it.

apksim is not affiliated with, endorsed by or sponsored by Google or Apple. Android is a trademark of Google LLC; macOS,
Metal and Apple Silicon are trademarks of Apple Inc. Those names are used only to describe what apksim runs and runs on.
