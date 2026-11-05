# apksim: agent guide

Runs unmodified Android APKs on an Apple Silicon Mac as **one ordinary process, no VM**. It was built to
match the "Android Prototype" demo from Android Dev Summit 2026 (talk: https://www.youtube.com/watch?v=w-auFZP5gJo,
11:00-13:20; keynote: https://www.youtube.com/watch?v=8Lmv7LGgL4E, 13:25-14:40). In those demos the app is on screen
3-4 s after Enter. Read README.md for results and limits; this file covers how to work on the code.

## Owner's goals (in order asked)
1. Launch as fast as the demo: done. Then "improve start up time by 10x, as fast as possible to load a new
   simulator": a launch is handed to a pre-booted standby JVM (see "Warm launches"): window ~0.1 s → 12 ms, first
   frame 1.49 s → ~0.22 s (test app), ~1.9 s → ~0.36 s (the owner's R8 app). What is left is mostly the app's own
   startup code, interpreted (cold JIT). Measured with `scripts/bench.py` (cold and warm).
2. Run real APKs, including the owner's own app: done (its R8 release and debug builds both run).
3. **120 fps**: the owner said "don't stop until it's that fast". Average is now ~117-120 fps on normal UI, the GPU
   stress shader and the owner's app's sheet animation. 15-35% of frames are still 9-12 ms rather than 8.3 ms. See
   "Open work".
4. **The owner's app's device tests as a CI testbed** (asked 2026-11-03: run its emulator tests here, compare CI cost
   and time, fix until nothing fails): all 4,107 pass with two fixes to the app's own tests (patches in the private notes),
   4.7 min on the Mac, 8.3 min in the Linux image under Rosetta, against ~19 on the emulators. In the app's own CI
   (2026-11-04, apksim 0.5.0) all 4,111 pass, none flaky and the emulators agreeing, in a 15.6 min job. See
   docs/ci-testbed.md, and docs/plan.md phase 0 for what is left.

## Layout
- `apksim`: launcher (bash). Hands the launch to a standby when it can (`work/runtime/apksim-client`), otherwise
  builds the classpath, sandbox (`sandbox-exec`) and AOT cache flags, then runs `dev.apksim.Main`. Builtins only up
  to that hand-off: each fork there cost a few ms.
- `runtime/src/main/java/dev/apksim/`
  - `Main`: entry point. Opens the window first, then installs app classes and runs Robolectric's JUnit runner on `Host`.
  - `Standby`, `StandbyWarmup`: warm launches (see "Warm launches").
  - `Host`: the Android main thread. Activity stack, input dispatch, the frame loop (on the display's vsync, else a 120 Hz grid), and the `--control`
    commands (`runCommand`).
  - `SimRunner`: points Robolectric at the APK. **FlatSandboxBuilder/FlatLoader** load the framework from the app
    class loader rather than Robolectric's instrumenting loader, so the JVM AOT cache can hold it. It also registers
    extra shadows.
  - `AppClasses`: adds an `apksim:` URL to the JVM app class path. Class bytes are translated from DEX on demand.
    DEX files are mapped from the APK (`DexFile` reads a `ByteBuffer`), not copied onto the heap; compressed ones
    (APKs for older Android versions) are inflated once into `work/cache/<apk>-<mtime>/dex/` and mapped from there
    (inflating the test app's 36 MB took ~60 ms a launch). Classes are found by binary search of the DEX's sorted
    type_ids (`DexFile.findClassType`), not a map of every class name (~80 ms for 21k classes). `loadAhead` defines
    the classes the app loaded last time (no static initializers, so no app code) on 2 threads while the main thread
    starts the framework: ~100 ms off a launch.
  - `ClassCache`: translated classes kept between launches in `work/cache/classes/<runtime hash>/<dex crc>.pack`.
    An entry is used only while the "is X an interface?" answers it was translated with still hold (the
    translator's only input from other classes). `<package>.classes` lists what the app loaded, so worker threads
    pre-translate the classes of changed DEX files once the app asks for its first class. The runtime hash covers
    every class in `apksim.jar`, so any rebuild starts with a cold cache. `--jvm -Dapksim.classCache=false` turns
    it off. It saves once translating pauses for 3 s, and again on the way out (`quit`, closing the window, Cmd+Q,
    `--exit-after-first-frame`), so `scripts/train.sh` leaves the test app's cache warm.
  - `dex/`: the **DEX→JVM translator** (written from scratch; dex2jar miscompiled code and took 30 s). One Dalvik
    register is one JVM local; union-find type inference over values. A handler sees the registers before an
    instruction that can throw (returns cannot), and only there: merging more (a handler's live registers at every
    block of its range, or at a return) typed ints and floats as references and dropped them
    (`-Dapksim.debugTypes=owner/Class.method` reports such a merge). Methods over 64 KB are split automatically
    (`emitSplit`). It also rewrites app calls: `System.loadLibrary` → `Natives`, `new RuntimeShader` →
    `TrackedRuntimeShader`, `Canvas.draw*(…Paint)` → `GpuCanvas`, `RenderEffect.create*` → `GpuEffects`,
    `new RenderNode` → `GpuRenderNode`. `Peephole` then shrinks each method (not with `--debug`): a parameter copy
    is read from the parameter, and an adjacent `xstore n; xload n` with n dead after (liveness, handlers included)
    goes, so a getter is `aload_0 getfield ireturn` rather than 7 bytecodes. ~15% fewer bytecodes, ~4% off a warm
    launch; `-Dapksim.peephole=false` for A/B (its own class cache). The translator runs on every class a launch
    after a rebuild asks for, so its data is flat (2026-11-03, from a JFR profile of `scripts/translate-all.java`):
    Peephole and liveness work per basic block over `long` words, labels exist only at block starts (every
    instruction only with debug info), DEX references sit in arrays by index shared across threads' views. That
    halved translation time and allocation; a translator change must leave `translate-all.java`'s output identical
    unless it means to change the bytecode.
  - `HwRenderer`: Android's real HWUI pipeline (CPU Skia). `syncAndDraw` runs on its own thread; the main thread
    waits only for HWUI's sync (the `FrameDrawingCallback`), so recording overlaps raster.
  - `gpu/`: AGSL → Metal. `AgslTranslator` puts uniforms and functions in one MSL struct. `TrackedRuntimeShader`
    renders on the GPU one frame ahead (`submit`/`collect`) and is drawn as a BitmapShader; a shader not drawn for a
    second has its frame collected by `sweep` (each one left behind mid-animation held a full-size Metal texture for
    good: the owner's app grew ~16 MB per visit to a shader screen). `uniform shader` inputs
    become textures (`AgslChild`: bilinear, transparent outside, as Skia samples an effect's input).
    `GpuRenderNode` (Compose's graphicsLayer nodes) runs `createRuntimeShaderEffect(shader, "content")` on Metal:
    the app's drawing goes into a separate content node, which `HwRenderer.draw` (`beforeSync`, before HWUI syncs)
    rasterizes every frame with its own HardwareRenderer, uploads, shades, and records into the layer as a bitmap.
    Anything it cannot do goes to HWUI's CPU effect as before. `-Dapksim.cpuLayerEffects=true` turns it off for A/B
    runs; `-Dapksim.effectSelfTest=true` compares it with HWUI's CPU result at startup (log: mean diff under 0.12),
    alone and inside a parent as scaled, rotated and nested layers. `shaders` reports `rasters` (open content
    rasters) and `discards`. Memory freed only at GC: HotSpot does not count native memory (Bitmap pixels, renderers)
    towards collecting as ART does, so free native-backed objects eagerly where the code knows they are done.
    `GpuBitmaps`: app `drawBitmap(bitmap, src, dst, paint)` calls (Compose's drawImage) that scale a filtered bitmap
    onto whole device pixels get a copy already that size, resampled once on Metal (bilinear in float, as Skia), which
    HWUI then blits 1:1; Skia's CPU bilinear was most of a map screen's frame. Copies are cached (96 MB, LRU) and made only
    for a size asked for again within 8 frames, so zooms stay on the CPU. `-Dapksim.gpuBitmaps=false` turns it off;
    `shaders` shows hits and why draws went to HWUI. Metal targets are linear (buffer-backed, pooled): reading back
    a tiled texture made the CPU untile it, ~3 ms per full screen.
  - `Instrument`, `TestActivities`, `ShadowSimUiAutomation`: `--instrument` test runs, the system server's part of
    them, and UiAutomator (see "Instrumentation tests").
  - `gl/`: GLES20/30 and EGL14 on a real GLES (ANGLE) for offscreen rendering in test runs (see "GLES").
  - `ShadowFastRipple`: forces the SOLID ripple with software animation. The patterned ripple's per-pixel shader
    cost ~15 ms a frame, and its render-thread animation never finished, because there is no native looper.
  - `ElfLoader`, `Natives`: load the APK's arm64 `.so` files through `native/`.
  - `Storage`: per-PID scratch under `work/tmp` (swept on next launch) and persistent app data under
    `work/cache/data/<pkg>/default` (or `instance-N` when several run); `--fresh` empties the slot it takes first.
    Files written beside their final name and renamed into place get `Storage.tmpTag()`, PID plus a random part:
    containers sharing a cache volume repeat PIDs (the image's JVM is PID 1).
  - `AdbServer`, `AdbShell`, `AdbLog`: the adb device (`--adb PORT`, see "adb device").
  - `FastNativeLoader`: loads Robolectric's native runtime from pre-extracted `work/runtime`, not a fresh temp dir per launch.
- `native/`
  - `simwindow.m`: Cocoa window and IOSurface present, input queue, Cmd+V paste, `wshot` self-capture, thread QoS.
  - `elfloader.c`, `bionic.c`, `jnibridge.c`: the Android ELF loader, bionic→libSystem shim, and a JNI table wrapper
    for the AAPCS-vs-Apple varargs difference (chosen by caller address).
  - `gpushader.m`: Metal pipelines for translated AGSL.
  - `standby.c` (JNI) and `apksim-client.c` (the launcher's client): the warm-launch hand-off.
  - `loopback.c`: under `--no-network` only (`DYLD_INSERT_LIBRARIES`, set through `/usr/bin/env` after
    `sandbox-exec`, since dyld drops DYLD_* variables for system binaries). The sandbox can allow only
    `localhost` (127.0.0.1 over IPv4, ::1 over IPv6), and the JDK reaches 127.0.0.1 on a dual-stack socket as
    ::ffff:127.0.0.1, which it refused; a TCP `connect()` to a mapped 127.x address swaps the socket for an IPv4
    one on the same descriptor (`dup2`, keeping a bound port), and an already swapped one connects in place.
    `dup2` drops the old socket's kqueue filters (a Selector registered before connect would hang), so it also
    interposes `kevent`/`close` to record each descriptor's filters and adds them to the new socket. Not UDP: the JDK
    later uses IPv6-level options on a DatagramChannel (disconnect fails on an IPv4 socket), so Java UDP to
    127.0.0.1 stays refused. The rest of 127/8 cannot be allowed (the profile language takes only `localhost`).
- `scripts/`: `setup.sh` (one-time; fetches Temurin 24 with `fetch-jdk.sh` and the released test app with
  `fetch-testapp.sh` when there is no JDK 24 or Android SDK), `jdk.sh` (sourced: finds the JDK 24 every build script
  uses; `build.sh` records it in `work/jdk.path` for the launcher), `build.sh` (Java → `work/apksim.jar`), `build-native.sh`, `train.sh` (AOT cache),
  `bench.py` (launch times, cold and warm), `drive.py` (control channel client), `ctl.py` (find and drive running instances, app
  data), `instrument.py` (androidTest suites, JUnit XML; `--apksim CMD` runs the Linux image instead;
  `instrument-check.py` checks how it handles a process that dies, against a stand-in apksim),
  `check-translator.sh` (translates and runs `testapks/translator-fixture`, see "Instrumentation tests"),
  `adb-check.py` (the adb device as a tool's on-device server uses it, see "adb device"), `relaunch.sh`, `both.py`/`vshift.py` (emulator comparison),
  `prof.py` (summarise macOS `sample` output), `so-imports.py` (native import audit), `prepare-runtime.sh`,
  `gen-gles-shadows.py` (writes `gl/ShadowSimGLES20/30`), `jdi/Trace.java` and `jdi/Ret.java` (breakpoints and return
  values over JDWP), `translate-class.java` (one class's translation, for javap), `translate-all.java` (every class of
  an APK: time per pass and a hash per class, to profile the translator and check a change leaves output identical),
  `make-media.sh` (test clips), `docker-build.sh` and `ci-smoke.sh` (Linux image, CI), `package-release.sh` (see
  "Releases"),
  `hooks/` (`pre-push`: no direct pushes to main; `pre-commit`/`pre-push` run `check-private`, see "Git and pull
  requests").
- `docs/`: `ios-simulator-parity.md` (feature gaps and status), `linux-docker.md`, `ci-testbed.md` (a large app's
  device tests here: results, CI cost, commands), `plan.md` (remaining work, in order).
- `.github/workflows/ci.yml`: the `linux-smoke` job every pull request must pass (see "Git and pull requests").
- `testapks/composeapp`: the test app. It has a list, Trips (SQLite rename: "Italian vacation" ↔ "Roman vacation"),
  Settings, and a **Shader** tab, a full-screen unthrottled noise shader used as the 120 fps stress test. It is
  adaptive (material3-adaptive window size classes): below 600 dp of width a bottom bar and a list, from 600 dp a
  navigation rail and a grid; the top bar shows the class and window size in dp ("compact 411×891"), which `nodes`
  reads. Build with `./gradlew :app:assembleDebug` inside it. Its `uiserver` module is an instrumentation-only APK, a
  tool's on-device server in miniature, for `scripts/adb-check.py` (`./gradlew :uiserver:assembleDebug`).
- `work/` (not source): `lib/` jars, `runtime/` dylibs, fonts and ICU, `apksim.jar`, `app.aot`, `cache/` (per-APK
  manifest, extracted `.so` files, app data).

## Everyday commands (from ~/apksim)
```bash
scripts/build.sh && scripts/train.sh          # after any Java change: the AOT cache is invalid once the jar changes
scripts/build-native.sh                        # after any native/ change (no retrain needed)
./apksim --control 7400 testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk
python3 scripts/drive.py 7400 'wait 3000' 'tap 720 1690' 'wait 3000' 'frames' 'fps' 'quit'
python3 scripts/bench.py 5 <apk> [<apk>...]   # launch times, cold and warm (--cold / --warm for one)
./apksim --stop-standbys                       # this folder's warm-launch standbys
```
Coordinates are device pixels (phone: 822x1782, density 2). Test-app bottom nav: Home 130, Trips 300-410,
Settings 550-690, Shader 720; y=1690. FAB is at 733,1533.
The owner's own app, its paths, coordinates and sign-in are in `~/.config/apksim/NOTES.md` (private, not in the
repo): read it before working with that app.

Control commands: `tap x y`, `swipe x1 y1 x2 y2 [ms]` (with ms: a slow drag that holds before lifting, no fling),
`wheel x y amount`, `type text`, `key NAME [count] [ctrl|shift|alt|meta]` (e.g. `key A 1 ctrl`, `key DEL 5`), `back`,
`paste`, `shot file.png` (app pixels), `wshot file.ppm` (the real window, captured in-process), `activity`, `windows`,
`insets` (system bar insets the app sees), `slop` (gesture thresholds), `frames` (frame-interval percentiles since the
last call, then what reached the screen per display vsync), `fps` (per-stage cost), `display`, `shaders` (which AGSL runs on GPU vs CPU), `stats`, `ping`, `quit`.
`back` on the last activity finishes it and the process exits: there is no soft keyboard for it to dismiss.
Simulator-style device controls (`DeviceControls.java`; each is also a Mac menu item, see "Simulator features"):
`appearance dark|light|toggle`, `locale fr-FR`, `fontscale 1.3|+|-`, `timezone` (the zone the app sees; set at launch with
`--timezone America/New_York`), `config` (configuration the app sees),
`device [phone|foldable|tablet|desktop|tv|WxH[@density]]` (switch devices while the app runs: size, density, system
bars, and for TV UI_MODE_TYPE_TELEVISION, no touchscreen and a D-pad), `size WxH` (display size in dp, same device),
`window WxH` (the Mac window's content in points, as dragging its corner would: checks the drag path),
`animscale 10|1|0|toggle`, `memory [moderate|low|critical]`, `home`/`resume`, `lock`/`unlock` (a click on the window
also returns), `open URL` (deep link into the app), `clipboard [set TEXT]`, `record start [file.mov]|stop|toggle` (Mac
window only), `reveal`, `pinch CX CY FROM TO [ms]` (two fingers), `rotate left|right|portrait|landscape`,
`zoom actual|fit|in|out` (window size only), `permission [allow|deny|grant NAME|revoke NAME|reset]`,
`notifications [open N|last|clear]`, `location LAT LNG|run|bike|drive|gpx FILE|none`,
`biometrics [enroll|unenroll|match|nomatch|cancel]`, `recreate` (as a configuration change: ViewModels kept),
`process-death` (state saved, activities destroyed and rebuilt from it; statics and the Application survive, so this
tests state saving, not a cold start), `debug layout on|off|toggle` (Show layout bounds, Views and Compose; minified
Compose apps need a `recreate`), `a11y [show]` (unlabeled controls, touch targets under 48 dp), `pick FILE...|cancel`
(answers a document/photo picker). Activities started for a result now get one (`ActivityResults`): an in-app
activity's setResult when it finishes; OPEN_DOCUMENT/GET_CONTENT/PICK/PICK_IMAGES open the Mac file panel (sheet;
headless: `pick`) and return `content://dev.apksim.files/N/NAME` (`PickedFiles`: name, size, stream); anything else
leaving the app answers RESULT_CANCELED. The launcher sandbox blocks ~/Documents and ~/Desktop, so pick from
Downloads. ACTION_SEND (and choosers) go to the Mac share menu and the log.
Background work (`BackgroundWork`): started services run (and stop on stopSelf), JobScheduler jobs run once their
delay passes (charging/idle ones wait for `jobs run ID`; WorkManager rides on this), alarms fire on the simulated clock
(Robolectric's auto-schedule) and reach manifest receivers. `jobs [run ID]`, `services`, `alarms` list them.
Bound services (`BoundServices`, from `TestActivities.ShadowSimInstrumentation`'s bindService/unbindService, so in
test runs and running instances alike): Robolectric never runs a service on bind and calls onServiceConnected with a
registered binder, else with (null, null). For a service in the app's own manifest apksim binds as a device does: a
BIND_AUTO_CREATE binding creates it, onBind runs once per intent, and each connection gets onServiceConnected with
that binder or, when onBind returned null, onNullBinding; unbinding calls onUnbind (then onDestroy once no
BIND_AUTO_CREATE binding is left) and never onServiceDisconnected. Other packages' services, and intents a binder was
registered for through ShadowApplication (the FCM service below), stay Robolectric's. A bound service is not the
instance startService runs, and an `android:process` service gets its local binder, not a proxy.
`push key=value...` or `push {"data":{..},"notification":{..}}` delivers an FCM message as Play Services would: the
c2dm RECEIVE broadcast to the app's FCM receiver, with the messaging service pre-bound (Robolectric connects a binding
only to a registered binder). Checked with the owner's app: its messaging service posts the alert.
`keyboard soft on|off` / `keyboard show|hide` (`SoftKeyboard`): as in the iOS Simulator a hardware keyboard counts as
connected, so no soft keyboard by default; with it on (Device > Software Keyboard Cmd+K, `--soft-keyboard`) a focused
text field raises a drawn keyboard and the app gets real IME insets (imePadding/adjustResize), Back closes it first.
The Android Keystore (`SimKeyStore`, provider "AndroidKeyStore": KeyStore, AES/HMAC KeyGenerator, EC/RSA
KeyPairGenerator with KeyGenParameterSpec) is a test keystore: keys persist in the app's data folder in the clear and
no use restriction is enforced.
`record` without a Mac window (headless, Linux) writes Motion-JPEG `.avi` (`MjpegRecorder`, 30 fps, Android's JPEG
encoder); with a window `.mov` H.264, or `.avi` if the file name says so. Configuration changes recreate activities as on a device (state not in
`rememberSaveable`/`onSaveInstanceState` resets). A command that throws answers `error: ...` instead of ending the app.
`ctl.py apps|container PKG|erase PKG` lists, locates and deletes app data (erase refuses while an instance holds it).
`scripts/ctl.py list` / `ctl.py [@PID|@PORT|@APKNAME] CMD [ARGS] [';' CMD...]` finds running instances by themselves
(each writes `work/tmp/<pid>/control.port`); `--control auto` takes a free port.

## Simulator features (Mac window)
The window is resizable: dragging an edge resizes the display (`Device` rewrites the whole qualifier string:
smallest width, size bucket and orientation follow), applied at most every 100 ms while dragging and once at the end;
meanwhile the last frame stays pinned top left. Activities get the configuration change as on a device in a freeform
window: recreated, or resized in place when they declare `screenSize` in configChanges. Zoom only
scales the window. A display bigger than the Mac's screen (desktop, TV) opens zoomed out to fit.
Menus as in the iOS Simulator: File (Save Screen Cmd+S, Record Screen Cmd+R), Device (Device Type > Phone/Foldable/
Tablet/Desktop/TV Cmd+Shift+1-5, Back, Home Cmd+Shift+H, Lock
Cmd+L, Volume Cmd+Opt+=/-, Rotate Cmd+Left/Right), Features (Dark Appearance Cmd+Shift+A, Larger/Smaller/Default
Text Cmd+Shift+=/-/0, Language and Region..., Location > None/Custom/City Run/Bicycle/Freeway, Fingerprint >
Enrolled/Matching Cmd+Opt+M/Non-matching Cmd+Opt+N, Open URL... Cmd+Shift+O), Window (Actual Size Cmd+1, Fit Screen
Cmd+3, Zoom In/Out Cmd+=/-), Debug (Slow Animations Cmd+T, Simulate Memory Warning Cmd+Shift+M, Show Layout Bounds,
Accessibility Audit). Permission requests
and biometric prompts show as sheets on the window; notifications as a banner (click opens it).
Saves go to `work/screenshots` (the sandbox keeps the app out of ~/Desktop). Option-drag pinches around the screen
centre, Option-Shift-drag pans with two fingers, trackpad pinch/rotate map to two fingers. The status bar (clock,
Wi-Fi, battery, dark icons when the app asks for light bars) and gesture handle are drawn over the app as layers
(`wshot` shows them, `shot` does not). The Mac and app clipboards stay in sync both ways. http(s)/mailto links the
app opens outside itself go to the Mac's browser/mail app. The keyboard sends Control shortcuts, Shift selection,
Option+arrow (as Ctrl+arrow: by word), Home/End/Page/Forward Delete as Android key events. Launch options:
`--appearance dark`, `--locale de-DE`, `--timezone America/New_York`, `--font-scale 1.3`, `--permissions ask|grant|deny` (grant = `adb install -g`),
`--biometrics enrolled`. Test-app Settings tab (scroll down) has a pinch area and Camera/Locate/Notify/Copy/Unlock/
Pick file/Choose trip (in-app result)/Share/Background/Keystore/Bind buttons for these (Bind: "bind: null
binding to .NullBindingService"), and "Play clip" (ExoPlayer, a 3 s
HEVC + AAC clip into a TextureView; "clip: ended at 3.0 s after N s, video video/hevc, audio audio/mp4a-latm" when it
plays through); Trips has a "Trip note" field with
imePadding; the app follows dark mode and handles `apksim://composeapp/<tab>` deep links.

## Video
WebRTC (libwebrtc in the APK) works end to end: `bionic_net.c` translates sockets and emulates the netlink
interface dump; `HostNetworks` shows the host's interfaces to ConnectivityManager (libwebrtc ignores unnamed ones);
`SimVideoDecoderFactory` adds H.264 on VideoToolbox (`VtH264Decoder`, `native/videotoolbox.m`); `SimEglRenderer`
draws frames without GL into TextureViews via `ShadowSimTextureView` and `VideoSurfaces`. The translator substitutes
these for `DefaultVideoDecoderFactory` and `EglRenderer` (`MethodTranslator.SUBSTITUTES`). `runtime/stubs/` holds
compile-only org.webrtc signatures: never give a stub field an initializer, javac inlines constants. Code that runs
for every app (startup, shadows) must not touch a class that references org.webrtc: APKs without libwebrtc then fail
to start (NoClassDefFoundError). `VideoToolbox` holds the natives for both paths and references no APK library.
`scripts/train.sh` trains on the test app, so a regression like that shows up there as a failed stage.
Recorded video: `ShadowSimMediaCodec` lists `apksim.avc.decoder` (H.264) and `apksim.hevc.decoder` (HEVC Main/Main10),
both on VideoToolbox, and `apksim.aac.decoder` / `apksim.mp3.decoder` on AudioToolbox (`native/audio.m`) in
MediaCodecList, and registers each with Robolectric's MediaCodec under both its type and its name (players create
codecs by name; a miss falls back to Robolectric's 512-byte fake). Robolectric allocates a codec's 10 input buffers,
zero-filled, when it is created, so they start small and `sizeInputs` sizes them at configure from max-input-size
(else 8 MB: a bound from the picture size can be too small for I_PCM frames): a fixed 8 MB held 80 MB per video codec
(playing the test clip, whose player sets max-input-size: +100 → +16-33 MB).
Its placeholders are told apart by identity, so
each must be its own object: one non-capturing lambda is a single shared instance. Video decoding happens in
`native_queueInputBuffer`, not Robolectric's per-buffer process call, so that pictures leave in presentation order
with their own timestamps: Robolectric pairs each input with one output that keeps the input's timestamp, and with
B-frames (decode order pts 0, 66, 33, ...) ExoPlayer dropped 58 of 90 frames as late. A few pictures are held back
(2, growing to 4 when one arrives older than one already out). An input whose picture is held keeps its buffer
(the codec owns it, as on a device); at end of stream those buffers carry the held pictures out in order, before the
end-of-stream buffer (`selftest clip.h264` checks every frame arrives). Other inputs that give no output are passed on
with API 34's BUFFER_FLAG_DECODE_ONLY, which Robolectric recycles without an output. Video output buffers carry a
reference to the decoded picture, drawn into the Surface's TextureView on releaseOutputBuffer(render=true); audio ones
carry 16-bit PCM. `ShadowSimAudioTrack` plays AudioTrack PCM (16-bit, 8-bit, float) on the Mac's default output
through a CoreAudio AudioUnit, and its playback head moves only as frames are played: Robolectric's counts everything
written as played at once, which would let ExoPlayer's audio clock race. `--jvm -Dapksim.mute=true` keeps the timing
and plays silence; `--jvm -Dapksim.audio=false` turns sound and the audio decoders off. Bitmaps here are BGRA in memory (Skia's macOS order),
not Android's RGBA: write pixels in `VtH264Decoder.BITMAP_BGRA` order.
Control commands: `video` (decoded/drawn counts, surfaces, MediaCodec counters with decode/draw ms, and each live
codec's output: a view size, or `untracked` when the player moved it to its own placeholder surface, i.e. it has no
view to draw to), `webrtclog [info|verbose]`,
`selftest [file]` (native scaler, colour order; with a `.h264`/`.h265` file MediaCodec onto a Surface, with an ADTS
`.aac` or `.mp3` file MediaCodec into AudioTrack, checking the sound took real time and that releasing a track while
another thread is blocked writing to it returns the write (`ShadowSimAudioTrack` frees the stream only once no native
call on it is in progress: before, the writer read the freed stream); `scripts/make-media.sh` writes 2 s
clips to `work/media`), `nodes` (accessibility tree: text, hints,
fields, clickables with bounds), `logcat on|off` (app's android.util.Log to stderr; `APKSIM_LOGCAT=1` at launch).
ELF natives whose stack arguments differ between Apple's and AAPCS64's conventions (more than 8 integer or 8 FP
arguments) are bound through trampolines in `jnibridge.c`; without them they read garbage and crashed. Variadic libc
functions an APK imports need a `THUNK` in `bionic.c` (AAPCS va_list) or they fall through to libSystem, which reads
the arguments the Apple way (`syslog` then crashed in `strlen`). `scripts/so-imports.py APK` lists the imports;
check new variadic ones against `bionic.c`. `syscall()` (`s_syscall`) handles Linux arm64 gettid, getpid, getrandom
and futex WAIT/WAKE (on `os_sync_wait_on_address`); everything else is ENOSYS. libc++abi's guard for function-local
statics needs the first two: with gettid -1 on every thread, two threads meeting at one static aborted an app now
and then at WebRTC start ("__cxa_guard_acquire detected recursive initialization", which it logged via syslog).
Recorded HLS clips (4K H.264 at 20-25 fps plus 8 kHz AAC, checked 2026-11-03) play at their frame rate, in step
with the audio clock, and seek. Drops with sound on (0-7%) came from the stream: its audio timestamps jump ~240 ms at
segment joins (logcat: "Unexpected audio track timestamp discontinuity") and Media3 moves its clock to match.
Apps that sign in on every cold start can be signed in by apksim itself (`Autofill.java`): it reads
`work/autofill/<package>.properties` (mode 600, written by the owner), finds fields by their label/placeholder in
the accessibility tree, fills them, checks they stuck, presses `submit`, and logs field names only. Never read,
print or edit that file, and never type the owner's password: if it is missing, ask the owner.

## Warm launches
A launch is handed to a **standby**: a JVM started in the background that has booted everything that does not depend
on the APK, as Android's zygote does, and waits on a Unix socket (`$TMPDIR/apksim-standby-<hash>.sock`, log beside
it). The launcher's client (`native/apksim-client.c`) sends the APK, the launch's options and working directory, and
its own stdin/stdout/stderr (SCM_RIGHTS); the standby (`Standby.java`, `native/standby.c`) puts those on fds 0-2,
moves to that directory, and becomes the instance, so its output reaches the terminal and its exit code becomes the
client's. If the client is killed (Ctrl-C, closed terminal, `kill`), the instance quits. Before waiting, a standby runs
`StandbyWarmup` as a Robolectric test with no APK: the sandbox `Host` reuses, the native runtime, an application,
then three rounds of a framework-only activity (views, text, a RenderNode drawn through `GpuCanvas`, HWUI frames
through `HwRenderer`, touches), so call sites Robolectric links through invokedynamic and framework classes are
done, plus the codec list, networks, XML parser and runtime hash; Robolectric then resets as between tests. No app
code runs before the launch. It then waits for the JIT queue to drain (~2.2 s from start to ready, ~410 MB idle).
- One standby per apksim folder and sandbox flags (`--no-network`, `--no-sandbox`), not per working directory.
  Instances start the next one 1 s after their first frame (`--spawn-standby`, inside their own sandbox) or on exit.
  A launch waits for a booting standby only past its first second (a cold launch is ~1.3 s), else launches cold.
- A standby quits after an hour unused (`-Dapksim.standbyIdle` seconds) and when `work/apksim.jar` or `work/app.aot`
  change, so after `build.sh` + `train.sh` the next launch is cold and starts a fresh one. `./apksim --stop-standbys`
  stops them; `APKSIM_STANDBY=0` launches cold and starts none. `--jvm` options other than the `-D` switches listed in
  the launcher (read after the hand-off), `--debug`, `--instrument` and training launch cold: a property read while
  the standby booted would be ignored. Linux has no standby.
- An instance from a standby has the standby's environment and PID, not the launcher's: `scripts/ctl.py` reads the
  APK from `work/tmp/<pid>/launch.info`. Resolve paths from commands with `Storage.absolute`, not
  `Path.toAbsolutePath` (the JDK fixes that at JVM start).
- `-Dapksim.timing=verbose` prints finer startup marks (app info, classes, sandbox, application state, host setup).
- Tried without gain: one merged uncompressed class-path jar (the JDK's 40-jar search per app class was not the
  cost), lower JIT thresholds (`CompileThresholdScaling` flooded the compile queue), more compiler threads.
- Measured 2026-11-03 (JFR, `-Dapksim.timing=verbose`): of a ~310 ms warm launch of the test app, the APK index and
  class cache take ~10 ms (index load mostly off the main thread) and the main thread waits 1-2 ms on locks in all,
  none on AppClasses'; the rest is the app's own startup (Application ~45 ms, activity ~65 ms, resume to first frame
  ~160 ms). So the class cache's index and locking are not worth redesigning for launch time. Translation is: a warm
  launch that translates everything (`-Dapksim.classCache=false`, as after a rebuild) took ~1.55 s, ~1.15 s since.
- Not tried: a standby prepared for the last APK (classes defined, resources parsed: ~40 ms more, but a second
  standby's memory for rebuilt APKs), per-APK AOT caches for app classes, WhiteBox-queued JIT of last launch's hot
  methods.

## adb device
`./apksim --adb PORT ...` (or `--adb auto`; the port is written to `work/tmp/<pid>/adb.port`), then
`adb connect localhost:PORT`: the instance shows in `adb devices` as `apksim_phone` and standard adb tooling drives
it. `AdbServer` is the device side of the adb transport (CNXN without AUTH or TLS, OPEN/OKAY/WRTE/CLSE with one
WRTE in flight per stream) plus `sync:` v1 (STAT/LIST/SEND/RECV). `AdbShell` is a small shell (`;` `&&` `||`,
quotes, `>`/`>>`; no pipes: pipe on the Mac) whose commands go through `Control.QUEUE`, i.e. the same
`Host.runCommand`/`DeviceControls.run` code as `--control`; anything needing the main thread otherwise is posted to
the main looper (`AdbShell.onMain`). Checked with adb 36 against the test app:
`adb shell input tap 733 1533` (FAB count goes up), `input swipe|text|keyevent` (BACK/HOME/POWER/MENU map to
back/home/lock/unlock), `adb exec-out screencap -p > shot.png` (app pixels, as `shot`; without `-p` raw RGBA),
`adb shell am start -a android.intent.action.VIEW -d apksim://composeapp/trips` (= `open URL`), `am start [-W] -n
PKG/.Act` (an already running activity is brought to the front, not stacked), `am broadcast|startservice`, `monkey -p
PKG 1`, `pm list packages|path|grant|revoke`, `wm size|density`, `settings get|put global|system|secure`
(`animator_duration_scale` = `animscale`, `font_scale` = `fontscale`), `cmd uimode night yes|no`, `getprop`
(`sys.boot_completed=1` once the app is up), `run-as PKG`, `ls/cat/rm/mkdir`, `dumpsys activity top`, and
`adb shell apksim CMD...` for any control command. `adb logcat` reads `AdbLog`: ShadowLog.stream is replaced by a
tee that timestamps lines into a 20k-line ring and still passes them on (`logcat on` keeps working; a watcher puts the
tee back when the stream is reassigned); supports `-d -c -t N -T N -s -e`, `TAG:LEVEL` and `*:S`.
`adb push/pull` (and `ls`) see `/data/data/PKG` = `/data/user/0/PKG` (the app's persistent data folder),
`/sdcard` = `/storage/emulated/0` (Robolectric's external storage, per process) and `/data/local/tmp` (this
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

## Instrumentation tests (androidTest APKs)
`./apksim --instrument app-debug-androidTest.apk [-e KEY VALUE]... app-debug.apk` runs a test APK against the app as
`am instrument -w -r` would and prints its raw output (INSTRUMENTATION_STATUS blocks, exit 1 on any failure). As on a
device, the test APK's classes join the app's in one process: `AppClasses.installTest` serves them from `apksim:/test/`
after the app's (their class cache hints are `<package>.test.classes`). `Instrument` reads `<instrumentation>` from the
test manifest, finds the test classes (library packages are not searched), and runs each under `SimRunner`
(`@RunWith(AndroidJUnit4)` is pointed there by `android.junit.runner`). Tests run the Robolectric way, not the device
way: the test body on the main thread, Espresso/ActivityScenario through Robolectric's in-process `LocalUiController`
and `LocalActivityInvoker`, a simulated clock that jumps over idle time, and no window or Host loop. The
instrumentation class itself (AndroidJUnitRunner or a subclass) is not run, except its `newApplication`: when it is
not plain AndroidJUnitRunner, `RunnerApplication` first runs a probe test (with a plain Application, so the app's
onCreate does not run) that creates the runner and calls its newApplication with the manifest's class; the
Instrumentation shadow records the class it hands on to Instrumentation.newApplication and stops there, and every test
then gets that Application (`SimTestEnvironment` sets `Config.application`; Robolectric's global config is fixed
before the probe). If the runner's newApplication fails with the probe's context, the manifest's is kept, with a
line in the log; `--jvm -Dapksim.runnerApplication=false` skips the probe. The test app's `HarnessRunner` swaps in
`HarnessTestApplication` (`RunnerApplicationTest`). The runner's listeners and other runner arguments are not run.
`-e class a.B#m,a.C`, `notClass`, `package`, `notPackage`, `log true` (list only) work (in `class`/`notClass`, `\,` is
a comma inside a name and `\\` a backslash, for Parameterized's `test[0: x, y]`: an apksim extension, which
`instrument.py` writes when it names tests; any other backslash is literal), and every `-e` reaches
`InstrumentationRegistry.getArguments()`; `--device tablet` is 2560x1600 at density 2, the emulator's Nexus 10.
Each test gets a fresh Application and data folder (the app's persistent data is not used), but app statics carry over
within a process. `scripts/instrument.py APP TEST [--isolate] [-j N] [--xml FILE] [-- APKSIM_OPTS]` runs a suite
and writes JUnit XML; `--isolate` gives every test its own process, as the AndroidX Test Orchestrator with
`clearPackageData` does. Without it, a process that ends before every test has a result (a native crash or abort, or
the Timeout watchdog below) does not end the run: the test that was running is reported failed with the exit status
and the stderr tail (with none running, a failed "(run)" entry is, so the run still fails), the suite is listed (`-e log true`, the run's own `-e` arguments, only once a process has died),
and the tests with no result run in a new process, as `-e class` (whole classes where none of their tests ran, else
Class#method, chunked under Linux's 128 KB argument limit) with the other `-e` arguments kept. No test runs twice; a
new process that adds no result (a crash at startup) ends the run, the rest reported failed as not run.
`ProcessDeathTest` checks it with `-e crashOnPurpose true` (otherwise its two crashing tests are skipped): one halts
the process, one ignores interrupts past its timeout, and the tests after each still pass. Two things this needed in `SimRunner`: one shared injector (so one sandbox: a second
sandbox brought a second main thread to a main Looper already bound to the first), and `SimTestEnvironment`, whose
reset between tests asks the JVM's loader which classes are loaded (Robolectric casts its own loader to
`ClassTracker`, which `FlatLoader` bypasses). Every test runs on API 36 whatever the APK targets (`SimRunner.OneSdk`:
Robolectric otherwise picks the manifest's targetSdk, and an app targeting 37 had no tests left to run). Test classes
are found from their class bytes (ASM), not reflection: that runs before the sandbox exists, and parsing an annotation
whose default is a framework enum initialized that class without it, breaking it for the run. A library's test APK
(KMP `androidDeviceTest`, which instruments itself) is passed as both: `--instrument X-androidTest.apk X-androidTest.apk`
(no launcher activity needed in a test run). The test app's `src/androidTest` (Compose rule, Espresso, UiAutomator, an
animation clock check, a translator check and offscreen GLES) is the check:
`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`; CI runs it in the Linux image. The translator's own
regression check is `scripts/check-translator.sh` (CI too): `testapks/translator-fixture` is plain Java built with R8
(`fixture.dex`) and with D8 in debug mode (`fixture-d8-debug.dex`), whose register reuse produced the two liveness
miscompilations (an int returned in a synchronized block's lock register; a return shared by paths inside and outside
a lock's catch-all range); it translates them and runs them on a plain JVM. Its `build.sh` rebuilds the committed
`.dex` files. When the translator miscompiles a new shape, add it there and check it fails before the fix.
UiAutomator works (`ShadowSimUiAutomation`): each window's root node is linked to its views with
`setQueryFromAppProcessEnabled` (API 34), so UiAutomator walks the real view and Compose tree through the framework's
AccessibilityInteractionController (By.text/desc/res with testTagsAsResourceId, clicks and gestures by injection,
setOrientation* as a real configuration change, waitForIdle advancing the simulated clock frame by frame until the
UI is quiet). No accessibility events: executeAndWaitForEvent reports a window change after the action settles,
other filters time out. `TestActivities` does the system server's part that Host's stack does otherwise: BACK goes to
the window's back callback as ViewRootImpl would (Robolectric injects past it), back on a launcher task's root moves it
to the background, finish() pauses/stops/destroys and resumes the one below, and a start for a running
singleTask/singleTop activity is delivered to its onNewIntent. More of a device's behaviour that a test run needs
(each found by a suite that passed on the emulator and failed here):
- Compose's test clock posts to the UI thread behind what is queued there on a device; here the test is on the main
  thread, so the translator calls `TestActivities.beforeUiThreadAction` before Compose's `runOnUiThread`, which runs
  the main queue first (GlobalSnapshotManager's apply). Without it, with autoAdvance off, a state set in runOnIdle
  never reached the composition. The looper stays PAUSED: Compose's Robolectric idling needs it
  (INSTRUMENTATION_TEST mode made it call Espresso.onIdle on the main thread; a device fingerprint made it wait for
  draw passes that never come).
- `Thread.sleep` in app and test code is translated to `TestActivities.sleep`: in a test run, on the main thread and
  outside a message dispatch, it runs the main queue's due messages while sleeping (the clock does not move), as a
  device's main thread would run beside the test thread. A journey polling its fake server for a request that a
  Dispatchers.Main coroutine had not sent yet otherwise waited out its timeout (`MainThreadSleepTest`).
  `runBlocking { delay() }` is not covered: kotlinx parks the thread itself, and the main queue waits.
- JUnit's `Timeout` rule (and `@Test(timeout)` in plain JUnit runners) runs the test on a thread of its own, and
  Robolectric refuses to drive the main looper from there ("Main looper can only be controlled from its thread in
  PAUSED mode"). apksim.jar comes first on the class path with its own `FailOnTimeout` (JUnit 4.13's API): the test
  stays on the main thread and a watchdog interrupts it when the time is up (`TimeoutRuleTest`). A test still running
  10 s later, ignoring interrupts, ends the process with its stack (a device abandons its thread and goes on; here it
  holds the main thread): `instrument.py` reports that test failed and runs the rest in a new process.
- `UiAutomation.executeShellCommand` (UiDevice's too) runs apksim's adb shell (`AdbShell`) in the process, to the end,
  and returns what it printed: `getprop`, `settings put global animator_duration_scale 0` (as `animscale`: Views and
  Compose both follow it), `touch`/`ls`/`rm` in /sdcard, `logcat -c`/`-d` (the log is kept from the first `logcat` a
  test asks for), `am`, `pm`. On the main thread a command runs at once, not through the Host loop, and in a test run
  the control commands it reaches are animscale and fontscale (`ShellCommandTest`).
- The framework's wall clock is the real one (`SimRunner.WallClock`): Robolectric pointed System.currentTimeMillis
  in framework code at its uptime clock, so the framework thought it was 1970 while the app's own code and the JDK
  knew the date (DateUtils.isToday(now) was false). The uptime clock stays simulated, and the wall clock does not jump
  when it skips idle time. `--timezone ZONE` sets the JDK's and android.icu's zone, at each test's setup (ICU caches
  its default, and a standby may have worked it out before the launch) (`WallClockTest` with `-e timezone ZONE`).
- `--realtime-clock` (opt-in, test runs only; `RealTimeClock`): the simulated clock follows real time instead of
  skipping idle time, for suites written for a device whose timed UI must last (a banner that closes itself after
  5 s). By default Robolectric's Choreographer is not paused in a test run, so each frame asked for moves the clock
  15 ms and runs at once, and one Espresso check ran every frame of a running animation, closing the banner first.
  With the switch the Choreographer is paused as Host runs it, each frame asked for queues an asynchronous vsync
  message (`ShadowRealTimeDisplayEventReceiver`), a main-thread clock read moves the clock up to real time
  (`ShadowRealTimeSystemClock`), a looper held by a traversal's sync barrier waits up to 100 ms for that frame
  (Espresso posts its check behind the barrier, drains, then blocks on it: without the wait it never ran), and
  anything moving the clock past real time (idleFor, SystemClock.sleep) waits for it. Off, nothing is registered.
  The test app's suite takes 13 s with it against 5.6 s (`RealTimeClockTest`, run with `-e realtimeClock true`).
- `robolectric.windowManager.useRequestedSize`: a window gets the size it asks for, so a Compose Dialog
  (WRAP_CONTENT) is not measured at its width and then at the display's on every traversal (it never got idle).
- Processes started together share one per-APK cache folder (`work/cache/<apk>-<mtime>`, new for every rebuild):
  `ElfLoader` extracts each `.so` to a per-process temporary file and renames it into place (and skips it by the
  entry's CRC, kept in `<name>.so.crc`), and `AppInfo` writes the manifest the same way. Copying in place let one of
  four `--isolate` processes load a library another was rewriting (`NoClassDefFoundError` for a bundled SQLite
  driver's native library, on the first test after a rebuild).
- The framework's 8 dp touch slop (`Host.useDeviceGestureThresholds`), JniConstants warmed on the main thread (Coil's
  ImageDecoder on a worker aborted the process), and receivers registered before a test's reset can be unregistered
  (WebRTC's NetworkMonitor, a static, aborted at teardown otherwise).
Not supported yet: `adb shell am instrument` of a test APK (only of a self-targeting instrumentation, see "adb device"), the test APK's own resources/context (`getInstrumentation().context`
is the app's), shell commands that need another process or the Host loop (input, screencap, `am instrument`), and
test-services shell calls. A test that reaches a
server compiled into the app reaches it from here when the Mac is on that network, where an emulator does not;
`--no-network` keeps only localhost (127.0.0.1 and ::1).

## GLES (offscreen)
`dev.apksim.gl`: android.opengl.GLES20/GLES30/EGL14 on a real GLES 3 in test runs (or `-Dapksim.gles=true`), for
code that renders into a pbuffer and reads it back. ANGLE on Metal from `work/runtime/gles` or the Android SDK's
`emulator/lib64/gles_angle` (`-Dapksim.gles.dir` to choose), the system's libEGL/libGLESv2 on Linux (the image has
Mesa's llvmpipe with `EGL_PLATFORM=surfaceless`: pbuffers, no display server), called through the JDK's foreign function API (hence `--release 22`). `ShadowSimGLES20/30` are generated by
`scripts/gen-gles-shadows.py` from the framework jar's signatures, mapping Java to C as Android's bindings do
(array+offset and Buffer arguments are pointers, an int where another overload has a Buffer is a buffer-object
offset, GLsizeiptr widened to 64 bits); `ShadowSimEGL14` is by hand, and a window surface is a pbuffer of its size
(nothing presents GLES to the Mac window). So `eglChooseConfig` asks the real EGL for pbuffer configs where the app asked
for window ones or named no surface type (EGL's default is windows), and drops `EGL_RECORDABLE_ANDROID`: Mesa's
surfaceless platform has no window configs, and WebRTC's `EglBase.create()` threw "Unable to find any matching EGL
config" in every journey that showed a live camera. Without a library the shadows fall back to Robolectric's stand-ins.

## Releases (Homebrew, install.sh)
`scripts/package-release.sh VERSION` turns a built checkout (`scripts/setup.sh --no-train`) into
`dist/apksim-VERSION-macos-arm64.tar.gz`: the launcher, `apksim.jar`, `work/lib` (without `nativeruntime-dist`, whose
fonts and natives are already in `work/runtime`), `work/runtime`, a jlink'ed JDK 24 in `work/jre` (`work/jdk.path`),
the test app, and `work/release` (the version). `work/release` makes the launcher keep its state (`STATE`: AOT cache,
`cache/`, `tmp/`, screenshots, autofill) in `~/.apksim` rather than `work/` (`APKSIM_HOME` overrides either way;
`train.sh` and `ctl.py` follow the same rule), so the install folder can be read-only. The sandbox allows writes to
`STATE`. Standbys are keyed by install folder plus `STATE`. A release has no AOT cache (it is tied to its folder and
JDK): the first launch without one starts `train.sh --headless` detached (`STATE/train.pid`, log `STATE/train.log`)
and goes on without it; a headless-trained cache is as fast as a windowed one (1.73 against 1.72 s). The launcher
follows a symlink to itself (Homebrew's and install.sh's `bin/apksim`).
`.github/workflows/release.yml`: a `v*` tag builds and checks the tarball on an Apple Silicon runner and makes a
*draft* release; publishing it pushes `packaging/homebrew/apksim.rb` (filled in) to the `aglose/homebrew-apksim` tap
as `Casks/apksim.rb` (it pushes with the `TAP_DEPLOY_KEY` secret: a deploy key with write access to the tap only); users run `brew tap aglose/apksim`, `brew trust aglose/apksim` (Homebrew 7 refuses casks from untrusted taps:
"Refusing to load cask ... from untrusted tap"; a local `brew tap-new` tap is trusted, so a local test does not show it), then
`brew install apksim`
(a third-party tap has no shorter form: Homebrew takes `user/tap/name` or a bare name from its own repos). A cask, not a formula: Homebrew installs a bottle-less formula
only with current Command Line Tools ("CLT does not support macOS 26" stopped it here). The cask clears the quarantine
flag, since the release is not notarized. Homebrew 7 deprecates cask `postflight` blocks for `postflight_steps`.

## Linux / Docker
`docker/Dockerfile` builds a headless linux/amd64 image (`scripts/docker-build.sh`; Colima with Rosetta on this Mac).
`HostOs` picks `.so` vs `.dylib` and the APK ABI (`x86_64` on Linux); on Linux the window, Metal and VideoToolbox
are skipped (headless, CPU shaders). `native/linux/` holds the bionic→glibc shim and a JNI bridge; `elfloader.c` is
shared, with `#ifdef`s. Use JDK 24 there: JDK 25's AOT record mode rejects translated classes. See docs/linux-docker.md.
Colima shares only the home folder with its VM, so mount folders under it (`work/...`), not the scratchpad in
/private/tmp: those mounts are empty in the container. Tag images you build for a check with a name of your own
(`apksim:ci` and `apksim:latest` are shared). Releases publish the image as `ghcr.io/aglose/apksim:VERSION` (`release.yml`'s `image`
job; `:latest` moves on publishing), with `instrument.py` and `drive.py` on its `PATH` for other repositories' CI
(`--entrypoint instrument.py`). `.github/actions/linux-build` is the Linux build both workflows share.

## Debugging app code
`./apksim --debug 5005 --control 7400 app.apk`, then IntelliJ/Android Studio "Remote JVM Debug" (Attach, localhost,
port 5005; the app does not wait for it). Breakpoints in app sources (`MainActivity.kt`, Java or Kotlin) stop, step
and show locals. `--debug` adds the JDWP agent and `-Dapksim.debugInfo=true`, which makes the translator read each
method's DEX `debug_info_item` (`MethodTranslator.readDebugInfo`) into a `LineNumberTable` and `LocalVariableTable`,
plus Kotlin's SMAP (`SourceDebugExtension`, for inlined code). A local's entry covers only instructions where its
register's JVM local holds the declared kind (`LocalRanges`), so a debugger never reads an int slot as a reference.
`this` is slot 0; parameters are the copies in Dalvik's registers, so JDI lists them as locals, not arguments.
Split methods (`emitSplit`) get line numbers but no locals (their registers live in arrays). Without `--debug`,
classes are byte-identical to before: debug info cost ~30 ms of a warm launch (bigger classes to load; R8 builds
such as the owner's app carry a line entry per instruction) and ~5% of a cold one. Debug classes have their own class
cache (`work/cache/classes/<hash>-debug`), so the first `--debug` launch after a build translates everything.
Checked with a JDI client (what IntelliJ uses): `count++` at MainActivity.kt:63 stops with `$count$delegate`.
R8-minified apps have pc-encoded lines (retrace them with the app's mapping file) and no locals.

## Comparing with the emulator
`ApksimCmp36` is an API 36 AVD (Pixel 2 profile, so no cutout) set with `adb shell wm size 822x1782` and
`wm density 320` to match the phone device pixel for pixel; dark mode on, gesture navigation overlay enabled.
`scripts/both.py PORT work/cmp 'tap x y' 'drag x1 y1 x2 y2' 'shot NAME' ...` sends the same input to both and writes
`e-`, `s-` and `c-NAME.png` (side by side plus a difference map; `work/cmp/.venv` has Pillow). `scripts/vshift.py`
measures a vertical offset between two shots. Under heavy machine load the emulator lags by seconds and its system
server can ANR; `both.py` retakes shots until the two agree.

## Git and pull requests
The repo is public (https://github.com/aglose/apksim, Apache 2.0). `main` takes changes only through pull requests: a
GitHub ruleset requires a PR whose `linux-smoke` check passes (`.github/workflows/ci.yml`: builds the runtime and the
Linux image, then runs the test app headless), and blocks force-pushes and deleting `main`. No approving review is
needed. Work on a branch, push it, `gh pr create`, wait for the check, then `gh pr merge --merge`: merge commits, so a
branch cut from another PR's branch stays a clean descendant of `main`. Merged branches are deleted on GitHub.
`scripts/hooks/pre-push` (set by `scripts/setup.sh`: `git config core.hooksPath scripts/hooks`) refuses direct pushes
to `main` before they leave the machine. CI does not cover the Mac-only parts (window, Metal, VideoToolbox, AOT
timings): say in the PR what was checked locally (`bench.py`, `drive.py` runs).
- **Several sessions share this folder.** Commit only your own files, and never switch branches, reset, stash, rebase
  or rewrite history here while another session may have uncommitted edits: a `git filter-repo` run here once threw
  away a peer's work in progress. Do git-side work in a worktree (`git worktree add DIR -b topic origin/main`), or
  ask the session working in the folder to commit first.
- **Nothing personal goes in the repo, ever:** not in files, code comments, commit messages, PR titles or bodies,
  issues or release notes. That means no names of the owner's own apps, employer or servers, package names, hostnames,
  IP addresses, email addresses other than the GitHub noreply one, 1Password item names or sign-in values. Say "the
  owner's app" or describe it ("a large R8 app"). Private notes go in `~/.config/apksim/NOTES.md`.
  `scripts/hooks/check-private` enforces it: `pre-commit` checks the staged changes, `pre-push` the branch name and
  every commit the remote lacks (diff, message, author/committer email), against the terms in `~/.config/apksim/denylist` (private, one regex per
  line). CI runs the same check with the `PRIVATE_DENYLIST` repository secret. Commits use the GitHub noreply
  address (this checkout's local `git config user.email`), and so must merge commits: merge with
  `gh pr merge --merge --author-email 7216340+aglose@users.noreply.github.com`.
- **No AI attribution:** no `Co-Authored-By` trailer for Claude (or any other assistant) in commit messages, and no
  "Generated with" line in PR bodies; GitHub lists co-authors as contributors. `.claude/settings.json` turns Claude
  Code's own trailers off. Every pull request gets a Copilot review automatically (a repository ruleset).

## Hard-won rules
- Restart your instance with `scripts/relaunch.sh PORT APK [LOG]`: launching while the old instance still holds the
  port leaves the old build running and the new one dead.
- **Never `pkill -f dev.apksim.Main`.** The owner may have their own instance open (for example, logged in to
  the owner's app), and standbys match it too. Quit your instances with `drive.py PORT quit`, standbys with
  `./apksim --stop-standbys`.
- **Credentials:** never type or paste the owner's real passwords. 1Password autofill only works in Chrome, not this
  window. The owner signs in themselves: Cmd+V in the window pastes the clipboard.
- **After any Java change, run `scripts/train.sh`** or timings silently get worse (no AOT). The cache is tied to the
  folder path (`work/app.aot.root`), and the launcher warns if it was built elsewhere.
- `scripts/build.sh` fails on compile errors. Check its output before trusting a run. It compiles for Java 22
  (`--release 22`: `gl/` uses the foreign function API).
- **A worktree needs `work/` of its own:** link `work/lib` and `work/media` from the main checkout, but copy
  `work/runtime` (`cp -Rc`, a clone) and run `scripts/build-native.sh` when its `native/` differs: a linked runtime
  built from another branch fails `train.sh` with UnsatisfiedLinkError, and building into it would change every
  other checkout's. Check `git log origin/main` before branching: a worktree cut before a history rewrite shares no
  history with main (rebuild the change on main rather than pushing it).
- **Debugging a test run or app code without sources:** start with `--jvm
  "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=PORT"` and attach `scripts/jdi/Trace.java` (which
  methods run, with arguments) or `Ret.java` (what they return). Suspect the translator when a value is impossible:
  `scripts/translate-class.java` and javap show `aconst_null`/`if_acmpne` where Dalvik had ints.
- **Launch benchmarks after a build:** the first launch of each APK other than the test app is cold (no class
  cache). Run each APK once before `bench.py`, or compare against a baseline with the same cache state. Warm-launch
  timings swing with machine load (another VM, an emulator): compare interleaved A/B runs.
- Keep the young generation small (`-Xmn64m` in the launcher). 256 MB gave 12-50 ms GC pauses mid-scroll; 32-64 MB
  gives about 1 ms. Measured with `--jvm -Xlog:gc:file=...` while flinging and on the Shader tab.
- The path has no spaces now, but Robolectric breaks on spaces in its dependency dir, so the launcher links
  `work/lib` through `$TMPDIR/apksim-<hash>`.
- Robolectric's looper clock has **1 ms resolution and drops fractions**. Always advance by whole ms (see
  `Host.run`).
- Vsync uses `ShadowChoreographer.setPaused(true)` + frame delay 1 ms. The loop only advances the clock to 120 Hz
  grid lines while a frame is wanted (`lineAtOrBefore`). Unpaused Robolectric vsync spins forever on infinite
  animations.
- Some apps step their shader clocks at 30 Hz on purpose (the owner's does): idle shader screens at 30-60 fps can be
  by design. Measure 120 fps on interactions or the test app's Shader tab.
- Measurements are noisy when the machine is busy (WindowServer and the Claude app use CPU). Use A/B runs, not
  one-offs.
- macOS forbids W+X pages, so 4 KB-aligned `.so` files (Flipper and its libc++) cannot load. 16 KB-aligned ones work.
- A `.so`'s code pages are mapped from the extracted file (`elfloader.c` `load_segment`), not copied, so pages nothing
  patches stay clean (a large app's WebRTC library: 6.1 of 10.9 MB). macOS refuses `mmap(PROT_EXEC)` of a file but
  allows `mprotect` to read+execute of a private file mapping; if refused, the range is copied as before. Never rewrite
  an extracted `.so` in place (ElfLoader renames a new file over it).
- Phone, foldable and tablet report a 24 dp (48 px) status bar and gesture-nav inset (Robolectric's `SystemUi`, set
  in `DeviceControls.applySystemUi`; desktop and TV have none), and `useDeviceGestureThresholds` replaces Robolectric's 16 dp touch slop with the framework's 8 dp.
  Without them layouts sat 26 px higher and every drag scrolled 16 px short of the emulator.
- `scripts/build.sh` writes the jar beside the old one and renames it, so running instances keep their classes.
- A RenderNode whose last parent goes away (removed from every renderer's tree, or its renderer destroyed) loses its
  display list in HWUI and draws nothing until recorded again. `GpuRenderNode` closes a content raster only a frame
  after the content is drawn in the screen's tree again; destroying a HardwareRenderer also deletes the display lists
  of everything it draws, so it empties its root first.
- zsh is the shell: `==` and unquoted globs (`--include=*.kt`) break one-liners. Quote them, or use bash/python.

## Test APKs
- `testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk`: the main regression app.
- The owner's own app (R8 and debug builds, the latter 179 MB and exercising method splitting): see
  `~/.config/apksim/NOTES.md`.
- An app whose native libraries are 4 KB-aligned (Flipper) fails as expected.

## Open work
- **The owner's app's tests in CI:** docs/plan.md phase 0 (measure on a Linux runner, the four failures that are the
  app's).
- **Frame pacing to a strict 120 Hz:** remaining stragglers come from CPU raster (~6-7 ms for busy full-screen
  frames, single core) and GPU wait on heavy shaders. Not yet tried: zero-copy GPU output (render into Bitmap memory).
- **Display vsync pacing is the default (2026-11-03):** Host starts each animation frame 0.3 ms after the display's
  vsync (CADisplayLink on the window, `SimWindow.untilVsync`), falling back to its 120 Hz grid whenever no vsyncs
  arrive (window hidden, display asleep, headless, test runs); `--jvm -Dapksim.vsync=grid` keeps the grid. A/B on a
  lit 120 Hz display, 5 interleaved runs, `frames`' `screen` part (new frame on N% of real vsyncs): Shader tab grid
  99-100% in three runs and 69% in two (its phase drifts against the display: ~146 frames shown twice, ~146 never
  shown), display 98-99% in all five, p95 interval 10.1 against 11.3 ms; flings grid ~140 fps of which 96-178 frames
  were replaced before being shown (84-97% new), display ~118 fps, 3-5 replaced, 97% new in every run. The grid's
  "on 120 Hz pace" figure is not comparable: it measures intervals against the grid, not the screen.
- **Perf ideas measured and not kept (2026-11-03):** linear textures for effect-layer inputs (a full phone screen
  uploads in 0.25 ms tiled, 0.19 ms linear: the ~3 ms untiling cost is readback only, and linear textures can sample
  slower); skipping an `analyze()` pass when a value gains a non-array type name (no measurable time, and 2 of 32k
  classes translated differently); writing GPU readback and video straight into Bitmap memory (the copies are
  0.12 ms a frame on the Shader tab, and Robolectric exposes no pixel address); HWUI rendering into the window's
  IOSurface (its buffers belong to Robolectric's ImageReader and CPU pipeline; the copy is 0.25-0.4 ms on the raster
  thread, which is not the limit).
- **Partial (damage-rect) redraw: tried 2026-11-03, no gain for Compose apps, not kept.** Robolectric's
  `SkiaCpuPipeline` draws into one buffer it keeps, but `getFrame()` reports buffer age 0 and `swapBuffers()` is a
  `return false` stub, so `CanvasContext` never clears `mHaveNewSurface` and redraws everything. Swapping vtable slots
  3 (getFrame: age 1) and 5 (swapBuffers: set `*requireSwap`, return true) of
  `__ZTVN7android10uirenderer12skiapipeline15SkiaCpuPipelineE` made HWUI honour damage (checked in lldb on
  `CanvasContext::computeDirtyRect`: age 1, flag 0), and HwRenderer skipped re-recording its root when the windows
  had not changed. But the damage HWUI collects was the whole screen on every frame of the test app (taps, typing,
  caret, fling): Compose draws everything outside a `graphicsLayer` into the full-screen `AndroidComposeView`'s own
  display list, so any change re-records it. Only layer-property animations would get smaller damage.
- AGSL drawn as a paint with `uniform shader` inputs runs on Metal when every input is an image BitmapShader (CLAMP,
  linear filter, no local matrix): the bitmaps are bound as textures sampled with clamped edges (self-test
  "input shaders"). Other inputs (gradients, nested runtime shaders) stay on
  the CPU.
- MediaCodec has H.264, HEVC, AAC and MP3; VP9/AV1 video and Opus/Vorbis audio are not listed (the owner's app needs
  none of them).
- Layer-content RenderEffects run on Metal (`GpuRenderNode`). On the owner's app, fps with them on the CPU → on
  Metal: a camera page 49 → 130, a weather screen 23 → 86, a map screen 8.6 → 30, and panning the map 26 → 64-73 once
  scaled bitmaps went to the GPU and readback stopped untiling (2026-11-03). What is left is the content raster: each
  effect layer is a second HWUI frame on the one render thread, serialized after the screen's frame and waited for on
  the main thread (the map: two full-screen layers, ~6 ms raster + 0.8 ms shader each). Not
  tried: one frame behind (it would not help much: the render thread, not the main thread, is the limit), one
  renderer for all layers. Effects set with View.setRenderEffect (framework RenderNodes) and chained/blur effects stay
  on the CPU.
- No Health Connect service, so apps hide what they would show from it.
- GLES and native audio (OpenSL ES/AAudio) libraries are stubs. Java AudioTrack plays (see "Video").
- Real mouse and keyboard in the window were first used by the owner on 2026-11-02. Scripted input goes through
  `--control` instead.
