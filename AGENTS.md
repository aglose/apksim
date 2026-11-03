# apksim: agent guide

Runs unmodified Android APKs on an Apple Silicon Mac as **one ordinary process, no VM**.
Read README.md for results and limits; this file covers how to work on the code.

## Owner's goals (in order asked)
1. Launch in a few seconds: done, 2.5 s first frame (window at 0.15 s), measured with `scripts/bench.py`.
2. Run real APKs, including their the large app app: done (the large app and debug builds both run).
3. **120 fps**: the owner said "don't stop until it's that fast". Average is now ~117-120 fps on normal UI, the GPU
   stress shader and the large app's sheet animation. 15-35% of frames are still 9-12 ms rather than 8.3 ms. See
   "Open work".

## Layout
- `apksim`: launcher (bash). Builds the classpath, sandbox (`sandbox-exec`) and AOT cache flags, then runs
  `dev.apksim.Main`.
- `runtime/src/main/java/dev/apksim/`
  - `Main`: entry point. Opens the window first, then installs app classes and runs Robolectric's JUnit runner on `Host`.
  - `Host`: the Android main thread. Activity stack, input dispatch, the frame loop (120 Hz grid), and the `--control`
    commands (`runCommand`).
  - `SimRunner`: points Robolectric at the APK. **FlatSandboxBuilder/FlatLoader** load the framework from the app
    class loader rather than Robolectric's instrumenting loader, so the JVM AOT cache can hold it. It also registers
    extra shadows.
  - `AppClasses`: adds an `apksim:` URL to the JVM app class path. Class bytes are translated from DEX on demand.
    DEX files are mapped from the APK (`DexFile` reads a `ByteBuffer`), not copied onto the heap.
  - `ClassCache`: translated classes kept between launches in `work/cache/classes/<runtime hash>/<dex crc>.pack`.
    An entry is used only while the "is X an interface?" answers it was translated with still hold (the
    translator's only input from other classes). `<package>.classes` lists what the app loaded, so worker threads
    pre-translate the classes of changed DEX files once the app asks for its first class. The runtime hash covers
    every class in `apksim.jar`, so any rebuild starts with a cold cache. `--jvm -Dapksim.classCache=false` turns
    it off. It saves once translating pauses for 3 s, and again on the way out (`quit`, closing the window, Cmd+Q,
    `--exit-after-first-frame`), so `scripts/train.sh` leaves the test app's cache warm.
  - `dex/`: the **DEX→JVM translator** (written from scratch; dex2jar miscompiled code and took 30 s). One Dalvik
    register is one JVM local; union-find type inference over values. Methods over 64 KB are split automatically
    (`emitSplit`). It also rewrites app calls: `System.loadLibrary` → `Natives`, `new RuntimeShader` →
    `TrackedRuntimeShader`, `Canvas.draw*(…Paint)` → `GpuCanvas`, `RenderEffect.create*` → `GpuEffects`.
  - `HwRenderer`: Android's real HWUI pipeline (CPU Skia). `syncAndDraw` runs on its own thread; the main thread
    waits only for HWUI's sync (the `FrameDrawingCallback`), so recording overlaps raster.
  - `gpu/`: AGSL → Metal. `AgslTranslator` puts uniforms and functions in one MSL struct. `TrackedRuntimeShader`
    renders on the GPU one frame ahead (`submit`/`collect`) and is drawn as a BitmapShader.
  - `ShadowFastRipple`: forces the SOLID ripple with software animation. The patterned ripple's per-pixel shader
    cost ~15 ms a frame, and its render-thread animation never finished, because there is no native looper.
  - `ElfLoader`, `Natives`: load the APK's arm64 `.so` files through `native/`.
  - `Storage`: per-PID scratch under `work/tmp` (swept on next launch) and persistent app data under
    `work/cache/data/<pkg>/default` (or `instance-N` when several run).
  - `FastNativeLoader`: loads Robolectric's native runtime from pre-extracted `work/runtime`, not a fresh temp dir per launch.
- `native/`
  - `simwindow.m`: Cocoa window and IOSurface present, input queue, Cmd+V paste, `wshot` self-capture, thread QoS.
  - `elfloader.c`, `bionic.c`, `jnibridge.c`: the Android ELF loader, bionic→libSystem shim, and a JNI table wrapper
    for the AAPCS-vs-Apple varargs difference (chosen by caller address).
  - `gpushader.m`: Metal pipelines for translated AGSL.
- `scripts/`: `setup.sh` (one-time), `build.sh` (Java → `work/apksim.jar`), `build-native.sh`, `train.sh` (AOT
  cache), `bench.py` (launch times), `drive.py` (control channel client), `prof.py` (summarise macOS `sample`
  output), `so-imports.py` (native import audit), `prepare-runtime.sh`.
- `testapks/composeapp`: the test app. It has a list, Trips (SQLite rename: "Italian vacation" ↔ "Roman vacation"),
  Settings, and a **Shader** tab, a full-screen unthrottled noise shader used as the 120 fps stress test. Build with
  `./gradlew :app:assembleDebug` inside it.
- `work/` (not source): `lib/` jars, `runtime/` dylibs, fonts and ICU, `apksim.jar`, `app.aot`, `cache/` (per-APK
  manifest, extracted `.so` files, app data).

## Everyday commands (from ~/apksim)
```bash
scripts/build.sh && scripts/train.sh          # after any Java change: the AOT cache is invalid once the jar changes
scripts/build-native.sh                        # after any native/ change (no retrain needed)
./apksim --control 7400 testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk
python3 scripts/drive.py 7400 'wait 3000' 'tap 720 1690' 'wait 3000' 'frames' 'fps' 'quit'
python3 scripts/bench.py 5 <apk> [<apk>...]   # launch times
```
Coordinates are device pixels (phone: 822x1782, density 2). Test-app bottom nav: Home 130, Trips 300-410,
Settings 550-690, Shader 720; y=1690. FAB is at 733,1533. the large app: the "Weather, Atlas…" row is at 400,1390, and in
the sheet Fitness is at 300,820 and Weather at 300,300.

Control commands: `tap x y`, `swipe x1 y1 x2 y2 [ms]` (with ms: a slow drag that holds before lifting, no fling),
`wheel x y amount`, `type text`, `key NAME [count] [ctrl|shift|alt|meta]` (e.g. `key A 1 ctrl`, `key DEL 5`), `back`,
`paste`, `shot file.png` (app pixels), `wshot file.ppm` (the real window, captured in-process), `activity`, `windows`,
`insets` (system bar insets the app sees), `slop` (gesture thresholds), `frames` (frame-interval percentiles since the
last call), `fps` (per-stage cost), `display`, `shaders` (which AGSL runs on GPU vs CPU), `stats`, `ping`, `quit`.
`back` on the last activity finishes it and the process exits: there is no soft keyboard for it to dismiss.
Simulator-style device controls (`DeviceControls.java`; each is also a Mac menu item, see "Simulator features"):
`appearance dark|light|toggle`, `locale fr-FR`, `fontscale 1.3|+|-`, `config` (configuration the app sees),
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
`push key=value...` or `push {"data":{..},"notification":{..}}` delivers an FCM message as Play Services would: the
c2dm RECEIVE broadcast to the app's FCM receiver, with the messaging service pre-bound (Robolectric connects a binding
only to a registered binder). Checked with the large app: its its messaging service posts the alert.
`keyboard soft on|off` / `keyboard show|hide` (`SoftKeyboard`): as in the iOS Simulator a hardware keyboard counts as
connected, so no soft keyboard by default; with it on (Device > Software Keyboard Cmd+K, `--soft-keyboard`) a focused
text field raises a drawn keyboard and the app gets real IME insets (imePadding/adjustResize), Back closes it first.
The Android Keystore (`SimKeyStore`, provider "AndroidKeyStore": KeyStore, AES/HMAC KeyGenerator, EC/RSA
KeyPairGenerator with KeyGenParameterSpec) is a test keystore: keys persist in the app's data folder in the clear and
no use restriction is enforced. the large app seals its API keys with it.
`record` without a Mac window (headless, Linux) writes Motion-JPEG `.avi` (`MjpegRecorder`, 30 fps, Android's JPEG
encoder); with a window `.mov` H.264, or `.avi` if the file name says so. Configuration changes recreate activities as on a device (state not in
`rememberSaveable`/`onSaveInstanceState` resets). A command that throws answers `error: ...` instead of ending the app.
`ctl.py apps|container PKG|erase PKG` lists, locates and deletes app data (erase refuses while an instance holds it).
`scripts/ctl.py list` / `ctl.py [@PID|@PORT|@APKNAME] CMD [ARGS] [';' CMD...]` finds running instances by themselves
(each writes `work/tmp/<pid>/control.port`); `--control auto` takes a free port.

## Simulator features (Mac window)
Menus as in the iOS Simulator: File (Save Screen Cmd+S, Record Screen Cmd+R), Device (Back, Home Cmd+Shift+H, Lock
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
`--appearance dark`, `--locale de-DE`, `--font-scale 1.3`, `--permissions ask|grant|deny` (grant = `adb install -g`),
`--biometrics enrolled`. Test-app Settings tab (scroll down) has a pinch area and Camera/Locate/Notify/Copy/Unlock/
Pick file/Choose trip (in-app result)/Share/Background/Keystore buttons for these; Trips has a "Trip note" field with
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
Recorded video: `ShadowSimMediaCodec` lists an `apksim.avc.decoder` (H.264, VideoToolbox) in MediaCodecList and
registers it with Robolectric's MediaCodec under both its type and its name (players create codecs by name; a
miss falls back to Robolectric's 512-byte fake). Output buffers carry a reference to the decoded picture, drawn into
the Surface's TextureView on releaseOutputBuffer(render=true). Bitmaps here are BGRA in memory (Skia's macOS order),
not Android's RGBA: write pixels in `VtH264Decoder.BITMAP_BGRA` order.
Control commands: `video` (decoded/drawn counts, surfaces, MediaCodec counters), `webrtclog [info|verbose]`,
`selftest [h264-file]` (native scaler, colour order, MediaCodec end to end), `nodes` (accessibility tree: text, hints,
fields, clickables with bounds), `logcat on|off` (app's android.util.Log to stderr; `APKSIM_LOGCAT=1` at launch).
ELF natives whose stack arguments differ between Apple's and AAPCS64's conventions (more than 8 integer or 8 FP
arguments) are bound through trampolines in `jnibridge.c`; without them they read garbage and crashed.
the large app needs a sign-in on every cold start. The owner ran `the sign-in script --save` once, which writes
`work/autofill/<package>.properties` (mode 600) from 1Password; from then on every apksim launch signs the app in by
itself (`Autofill.java`: finds fields by their label/placeholder in the accessibility tree, fills them, checks they
stuck, presses `submit`; logs field names only). So restart freely with `scripts/relaunch.sh`. Never read, print or
edit that file, never run `op` for the owner's secrets, and never type their password: if the file is missing, ask
the owner to run `the sign-in script --save` (or plain `the sign-in script` for a one-off sign-in).
the large app also tries its compiled-in LAN server, so a test sign-in with dummy values still reaches the real the server as a
failed login: test autofill without a `submit` line.

## Linux / Docker
`docker/Dockerfile` builds a headless linux/amd64 image (`scripts/docker-build.sh`; Colima with Rosetta on this Mac).
`HostOs` picks `.so` vs `.dylib` and the APK ABI (`x86_64` on Linux); on Linux the window, Metal and VideoToolbox
are skipped (headless, CPU shaders). `native/linux/` holds the bionic→glibc shim and a JNI bridge; `elfloader.c` is
shared, with `#ifdef`s. Use JDK 24 there: JDK 25's AOT record mode rejects translated classes. See docs/linux-docker.md.

## Comparing with the emulator
`ApksimCmp36` is an API 36 AVD (Pixel 2 profile, so no cutout) set with `adb shell wm size 822x1782` and
`wm density 320` to match the phone device pixel for pixel; dark mode on, gesture navigation overlay enabled.
`scripts/both.py PORT work/cmp 'tap x y' 'drag x1 y1 x2 y2' 'shot NAME' ...` sends the same input to both and writes
`e-`, `s-` and `c-NAME.png` (side by side plus a difference map; `work/cmp/.venv` has Pillow). `scripts/vshift.py`
measures a vertical offset between two shots. Under heavy machine load the emulator lags by seconds and its system
server can ANR; `both.py` retakes shots until the two agree.

## Hard-won rules
- Restart your instance with `scripts/relaunch.sh PORT APK [LOG]`: launching while the old instance still holds the
  port leaves the old build running and the new one dead.
- **Never `pkill -f dev.apksim.Main`.** The owner may have their own instance open (for example, logged in to
  the large app). Quit your instances with `drive.py PORT quit`.
- **Credentials:** never type or paste the owner's real passwords. 1Password autofill only works in Chrome, not this
  window. The owner signs in themselves: Cmd+V in the window pastes the clipboard.
- **After any Java change, run `scripts/train.sh`** or timings silently get worse (no AOT). The cache is tied to the
  folder path (`work/app.aot.root`), and the launcher warns if it was built elsewhere.
- `scripts/build.sh` fails on compile errors. Check its output before trusting a run.
- **Launch benchmarks after a build:** the first launch of each APK other than the test app is cold (no class
  cache). Run each APK once before `bench.py`, or compare against a baseline with the same cache state.
- Keep the young generation small (`-Xmn64m` in the launcher). 256 MB gave 12-50 ms GC pauses mid-scroll; 32-64 MB
  gives about 1 ms. Measured with `--jvm -Xlog:gc:file=...` while flinging and on the Shader tab.
- The path has no spaces now, but Robolectric breaks on spaces in its dependency dir, so the launcher links
  `work/lib` through `$TMPDIR/apksim-<hash>`.
- Robolectric's looper clock has **1 ms resolution and drops fractions**. Always advance by whole ms (see
  `Host.run`).
- Vsync uses `ShadowChoreographer.setPaused(true)` + frame delay 1 ms. The loop only advances the clock to 120 Hz
  grid lines while a frame is wanted (`lineAtOrBefore`). Unpaused Robolectric vsync spins forever on infinite
  animations.
- **the large app steps its shader clocks at 30 Hz on purpose** (`rememberShaderClock(frameSeconds = 1f/30f)` in
  ~/AndroidStudioProjects/the large app). Idle shader screens at 30-60 fps are by design. Measure 120 fps on interactions
  or the test app's Shader tab. the large app source is for reference only; do not edit it.
- Measurements are noisy when the machine is busy (WindowServer and the Claude app use CPU). Use A/B runs, not
  one-offs.
- macOS forbids W+X pages, so 4 KB-aligned `.so` files (an app's Flipper/libc++) cannot load. 16 KB-aligned ones work.
- Phone, foldable and tablet report a 48 px status bar and 48 px gesture-nav inset (Robolectric's `SystemUi`, set in
  `Host.run`), and `useDeviceGestureThresholds` replaces Robolectric's 16 dp touch slop with the framework's 8 dp.
  Without them layouts sat 26 px higher and every drag scrolled 16 px short of the emulator.
- `scripts/build.sh` writes the jar beside the old one and renames it, so running instances keep their classes.
- zsh is the shell: `==` and unquoted globs (`--include=*.kt`) break one-liners. Quote them, or use bash/python.

## Test APKs
- `testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk`: the main regression app.
- `~/Downloads/app-release.apk`: the owner's app (R8, SQLite + WebRTC `.so`). It reaches its server
  `server.local` over a VPN.
- `~/AndroidStudioProjects/the large app/.claude/worktrees/*/androidApp/build/outputs/apk/debug/androidApp-debug.apk`:
  179 MB debug build; exercises method splitting.
- another app debug: fails as expected (4 KB-aligned native libs + Crashlytics `System.exit`). Run it with
  `--no-network`.

## Open work
- **Frame pacing to a strict 120 Hz:** remaining stragglers come from CPU raster (~6-7 ms for busy full-screen
  frames, single core) and GPU wait on heavy shaders. Ideas not yet tried: partial (damage-rect) redraw; zero-copy
  GPU output (render into Bitmap memory); presenting on the display's vsync (CADisplayLink) rather than commit-on-ready.
- AGSL with `uniform shader` inputs (RenderEffect-style, 7 of the large app's 29 shaders) still runs on the CPU.
- MediaCodec decodes H.264 only (`ShadowSimMediaCodec`); HEVC and audio codecs are not listed, so ExoPlayer plays such
  tracks' video only if H.264, and never their sound.
- AGSL RenderEffects that read layer content (the large app's LIQUID_LENS on the camera timeline) run per pixel on the CPU:
  ~55 ms a frame on the camera page while video changes the layer. Moving them to Metal is the biggest perf item left.
- No Health Connect service, so the large app Fitness hides its Body card and Heart rate chip that the emulator shows.
- GLES/audio native libraries are stubs.
- Real mouse and keyboard in the window were first used by the owner on 2026-11-02 (the large app login). Scripted input
  goes through `--control` instead.
