# apksim

Runs unmodified Android APKs on an Apple Silicon Mac as a single ordinary process, with no VM.

    git clone https://github.com/aglose/apksim && cd apksim
    scripts/setup.sh                 # once, a few minutes
    ./apksim app.apk                 # run it
    ./apksim --device foldable app.apk

Needs an Apple Silicon Mac with the Xcode command-line tools (`xcode-select --install`). `setup.sh` finds or fetches
the rest:

- **JDK 24.** It uses one it finds (`APKSIM_JDK`, Homebrew's `openjdk` if that is 24, `/usr/libexec/java_home -v 24`,
  or `JAVA_HOME`), else downloads Eclipse Temurin 24 into `work/jdk-24` (136 MB, checksum verified). Later JDKs run
  apksim but cannot build its startup cache yet: their AOT training verifies the translated app classes, which carry
  no stack map frames. Homebrew's `openjdk` is past 24 now, so a fresh `brew install openjdk` does not do.
- **Gradle:** the test app's wrapper (`testapks/composeapp/gradlew`), so no Gradle install.
- **The Android SDK** is only needed to build the test app (`testapks/composeapp`), which trains the startup cache.
  With `ANDROID_HOME` set or the SDK in `~/Library/Android/sdk`, setup builds it; without, it downloads the released
  test app instead (checksum verified).

After moving the folder, run `scripts/train.sh` once: the startup cache is tied to its location.

It also runs headless on Linux x86_64 in a 565 MB Docker image, for CI: see [docs/linux-docker.md](docs/linux-docker.md).

Options: `--device phone|foldable|tablet|desktop|tv|WxH[@density]` (dp, e.g. `1024x768@1.5`), `--no-network`, `--no-sandbox`, `--headless`,
`--control PORT|auto`, `--control-bind ADDR`, `--shot FILE`, `--exit-after-first-frame`, `--window-x N`,
`--appearance dark|light`, `--locale TAG`, `--font-scale N`, `--permissions ask|grant|deny`, `--biometrics enrolled`,
`--soft-keyboard`, `--adb PORT|auto`, `--debug PORT`, `--instrument TEST.apk [-e KEY VALUE]...`, `--jvm ARG`.

For tools: `--adb PORT` makes the instance an adb device (`adb connect localhost:PORT`, then `adb shell`, `push`/`pull`,
`logcat`, `screencap`), and `--debug PORT` lets Android Studio or IntelliJ's "Remote JVM Debug" stop at breakpoints in
the app's own Kotlin and Java sources.

The window is resizable: dragging its edges resizes the app's display in dp, as a resizable emulator or a
freeform window does, so adaptive Compose layouts (window size classes, `BoxWithConstraints`) follow live. Device >
Device Type (Cmd+Shift+1-5) switches between phone, foldable, tablet, desktop and TV while the app runs.

The window has the iOS Simulator's menus where Android has the same thing: screenshots and screen recording, Home,
Lock, volume, rotation, zoom, dark appearance, text size, language, location (fixed or moving), fingerprint, Open URL,
slow animations and memory warnings, two-finger gestures with Option-drag, a drawn status bar, permission prompts,
notification banners, and a clipboard shared with the Mac. `scripts/ctl.py` drives running
instances like `xcrun simctl`. See [docs/ios-simulator-parity.md](docs/ios-simulator-parity.md) for what is left.

## Instrumented tests

`./apksim --instrument app-debug-androidTest.apk app-debug.apk` runs an app's androidTest suite in one process, as
`am instrument -w -r` would on a device, and prints the same output; `scripts/instrument.py` runs a suite with one
process per test (as the AndroidX Test Orchestrator does) and writes JUnit XML. Compose UI tests, Espresso,
ActivityScenario and UiAutomator work; a library's device-test APK (KMP `androidDeviceTest`) is passed as both. Code
that renders offscreen through Android's GLES20/30 and EGL14 gets a real GLES 3 (ANGLE on Metal; Mesa in the Linux
image).

A large app's device tests, the same APKs its CI runs on two API 34 emulators: 4,107 tests in about 4.7 minutes on a
Mac and 8.3 in the Linux image under Rosetta, against about 19 on the emulators. All pass once two of the app's tests
are fixed (one clicked the wrong node; some journeys reached a real server on the developer's own network). Details,
costs and commands: [docs/ci-testbed.md](docs/ci-testbed.md).

## Speed

Measured here (M4 Pro, 24 GB) from pressing Enter to the app's first frame, medians of 5 runs (`scripts/bench.py`).
A launch is normally *warm*: handed to a standby process that has already booted the framework (below). A *cold*
launch, the first one from a folder or with `APKSIM_STANDBY=0`, starts a JVM.

| | Window | App on screen, warm | Cold | Memory |
|---|---|---|---|---|
| apksim, small Compose app | 0.01 s | 0.22 s | 1.35 s | ~550-780 MB |
| apksim, a large app (R8 release build, 12 MB DEX, SQLite + WebRTC .so) | 0.01 s | 0.36 s | 1.5 s | |
| apksim, the same app's debug build (179 MB APK) | 0.01 s | 0.48 s | 1.6 s | |
| Stock emulator, Quick Boot snapshot (same small app) | - | | 8.3-9.0 s | 3-4 GB |
| Stock emulator, full boot | - | | 18 s | 3.3-4 GB |

Before warm launches (same machine, interleaved runs): window 0.1 s, first frame 1.5 s (small app), 1.9 s (large
app) and 2.0 s (its debug build).

## How it works

- **Framework:** real AOSP Android 16 (API 36) code, from Robolectric's `android-all`, with its native
  Skia/HWUI/SQLite for macOS. Robolectric's shadows stand in for system services.
- **App code:** `runtime/.../dex/` translates DEX to JVM bytecode lazily, class by class, as classes are
  first loaded (about 5,400 classes in about 0.6 s). Methods over the JVM's 64 KB limit are split automatically.
  Translated classes are kept in `work/cache/classes` per DEX file, so later launches skip translation, and a
  rebuilt APK only retranslates the DEX files that changed, on worker threads using the class list from the
  previous launch. DEX files are mapped from the APK rather than copied onto the Java heap. With a warm cache,
  first frame went from 1.79 to 1.43 s (test app), 1.83 to 1.66 s (the large app) and 2.38 to 1.78 s (its
  debug build), medians of 5 interleaved runs on a busy machine.
- **Memory:** a 64 MB young generation (was 256 MB) and the mapped DEX files brought the footprint at first frame
  from about 580 to 410 MB (test app) and 750 to 450 MB (the large app's debug build). GC pauses while scrolling went from
  about 12 ms median (50 ms worst) to about 1 ms (9 ms worst), because fewer objects survive each collection.
- **Native code:** `native/` loads Android arm64 ELF `.so` files directly (same CPU, so no emulation), with a
  bionic-to-macOS shim for structs, constants, errno and pthreads, and a bridge for the varargs ABI difference.
- **Pixels:** Android's real render pipeline draws into a buffer that goes straight to an IOSurface on a
  native Cocoa window, about 0.5 ms per frame to present.
- **Startup:** the JVM's AOT cache covers the framework, for any APK, so there is no per-APK install step. On a Mac,
  a launch goes to a **standby**: a JVM started in the background after the previous launch, which has loaded the
  framework, its native runtime and an application, run a framework-only activity through rendering (so the
  framework's call sites are linked and its hot code compiled), and waits. As Android's zygote, it holds no app
  code: the launch hands it the APK and the terminal, and it becomes that instance in ~10 ms, window first. Then the
  classes the app used last time are defined on background threads while the framework sets up the app, and
  translated methods are about 15% shorter than straight translation. The rest of a warm launch is the app's own
  startup code. A standby takes ~410 MB while it waits and quits after an hour unused; `./apksim --stop-standbys`
  stops it and `APKSIM_STANDBY=0` turns it off.

## Frame rate

Frames follow a fixed 120 Hz grid, like a display's vsync. Measured with `--control` scripts (`frames`
command: time between frames reaching the window):

| Workload | Average | On the 120 Hz grid |
|---|---|---|
| Compose list fling | 117-120 fps | ~65-75% of frames |
| Full-screen 6-octave noise shader, every frame (test app's Shader tab) | 118 fps | ~70-80% |
| Three instances at once (phone, foldable, desktop), shader on each | 120 fps | ~84% |
| The large app's bottom sheet opening / closing | 117-118 fps | ~82-85% |

Frames off the grid are typically 9-12 ms rather than dropped. Idle shader screens in the large app update at 30 Hz
because the app steps its shader clocks at 30 Hz on purpose.

How it gets there:
- **AGSL shaders run on the GPU.** The library's renderer is CPU-only and interpreted shaders per pixel (about 5 fps
  on the large app). App calls are rewritten so `RuntimeShader` draws are translated to Metal (`runtime/.../gpu/`),
  rendered a frame ahead, and drawn as bitmaps. All 28 AGSL programs in the large app's DEX translate and compile
  on Metal; the one that takes child shaders as a paint (a heat map) still runs on the CPU. Shaders over a
  layer's content (`RenderEffect.createRuntimeShaderEffect`, set through Compose's graphicsLayer) run on Metal too: the
  layer's content is rasterized on its own, uploaded as a texture, shaded, and drawn as a bitmap (the large app's camera
  page 49 → 130 fps, a weather screen 23 → 86, a map screen 8.6 → 30). Scaled bitmaps (map and photo tiles) are resampled on Metal
  once and then blitted 1:1, which took panning the map from 26 to 64-73 fps.
- **Rasterizing overlaps recording.** HWUI's synchronous draw runs on its own thread, and the main thread starts on
  the next frame as soon as HWUI has synced.
- **Ripples use the plain solid style.** The patterned Material ripple costs ~15 ms a frame on the CPU, and its
  render-thread animation never finished here. The visible difference is the absence of the sparkle texture.
- **Smaller fixes:** the simulated clock no longer loses sub-millisecond time, the frame threads get interactive
  priority, and a startup full GC is avoided.

What is still CPU-bound: HWUI rasterizes on one core, about 6-7 ms for a busy full-screen frame, which is close to
the 8.3 ms budget. Screens with full-screen translucent layers can exceed it on some frames.

## Limits

- **Rasterizing is CPU-only, on one core.** See Frame rate above.
- **Older native libraries fail to load.** Code and data must not share a 16 KB page, because macOS forbids
  writable + executable memory. Libraries built with 4 KB alignment (Flipper, and its libc++ here) get an
  UnsatisfiedLinkError. Those built 16 KB aligned work. So debug builds that bundle Flipper do not start.
- **APKs need arm64-v8a** for native code (x86_64 in the Linux image). Native libraries that use GLES, audio (OpenSL/AAudio) or the camera will
  not work: those libraries are stubs. Java's AudioTrack does play, and in test runs Java's GLES20/30 and EGL14
  render offscreen on ANGLE, but nothing presents GLES in the window.
- **Test runs are Robolectric-style:** the test body runs on the main thread with a simulated clock, so tests that
  wait for real time or another process (UiAutomator's shell commands, accessibility events) are not covered; see
  AGENTS.md, "Instrumentation tests".
- **The native C library shim is partial.** It covers the functions these APKs import (sockets included, which
  libwebrtc needs), and an unsupported call prints its name.
- **It is not a device.** System services are stand-ins (no Play Services, Health Connect, real camera or sensors),
  and other apps do not exist. What the simulator does provide: started services, JobScheduler jobs (so WorkManager)
  and alarms run, FCM pushes can be delivered with `push`, notifications show as a banner, web and mail links open
  on the Mac, and sharing goes to the Mac's share menu; other intents leaving the app go nowhere (cancelled, if a
  result was asked for). Treat it as a fast UI and logic check, not a compatibility guarantee.
- **Security:** an APK runs as code in this process. `sandbox-exec` confines it: writes only under `work/` and
  temp, no reads of `~/.ssh`, `~/Documents`, keychains and similar. It is deprecated by Apple and is a safeguard
  for your own apps, not a boundary for hostile ones.

## Tested

`testapks/composeapp`: taps, swipes and flings, FAB state, navigation, SQLite write surviving a restart,
three device sizes at once. The large app: first screen, typing, sheet with back, AGSL backgrounds, live network
data, and its Android `libsqliteJni.so` writing a database that `sqlite3` validates (55 tables, integrity ok).
Signed in to the large app, every screen reached (about a dozen, from sign-in and a map to a live camera page and
settings) matched an API 36 emulator at the same size to within a mean pixel
difference of about 2/255, apart from Health Connect content and animation timing. Live camera video (WebRTC) and recorded
clips (Media3/ExoPlayer through MediaCodec) play, H.264 decoded on VideoToolbox. MediaCodec also decodes HEVC
(VideoToolbox) and AAC and MP3 (AudioToolbox), and AudioTrack plays through the Mac's speakers at real speed
(`selftest` checks each with a 2 s clip).
`scripts/drive.py` scripts a running instance over `--control`. All input tests went through that channel: the
window's own mouse, trackpad and keyboard handling is written but has not been exercised by a test.

## Contributing

Changes go to `main` through pull requests, which need the `linux-smoke` CI check (the Linux image running the test
app headless and its androidTest suite, plus a DEX translator check) to pass. CI cannot check the Mac-only parts, the window, Metal and launch timings, so say in the PR what
you checked locally. [AGENTS.md](AGENTS.md) explains how to build, run and measure.
The `linux-smoke` check and the git hooks (`scripts/hooks/check-private`) also refuse personal information: names of
private apps, servers and emails.

## License
Apache License 2.0: see [LICENSE](LICENSE). Running an APK does not change its own license; Robolectric and the
Android framework jars that `scripts/setup.sh` downloads keep theirs.
