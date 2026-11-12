# apksim: agent guide

Runs unmodified Android APKs on an Apple Silicon Mac as **one ordinary process, no VM**.
Read README.md for results and limits; this file covers how to work on the code.

## Owner's goals (in order asked)
1. Launch in a few seconds: done. Then "improve start up time by 10x, as fast as possible to load a new
   simulator": a launch is handed to a pre-booted standby JVM (see "Warm launches"): window ~0.1 s → 12 ms, first
   frame 1.49 s → ~0.22 s (test app), ~1.9 s → ~0.36 s (the owner's R8 app). What is left is mostly the app's own
   startup code, interpreted (cold JIT). Measured with `scripts/bench.py` (cold and warm); CI's `startup-gate`
   job fails a pull request that makes it slower than its budget against a fixed anchor release (docs/startup-gate.md).
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
    A cold launch first initializes Robolectric's `AndroidTestEnvironment` on a thread of its own (its static
    BouncyCastleProvider: ~100 ms off a cold first frame and a test process's start; `-Dapksim.initAhead=false`).
    Its jar is unsigned in `work/lib` (`gradle deps` strips the signature files): the JVM's AOT cache skips signed jars,
    and with them its ~670 classes are in the cache (issue 205).
    Conscrypt's native library (OkHttp's start asks for it) loads on a thread of its own as the first test environment
    is set up (`SimRunner.conscryptAhead`; not earlier: it reads android.os.Build, which must initialize inside the
    sandbox). docs/perf-plan.md is the plan for memory, launch and run time, with what was done and measured.
  - `Standby`, `StandbyWarmup`: warm launches (see "Warm launches").
  - `Host`: the Android main thread. Activity stack, input dispatch, the frame loop (on the display's vsync, else a 120 Hz grid), and the `--control`
    commands (`runCommand`). A frame is drawn when a shown window is dirty or the shown windows changed (`needsFrame`);
    hidden windows (an activity below the top one) never count, as nothing draws them clean: one left dirty made every
    loop turn draw, 150-400 frames a second on a video screen. It is drawn only on a turn that ran a vsync, or when
    none is pending (a TextureView's new picture): input dispatched at a turn's start dirtied the window, which was
    drawn then and again at the vsync (`frames` counts the frames `held for their vsync`). A vsync asked for since the
    last frame waits for the display's next one, and while a timed drag (`swipe ... MS`) runs the loop turns at each
    vsync and sends the move there, as a device consumes batched touch input in the vsync's frame; other input goes
    out 7 ms apart. When nothing is wanted (no frame, no input for 100 ms, no command or `settle`), it sleeps in
    Robolectric's `ShadowPausedLooper.poll` until the next main-queue message is due, at most 40 ms: a post to the main
    queue ends it, as do `Control.submit` and window input (`apksim-input-wait` thread); `-Dapksim.idleSleep=false`
    keeps the old 2 ms poll. `Host.Memory`: once the screen has been still for 2 s and the footprint has grown by
    `-Dapksim.idleGcMB` (256) since the last check, a full collection runs then (HotSpot does not count native memory);
    under the Mac's memory pressure GpuBitmaps' caches go and a collection runs at once, and a standby quits
    (`Footprint.pressure`). Before (2026-11-07), slow drags drew ~170 frames a second at 120 Hz, a third never shown
    (`-Dapksim.offVsyncFrames=true` restores it). Without a window, `vsync PERIOD_US SHIFT_US` (sent by serve's page
    twice a second) makes a viewer's display the vsync to follow (`SimWindow.remoteVsync`) until 1.5 s pass without
    one. The first frame waits for the bottom window that is
    shown and sized to be laid out (`laidOut`), not for the bottom one: an app's overlay added before its activity and
    never sized (0x0) kept the screen black for good (issue 83). A touch goes to the top window it lands in, or on past one that is not touch-modal, as
    InputDispatcher decides: a window is touch-modal only when it is focusable and lacks FLAG_NOT_TOUCH_MODAL, so a
    non-focusable popup (a slider's value tooltip) takes only touches inside it (`isTouchThrough`). A touch event's
    raw coordinates are the screen's and x/y the window's, as a device delivers them (Compose reads the raw ones).
    Host places windows itself (`position`: gravity and offsets on the display with `Gravity.apply`, as the window
    manager does, so an axis with no gravity (NO_GRAVITY, a window's default) is centred; Robolectric's window session
    gives a window that is not full-screen the display's size at lp.x/lp.y), and tells their views: `ShadowSimViewRootImpl`
    moves the frame a relayout gives to that place, and `syncWindowPositions` each loop turn, so AttachInfo's window
    position (getLocationOnScreen, boundsInScreen, Compose's window position) is where the window is drawn. Before, a
    centred dialog's views thought they were at the top left, and `nodes`, `tap-text` and UiAutomator missed them.
    The stack is kept as a device's activity manager keeps it (`Host.arrange`, where every
    path that changes it ends: start, finish (of the top or of one below it), `home`/`resume`, configuration changes,
    `recreate`, `process-death`, test runs' launches): the top resumed; below it, while everything above is translucent or floating (a
    dialog-themed activity: the test app's `FloatingActivity`, `apksim://floating`), paused and visible; the rest
    stopped with their windows hidden, so only what a device shows draws and takes touches. A visible activity with
    no window (recreated or rebuilt while stopped: Robolectric's recreate leaves it detached, and resumed whatever it
    was) gets one, windows are put in the stack's order, and in test runs the top has the focus. An activity's dialogs
    and popups hide with it, and a hidden window is FLAG_NOT_TOUCHABLE meanwhile (Robolectric's UiAutomation injection
    reads only that, and ranks dialogs above every activity window). Before, a rotation left the whole stack resumed,
    and finishing the top of a recreated stack left nothing on screen. In test runs a finish only brings activities
    up (a test may have launched several side by side), and UiDevice rotation restores each activity's own state
    (Robolectric's instrumentation recreates ActivityScenario's activities itself on `setQualifiers`).
  - `TimeTick`: ACTION_TIME_TICK (registered receivers only) from Host's loop at each minute of `SimClock`'s clock; not under `--snapshot` (issue 109).
  - `Crashes`: uncaught exceptions in running instances, as RuntimeInit's KillApplicationHandler (issue 104). The JVM
    had no default handler, so ThreadGroup printed "Exception in thread" and the app ran on: kotlinx.coroutines hands a
    coroutine's exception to the thread's handler itself (traced: `propagateExceptionFinalResort`, ThreadGroup, no
    default; AndroidExceptionPreHandler does nothing past API 27), and a crash reporter passing it on to the handler it
    found (none) left nothing at all. Main sets `KillApplication` as the default before any app code: `E
    AndroidRuntime: FATAL EXCEPTION: <thread>` (the main looper's is `main`), `Process: PKG, PID: N` and the stack through
    android.util.Log (`adb logcat`; stderr too when logcat is off), then exit 10 (RuntimeInit's System.exit(10); warm,
    the client's). An app's own default handler replaces it, as on a device; one that passes the exception on reaches
    it. An exception leaving the main looper (Host's loop, the first activity's start, the Application's construction,
    attachBaseContext and onCreate (SimRunner, TestActivities), and app code a command's lifecycle transition runs:
    `Crashes.app` around restart, recreate, process-death, configuration changes, home/resume, memory, `Host.arrange`;
    the command, menu item or adb shell call is answered `error: app crashed: ...` first; a command's bad arguments stay
    `error:`/usage answers) is logged
    first, as the device's pre-handler does, then goes to the thread's handler, and the process ends even if that
    returns (the main thread is dead on a device). Not a device's: an app thread whose exception reaches the app's own
    non-passing handler is not logged (no pre-handler for JVM-dispatched exceptions). apksim's own threads (every thread
    apksim starts is named `apksim-...`: keep it so for new ones) are printed and end alone, as before; every other
    thread is the app's (not judged by the exception's stack, which may be a JDK one or have none). The adb log's tee is
    put back in front of ShadowLog.stream before logging, and stderr gets the crash whenever Log.e failed. `--jvm -Dapksim.crashes=log` logs and runs on (the
    main loop too), to look at a broken screen. Logging and each cleanup step are best-effort (an OutOfMemoryError may
    be the crash) and `Standby.haltNow` always halts with 10, the client told first; `-Dapksim.crashTest.failCleanup=true`
    makes the cache save throw, to check it. Test runs install nothing: AndroidJUnitRunner's handler
    (MonitoringInstrumentation's, set in its onCreate) logs and the test goes on (`UncaughtExceptionTest`).
  - `Exits`: why a running instance ends, one `[apksim] exit N: REASON` line on stderr for every way (issue 116: a
    process ended 0 with nothing in the log). The last activity finishing names it and the call, with the stack of
    the code that made it (`finish`, `finishAffinity`, `finishAndRemoveTask` shadows in `TestActivities.ShadowSimActivity`,
    back included); the window closing, `quit`, `--exit-after-first-frame`, the launcher's client going away (130) and
    crashes (10) say so. Host's loop ends only once no activity has been left for `Exits.LINGER_MS` (1 s) of real
    time, the app running meanwhile: a device's process lingers, and a hand-off that finished first and started the
    next activity from a posted message or coroutine exited 0 before it landed. The app's own `System.exit`,
    `Runtime.exit` and `Runtime.halt` go here through the translator: logged with the code and caller, and that code
    reaches a warm launch's client (the standby's shutdown hook sent 0 for every exit, and a halt sent nothing: 134).
    `Process.killProcess(myPid())` and `sendSignal(myPid, SIGNAL_KILL)` end a running instance with 137, as a device
    kills the process (Robolectric only recorded the pid and the app ran on; test runs keep that). The test app's
    `apksim://exit?kind=finish|affinity|handoff|exit|halt|kill` does each (`ci-smoke.sh`). `-Dapksim.traceExit=true`
    still prints every thread's stack at a JVM shutdown.
  - `SimRunner`: points Robolectric at the APK. **FlatSandboxBuilder/FlatLoader** load the framework from the app
    class loader rather than Robolectric's instrumenting loader, so the JVM AOT cache can hold it. It also registers
    extra shadows. In running instances (not test runs) `android.os.Trace` is a device nobody traces
    (`untracedDevice`: ShadowTrace's tags 0, every call a no-op), set before every setup since Robolectric's reset,
    the standby's warmup included, turns its checks back on: ShadowTrace threw "Section is already running" for an
    async section begun twice, which ended a large app opening a screen. Test runs keep ShadowTrace as it is.
  - `org.robolectric.internal.bytecode.InvokeDynamicSupport` and `ShadowInvalidator` (apksim's copies, ahead of
    Robolectric's on the class path): every framework call site is bound straight to its shadow or original, without
    Robolectric's SwitchPoint guard, fallback and per-site stack-trace cleaning (a quarter of a cold launch in method
    handle chains, ~35-50 MB). An invalidation resets that class's sites to bind again at their next call. Traces are
    cleaned where apksim reports them (`StackTraces`: crashes, test failures, bundles); one the app prints itself shows
    `$$robo$$` frames. `-Dapksim.directCallSites=false` is Robolectric's binding (docs/perf-plan.md, B1).
    `scripts/check-call-sites.sh` (CI) checks re-binding and a binding racing an invalidation (`CallSiteCheck`).
  - `AppClasses`: adds an `apksim:` URL to the JVM app class path. Class bytes are translated from DEX on demand.
    DEX files are mapped from the APK (`DexFile` reads a `ByteBuffer`), not copied onto the heap; compressed ones
    (APKs for older Android versions) are inflated once into the APK's cache entry's `dex/` (`ApkCache`) and mapped from there
    (inflating the test app's 36 MB took ~60 ms a launch). Classes are found by binary search of the DEX's sorted
    type_ids (`DexFile.findClassType`), not a map of every class name (~80 ms for 21k classes). `loadAhead` defines
    the classes the app loaded last time (no static initializers, so no app code) on 2 threads while the main thread
    starts the framework: ~100 ms off a launch.
  - `ApkCache`: the per-APK folder in `STATE/cache` (manifest, extracted `.so` files, inflated DEX), named
    `<file name>-<16 hex>`: CRC32 and Adler32 of the APK's ZIP central directory and its length, so a copy, `touch` or
    re-download of the same build reuses it (keyed by mtime, every rebuild and CI run left another, ~370 MB for a large
    app, for good: issue 125). Taken on a thread of its own at the top of `Main` (~2 ms: SHA-256's provider lookup was
    ~60 ms of a cold JVM, CRC32C and String.format ~3 ms interpreted), under a shared lock on `cache/apk-cache.lock`;
    the process then holds a shared lock on `<entry>/in-use.lock` until it dies and touches `<entry>/last-used`.
    A sweep runs at most once an hour (`cache/apk-cache.swept`), 3 s after startup on a background thread, or from
    `Standby.halt` (before the client gets its exit code, up to 10 s: told first, the next launch could start while
    this process still held the data slot) when the launch ends sooner: Runtime.halt would kill the thread, and short
    test listings never swept. It lists and sizes entries without a lock (a launch's take
    waits for an exclusive holder), picks those not this process's and unused for 10 minutes (unused 14 days first,
    then least recently used until the rest fit in `-Dapksim.apkCacheMB`, default 2048), then under the global lock
    held exclusively checks each again (used since, or locked: kept) and moves it into `cache/.apk-cache-trash/`,
    emptied after the lock (no entry can be named that: an entry's name ends in -16 hex). Old
    mtime-named entries (no lock file, so one may be running unseen) go only once 14 days unused by their manifest's
    mtime, never for the cap. File locks, not PIDs, as containers sharing a cache
    volume repeat PIDs. `scripts/apk-cache-check.sh` checks it (CI: `ci-smoke.sh`).
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
    `TrackedRuntimeShader`, `Canvas.draw*(…Paint)` of shapes and paths → `GpuCanvas`, `RenderEffect.create*` → `GpuEffects`,
    `new RenderNode` → `GpuRenderNode`, wall-clock reads (`System.currentTimeMillis`, java.time `X.now()`,
    `Clock.system*`, `new Date()`, `Calendar.getInstance`) → `SimClock`, and Compose's `CursorAnimationState(animate)`
    through `Snapshot.cursorBlink`. `Peephole` then shrinks each method (not with `--debug`): a parameter copy
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
    `GpuBitmaps`: every `drawBitmap` recorded into a RenderNode (`ShadowGpuRecordingCanvas`, a shadow of
    BaseRecordingCanvas and RecordingCanvas: framework Views such as ImageView and BitmapDrawable backgrounds, Compose's
    drawImage, app code; the src/dst Rect(F), x/y and Matrix overloads) that scales a filtered bitmap onto whole device
    pixels (canvas and Matrix scale+translate only) gets a copy already that size, resampled once on Metal (bilinear in
    float, as Skia), which HWUI then blits 1:1; Skia's CPU bilinear was most of a map screen's frame, and of a 2560x1600
    tablet screen with a full-screen background (raster ~36 → ~2.3 ms, test app `apksim://bitmaps`). Copies are cached
    (96 MB, LRU), and so are the uploaded originals (their own 96 MB); no table holds a Bitmap itself (copies are keyed
    by Skia's process-wide generation ID), so an app drawing a new bitmap every frame keeps nothing alive. They are made for every size asked for in the frame a bitmap is first drawn (a View records its
    background once and HWUI redraws that recording every frame, so it is never asked for twice), then only for a
    size asked for again within 8 frames, so zooms and per-frame content (video) stay on the CPU after their first
    frame. sRGB ARGB_8888 only (other colour spaces and configs stay HWUI's, counted under `config`). A copy dropped from the
    cache stays alive in display lists still drawing it. `-Dapksim.gpuBitmaps=false` turns it off; `shaders` shows
    hits and why draws went to HWUI (`small`, `config` by Bitmap.Config, `new`, `budget`, `unaligned`). Metal targets
    are linear (buffer-backed, pooled): reading back a tiled texture made the CPU untile it, ~3 ms per full screen.
  - `FlightRecorder`, `rec/` (`Rec`, `Ring`, `Db`): the flight recorder (`--rec CHANNELS`, `rec` command,
    docs/flight-recorder.md). Rings of the last N seconds (input at `Host.dispatch`, device commands at
    `runCommand`, logcat, net through `Net.capture`, SQLite through translator hooks to `rec.Db`, screen frames from
    the raster thread), checkpoints (stack intents + saved state, a clone of the data folder), and `rec save` to a
    `.apkrec` zip; a crash saves one by itself (`Crashes.terminate`). A checkpoint cycles the visible activities
    through onStop/onStart as a quick Home and back would: the one way recording shows in the app.
    `scripts/replay-adb.py BUNDLE -s SERIAL --yes` plays a bundle on a device over adb (the checkpoint's data, /sdcard
    and Settings put back, its display set, `input.jsonl` at its recorded offsets; the network is live; issue 192).
    It replaces the app's data, /sdcard files and Settings values there, so it stops without `--yes` and backs the
    app's data up first (`--backup-dir`, `--no-backup`); `--dry-run` prints the plan and touches nothing. Only run
    against apksim's own adb device so far, not a phone or emulator.
  - `ReplayPlan`, `Replay`: `apksim replay BUNDLE` (docs/replay.md): the checkpoint's slot, device and stack, `input.jsonl` fed to
    the Host loop at its offsets, the HAR answering, frames compared; exit 0/3 (diverged)/10; `scripts/replay-check.py`.
    `scripts/bisect-rec.py BUNDLE BUILD...`: the first build whose replay diverges, an HTML report (`bisect-rec-check.py`).
    `apksim replay --journey` writes journey.json (taps resolved to nodes: `Journey`), run by `scripts/journey.py` (JUnit XML; `journey-start-check.py`: its start and steps' timing, against a stand-in apksim; `check-journey-texts.sh`: `JourneyCheck`, which texts it may expect).
  - `serve/TraceView`, `resources/dev/apksim/serve/trace-viewer.html`: `apksim rec view [FILE]`, the static trace viewer for
    `.apkrec` and zipped failure bundles (docs/trace-viewer.md; `scripts/trace-viewer-check.py`, `--update-csp` after editing its script).
  - `TabFiles`: `devpush DEVICE_PATH BASE64`, a file a browser tab sent (`apksim serve` and `beam`, issue 187), written
    where `adb push` could write it (the app's `files/`, `cache/`, `shared_prefs/`, `databases/`, `/sdcard`,
    `/data/local/tmp`) and moved into place whole. Only the server sends it: it is not a command a tab may type.
  - `--coverage DIR` (`dex/Coverage`, `CoverageDump`, `scripts/coverage.py`): hits per method and line of `--instrument` runs, JaCoCo XML, LCOV and HTML; docs/coverage.md.
  - `Instrument`, `TestActivities`, `ShadowSimUiAutomation`: `--instrument` test runs, the system server's part of
    them, and UiAutomator (see "Instrumentation tests"). `Bundles`: failure bundles in test runs (`--bundles DIR`).
    `AccessibleWindows`: which windows accessibility reports (UiAutomation, `uiautomator dump`, `nodes`, bundles'
    nodes.txt), as the window manager computes them: top down, a window is left out once touch-modal windows above it
    (any Dialog, a dialog-themed activity, a focusable popup: their touchable region is the whole task) or their frames
    leave none of it uncovered, unless it has the focus. AOSP's `AccessibilityController.computeWindowsForAccessibility`
    (Android SDK sources, `android-33/com/android/server/wm`), down to its bounding-box test (`Region.quickReject`:
    a window inside a hole of what is left still counts, as there). Touch input does not use it (`Host.isTouchThrough`).
    `AccessibleWindows.stack()` is the one window order: WindowManagerGlobal's, in which windows were added, as Host
    draws them and sends touches down; every window list (UiAutomation, dump, `nodes`, bundles) uses it. Sorting by
    window type put a popup opened before a dialog above the dialog.
  - Screenshot tests (docs/snapshots.md): `Snapshot` (`--snapshot`: CPU-only drawing, carets drawn and not
    blinking, the top window focused), `SimClock` (`clock`, `--clock`), `ImageDiff` (`diff`), and in `Host` the
    `settle` idle signal and `restart`.
  - `gl/`: GLES20/30 and EGL14 on a real GLES (ANGLE) for offscreen rendering in test runs (see "GLES").
  - `ShadowFastRipple`: forces the SOLID ripple with software animation. The patterned ripple's per-pixel shader
    cost ~15 ms a frame, and its render-thread animation never finished, because there is no native looper.
  - `ShadowRepeatingAnimator`: a ValueAnimator's `INFINITE` repeat count is kept in running instances and with
    `--realtime-clock`, where frames come at vsyncs. Robolectric's shadow makes it 1, so an endless View animation
    stopped after two cycles. Default test runs keep that cap: their unpaused Choreographer would never let the looper
    go idle. `-Dapksim.repeatForever=false` keeps the cap everywhere (docs/conformance.md, cause 4).
  - `ElfLoader`, `Natives`: load the APK's arm64 `.so` files through `native/`.
  - `net/`: the app's network recorded, replayed and shaped (`Net`, `NetEngine`, `FakeTlsSocket`, `NetResolver`,
    `Recording`), and `NetConnectivity`: offline as ConnectivityManager shows it (see "Network").
  - `Storage`: per-PID scratch under `work/tmp` (swept on next launch) and persistent app data under
    `work/cache/data/<pkg>/default` (or `instance-N` when several run); `--fresh` empties the slot it takes first.
    `SlotDevice`: what a device keeps outside the app's data, beside the slot in `<slot>.device/`, taken with the slot
    before Application.onCreate (warm or cold): `sdcard/` is external storage (`/sdcard`, its public folders such as
    Download inside it, mounted; Robolectric's was two temp folders per process, "removed"; the File overloads too:
    `getExternalStorageState(File)` for anything under it, emulated for it and the app's external folder), and `settings.properties`
    the Settings.Global/Secure/System values that differ from the defaults (by the app or `adb shell settings put`;
    Robolectric's static maps, read every 250 ms from Host's loop and on exit (Host.exit, the warm client hanging up,
    a JVM shutdown hook for SIGTERM and the app's System.exit), as no write hook exists, and written a
    last time by `SlotDevice.close` at the start of `SimTestEnvironment.resetState`, which then ends it: the reset puts the
    defaults back, and a flush after it (the warm client hanging up after Host.run threw) erased the file; not
    animator_duration_scale or font_scale, which `animscale`/`fontscale` own). Apps keep a device id in /sdcard and a
    setup-done flag in Settings.Global on purpose; before, every launch lost them. `--fresh`, `ctl.py erase` and serve's
    cleanup clear it with the slot; `--sdcard-file PATH=text:TEXT|BASE64` (repeatable; `SdcardSeeds`, 64 KB a file, 72 KB in all, inline so serve and beam allow it) writes a file into the slot's `sdcard/` before onCreate, over what the slot had; `--broadcast 'ACTION;es|ez|ei|el|ef:K=V'` (repeatable, 8 at most; `LaunchBroadcasts`, from `Host.run` just before the start activity, so after Application.onCreate; ordered, package-set, runtime and manifest receivers; logs `[apksim] broadcast ACTION: result=N`; the test app's `ComposeApp` has a `dev.apksim.testapp.SET_FLAG` receiver) sends one to the app; `--setting NS:KEY=VALUE` (repeatable) sets a value before onCreate, kept like a
    `settings put`, and in test runs at each test's setup (one for animator_duration_scale or font_scale runs the
    control's code, `setAnimatorScale`/`setFontScale`, and is not kept; `--anim-scale` wins over an
    animator_duration_scale seed; the font scale is `--font-scale`, else the last
    font_scale seed, with Settings.System FONT_SCALE beside it (as `fontscale` now sets too), set again after each setQualifiers that resets it: Host.run's, and a test's setup in SimRunner,
    so the test's activities get it too). Test runs otherwise start each test from a clean device.
    `scripts/slot-check.sh` checks it (CI: `ci-smoke.sh`). Files written beside their final name and renamed into place get `Storage.tmpTag()`, PID plus a random part:
    containers sharing a cache volume repeat PIDs (the image's JVM is PID 1).
  - `AdbServer`, `AdbShell`, `AdbLog`, `UiDump`: the adb device (`--adb PORT`, see "adb device").
  - `Cameras`, `camera/` (`FakeCamera`, `Scene`, `ShadowSimCameraCaptureSession`, `ShadowSimImageReader`,
    `ShadowSimCameraManager`): the simulated cameras (`--camera fake`, see "Camera").
  - `FrameStream`: the screen as JPEG or H.264 frames for `apksim serve` (see "apksim serve"; H.264 is
    `video/H264Encoder`: VideoToolbox in `native/h264enc.m`, OpenH264 opened at run time in
    `native/linux/h264enc_linux.c`, `libh264enc`). `Control` serves each connection
    on its own thread, so one can stream while another drives.
  - `skin/` (`Skins`, `Skin`, `Frames`; `DeviceSpec` resolves a `--device` value): `apksim skin`, the local skin store and its commands, docs/skins.md. Runs on a plain JVM with only apksim.jar (the launcher's `skin` case). Drawing skins and the serve/beam parts are separate issues.
  - `serve/`: `apksim serve` itself (`Serve`, `Session`, `Catalog`, `Http`, `WebSocket`; `Beam`: `apksim beam`, the
    loopback agent that runs a browser session's app on the visitor's Mac, docs/beam.md, with the sites its owner
    allowed, the consent dialog (`/beam/hello`'s `asking` while it is up, issue 178) and the addresses it may download a
    build a page names from (`apksim beam config allow-download`, a start's `source`, issue 177); `SharedDataLibs`: the agent adds
    `--move-shared-data` itself for a build with a 4 KB-aligned library macOS cannot map as it is (issue 171); `BeamSandbox` (in `dev.apksim`): a beamed instance's
    sandbox, its own standbys (`b`) and caches per build, docs/beam.md "The beam sandbox"; `BeamStore`: what the agent
    keeps and for how long, builds held `keep-days` after their last session, sessions kept on request, `apksim beam
    saved|run|to-device|forget|config|sweep|status`, and a saved session reopened from its site's tab on a copy written back when
    it ends, or a newer build of the same app started on a copy (`/beam/start`'s `saved`/`dataFrom`, `GET /beam/saved`,
    issue 181; `ApkSigners`: the same key, as Android requires of an update), and the flight recorder channels a beam
    session starts with (`apksim beam config rec input|all|off|CHANNELS`, `input` by default; an app's own `--rec`
    wins); `DeviceHandoff`: `apksim beam to-device ID [-s SERIAL] [--data] [--settings KEYS|all] [--install
    replace|skip] [--yes] [--dry-run]`, a saved session carried on to an adb device (the build compared by hash and
    signer, the device's data backed up before the slot is written through `run-as`, settings without identity keys, an
    undo script; docs/beam.md "Moving a session to a device", issue 191; no real device has run it yet); `BeamLaunchd`: `apksim beam install|uninstall`, launchd starting the agent
    when a page asks, `--launchd` taking the socket on stdin; `SlotPack`: a session's data slot and `.device` folder
    zipped by the server (`handoff`) and unpacked by the agent into a `bm<12 hex>-0` slot, docs/beam.md phase 5; the page is
    `resources/dev/apksim/serve/serve.html`). Runs on a plain JVM with only apksim.jar: no Robolectric or Android there.
  - `net/TabRelay`, `RelaySocket`, `RelayConnection`: the beam tab relay (issue 276, docs/beam.md "The tab relay"): with
    `--tab-relay` (the agent adds it for a start with `"relay": true`, protocol 3), plain-HTTP requests to
    `page.apksim.invalid` are carried to the browser tab as `y ID {json}` text messages and answered by it. In-JVM (the
    beam sandbox denies loopback): `SocketFactory.createSocket()` in app code goes to `Net.createSocket`, which returns a
    `RelaySocket` while the relay is on (the factory's own socket for any other address), `URL.openConnection` a
    `RelayConnection`; the instance's `relaywatch` control connection is the link to `Session`. `scripts/check-tab-relay.sh` (CI).
  - `FastNativeLoader`: loads Robolectric's native runtime from pre-extracted `work/runtime`, not a fresh temp dir per launch.
- `native/`
  - `simwindow.m`: Cocoa window and IOSurface present, input queue, Cmd+V paste, `wshot` self-capture, thread QoS.
  - `elfloader.c`, `bionic.c`, `jnibridge.c`: the Android ELF loader, bionic→libSystem shim, and a JNI table wrapper
    for the AAPCS-vs-Apple varargs difference (chosen by caller address).
  - `jnigraphics.c` (Mac and Linux): libjnigraphics' `AndroidBitmap_*` for ELF code, through `NativeBitmaps` (see "Video").
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
  `bench.py` (launch times, cold and warm; `--mem` adds footprints), `startup-gate.py` (CI's startup-time gate: this build against an anchor
  release, interleaved; docs/startup-gate.md), `drive.py` (control channel client), `ctl.py` (find and drive running instances, app
  data), `fleet.py` (N headless instances driven through one script at once: results, readiness, memory; see
  docs/parallel.md), `scene.py` (a scene: roles with their own APK, device and options driven step by step,
  waiting on one another, against one shared fixture; `scene-check.sh` in CI; docs/scenes.md), `snapshot.py` (screenshot tests: screens × device/appearance/locale/fontscale matrix against
  goldens, HTML report, `--update`; see docs/snapshots.md), `instrument.py` (androidTest suites, JUnit XML;
  `--apksim CMD` runs the Linux image instead; `--test-timeout SEC` kills a process stuck SEC s on one test;
  `instrument-check.py` checks how it handles a process that dies or hangs,
  against a stand-in apksim), `affected.py` (instrument.py's test-impact recording and selection and its
  time-balanced shards, see docs/affected-tests.md; `affected-check.py` checks them on the test app, breaking a file
  and rebuilding it),
  `check-translator.sh` (translates and runs `testapks/translator-fixture`, see "Instrumentation tests"),
  `adb-check.py` (the adb device as a tool's on-device server uses it, see "adb device"), `video-check.py` (recorded
  video over `--control`: selftest on the H.264 fixture clip, `apksim://video`, no HEVC decoder listed; `--hevc` for an
  image built with it: the HEVC clip and the Settings clip; CI runs it in both Linux
  images from `ci-smoke.sh`), `timer-check.py` (app timers
  against real time and onUserInteraction per input path, on the test app's `apksim://timer`), `bundle-check.py` (failure
  bundles, see "Instrumentation tests"), `beam-check.py` (`apksim beam` and
  `serve --allow-beam` started and driven as a page would, docs/beam.md), `beam-parity.py` (the app in its window
  against the same app beamed into Chrome, same input: new frames per display refresh, fps, CPU, latency),
  `beam-page-check.py` (the page's start on this Mac in Chrome: Stop while the agent has not answered, a full agent's
  fallback to the server), `beam-size-check.py` (Fill mode and the simulator window as two views of one display, in
  Chrome), `beam-move-check.py` (a running session moved between the server and this Mac with no blank frame, and
  with its data, in Chrome), `beam-launchd-check.py` (the launchd agent's jobs and its socket-activated start and idle exit, with a
  stand-in for launchd; `--real` installs a test LaunchAgent),
  `skin-check.py` (`apksim skin` against a frame drawn with a known hole), `rec-check.py` (the flight recorder against the test app and the network fixture: every channel, a checkpoint read
  back, the saved bundle, a crash bundle), `replay-adb.py` (a flight recording replayed on a device over adb, shipped in
  releases; `replay-adb-check.py` checks its plan on a synthetic bundle, no device), `beam-device-check.py` (`apksim beam
  to-device` against a stand-in `adb` and a temporary state folder), `tabfiles-check.py` (a tab's file pushes and their
  refusals, and a recording saved and downloaded, against a running `apksim serve --allow-rec`), `kept-check.py` (kept
  sessions on a running `apksim serve --keep-max N`),
  `crawl.py` (explores an APK's screens breadth-first over the control channel: graph, replayable paths, a capture per
  screen per matrix combination, crashes, `--base` comparison of two builds; docs/crawl.md; `crawl-check.py` checks it),
  `serve-check.py` (a running `apksim serve` driven as a browser tab, see
  "apksim serve"), `slot-check.sh` (a data slot's /sdcard and Settings across launches, `SlotDevice`), `relaunch.sh`,
  `both.py`/`vshift.py` (emulator comparison),
  `prof.py` (summarise macOS `sample` output), `so-imports.py` (native import audit), `prepare-runtime.sh`,
  `gen-gles-shadows.py` (writes `gl/ShadowSimGLES20/30`), `jdi/Trace.java` and `jdi/Ret.java` (breakpoints and return
  values over JDWP), `translate-class.java` (one class's translation, for javap), `translate-all.java` (every class of
  an APK: time per pass and a hash per class, to profile the translator and check a change leaves output identical),
  `make-media.sh` (test clips), `docker-build.sh` and `ci-smoke.sh` (Linux image, CI), `network-check.sh` and
  `network-fixture.py` (record, replay and conditions against the test app, see "Network"), `package-release.sh` (see
  "Releases"),
  `hooks/` (`pre-push`: no direct pushes to main; `pre-commit`/`pre-push` run `check-private`, see "Git and pull
  requests").
- `docs/`: `ios-simulator-parity.md` (feature gaps and status), `flight-recorder.md` (`--rec`, checkpoints, `.apkrec` bundles, the replay plan), `serve.md` (APKs in a browser tab), `beam.md` (a tab's app run on the visitor's own Mac), `skins.md` (device skins: format and `apksim skin`), `linux-docker.md`, `crawl.md` (the crawler and screen map: what a screen is, budgets, safety, `--base`), `ci-testbed.md` (a large app's
  device tests here: results, CI cost, commands), `parallel.md` (many instances and test processes at once: shards,
  suites side by side, fleets, what limits them), `affected-tests.md` (running only the tests a change reaches, and
  shards balanced by measured time), `network.md` (record, replay and network conditions: the spec, where it hooks
  and why, what each client gets), `snapshots.md` (screenshot tests: spec, determinism, Mac against the Linux
  image), `plan.md` (remaining work, in order), `startup-gate.md` (CI's
  launch-time budget: what it measures, why against a fixed anchor, moving the anchor), `conformance.md` (libraries'
  documented behaviour here against an emulator, `scripts/conformance.py`: the table per library, causes fixed and left).
- `.github/workflows/ci.yml`: `build` (the runtime, the test app and the Linux image, once, plus the quick checks),
  then five `linux` legs side by side on that image (`app`, `data`, `replay`, `crawl`, `tests`: `scripts/ci-smoke.sh`
  split by `SMOKE_PARTS`, the androidTest runs, affected-check), and `linux-smoke`, the check every pull request must
  pass (see "Git and pull requests"), which passes when they all did; ~15 min where one runner took ~38. Add a new
  image check to the leg whose time it fits (each is ~5-8 min; the crawler's alone is ~7).
  `linux-smoke-arm64` (the arm64 image on an arm64 runner, with the test APKs `build` makes; on pull requests only
  when natives, the image, the Gradle build or the smoke script change, always on `main`; not required), and
  `startup-gate` on an Apple Silicon runner (`macos-15`, or a self-hosted Mac while the `STARTUP_GATE_RUNNER` repository variable is set; the Mac's job-started hook, `scripts/runner-job-started.sh`, refuses every other job and forks' pull requests: docs/startup-gate.md). A `changes` job runs the personal-information check and skips the build, test and Mac jobs for a pull request that
  touches only Markdown, `.txt`, `docs/`, `.claude/`, LICENSE or NOTICE, and `startup-gate` for one that touches
  nothing a packaged Mac launch is built from (only the test app, `docker/`, `native/linux/`, CI's check scripts or
  Python tools; skipped jobs count as passed for the required checks, which a workflow path filter would leave pending).
- `testapks/composeapp`: the test app. It has a list, Trips (SQLite rename: "Italian vacation" ↔ "Roman vacation"),
  Settings, and a **Shader** tab, a full-screen unthrottled noise shader used as the 120 fps stress test. It is
  adaptive (material3-adaptive window size classes): below 600 dp of width a bottom bar and a list, from 600 dp a
  navigation rail and a grid; the top bar shows the class and window size in dp ("compact 411×891"), which `nodes`
  reads; Settings shows the wall clock in the locale's format. `apksim://stack` opens `SecondActivity` (plain views, a
colour and tap count per level) over what is running, and its "Deeper" stacks another, for stack checks;
`apksim://floating` a dialog-themed `FloatingActivity`; `--feature dev.apksim.testapp.launch_overlay` has its
Application add an empty 0x0 overlay window before any activity (`ci-smoke.sh`: the first frame still comes). `snapshots/` has its screenshot-test specs
  (`spec.json`: 5 screens × 16 combinations; `ci.json`, the CI's). Build with `./gradlew :app:assembleDebug` inside it. Its `uiserver` module is an instrumentation-only APK, a
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

Control commands: `tap x y`, `swipe x1 y1 x2 y2 [ms]` (with ms: a drag over ms of real time that holds 120 ms before
lifting, no fling; Host's `Drag` follows the clock, not a count of queued events),
`wheel x y amount`, `type text`, `key NAME [count] [ctrl|shift|alt|meta]` (e.g. `key A 1 ctrl`, `key DEL 5`), `back`,
`paste`, `shot file.png` (app pixels), `wshot file.ppm` (the real window, captured in-process), `activity`, `windows`
(each window's position, size, focus, visibility and owning activity with its lifecycle flags, e.g.
`.MainActivity{stopped}`), `viewtree [FILE]` (`ViewTree`: every view of every window, indented: class, id name, screen
position and size, and only what differs from a plain view: invisible/gone, alpha, translate, z, scale, rotation,
click/longclick listeners, focus; `[compose]` marks an AndroidComposeView and `[AndroidView]` the holders of its
AndroidViews; the reply joins lines with `; `, FILE gets them as lines, `adb shell apksim viewtree` prints lines),
`insets` (system bar insets the app sees), `slop` (gesture thresholds), `frames` (frame-interval percentiles since the
last call, then what reached the screen per display vsync), `fps` (per-stage cost), `display`, `shaders` (which AGSL runs on GPU vs CPU), `stats`
(frames, heap, `footprintMb`: the physical footprint as `footprint PID` shows it (`Footprint`), `cpuMs`, PID, data slot, `clockAheadMs`: how far SystemClock has run ahead of real time), `redraw`, `stream [QUALITY] [h264 [KBPS]]` (the connection becomes a JPEG or H.264 frame stream: see "apksim serve"), `ping`, `quit`,
`audio [on|mute]` (sound or silence now, open AudioTracks included; off is launch only) and `video [on|blank]` (next codec),
`maxfps [N]` (frame rate cap, 1-120; see "Per-instance costs" below), `animscale N` (animation speed, any time),
`vsync [PERIOD_US SHIFT_US|off]` (pace to a viewer's display: see `Host`).
For screenshot tests (docs/snapshots.md): `settle [MAX_MS] [QUIET_MS] [HORIZON_MS]` (answers once nothing is queued,
dirty or wanting a frame and no main-thread message is due within HORIZON_MS, for QUIET_MS of real time with no frame
drawn; else `error: not settled ...: REASON`), `restart [URL]` (every activity destroyed, the start activity (launcher or
`--activity`) or the URL's started fresh: no saved state, ViewModels cleared), `diff GOLDEN ACTUAL [MAP] [DELTA]` (pixels differing by more
than DELTA, and a difference map), `clock [ISO|epoch-ms|real]` (the app's wall clock starts there and runs on).
`back` on the last activity finishes it and the process exits a second later (`[apksim] exit 0: the last activity,
...`, see `Exits`): there is no soft keyboard for it to dismiss.
Simulator-style device controls (`DeviceControls.java`; each is also a Mac menu item, see "Simulator features"):
`appearance dark|light|toggle`, `locale fr-FR`, `fontscale 1.3|+|-`, `timezone [ZONE]` (the zone the app sees; set at
launch with `--timezone America/New_York`; see `TimeZones` below), `config` (configuration the app sees),
`device [phone|foldable|tablet|desktop|tv|WxH[@density]]` (switch devices while the app runs: size, density, system
bars, and for TV UI_MODE_TYPE_TELEVISION, no touchscreen and a D-pad), `size WxH` (display size in dp, same device),
`window WxH` (the Mac window's content in points, as dragging its corner would: checks the drag path),
`window front|show|background|minimize|hide` (as `--window`, below; `window` alone reports visible/minimized/hidden
and whether the app is active), `window close` (as the close button: while a stream viewer is attached, apksim beam's
tab, it only hides the window),
`animscale 10|1|0|toggle`, `memory [moderate|low|critical]`, `home`/`resume`, `lock`/`unlock` (a click on the window
also returns), `open URL` (deep link into the app), `clipboard [set TEXT]`, `record start [file.mov]|stop|toggle` (Mac
window only), `reveal`, `pinch CX CY FROM TO [ms]` (two fingers), `rotate left|right|portrait|landscape`,
`zoom actual|fit|in|out` (window size only), `permission [allow|deny|grant NAME|revoke NAME|reset]`,
`notifications [open N|last|clear]`, `location LAT LNG|run|bike|drive|gpx FILE|none`,
`network [online|offline|3g|lossy|latency MS|bandwidth KBPS|unmatched [clear]|save]` (see "Network"),
`biometrics [enroll|unenroll|match|nomatch|cancel]`, `bluetooth [on|off|toggle]` (`Bluetooth`: the adapter, on at
launch as an emulator boots, `--bluetooth off` otherwise; each switch, the app's own BluetoothAdapter.enable()/disable()
included (`ShadowSimBluetoothAdapter`, allowed at any targetSdk as Robolectric allowed it), goes TURNING_ON then ON, or
TURNING_OFF then OFF, with an ACTION_STATE_CHANGED broadcast for each and Settings.Global `bluetooth_on` following
(written even when there is nothing to switch); `toggle` and a `device` switch read the adapter's own state, which is the
only record of it; name Build.MODEL, address
02:00:00:00:00:00 as a device tells apps; Robolectric's LE scanner, which finds nothing; no adapter at all without
android.hardware.bluetooth; in test runs each test starts with the launch's state, and `executeShellCommand("svc
bluetooth disable")` switches it, `BluetoothTest`), `recreate` (as a configuration change: ViewModels kept),
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
BIND_AUTO_CREATE binding is left) and never onServiceDisconnected; connections bound without the flag that outlive
the service get onServiceDisconnected and onBindingDied, and are not connected again. As on the API 36 emulator (`NullBindingTest`), the
service class initializes on the main thread, onBind gets the first binding's intent for its filter even if that
binding ended before the service ran, and a connection bound twice hears of a binder once but of a null binding once
per binding (LoadedApk's ServiceDispatcher). Intents a binder was registered for through ShadowApplication (the FCM
service below) stay Robolectric's. A bind no installed service resolves (another app's: a companion app, Play
services) returns false and never calls back, and unbinding that connection calls nothing either, as on a device;
Robolectric returned true and called onServiceConnected(null, null), then onServiceDisconnected(null) on unbind, which
a Kotlin connection's non-null parameters threw on (a running instance died on its first idle; `apksim://install`,
`BindServiceTest`). A bound service is not the
instance startService runs, and an `android:process` service gets its local binder, not a proxy.
`push key=value...` or `push {"data":{..},"notification":{..}}` delivers an FCM message as Play Services would: the
c2dm RECEIVE broadcast to the app's FCM receiver, with the messaging service pre-bound (Robolectric connects a binding
only to a registered binder). Checked with the owner's app: its messaging service posts the alert.
`ble [add FILE|PRESET[:ADDR]|remove ADDR|all|rssi ADDR N|notify|set ADDR CHAR HEX|disconnect ADDR]` and `--ble FILE|PRESET`
(`dev.apksim.ble`, docs/ble.md): scripted BLE peripherals that the framework's own BluetoothLeScanner and BluetoothGatt
find and talk to (apksim answers their IBluetoothScan/IBluetoothGatt binders), in running instances and test runs
(`BleTest`; `scripts/ble-check.py` drives `apksim://ble`).
`camera [pattern|image PATH|off]` (`Cameras`, see "Camera": what the simulated cameras see; alone, their state: source,
open and streaming devices, frames, images, drops, outputs).
`thermal [none|light|moderate|severe|critical|emergency|shutdown]` (`Thermal`, issue 149): PowerManager's thermal status,
NONE at launch; `addThermalStatusListener(Executor, listener)` registers (Robolectric's shadow covered only the one-argument
overload, and the real one threw "Thermal status listener failed to set"), is called at once with the status and again on
each `thermal` change, and `getCurrentThermalStatus` agrees (`apksim://system-sources` has a "thermal" line).
`keyboard soft on|off` / `keyboard show|hide` (`SoftKeyboard`): as in the iOS Simulator a hardware keyboard counts as
connected, so no soft keyboard by default; with it on (Device > Software Keyboard Cmd+K, `--soft-keyboard`) a focused
text field raises a drawn keyboard and the app gets real IME insets (imePadding/adjustResize), Back closes it first.
`rotary N` (the crown, `SOURCE_ROTARY_ENCODER`/`AXIS_SCROLL`; the wheel sends it on a watch device) and `ambient [on|off]` (Wear OS
ambient mode through `com.google.android.wearable.compat.WearableActivityController`, apksim's stand-in for the watch's shared
library, which androidx.wear.ambient finds; never entered unless asked). `--device watch|watch-small|watch-large`: round
(qualifier `round`), UI_MODE_TYPE_WATCH, `android.hardware.type.watch`, no bars; `FrameStream.roundMask` clears the corners of
streamed frames. Not done: a round mask in the Mac window, tiles and complications, a Wear test app.
`bars [status on|off|toggle] [nav gesture|off|toggle]` (`SystemBars`; Device > Status Bar / Navigation Bar,
`--status-bar on|off`, `--nav-bar gesture|off`): which system bars the device has, overriding the `--device` preset
(phone, foldable, tablet: both; desktop, TV: neither) and kept across `device` switches. A bar that is off is not drawn
and gets no inset (Robolectric's `SystemUi` behaviour NO_STATUS_BAR / NO_NAVIGATION_BAR), as on a device without it;
`bars` alone says what the app sees ("status on (hidden by the app), nav gesture (shown)"). The app hiding the bars needs
no hook of ours: `WindowInsetsController.hide` reaches Robolectric's window session as the window's requested visible
types, which sets the bar invisible in `SystemUi` (inset 0), and ViewRootImpl's compatibility code turns the legacy
`SYSTEM_UI_FLAG_FULLSCREEN`/`HIDE_NAVIGATION`/`IMMERSIVE*` flags and `FLAG_FULLSCREEN` into the same hide;
`SystemBars.drawn` reads that visibility every 50 ms and draws only the bars that show. A swipe in from a hidden bar's
edge (from within 24 dp, moving 24 dp inward; `Host.dispatchTouch`, the app still gets the touch) shows them as the
system does for the window's behaviour: BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE (legacy IMMERSIVE_STICKY) draws them over
the app for 2.25 s with the insets unchanged, anything else calls the window's `show` (a device's `IWindow.showInsets`),
and the deprecated show-by-touch behaviour (legacy flags without IMMERSIVE) shows them on any touch. Test runs keep
Robolectric's no bars unless `--status-bar`/`--nav-bar` ask for them, reset at each test's setup; a test's
`executeShellCommand("apksim bars ...")` changes them for that test (`SystemBarsTest`). The test app's
`apksim://systembars` screen shows its insets ("bars: status=48 nav=48") and has buttons that hide the bars each way.
The Android Keystore (`SimKeyStore`, provider "AndroidKeyStore": KeyStore, AES/HMAC KeyGenerator, EC/RSA
KeyPairGenerator with KeyGenParameterSpec) is a test keystore: keys persist in the app's data folder in the clear and
no use restriction is enforced.
`record` without a Mac window (headless, Linux) writes Motion-JPEG `.avi` (`MjpegRecorder`, 30 fps, Android's JPEG
encoder); with a window `.mov` H.264, or `.avi` if the file name says so. Configuration changes recreate activities as on a device (state not in
`rememberSaveable`/`onSaveInstanceState` resets). A command that throws answers `error: ...` instead of ending the app.
`nodes tree [all]` (every accessibility node with depth, class, id and flags, records split by U+001E: what `scripts/crawl.py`
hashes and taps),
`ctl.py apps|container PKG|erase PKG` lists, locates and deletes app data (erase refuses while an instance holds it),
erase with each slot's `<slot>.device/` (its /sdcard and Settings values, `SlotDevice`).
`scripts/ctl.py list` / `ctl.py [@PID|@PORT|@APKNAME] CMD [ARGS] [';' CMD...]` finds running instances by themselves
(each writes `work/tmp/<pid>/control.port`); `--control auto` takes a free port.

## Simulator features (Mac window)
The window is resizable: dragging an edge resizes the display (`Device` rewrites the whole qualifier string:
smallest width, size bucket and orientation follow), applied at most every 100 ms while dragging and once at the end;
meanwhile the last frame stays pinned top left. Activities get the configuration change as on a device in a freeform
window: recreated, or resized in place when they declare `screenSize` in configChanges. Zoom only
scales the window. A display bigger than the Mac's screen (desktop, TV) opens zoomed out to fit.
Menus as in the iOS Simulator: File (Save Screen Cmd+S, Record Screen Cmd+R, Save Replay Cmd+Shift+S: the flight recorder), Device (Device Type > Phone/Foldable/
Tablet/Desktop/TV Cmd+Shift+1-5, Back, Home Cmd+Shift+H, Lock
Cmd+L, Volume Cmd+Opt+=/-, Rotate Cmd+Left/Right), Features (Dark Appearance Cmd+Shift+A, Larger/Smaller/Default
Text Cmd+Shift+=/-/0, Language and Region..., Location > None/Custom/City Run/Bicycle/Freeway, Fingerprint >
Enrolled/Matching Cmd+Opt+M/Non-matching Cmd+Opt+N, Network > Online/Offline/3G/Lossy, Camera > Off/Test Pattern/Image
File... (`--camera fake`), Open URL... Cmd+Shift+O), Window (Actual Size Cmd+1, Fit Screen
Cmd+3, Zoom In/Out Cmd+=/-, Minimize Cmd+M; the app menu has Hide Cmd+H), Debug (Slow Animations Cmd+T, Simulate Memory Warning Cmd+Shift+M, Show Layout Bounds,
Accessibility Audit), and Device > Status Bar / Navigation Bar (see `bars`). Permission requests
and biometric prompts show as sheets on the window; notifications as a banner (click opens it).
Saves go to `work/screenshots` (the sandbox keeps the app out of ~/Desktop). Option-drag pinches around the screen
centre, Option-Shift-drag pans with two fingers, trackpad pinch/rotate map to two fingers. The status bar (clock,
Wi-Fi, battery, dark icons when the app asks for light bars) and gesture handle are drawn over the app as layers,
each only while the device has it and the app has not hidden it (`wshot` shows them, `shot` does not). The Mac and app clipboards stay in sync both ways. http(s)/mailto links the
app opens outside itself go to the Mac's browser/mail app. The keyboard sends Control shortcuts, Shift selection,
Option+arrow (as Ctrl+arrow: by word), Home/End/Page/Forward Delete as Android key events. Launch options:
`--activity PKG/.Act|.Act|CLASS` (`AppInfo`: start that activity, exported or not, instead of the manifest's first
MAIN/LAUNCHER one, as `am start -n` would: a debug manifest's test LAUNCHER listed before a home app's MAIN/HOME
entry, or a screen deep inside; a simple name such as `SecondActivity` works when one activity has it; with a MAIN
filter it gets MAIN and that filter's LAUNCHER or HOME category, else the component alone; an alias stays the intent's
component and its targetActivity is made (the test app's `.SecondAlias`). `restart` starts it again
and `process-death` rebuilds it; one the manifest lacks stops the launch with exit 2 and the list; not in test runs.
With no LAUNCHER activity the first MAIN/HOME one starts), `--appearance dark`, `--locale de-DE`, `--timezone America/New_York`, `--clock 2026-01-01T09:41:00Z`, `--font-scale 1.3`, `--permissions ask|grant|deny` (grant = `adb install -g`),
`--biometrics enrolled`, `--bluetooth on|off`, `--status-bar on|off`, `--nav-bar gesture|off`, `--snapshot` (screenshot tests: cold launch), `--window front|nofocus|background|minimized|hidden` (default for every launch:
`APKSIM_WINDOW`), and device identity (`DeviceIdentity`, set just before Application.onCreate in running instances,
warm or cold, and in test runs): `--serial VALUE` (Build.getSerial() and Build.SERIAL; by default 16 letters and
digits hashed from the data slot's name, so one slot keeps one serial and `instance-1` gets another, and test runs get
the `default` slot's: Robolectric's `unknown` failed a backend's format check), system features (`SystemFeatures`:
Robolectric's few (touchscreen, wifi*, telephony*), then what an emulator of the device's kind lists for what apksim
simulates: bluetooth, bluetooth_le, location*, multitouch*, faketouch, fingerprint, audio.output, both screen
orientations; a TV drops touchscreen, telephony, GPS and fingerprint and has leanback and type.television, a desktop
adds type.pc; no camera* unless `--camera fake` (see "Camera"). The `device`
command re-applies them for the new kind. `pm list features` and getSystemAvailableFeatures list them), `--feature
NAME[=false]` (repeatable, after the defaults: `=false` takes one away, a default too) and
`--build FIELD=VALUE` (repeatable: any public static Build or Build.VERSION field, `MODEL=Pixel 9`,
`VERSION.SECURITY_PATCH=...`, typed by the field; not VERSION.SDK_INT, and leave FINGERPRINT alone in test runs,
see "Instrumentation tests"; Robolectric resets them between tests, and they are set again for each). `DeviceIdentityTest`
checks them (CI runs it with the options). `--setting global|secure|system:KEY=VALUE` (repeatable) puts a Settings value
before onCreate, as `adb shell settings put` before a first launch would (`SlotDevice`; kept with the slot, and in test
runs set at each test's setup: `SlotStateTest`). `global:bluetooth_on` is refused (use `--bluetooth on|off`) and not kept per slot: the adapter's state follows the launch. A running instance takes its data slot in the same hook (`Host.usePersistentDataDir`,
before onCreate, not in `Host.run` after it), so what onCreate writes is kept, it finds the slot's /sdcard and Settings
values (`SlotDevice`, with the --setting seeds over them), and `APKSIM_LOGCAT` shows its log.
Then, as in test runs, what installing gives the app (`InstallState`, see "Instrumentation tests"): normal permissions
granted (one app's onCreate threw "Need BLUETOOTH permission" before), an ANDROID_ID (from the data slot, as the
serial; only if the slot's settings or a seed did not already give one, so the slot keeps it, and an
`android_id` set with `settings put` or `--setting secure:android_id=...` wins), the host's networks, and the app's content providers created (AndroidX Startup's initializers ran never
before, providers being made only when first queried); a provider that fails is logged and skipped
(`-Dapksim.providerFailures=fail` crashes the launch as a device would: FATAL EXCEPTION, exit 10, see `Crashes`; log
stays the default here, as a provider apksim cannot start may be apksim's gap, and such apps ran before). The standby's warm-up loads Conscrypt's
native library, which OkHttp's startup initializer asks for (~125 ms of a warm launch otherwise). `apksim://install`
(`InstallStateActivity`) shows what onCreate saw and binds to a service nothing provides (`ci-smoke.sh` checks it). Window modes (`applyWindowMode` in `simwindow.m`): only `front` takes focus; `nofocus` shows the
window on top, `background` behind every other window (orderBack), `minimized` in the Dock, `hidden` not at all; a
Dock icon click shows it focused. The window server activates a newly launched regular app just after it finishes
launching, asked or not, so the other modes start as an accessory app and turn regular (Dock icon) in
applicationDidFinishLaunching. Hidden or minimized, there is no display vsync and Host paces on its grid; App Nap is
held off (`beginActivityWithOptions`) in every mode, since it throttles the timers of covered or hidden apps. A
permission or biometric sheet on a window the user cannot see bounces the Dock icon. Test-app Settings tab (scroll down) has a pinch area and Camera/Locate/Notify/Copy/Unlock/
Pick file/Choose trip (in-app result)/Share/Background/Keystore/Bind buttons for these (Bind: "bind: null
binding to .NullBindingService"), and "Play clip" (ExoPlayer, a 3 s
HEVC + AAC clip into a TextureView; "clip: ended at 3.0 s after N s, video video/hevc, audio audio/mp4a-latm" when it
plays through); Trips has a "Trip note" field with
imePadding; the app follows dark mode and handles `apksim://composeapp/<tab>` deep links. `apksim://network` opens
`NetworkActivity` (HTTPS fetches with OkHttp and HttpURLConnection, ConnectivityManager's state; see "Network").
`apksim://bitmaps` opens `ViewBitmapsActivity` (Views only: a stretched BitmapDrawable background, FIT_XY and
CENTER_CROP ImageViews, `drawBitmap(Bitmap, Matrix, Paint)`, a mutable bitmap redrawn every frame; `?animate=0` holds
still for pixel comparisons, `?video=1` adds the clip scaled up in a large TextureView), the GpuBitmaps check on
`--device tablet`.
`apksim://bluetooth` opens `BluetoothActivity`: "bluetooth: on, scanner yes", the ACTION_STATE_CHANGED broadcasts
received ("broadcasts: TURNING_OFF OFF") and the Bluetooth features (`scripts/adb-check.py` switches it with `svc`).
`apksim://timer` opens `TimerActivity`: a 60 s CountDownTimer, SystemClock's drift from the wall clock, counts of
onUserInteraction, touches and keys, a "Sleep worker" (SystemClock.sleep on a thread) and "Sleep on main" (an uptime
deadline waited for with SystemClock.sleep); `scripts/timer-check.py
PORT [--adb SERIAL]` drives it (~70 s).
`apksim://timezone` opens `TimeZoneActivity`: each zone source (JDK, android.icu, `user.timezone`, Joda-Time 2.10's
default) on a line, and ACTION_TIMEZONE_CHANGED heard as joda-time-android hears it ("tz changes: 1 to Asia/Tokyo";
`ci-smoke.sh` drives it).
Time zones (`TimeZones`): `--timezone ZONE` is applied before the Application exists, warm or cold, and in test runs at
each setup: the JDK's default, android.icu's (its cached default dropped: a standby may have worked it out before the
launch), the `user.timezone` property (Joda-Time up to 2.10 reads it before the JDK's default and caches the result; a
standby's JVM had set it to the host's, so Joda dates were a day off), the status bar clock and logcat's timestamps.
`timezone ZONE` does the same while the app runs and broadcasts ACTION_TIMEZONE_CHANGED with EXTRA_TIMEZONE, as a
device does (joda-time-android sets Joda's default from it; plain Joda keeps its cached zone, as on a device). Without
`--timezone` nothing is changed.
`apksim://composeapp/trips?overlay=1` adds an empty, non-focusable overlay window after the activity's window (issue 123);
`scripts/overlay-input-check.py PORT` types into the Trip note field under it over `--control` and expects "ab", so typing
that reaches the wrong window fails it (`keyTarget` in `Host`: keys go to the topmost shown, sized, focusable window).
`apksim://popup-trace` opens `PopupTraceActivity`: "Trace twice" begins one Trace async section twice, and dragging
its slider leaves a non-focusable value Popup up, which a tap on "Tap me" outside it must not reach (`ci-smoke.sh`
drives it; `PopupTouchTest` in a test run).
`apksim://drawer` opens `DrawerActivity`: a `DrawerLayout` with three items that count clicks, settling through
ViewDragHelper's Scroller (`DrawerSettleTest`, opt-in, see `--realtime-clock`).
`apksim://dialogs[?open=full|modal|nonmodal|compose]` opens `DialogsActivity`: a full-screen dialog, a small
(touch-modal) one, a small FLAG_NOT_TOUCH_MODAL one or a Compose Dialog over it; under all but the third accessibility
reports only the dialog. Each counts taps on its "Count" button, for clicks at a centred dialog's node bounds
(`CoveredWindowsTest`, `adb-check.py`).
`apksim://slot` opens `SlotStateActivity`: the Settings.Global values `apksim_slot_check` and `apksim_slot_seed` and the
file `/sdcard/apksim-slot-check.txt` as Application.onCreate saw them ("at app start") and now, and "Write" to set the
first and the file, plus typed values (Global putInt, Secure putLong, System putFloat; "typed:" reads them back with the
typed getters: the slot keeps text, as Android's provider does, and the getters parse it) (`scripts/slot-check.sh`
drives it across launches, and checks `adb shell settings get` when adb is on PATH).
`apksim://camera[?mode=compatible]` opens `CameraActivity`: CameraX's Preview in a PreviewView (a SurfaceView; `compatible`:
a TextureView) and an ImageAnalysis on the front camera, "camera: N frames, 1280x720 YUV, rotation 270"; the Settings
tab's Camera button opens it once the permission is granted on a device with a camera (`--camera fake`; `CameraTest`).
`apksim://video` opens `VideoActivity`: a 2 s 1080p60 H.264 clip looping full-screen in a TextureView with ExoPlayer,
muted; `video` twice a few seconds apart should show `rendered` going up ~60 a second and `dropped` not at all.
`apksim://crash?kind=coroutine|post|worker|bare|resume|config|destroy|handled|chained` opens `CrashActivity`, which throws 300 ms later: in
viewModelScope.launch, a posted Runnable, a worker thread, a worker thread with an exception made without a stack trace
(`bare`); `resume`, `config`, `destroy` throw from the second onResume (`recreate`, `restart URL`), onConfigurationChanged
(`appearance dark`, `rotate`) and onDestroy (`restart`); `handled` first sets a default handler that keeps the app
running ("crash: handled by the app ..."), `chained` one that passes it on (`--activity .CrashActivity` is `coroutine`;
`ci-smoke.sh` checks it exits 10 with FATAL EXCEPTION, `handled`, and the test app's attachBaseContext throwing with
`--jvm -Ddev.apksim.testapp.crash_in_attach=true`).
`apksim://system-sources` opens `SystemSourcesActivity`: system state as apps read it and await it in coroutines
(callbackFlow + first(): Wi-Fi's signal level bucketed as `calculateSignalLevel(rssi) / (maxSignalLevel / 4)`, Wi-Fi's, the network's and the battery's
sticky broadcasts, a default and a Wi-Fi network callback, one registered in Application.onCreate, VOLUME_CHANGED_ACTION
after setStreamVolume, a settings observer, an A2DP profile proxy), one line each, "timed out" after 3 s or "error"
(`ci-smoke.sh` checks none does but Bluetooth's, which connects only while Bluetooth is on; `SystemSourcesTest` in a
test run).

Compare mode and previews (docs/pr-preview.md): `/?compare=A,B` is `serve/compare.html`, two ordinary sessions in one tab
with every input sent to both and the differing pixels counted in the page (`window.apksimCompare()`); `scripts/pr-preview.py`
and `.github/actions/preview` put a pull request's build on a serve (with `ttl`, `Catalog.expireAt`) and keep one comment.
`scripts/compare-check.py` checks them (CI: `ci-smoke.sh`, protocol layer; `--chrome` for the page's own count).
Report button and network inspector (same doc; `serve --allow-rec --issue-url`, `serve --inspect-network`): the page's cards and script are
`serve/report.html`, put into `serve.html` at its two `apksim:report` markers; the instance side is `net/Inspector.java` (`--network-inspect`:
capture in memory, `netwatch` event stream, `network rule|detail`, rules answered in `NetEngine.answerRule`); `scripts/inspector-check.py`.

## MCP server
`apksim mcp --apps DIR [--out DIR]` (`scripts/mcp.py`, stdlib Python, protocol 2026-07-28 and the handshake revisions): tools to launch and drive instances over `--control`; each action answers with the node changes since the last answer. Allow-listed launch options, output folder only, `--no-autofill`. docs/mcp.md; `scripts/mcp-check.py` checks it.

## Network (record, replay, conditions)
docs/network.md is the spec. `--network record FILE` captures every HTTP(S) exchange into a HAR (bodies, headers,
timings, each host's certificate chain; Authorization, Proxy-Authorization, Cookie and Set-Cookie redacted, more with
`--network-redact`), `--network replay FILE` answers from it with nothing leaving the process (match on method, URL,
body hash, optionally headers; `--network-ignore-query/-header`; an unmatched request gets a 599 with the closest entry
in the log, and in a test run fails the test it happened in unless it ran `apksim network unmatched clear`),
`--network-profile` and the `network` command set conditions (offline, 3g, lossy, latency, bandwidth), and
`--network-host H=ADDR:PORT` points an address compiled into the app at a fixture server (`--network-trust CERT` for
its certificate). Any option turns it on; without one, the hooks cost one static read.
- Where it hooks (measured: the owner's R8 build has no `okhttp3/OkHttpClient$Builder`, R8 renamed OkHttp): the
  translator sends app calls to JDK APIs, which R8 never renames, to `dev.apksim.net.Net` (`MethodTranslator.NET_HOOKS`):
  `Socket.connect`, `SocketFactory`/`SSLSocketFactory.createSocket(host, port)`, `SSLSocketFactory.createSocket(socket,
  host, port, autoClose)`, `URL.openConnection`/`openStream` and `HttpsURLConnection.setSSLSocketFactory`. A connection to a non-loopback address goes to
  `NetEngine` on a loopback port instead (a first line says where it was going; the engine answers with the real
  connect's result). Recording or replaying, TLS on such a socket is a `FakeTlsSocket`: its handshake gets the
  server's certificates from the engine (the real chain, so hostname checks and pins pass) and the bytes stay plain
  text, so HTTP/1.1 is readable. The JDK's HttpURLConnection is not app code; Net hands it the engine as an HTTP proxy.
  `NetResolver` (META-INF/services) answers every JVM lookup: offline fails it, replaying returns a 198.18/15
  placeholder. Natives: offline only (`bionic_net.c`, Mac). Loopback is never touched.
- Code that runs for every app references only JDK types (no OkHttp), as for org.webrtc ("Video").
- Offline also drops the engine's open connections, flushes the JDK's lookup cache (`InetAddress.cache`, as Android
  flushes its resolver's) and takes ConnectivityManager's default network away (`NetConnectivity`). Robolectric finds
  the active network by its type (`getActiveNetwork` looks up netId TYPE_WIFI), so `HostNetworks` gives the default
  network that netId: before, apps got Robolectric's stand-in (no INTERNET or VALIDATED capability). On Linux the
  container's `eth0` counts as Ethernet; before, ConnectivityManager had no network there at all.
  `setDefaultNetworkActive(true)` with an active network calls onAvailable itself, so NetConnectivity sets it first.
  The shadow's state is static, so NetConnectivity applies an offline launch profile before the Application exists
  (`SimTestEnvironment.setUpApplicationState`): `Application.onCreate` sees no network, in Host and in tests.
- What a device's system server has already sent or sends at once, which Robolectric never does (issue 96: an app's
  coroutine awaiting a sticky broadcast or a network callback waited for ever, no exception): `SystemBroadcasts` keeps
  the sticky WIFI_STATE_CHANGED, Wi-Fi NETWORK_STATE_CHANGED, CONNECTIVITY_ACTION (from ConnectivityManager, again on every
  `network offline|online` and HostNetworks install, which publishes nothing under `--network-profile offline`) and
  ACTION_BATTERY_CHANGED (full, not charging), set before Application.onCreate and the providers InstallState creates,
  in running instances and every test. `ShadowSimConnectivityManager` runs network callbacks as ConnectivityService
  does: a default callback is owed the active network, requestNetwork and registerBestMatchingNetworkCallback one
  network (the active one if it satisfies the request, else the first that does), registerNetworkCallback every
  network that satisfies it (`NetworkRequest.canBeSatisfiedBy`). What each is owed is decided as it registers and at
  every change (`rematch`: HostNetworks install, NetConnectivity, which no longer calls callbacks itself), as diffs
  against what it was told (onAvailable + onCapabilitiesChanged + onLinkPropertiesChanged + onBlockedStatusChanged,
  changed capabilities or links, onLost), queued per callback and run in order on its handler (a background ConnectivityThread without one, as on Android);
  a change made on that thread runs the queue at once (unless it is made from inside that callback: then the drain
  under way delivers it after the current announcement), so offline and online before a registration's post give
  available, lost, available. A default callback or a request hears onLost only when its network disconnected or
  nothing replaces it: a switch to another network is just onAvailable, as Android reassigns (a listen hears every
  loss; `NetConnectivity.switchDefaultToEthernetForTest` is SystemSourcesTest's hook for it). Only connected networks
  are offered. requestNetwork with a timeout that nothing satisfies by then hears onUnavailable on its handler (the
  uptime clock) and is released; satisfied or unregistered first, the timeout is cancelled. The host's networks are
  installed before Application.onCreate in running instances too (InstallState), so a monitor registered there hears
  connected first (apksim://system-sources' "startup heard": it heard Wi-Fi down and its default network lost before
  that when Host installed them after onCreate). HostNetworks' networks carry a device Wi-Fi's capabilities (NOT_RESTRICTED, TRUSTED,
  NOT_METERED..., which a default NetworkRequest asks for). `SystemBroadcasts.ShadowSimAudioManager` sends
  VOLUME_CHANGED_ACTION (STREAM_MUTE_CHANGED_ACTION) when setStreamVolume or adjustStreamVolume changes a stream.
  Robolectric prints every broadcast it sends to stderr, so a sticky nobody listens for yet is only kept.
- WifiManager as a connected API 31+ device's (`SimWifi`; issue 96's actual cause): getMaxSignalLevel() 4 and
  calculateSignalLevel(rssi) on RssiUtil's default thresholds (-88/-77/-66/-55), the static
  calculateSignalLevel(rssi, n) by the classic -100..-55 formula, getConnectionInfo at -50 dBm, 5180 MHz, 866 Mbps while
  the active network is Wi-Fi (SSID "AndroidWifi", BSSID and network id only with ACCESS_FINE_LOCATION, else redacted
  as a device does; offline: disconnected, rssi -127). Robolectric's stub IWifiManager made getMaxSignalLevel() 0, so code that
  buckets the level as `level / (max / 4)` threw ArithmeticException (issue 96), which an app's coroutine may never
  report. HostNetworks gives the Wi-Fi network's capabilities that (redacted) WifiInfo and signal strength.
- In test runs each test starts with the launch profile and no unmatched requests (`Instrument.RawListener`; a test
  failed for an unmatched request gets a failure bundle with `--bundles`, through `Bundles.listenerFailure`); a test
  changes conditions with `executeShellCommand("apksim network offline")`. Recording launches cold (its sandbox must
  allow writing the file and `FILE.apksim-tmp`, and only those, not the folder); replay and conditions launch warm.
- Not covered: Ktor CIO and other NIO `SocketChannel` clients, `new Socket(host, port)`, Cronet's native stack
  (docs/network.md says what it would need), WebSocket replay (relayed while recording). Checks:
  `scripts/network-check.sh APK OUT [IMAGE]` (CI runs it in the image from `ci-smoke.sh`) and `NetworkReplayTest`
  (`-e networkReplay true` with `--network replay testapks/composeapp/app/src/androidTest/network/greeting.har`).

## Camera
`--camera fake` (`Cameras`, issue 219; `fake=IMAGE` for a still image, resolved like any path) declares
android.hardware.camera, camera.any and camera.front and lists two cameras in CameraManager, as an emulator: "0" back
(sensor 90) and "1" front (270), LIMITED, 1280x720 and 1920x1080 in YUV_420_888, JPEG and PRIVATE at 30 fps. A front
camera alone with android.hardware.camera declared (it means a back camera) made CameraX refuse to start ("Expected
camera missing from device"). `ShadowSimCameraManager`: openCamera throws SecurityException without CAMERA (Robolectric
opened it anyway). Robolectric's CameraDevice opens and configures sessions but never produces a frame, so
`ShadowSimCameraCaptureSession` hands capture, burst and repeating requests (Handler or Executor), stopRepeating,
abortCaptures and close to `FakeCamera`, which runs them on its own thread (`apksim-camera`) at 30 fps, as a device
does: each frame takes the next single capture, else the repeating request; draws the `Scene` (colour bars, a white bar
sweeping across every 2 s, frame number and time, a black triangle at the top; or the still image; or black for
`camera off`) into each target Surface; and calls onCaptureStarted and onCaptureCompleted with a TotalCaptureResult
(SENSOR_TIMESTAMP, 3A converged, the request's modes echoed) on the callback's handler or executor, then
onCaptureSequenceCompleted/Aborted as sequences end. Timestamps are SystemClock.elapsedRealtimeNanos, kept strictly
increasing (the simulated clock may stand still in a test run): CameraX pairs Images with results by timestamp.
Targets: an ImageReader's Surface (Robolectric's native Surface shadow names its reader) gets an Image from
`ShadowSimImageReader` (a subclass of Robolectric's native ImageReader shadow; readers the camera never wrote to, HWUI's
included, are Robolectric's): YUV_420_888 as a device's NV21-backed planes (Y, then U and V views of one interleaved
buffer, pixel stride 2, direct ByteBuffers for native readers), JPEG, RGBA_8888, or PRIVATE without planes, under the
reader's maxImages (a frame with no free buffer is dropped, as the camera stalls on a device). A TextureView's or
SurfaceView's Surface gets a picture through `VideoSurfaces`, as video does. Orientation follows the device: an
ImageReader's buffer is the scene turned so that the rotation apps compute ((sensor + display) mod 360 front, (sensor -
display) back) makes it upright; a TextureView shows the buffer as the camera's transform turns it (mirrored for the
front camera) and CameraX's PreviewView corrects for the display with the view's transform, which `ShadowSimTextureView`
now applies; a SurfaceView's buffer is composited with the display's rotation, so it shows the scene upright.
Two framework gaps this needed: a Surface made from a SurfaceTexture had no native object, so `isValid()` was false and
CameraX's SurfaceRequest refused the preview ("invalid surface"): `video.ShadowSimSurface` gives it a negative handle that
no native code sees (every native Surface method answers for it), and answers for SurfaceNatives' JNI methods Robolectric's
runtime lacks (isValid, release threw UnsatisfiedLinkError for an ImageReader's Surface). And `ShadowSimSurfaceView`'s
holder honours setFixedSize in surfaceChanged (PreviewView waits there for the camera's resolution).
`camera pattern|image PATH|off` and Features > Camera (Image File... opens the file panel) switch the source; `camera`
alone reports it. In test runs it is reachable through `executeShellCommand("apksim camera ...")` until the next setup.
serve accepts `--camera off|fake` as an app option and `camera pattern|off` from a tab. Not done: the Mac's webcam, a
looping video file, Surfaces of encoders (VideoCapture, MediaRecorder: they get nothing), reprocessing, the flight
recorder (no frames recorded). `CameraTest` (`-e camera fake|off` with `--camera fake|off`; CI runs both) covers
detection, the permission, Camera2 into an ImageReader (layout, changing frames, orientation, results) and CameraX's
Preview and ImageAnalysis on `apksim://camera`.

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
In async mode (Media3's default) Robolectric's start hands the app one input buffer and gives each back only when the
output it became is released, so a player had one picture in flight and ExoPlayer decoded ~40 a second: a 1080p60
clip showed ~9 pictures a second and dropped the rest as late. `handOutInputs` offers every buffer at start but the
4 kept for held pictures (below), as a device's codec offers several (`apksim://video`: 60 of 60 a second).
Its placeholders are told apart by identity, so
each must be its own object: one non-capturing lambda is a single shared instance. Video decoding happens in
`native_queueInputBuffer`, not Robolectric's per-buffer process call, so that pictures leave in presentation order
with their own timestamps: Robolectric pairs each input with one output that keeps the input's timestamp, and with
B-frames (decode order pts 0, 66, 33, ...) ExoPlayer dropped 58 of 90 frames as late. A few pictures are held back
(2, growing to 4 when one arrives older than one already out). An input whose picture is held keeps its buffer
(the codec owns it, as on a device); at end of stream those buffers carry the held pictures out in order, before the
end-of-stream buffer (`selftest clip.h264` checks every frame arrives). Other inputs that give no output are passed on
with API 34's BUFFER_FLAG_DECODE_ONLY, which Robolectric recycles without an output. Video output buffers carry a
reference to the decoded picture, drawn into the Surface's TextureView on releaseOutputBuffer(render=true), converted
by VideoToolbox at the view's size, up or down (3 pooled Bitmaps of that size per codec), so the raster thread blits it
1:1 (a new view size is taken once it has held for 10 pictures, so a window drag does not replace the pool on
every picture): a 640x360 clip in a 1600x900 view had Skia's upscale at ~40% of raster time (raster 9.4 → 5.9 ms on average,
`apksim://bitmaps?video=1` on `--device tablet`; `-Dapksim.videoAtViewSize=false` keeps the picture's size when
smaller); audio ones
carry 16-bit PCM. `ShadowSimAudioTrack` plays AudioTrack PCM (16-bit, 8-bit, float) on the Mac's default output
through a CoreAudio AudioUnit, and its playback head moves only as frames are played: Robolectric's counts everything
written as played at once, which would let ExoPlayer's audio clock race. `--jvm -Dapksim.mute=true` keeps the timing
and plays silence; `--audio off` turns sound and the audio decoders off (`--audio mute`, `--jvm -Dapksim.mute=true` and
`-Dapksim.audio=false` still work). `--video blank|off` and `video on|blank`: see "Per-instance costs". Bitmaps here are BGRA in memory (Skia's macOS order),
not Android's RGBA: write pixels in `VtH264Decoder.BITMAP_BGRA` order.
On Linux (issue 229) the video decoder is libavcodec (FFmpeg 7.1.5, only the decoders in the Dockerfile's
`VIDEO_DECODERS` and swscale: H.264 alone by default, so the published image lists no HEVC decoder and logs one line
saying so; `--build-arg VIDEO_DECODERS=h264,hevc` adds it, and `VideoToolbox.hevcAvailable()` finds it by opening one;
built in the Dockerfile's build stage and linked statically into `libvtdecoder.so` with private symbols:
`native/linux/avdecoder_linux.c`, the same JNI class). libavcodec returns pictures in presentation order, later than
the input that carried them, where VideoToolbox returns one per input and Java reorders; so behind
`VideoToolbox.REORDERS` (`!HostOs.MAC`) `Decoder` sends each input (`nativeSend`, with a sequence number in place of
its pts: two DECODE_ONLY inputs may share a pts), receives every finished picture (`nativeReceive`) into `held` with
depth 0, counts pictures still inside the decoder in `held()` so an input buffer is reserved for each, drains at end
of stream and resets on flush (`nativeReset`). Every such branch is behind that flag: the Mac path is as before. One
decoding thread (frame threads would add pictures of delay the 4 reserved buffers cannot carry); 1080p60 plays at 60
with no drops in both images (docs/linux-docker.md "Video" has the numbers). `selftest`'s order check there is that
every pts leaves once (it labels inputs in decode order). libwebrtc's H.264 stays Mac-only
(`VideoToolbox.webrtcAvailable()`), and Linux has no AAC/MP3 decoder or audio output. The build stage runs
`native/linux/avdecoder_check.c` on `testapks/media-fixture/clip.h264` (B-frames, pts, size, colours, a reset; `.h265`
too when HEVC is built, else it checks HEVC does not open).
Control commands: `video` (decoded/drawn counts, surfaces, MediaCodec counters with decode/draw ms, the video decoders
MediaCodecList lists (`listed`), and each live
codec's output: a view size, or `untracked` when the player moved it to its own placeholder surface, i.e. it has no
view to draw to), `webrtclog [info|verbose]`,
`selftest [file]` (native scaler, colour order; with a `.h264`/`.h265` file MediaCodec onto a Surface, with an ADTS
`.aac` or `.mp3` file MediaCodec into AudioTrack, checking the sound took real time and that releasing a track while
another thread is blocked writing to it returns the write (`ShadowSimAudioTrack` frees the stream only once no native
call on it is in progress: before, the writer read the freed stream); `scripts/make-media.sh` writes 2 s
clips to `work/media`), `nodes` (accessibility tree: text, hints,
fields, clickables with bounds, of the windows accessibility reports, so not what a dialog covers; `nodes all`: every
window; `tap-text` looks there too), `logcat on|off` (app's android.util.Log to stderr; `APKSIM_LOGCAT=1` at launch).
ELF natives whose stack arguments differ between Apple's and AAPCS64's conventions (more than 8 integer or 8 FP
arguments) are bound through trampolines in `jnibridge.c`; without them they read garbage and crashed. Variadic libc
functions an APK imports need a `THUNK` in `bionic.c` (AAPCS va_list) or they fall through to libSystem, which reads
the arguments the Apple way (`syslog` then crashed in `strlen`). `scripts/so-imports.py APK` lists the imports;
check new variadic ones against `bionic.c`. Condition variables keep the clock `pthread_condattr_setclock` gave them
(`CondShell`): Apple's `pthread_cond_timedwait` reads deadlines as wall-clock time, so a monotonic deadline becomes a
relative wait. It used to return at once, and two of WebRTC's threads spun a core each for as long as an app with
libjingle ran. `jnibridge.c`'s `fill_args` parks a native thread that calls into Java once the VM is exiting (JVMTI
then gives no signature; reading it crashed short test runs' exits while WebRTC's threads were starting). `syscall()` (`s_syscall`) handles Linux arm64 gettid, getpid, getrandom
and futex WAIT/WAKE (on `os_sync_wait_on_address`); everything else is ENOSYS. libc++abi's guard for function-local
statics needs the first two: with gettid -1 on every thread, two threads meeting at one static aborted an app now
and then at WebRTC start ("__cxa_guard_acquire detected recursive initialization", which it logged via syslog).
libjnigraphics (`native/jnigraphics.c`, the same file in the Linux build; `NativeBitmaps` for the Java side):
`AndroidBitmap_getInfo`, `lockPixels`, `unlockPixels`, `getDataSpace`, `compress`, and `getHardwareBuffer` (always
BAD_PARAMETER: no hardware bitmaps). They were unresolved stubs, so Fresco's postprocessed images (`nativeCopyBitmap`
locks two bitmaps) never loaded. Robolectric gives no pixel address, so the first lock of a Bitmap copies its pixels
(`copyPixelsToBuffer`) into memory of ours, ARGB_8888 turned from the Mac's BGRA into Android's RGBA; locks nest and
are shared across threads (one copy per Bitmap); the last unlock turns it back and, if it changed, copies it in
(`copyPixelsFromBuffer`, which bumps the generation ID GpuBitmaps keys its copies by). Java writes made while native
code holds a lock are not seen by it. Checked by `NativeBitmapTest` in the suite with `testapks/bitmap-fixture` (an NDK
library, built by its `build.sh` into the test app's `jniLibs` for arm64-v8a and x86_64; the `.so` files are committed).
Recorded HLS clips (4K H.264 at 20-25 fps plus 8 kHz AAC, checked 2026-11-03) play at their frame rate, in step
with the audio clock, and seek. Drops with sound on (0-7%) came from the stream: its audio timestamps jump ~240 ms at
segment joins (logcat: "Unexpected audio track timestamp discontinuity") and Media3 moves its clock to match.
Apps that sign in on every cold start can be signed in by apksim itself (`Autofill.java`): it reads
`work/autofill/<package>.properties` (mode 600, written by the owner), finds fields by their label/placeholder in
the accessibility tree, fills them, checks they stuck, presses `submit`, and logs field names only. Never read,
print or edit that file, and never type the owner's password: if it is missing, ask the owner.

## Per-instance costs (frame rate, video, audio, animations)
Launch options for what a journey may not need, each per instance and accepted by a warm launch (`Features.java`,
read again as a standby takes the launch, before the app's Application exists; else at first use). docs/parallel.md
has the measurements.
- `--max-fps N` (1-120) and `maxfps N` at run time: at most N frames a second, not 120 Hz or the display's vsync.
  The clock still follows real time and input and commands go out every 8 ms: the Choreographer's frame delay is the
  cap's interval (`Host.applyFrameDelay`; the first frame comes at once). Moving the clock only to an N Hz grid's
  lines put a tap's DOWN and UP a second apart at 1 fps, and ran a long-press timeout between them. Animations keep
  their speed in fewer frames. A video reaches the screen at most N times a second too (`apksim://video` at
  `--max-fps 10`: 10.1 screen frames a second), but every picture is still decoded and converted (59.9 a second for
  its 60 fps clip): `--video blank` saves that. The biggest lever for many headless instances: the test app's Shader
  tab, which never stops animating, costs ~0.7 of a core at 120 fps, ~0.4 at 60, ~0.2 at 30. The display's refresh
  rate the app sees is unchanged.
- `--video on|blank|off`: `blank` keeps the H.264/HEVC decoders listed and a player plays at its pace, but nothing is
  decoded or drawn (each input gives an empty output with its timestamp, in presentation order as before) and
  libwebrtc's frames are dropped before conversion (one an app asks for with addFrameListener is converted for it,
  not drawn); the TextureView stays empty. A MediaCodec takes the mode it is configured in, so `video blank`/`video on` at run time
  apply to the next codec (and libwebrtc's next frame). `off`: the decoders are not listed, as on a device without
  them (ExoPlayer then plays the clip's audio alone); launch only, since players cache the codec list. Test runs never
  list them anyway. On Linux the same switches apply to the libavcodec decoders.
- `--audio on|mute|off`: mute decodes and keeps the timing, silent; off lists no AAC/MP3 decoders and leaves
  AudioTrack to Robolectric (a launch with it cold never loads the CoreAudio library).
- `--anim-scale N`: the animator duration scale from the start (0: animations jump to their end), in running
  instances before the first activity and in test runs at each test's setup, since Robolectric resets it
  (`AnimScaleOptionTest`, CI runs it with `-e animScale 0`). Changes what a test sees: one that waits on an
  animation's middle fails, as on a device with animations off.
- `--gles on|off`: offscreen GLES (on in test runs by default; off falls back to Robolectric's stand-ins).
Not switchable, because unused features cost nothing: GLES loads at its first call, codecs and CoreAudio only when
the app opens one, WebRTC only in APKs that have it, network hooks one static read.

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
- One standby per apksim folder and sandbox flags (`--no-network`, `--no-sandbox`, and `b` for `apksim beam`'s
  instances, which the agent starts itself: docs/beam.md "The beam sandbox"), not per working directory.
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
- `-Dapksim.timing=verbose` prints finer startup marks (app info, classes, sandbox, application state, host setup),
  each with the process's CPU time so far (all threads).
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
the main looper (`AdbShell.onMain`). The port answers before the sandbox exists, so `shell:`/`exec:` requests are held
until the framework is ready (`AdbServer.frameworkReady`, from `Host.run`, or `SimRunner` in test runs; after 60 s:
`apksim: still starting, retry`): one that ran in that window initialised `android.os.Looper` on an adb thread outside
the sandbox and the app died with exit 10 (issue 189). Checked with adb 36 against the test app:
`adb shell input tap 733 1533` (FAB count goes up), `input swipe|text|keyevent` (BACK/HOME/POWER/MENU map to
back/home/lock/unlock; key codes by number too), `input tap|swipe` return once the Host has dispatched them, a swipe
after its duration, as on a device (they returned at once, and a 1.5 s hold lifted seconds later on a slow screen), `adb exec-out screencap -p > shot.png` (the display: `shot --display`, the app's pixels with the drawn soft keyboard, bars and banner over them when there is a window; headless it is the app's pixels, as `shot`; without `-p` raw RGBA),
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
`bluetooth`), `svc bluetooth enable|disable` and `cmd bluetooth_manager enable|disable|wait-for-state:STATE_X` (=
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

## apksim serve (APKs in a browser tab)
`./apksim serve [--port 8080] [--bind ADDR] [--token FILE] [--max 4] [--idle 600] [--apps DIR|FILE]... [--max-upload MB]`:
an HTTP server whose page (one self-contained file) runs a listed or uploaded APK per tab and streams it; docs/serve.md
is the spec, the security model and the measurements. Each tab is an ordinary launch (`--headless --fresh --control
auto`, warm on the Mac when the standby is ready, cold on Linux), read from its stderr (`[apksim] control port N`;
the rest goes to `STATE/serve/logs/<session>.log`). Serve opens two control connections: commands, pipelined, and
`stream`, after which `FrameStream` sends each presented frame that differs from the last as a JPEG (Android's encoder,
on its own thread; `redraw` makes the Host draw one for a new subscriber), or as H.264 when the tab asked
(`codec=h264`, sent by the page when WebCodecs decodes it) and the instance has an encoder (VideoToolbox; OpenH264
in the image), answered `c h264`/`c jpeg`: no reordering, keyframes only at the start, a size change or a `key`
request, and a still screen encoded twice more to sharpen it; `--bitrate` (4000 kbps), `--codec jpeg` turns it off.
Serve relays frames with at most two in flight per tab (the page acknowledges each one decoded and shows it at its
next display refresh, from a queue of at most two, pacing the instance to that display with `vsync`; with H.264 a tab that
falls behind drops to the next keyframe, which serve asks for; when that repeats, the tab decoding slower than the app
draws, serve sends the instance `pace MS` and `FrameStream` encodes at most one new frame per MS, the newest, so the
stream stays a delta chain at the tab's rate instead of freezing on keyframes: `serve.Pacer`, issue 232, checked on a
simulated clock by `scripts/check-pacer.sh` (CI) and end to end by `serve-check.py`'s slow tab (Mac only: the Linux
image's CPU shader is too slow to outrun a tab); `s` stats `keyDrops`, `paceMs`, `busyMs`, `tabFrameMs`), and passes on only input and device commands
(`Session.check`): never `shot`, `record`, `pick` or anything else that reads or writes files. Loopback by default;
another `--bind` needs `--token`; WebSocket upgrades and POSTs from another Origin are refused, and on loopback a
non-loopback `Host` header (DNS rebinding). Instances are never given `--adb` or `--control-bind`. A session ends when
its tab closes, after `--idle` seconds without input, or when the app exits; its data slot is then emptied. Instances
run with `--no-autofill` (never the owner's saved sign-ins) and `--data-slots serve` (`Storage.dataDir`: slots
`serve-N`, so `--fresh` never empties an ordinary launch's `default`), and the control log shows typed text only as its
length.
`/api/health` needs no token (503 with the reason when a session could not start). `--allow-origin ORIGIN` lets a
trusted page frame it (`/?embed=1&app=ID&token=...`: screen and device controls only, the token kept in memory and sent
as `?token=`, since a cross-site frame gets no SameSite cookie) and open its WebSockets. `POST /api/apps {"url",
"headers", "options"}` downloads an APK in the background (adds of one URL and headers share a fetch in progress and
reuse a finished one for 5 minutes: `Catalog.sources`, keyed by a hash, issue 162) and answers its id at once (`GET /api/apps/ID` for its state;
a session opened on it meanwhile shows `p downloading N%`); uploads may be chunked; catalog lines take ` -- ` launch
options. Apps' launch options are limited to `Catalog.LAUNCH_OPTIONS` (activity, Build fields, settings...: nothing
that touches files, ports or the JVM). Behind a reverse proxy (issue 143): `--trust-proxy` takes X-Forwarded-Host/-Proto
from a loopback peer as the browser's (Origin is checked against them, and the loopback Host check is skipped),
`--public-origin URL` accepts that origin as serve's own and prints links with it, `--base-path /sim` serves everything
under a prefix (a proxy that strips it works too); the page builds every URL relative to its folder, and the token
redirect is relative. WebSocket pings go to every session every 20 s.
`--allow-beam` (docs/beam.md): the page offers "On this Mac" when the visitor runs `apksim beam`, which runs the app in
the beam sandbox and streams it into the same canvas; the server hands the page a signed ticket (`BeamTicket`) with
which the agent downloads the APK itself (`GET /api/beam/apk`, no token), the tab relaying it when that fails. A move
to the Mac "With its data" sends `handoff` on the server's socket: the app quits, its slot is packed, and the agent
downloads it with a ticket (`GET /api/beam/data`) into the slot the app starts in there.
Files in and recordings out (issue 187, docs/serve.md): `POST /api/sessions/ID/files?path=PATH` writes the body into the
running app's storage (`--max-push MB` a file, default 16; 64 MB a session); the server reads the upload and sends the
instance `devpush` (`TabFiles`), so no host path is named. With `--allow-rec` (off by default: a recording holds the
app's network calls and logs) a tab may send `rec on|off [CHANNEL...]`, `rec checkpoint now` and `rec save [SECONDS]`,
never a file name: the server writes `STATE/serve/logs/rec-ID/RID.apkrec`, kept a week with the logs, and `GET
/api/sessions/ID/recordings/RID` streams it. `apksim beam` has the same routes under `/beam/sessions/ID/...` with the
page's `Origin` and a per-session `key`, recording always allowed; those were not run when this landed.
`scripts/tabfiles-check.py URL --token FILE` checks the serve routes.
Kept sessions (issue 221, docs/serve.md "Kept sessions"): with `--keep-max N` (0 by default, off) and `--keep-days N`
(14), `/ws?keep=KEY` runs the app in a seed slot (`ks<hex>-0`, which beam's sweep never takes) of the server's naming and, when the session ends, packs
it with SlotPack into `STATE/serve/kept/<HMAC of KEY under the token>/<package>/` (`KeptStore`); the next session with
that key on the same package starts on it (`reopened`, or `copied` for another build with the same signer, `refused`
for another signer), told to the tab as `d {"kept":...}`. One session per key (`busy`, or `keepBusy=refuse`: 409);
`GET`/`DELETE /api/kept/KEY`. `scripts/kept-check.py URL --state DIR` checks it.
`scripts/serve-check.py URL [--token FILE]` drives it as a tab would and prints first-frame time, latency, fps and
memory; `ci-smoke.sh` runs it against the image. Measured 2026-11-05 (test app): Mac first frame 0.26-0.45 s warm,
tap to frame ~25 ms, Shader tab streamed at 120 fps (4 ms encode, ~100 KB a frame), 370-620 MB resident per session;
Linux image (Rosetta) first frame 6-10 s cold, tap to frame 44-61 ms, ~700 MB a session. The page's latency figures
match the tab's clock to the server's (`t` messages), so frame times compare across machines.

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
`HarnessTestApplication` (`RunnerApplicationTest`). The runner's onCreate runs too, once a process at the first
test's setup (`TestRunnerSetup`): up to its `super.onCreate(arguments)`, which the translator sends to
`TestRunnerSetup.superOnCreate` so it keeps the arguments the runner passes on instead of starting a run. The
listeners those arguments name (`listener`, which a report library's runner adds to) and any `-e listener` hear the
run through `TestRunnerSetup.LISTENERS`, as AndroidJUnitRunner registers them; they join at the first test, which
they hear started (tests ignored before it are replayed to them), and the arguments the runner passed on are the run's
from then on. Only a runner's own onCreate runs (AndroidJUnitRunner's would start a run), after `Instrumentation.init`
with the test APK's and the target's contexts. A listener that throws stops hearing the run and the run fails (exit
status), as JUnit drops a failing listener. A suite whose every test is ignored reaches no test setup, so its runner
and listeners are not set up. Robolectric resets its providers before JUnit reports a test's end, so the Test Storage provider
(`SimTestStorage`: test_outputfiles, test_runfiles, internal_use and testargs, files under `--test-outputs DIR`,
default `cache/test-outputs` in the state folder) is registered again before each listener call. The test app's
`HarnessRunner` adds `RecordingListener` (`RunnerListenersTest`).
`-e class a.B#m,a.C`, `notClass`, `package`, `notPackage`, `log true` (list only) work (in `class`/`notClass`, `\,` is
a comma inside a name and `\\` a backslash, for Parameterized's `test[0: x, y]`: an apksim extension, which
`instrument.py` writes when it names tests; any other backslash is literal), and every `-e` reaches
`InstrumentationRegistry.getArguments()`; `--device tablet` is 2560x1600 at density 2, the emulator's Nexus 10.
Each test gets a fresh Application and data folder (the app's persistent data is not used), but app statics carry over
within a process. `scripts/instrument.py APP TEST [--isolate] [-j N] [--shards N [--timings XML] [--shard I]]
[--xml FILE] [--record-deps FILE | --affected-by FILE --changed PATH...|--diff REV] [--test-timeout SEC]
[--order interleave|longest-first|suite] [--exit-marks] [--reap-early SEC] [--run-ignored] [-- APKSIM_OPTS]` runs a
suite and writes JUnit XML (with each test's time, from when its output arrived); `--isolate` gives every test its own
process, as the AndroidX Test Orchestrator with `clearPackageData` does, except that a test `@Ignore`'d on its method or
class is reported ignored without one (its listing passes `-e apksimListIgnored true`, which makes `Instrument`'s `-e log
true` listing report such tests ignored rather than passed; an assumption's skip still needs its process;
`--run-ignored` gives them one, as the Orchestrator does; the app's runner listeners hear of them neither way, as a
process whose every test is ignored sets none up; issue 205); `--shards N` splits the suite over N processes
at once with AndroidJUnitRunner's `-e numShards N -e shardIndex I`, which `Instrument` takes as a device's runner does
(a test's shard is `abs(description.hashCode() % N)`, AndroidJUnitRunner's `abs(hash) % N` except for a hash of
`Integer.MIN_VALUE`, which matched no shard there; an empty shard is a run of no tests); with `--timings XML` (an
earlier run's JUnit XML) the shards are balanced by measured time instead, longest first, each named with `-e class`,
and `--shard I` runs only one of them (a CI matrix); with `--isolate` the tests' start order is `--order` (default
`interleave`: the longest and shortest remaining alternate, so a machine's heaviest tests do not all start together and
push one another past a `Timeout`; `longest-first` was the old order; docs/affected-tests.md). `--exit-marks`
(`-Dapksim.exitMarks=true`) prints under each isolated test where its process's exit went (`exit marks`: profile,
class cache, scratch removal, the non-daemon threads left; `halt marks` from `Standby.halt`: net flush, next standby,
cache sweep), and makes apksim print `APKSIM_EXIT: STATUS` right after `INSTRUMENTATION_CODE`; `--isolate --reap-early
SEC` takes that line as the process's end, frees its `-j` slot and finishes the teardown in the background, killing the
process group after SEC s (off by default; everything a test leaves, bundles, recordings, outputs, listeners, is
written before it; only the class cache save, scratch removal and cache sweep come after). `--isolate` keeps `--prewarm`
(2 x `-j`, or N) processes booted ahead (`TestPool`, launcher `--pooled`: boots the sandbox through a probe with no runner, then reads its test's
`-e` pairs on stdin), one test each as before; `--prewarm off` starts each cold (docs/test-pool.md).
`scripts/flake-hunt.py` reruns chosen tests under shuffled order (`-e apksimOrder`), isolation, CPU load and
`--realtime-clock`, and reduces order-dependent failures to their culprits by ddmin (docs/flake-hunt.md). `--record-deps FILE` records which of the app's and tests'
classes each test runs code of (`-Dapksim.deps=true`: the translator adds a probe to every method of the app's
packages, `dex/Probes`, and Instrument's `DepsListener` reports each test's classes on stdout), and `--affected-by FILE
--changed PATH...|--diff REV` runs only the tests that reached a class compiled from a changed `.kt`/`.java` file (by
DEX source file, package and Kotlin's SMAP), and the whole suite for any other change (docs/affected-tests.md). Test
runs stop the JIT at C1 (the launcher adds `-XX:TieredStopAtLevel=1` for `--instrument`, before `--jvm` options): ~40% less CPU at the same
wall time on a large app's suites, and no cliff when more processes run than there are cores (docs/parallel.md). Without it, a process that ends before every test has a result (a native crash or abort, or
the Timeout watchdog below) does not end the run: the test that was running is reported failed with the exit status
and the stderr tail (with none running, a failed "(run)" entry is, so the run still fails), the suite is listed (`-e log true`, the run's own `-e` arguments, only once a process has died),
and the tests with no result run in a new process, as `-e class` (whole classes where none of their tests ran, else
Class#method, chunked under Linux's 128 KB argument limit) with the other `-e` arguments kept. No test runs twice; a
new process that adds no result (a crash at startup) ends the run, the rest reported failed as not run.
`--test-timeout SEC` (default 900, 0 for none) handles a hang the same way: a process that goes SEC s without a test
starting or ending (stuck in teardown, or out of the watchdog's sight) gets a SIGQUIT (the JVM's thread dump: the
stacks of the threads in JUnit code go in the failure) and then SIGKILL to its process group (each process gets a
session of its own, so instrument.py forwards Ctrl-C/SIGTERM itself: a process starts and is noted for that under one
lock, and none starts once a stop has begun), and with `--apksim "docker|podman run ..."` a `docker rm -f` (by a
`--cidfile` instrument.py adds: SIGKILL to the client leaves the container running; the ID is read before the client is
killed and the client killed before `rm -f`, and a client that dies of any signal has its container removed too; each
process gets a cidfile of its own, as docker refuses one that exists, so a `--cidfile` in `--apksim` is taken out and
gets the ID of the last container to end); the test
fails "timed out after SEC s" with the dump's JUnit threads first (each whole from its header down, cut at the
bottom), then the stderr tail, which starts at a stack's header rather than inside it; a stack at the end of stderr
need not be where the process is stuck (issue 115's was CloseGuard reporting, at a GC, a descriptor a listener's
executeShellCommand returned and never closed), and with `--bundles` gets process.txt. Test runs launch cold, so
the process is the JVM itself (the launcher execs it), never a standby's client.
`ProcessDeathTest` checks it with `-e crashOnPurpose true` (otherwise its two crashing tests are skipped): one halts
the process, one ignores interrupts past its timeout, and the tests after each still pass; with `-e hangOnPurpose
true` one more hangs with nothing to end it but `--test-timeout`. `instrument-check.py` covers both against a stand-in
(hangs one process, sharded, isolated, through a stand-in docker, and Ctrl-C, also while a process starts).
**Failure bundles** (`--bundles DIR`, also `instrument.py --bundles DIR`; `Bundles`, docs/plan.md phase 5): each
failing test leaves `DIR/<class>/<method>-<8 hex of SHA-256("class#method")>/` with `failure.txt` (stack, settings,
which capture point wrote it, how long each part took), `screen.png` (every window, drawn as UiAutomation's screenshot
is), `windows.txt` (every window, those accessibility leaves out marked `covered`), `nodes.txt` (as the `nodes`
command: what UiAutomator could reach), `logcat.txt` (that test's lines only: `AdbLog` is installed
for the run), `threads.txt`, `looper.txt` (the main queue and androidx.test's activity stages) and `repro.sh` (the
launch narrowed to `-e class` that test, from `-Dapksim.argv`). Where it captures matters: listeners hear of a
failure after `@After` and rules have closed the activity, so `SimRunner`'s helper runner wraps `methodInvoker`
(the body), the handler Espresso has at the start of each body is wrapped through its holder (`Espresso.BASE
.failureHolder()`: the wrapper captures, then hands the failure to that handler, so a test's own still decides; a
scenario closed by `use {}` is gone by the time the exception leaves the body; a test that catches an Espresso failure
and passes loses its bundle), the listener takes the rest (@Before/@After/rule failures, classes with their own
`@RunWith`; with `--bundles-on all` it rewrites a body's "passed" bundle when teardown then fails), and
`FailOnTimeout`'s watchdog writes the failure, threads and log before it ends the process. A capture must not change
what the test does next, and reading the tree does: Compose's node provider adds a child view (AndroidViewsHandler) to
the AndroidComposeView and builds its accessibility caches, and a test polling past a handled Espresso failure then
failed (#73; `FailureBundleHandledTest`). So the Espresso handler's capture leaves `nodes.txt` for later (`nodesPending`:
a placeholder until then) and reads it once that screen cannot matter: when the failure leaves the body, as an activity
finishes (`ShadowSimActivity.finish`, before it: that activity's captured windows only, e.g. a `use {}` scenario
closing, as another shown below stays in use), or at the listener; each read is appended with a line saying when and
whose windows, until every captured window is read (`pendingRoots`), and failure.txt's parts get its time. Body and listener captures read it at once. A capture never runs the
main queue or moves the clock (uptime is read through `ApksimClock`, not SystemClock, which `--realtime-clock` would
advance); a part past its deadline (screen 2 s, others 5 s) has its thread interrupted and is skipped in later bundles;
a test's first 2,500 log lines are kept as they arrive (`AdbLog.keepHead`), past the ring's 20,000. A bundle's folder is
emptied before it is written. `--bundles-on all`, `--bundles-max N` (default 50 full bundles a process),
`--bundles-redact REGEX` (instrument.py applies it to process.txt and index.html's previews too, with Python's re:
`\Q...\E` is translated, other syntax must be what both read, and one Python cannot read stops the run before it
starts). A bundle follows the test's outcome: a different exception ending the test after a handled one rewrites it
(Espresso's handler throws its own exception for what it got, which counts as the same failure); with
`--bundles-on all` a test that passed, including one whose class has its own runner or whose body threw what
`@Test(expected)` expected, ends with a bundle that says passed.
**Test movies** (`--record failed|all --record-dir DIR [--record-size WxH] [--record-fps N]`, also `instrument.py`, which
links them from the JUnit XML like bundles; `TestRecorder`, `scripts/record-check.py`, issue 132): Motion-JPEG
`DIR/<class>/<method>-<hash>.avi` (`MjpegRecorder`, so headless and in the Linux image too), kept for a failed test (all
with `all`) and deleted otherwise. A test run has no display loop, so `ShadowSimViewRootImpl` offers a frame after each
window traversal and `TestRecorder` draws the screen (`Bundles.screenBitmap`) at most N (default 15) times a second of
real time, fitted into WxH (default 1280x720, never up); the writer repeats the last frame, so a movie runs in real time
(the simulated clock's idle jumps do not show), and a test that never shows a window has none. A recording must never
fail a test (issue 144: a capture taken inside `ViewRootImpl.performDraw` through HWUI's `HardwareRenderingScreenshot`
never returned from `nSyncAndDrawFrame`), so a frame is taken from a main-thread message posted after the window's draw
(never inside one: `ShadowSimViewRootImpl.drawing`), drawn on a CPU canvas (no render thread; elevation shadows are not
drawn, unlike a bundle's screen.png), and a frame slower than 2 s ends that test's movie with a line on stderr.
Not done: H.264/mp4, `adb shell screenrecord`; a process that dies mid-test leaves an unfinished (index-less) movie.
`instrument.py` removes an earlier run's bundles from DIR first, adds `process.txt` for a test whose process died, links bundles from the XML (a property, and
`[[ATTACHMENT|...]]` in `system-out`) and writes `DIR/index.html`; with `--apksim` it reads them from
`--bundles-local`. The sandbox allows writes to DIR. As root (the image) the folders are made writable by everyone,
for the host's `instrument.py`. `scripts/bundle-check.py` (CI) checks it with the test app's `FailureBundle*Test`
classes, which fail only with `-e failOnPurpose true` (`watchdog` for the one that ends the process). Two things this needed in `SimRunner`: one shared injector (so one sandbox: a second
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
The native loader's is `testapks/loader-fixture` (`loaderfixture.c`, built with the NDK by its `build.sh` into the
test app's `jniLibs` for arm64-v8a and x86_64; the `.so` files are committed so the test app needs no NDK), run by
`NativeLoaderTest` in the suite, so on the Mac and in both Linux images: the stack guard through the thread pointer,
TLS on several threads with registers live across the access (TLSDESC on arm64), barriers (reused, destroyed at once), mutexes and condition
variables at 4 mod 8 (boxed on both Linux architectures), thread attributes with `pthread_getattr_np` and an app-supplied stack, and setjmp/longjmp into bionic's jmp_buf. It is
built for API 29, so it also has Android's own RELR tags (`DT_ANDROID_RELR`, which the NDK writes for API 28-29
libraries); the loader once skipped them and called an unrelocated initialiser. Beside it, `pagefixture.c` builds two
libraries linked for 4 KB pages: `libpagefixture4k.so` (code and RELRO on one 16 KB page: must load and run on the
Mac) and `libpagefixturenorelro.so` (`-z norelro`: code and writable data on one page: refused on the Mac by default,
moved with `--move-shared-data`), checked by `NativeLoaderTest` too (the move tests run on the Mac only with the switch,
whose property the test reads; the refusal test only without it) (relocated pointers, .data, .bss, an exported variable through the GOT and
dlsym, a load (literal) and an adr in assembly, and through dl_iterate_phdr: .data's PT_LOAD, PT_DYNAMIC, DT_PLTGOT's
live GOT and the symbol's st_value); `pagefixturefar.c` (moved data out of an adr's reach: an UnsatisfiedLinkError
naming the entry on every try) and `pagefixturecxx.cpp` (a C++ exception caught with its personality slot moved).
`build.sh` checks each still has code and data on one 16 KB page (issue 137). When the loader or a bionic
shim gets a new case wrong, add it there; a device passes every check.
UiAutomator works (`ShadowSimUiAutomation`): getWindows lists the windows `AccessibleWindows` reports (under a
Dialog, only the dialog, as on a device: issue 97), getRootInActiveWindow the topmost focusable one. Each window gets an
accessibility window id (`AttachInfo.mAccessibilityWindowId`, which the system assigns on a device): a node's
equals/hashCode include it, and without it every window's root was equal to every other's, so UiDevice (which keeps
the roots in a set) searched only the active window. Node bounds are on the screen for a window not at 0,0 (a
centred dialog): AccessibilityInteractionController takes the window's position off each node and applies the
window-to-screen matrix the accessibility service gives (`ShadowSimAccessibilityManager.getWindowTransformationSpec`;
Robolectric's is the identity, so a dialog's nodes sat at the top left and clicks missed). Each window's root node is
linked to its views with
`setQueryFromAppProcessEnabled` (API 34), so UiAutomator walks the real view and Compose tree through the framework's
AccessibilityInteractionController (By.text/desc/res with testTagsAsResourceId, clicks and gestures by injection,
setOrientation* as a real configuration change, waitForIdle advancing the simulated clock frame by frame until the
UI is quiet, for its quiet time at most: an open Compose Popup polls its position every frame, and with Robolectric's
unpaused Choreographer that frame loop never let the looper idle). UiAutomator waits for a Choreographer frame after each step of `UiDevice.swipe`/`drag`, `UiObject.swipe*`, `UiScrollable` and multi-pointer gestures (`InteractionController.waitForNextFrame`: a post to the main looper, then `latch.await(5 s)`); on the main thread that post never ran and each step waited out 5 s (a 20-step swipe took 100 s), so the translator sends that await to `TestActivities.awaitFrame`, which runs the main queue one task at a time until the frame has been drawn, checking the latch and timeout between tasks (an open Popup's frame loop never let `idle()` return) (`UiAutomatorTest.swipeScrollsTheListWithoutWaitingForFrames`, `PopupTouchTest.aSwipeWithAPopupOpenWaitsOnlyForFrames`). No accessibility events: executeAndWaitForEvent reports a window change after the action settles,
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
  Dispatchers.Main coroutine had not sent yet otherwise waited out its timeout (`MainThreadSleepTest`). Work Compose's
  test library hands to `runOnUiThread` (its clock's frames, runOnIdle) counts as a dispatch, as it is a main-thread
  message on a device: a composable's `runBlocking` in a frame the clock stepped ran a window frame from inside the
  measure, which Compose refused ("performMeasureAndLayout called during measure layout", `ComposeBlockingReadTest`).
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
  the control commands it reaches are animscale, fontscale, network, bars, timezone, bluetooth, thermal and camera
  (`ShellCommandTest`, `ThermalTest`), and `pm grant|revoke PKG PERMISSION` (Robolectric's granted set, reset at the next
  test's setup; `CameraTest`).
- The framework's wall clock is the real one (`SimRunner.WallClock`): Robolectric pointed System.currentTimeMillis
  in framework code at its uptime clock, so the framework thought it was 1970 while the app's own code and the JDK
  knew the date (DateUtils.isToday(now) was false). The uptime clock stays simulated, and the wall clock does not jump
  when it skips idle time. `--timezone ZONE` sets the zone at each test's setup (`TimeZones`, as in running
  instances), and a test's `executeShellCommand("apksim timezone ZONE")` changes it until the next setup
  (`WallClockTest` with `-e timezone ZONE`).
- `--realtime-clock` (opt-in, test runs only; `RealTimeClock`): the simulated clock follows real time instead of
  skipping idle time, for suites written for a device whose timed UI must last (a banner that closes itself after
  5 s). By default Robolectric's Choreographer is not paused in a test run, so each frame asked for moves the clock
  15 ms and runs at once, and one Espresso check ran every frame of a running animation, closing the banner first.
  With the switch the Choreographer is paused as Host runs it, each frame asked for queues an asynchronous vsync
  message (`ShadowRealTimeDisplayEventReceiver`), a main-thread clock read moves the clock up to real time
  (`ShadowRealTimeSystemClock`), a looper held by a traversal's sync barrier waits up to 100 ms for that frame
  (Espresso posts its check behind the barrier, drains, then blocks on it: without the wait it never ran) until
  barriers have kept coming back for 250 ms with gaps under 100 ms (an endless animation), after which a barrier with
  nothing due behind it counts as idle, as on a device; neither a message at the head between frames nor work due
  behind one barrier starts that streak again (issue 131: a 16 ms ticker made an Espresso swipe's nested idle wait
  frame after frame until the test's timeout; `RealTimeSwipeTest`), and
  anything moving the clock past real time (idleFor, SystemClock.sleep) waits for it. Off, nothing is registered.
  The test app's suite takes 13 s with it against 5.6 s (`RealTimeClockTest`, run with `-e realtimeClock true`).
  Short waits (`waitOutBarrier`, the wait for real time after `idleFor`, `pumpFor`'s steps) sleep only up to a margin
  before their deadline and spin the rest (`RealTimeClock.waitUntil`) where the OS overshoots sleeps: macOS VM CI
  runners woke a 16 ms sleep ~115 ms late, so a tap's idle took seconds (issue 201). `--precise-waits on|off|auto`
  (default auto: five 5 ms sleeps probed on first use; margin 4x the median overshoot, 200 ms at most, none under
  3 ms; one stderr line says so). Costs a core while a main thread waits. `-Dapksim.barrierStreakMs=N` sets the
  250 ms streak (issue 215): a DrawerLayout settling through ViewDragHelper's Scroller (no animator) looks like an
  endless animation for that long, so an Espresso click right after `openDrawer` acted mid-slide and missed
  (`DrawerSettleTest` on `apksim://drawer`, `-e drawerRepro true -e realtimeClock true`: fails at 250, passes at 500
  and 1000 with `RealTimeSwipeTest` still passing; the default is unchanged, and spotting a running Scroller is not done).
- `robolectric.windowManager.useRequestedSize`: a window gets the size it asks for, so a Compose Dialog
  (WRAP_CONTENT) is not measured at its width and then at the display's on every traversal (it never got idle).
- Processes started together share one per-APK cache folder (`work/cache/<apk name>-<content key>`, `ApkCache`):
  `ElfLoader` extracts each `.so` to a per-process temporary file and renames it into place (and skips it by the
  entry's CRC, kept in `<name>.so.crc`), and `AppInfo` writes the manifest the same way. Copying in place let one of
  four `--isolate` processes load a library another was rewriting (`NoClassDefFoundError` for a bundled SQLite
  driver's native library, on the first test after a rebuild).
- The framework's 8 dp touch slop (`Host.useDeviceGestureThresholds`), JniConstants warmed on the main thread (Coil's
  ImageDecoder on a worker aborted the process), and receivers registered before a test's reset can be unregistered
  (WebRTC's NetworkMonitor, a static, aborted at teardown otherwise).
- Before Application.onCreate, as installing and ActivityThread do (`InstallState`, in running instances too: see
  "Simulator features"): `Settings.Secure.ANDROID_ID` is set
  (Robolectric leaves it null), every requested permission of protection level normal is granted (runtime ones too
  with `--permissions grant`; signature, privileged and internal ones stay denied unless the app defines them, with
  the framework's levels read from its own manifest, which Robolectric's package manager lacks), and the app's content
  providers are created, highest initOrder first (AndroidX Startup's initializers run; not for the runner probe's
  plain Application; a provider that fails fails the test's setup, as it crashes a device's app start, unless
  `-Dapksim.providerFailures=log`). `--network-profile offline` still holds through it (`NetConnectivity.networksReplaced`). The host's network is connected and VALIDATED (`HostNetworks`), as an app checks.
- `getInstrumentation().getContext()` is the test APK's own context (its package and assets: JSON fixtures), through
  the translator (`TestActivities.instrumentationContext`).
- JUnit's `@RunWith(Parameterized)` runs inside the test environment, each parameter set as a SimRunner child
  (`SimParameterized`), so a constructor can read the instrumentation's arguments. `@Parameters` is called once;
  `@BeforeParam`/`@AfterParam` run around each set. Not yet: `@Parameters` itself runs before the test environment
  exists (a factory reading the instrumentation's arguments or the app's resources fails), class fixtures run once
  per set rather than once, and the test APK's context is published after the Application's onCreate (an Application
  or provider calling getInstrumentation().getContext() at startup gets the app's).
- bindService to a service no installed app has returns false and never calls back, in running instances too (see
  "Bound services"). `LockSupport.parkNanos` on the main thread in a test run
  (`runBlocking`'s event loop) runs the main queue meanwhile, the clock following real time, where `Thread.sleep` does;
  so does `CountDownLatch.await` (`TestActivities.await`: a test waiting for a post or a listener on the main thread
  timed out, `LatchConformanceTest`). All three run the queue's due tasks one at a time (`runDue`), checking the
  latch, an interrupt and the deadline between them, not `ShadowLooper.idle()`: an endless frame loop (a Compose
  Popup, an animation that never ends) never lets that return with Robolectric's unpaused Choreographer, nor a
  Runnable that posts itself again in either clock mode, and a wait or sleep under one never ended.
- Views draw after each traversal (`ShadowSimViewRootImpl`: recorded into a display list that is dropped, so
  View.computeScroll runs and Scroller-driven moves finish), a focusable window on top (a dialog) takes the window
  focus (`ShadowSimWindowManagerImpl`: Espresso looks in the focused window), and Espresso's
  loopMainThreadUntilIdle also waits for the Compose rule's idling resource, as it is registered on a device.
- Compose lazy layouts prefetch: after a scroll the item past the viewport is composed and measured in idle time, as
  on a device (a suite counted a second "loading more" row only one of which was on screen). Compose picks a
  scheduler that does nothing when `Build.FINGERPRINT` is "robolectric" (`PrefetchScheduler_androidKt`); the
  translator sends that one read to `TestActivities.prefetchFingerprint`, so test runs get Compose's own
  `AndroidPrefetchScheduler` (view posts; `drawingTime` is 0 here, so every run counts as an idle frame, and a request
  past the first in a run waits for the next Choreographer frame). FINGERPRINT itself stays (`LazyPrefetchTest`).
  Running instances keep Compose's choice, so they do not prefetch (not yet measured for frame pacing).
- `--start-activities` (opt-in, `-Dapksim.startActivities`): a start of one of the app's activities that no running
  one takes launches it, as ActivityThread does (the one below is paused, then stopped), and NEW_TASK | CLEAR_TASK
  finishes the task's others; by default Robolectric only records the start, which Robolectric-style suites check.
  The stopped activity's window is hidden until it comes back (also for back on a launcher root, which sends the task
  to the background), so UiAutomator, UiAutomation's windows and screenshots, and failure bundles see only the top
  one, and gets the window focus back when it returns (`StackWindowsTest`). UiDevice rotation puts the windows back
  in the stack's order afterwards (a recreated activity's window is added last) and the focus on the top one.
  Robolectric's own stops (ActivityScenario.moveToState) leave windows as they were, and any resume shows a hidden
  window again with the focus (`ShadowSimInstrumentation.callActivityOnResume`), so `moveToState(RESUMED)` after one
  of apksim's stops brings the screen back.
  Not yet: a start for a result (startActivityForResult, ActivityResultContracts) is still only recorded, so a flow
  that waits for the started activity's result needs the test to supply it, as with Robolectric.
- Mockito (`MockMakers`, `--mock-maker MODE`, `-Dapksim.mockMaker`): a test APK's
  `mockito-extensions/org.mockito.plugins.MockMaker` names a maker for ART (mockito-android's, dexmaker's), whose first
  mock threw "Plugins.registry is null" here; `AppClasses` serves that resource through MockMakers. `auto` (default)
  swaps an ART maker for `mock-maker-subclass` (translated classes are JVM classes), `keep` leaves it, `subclass` or
  `inline` force Mockito's, anything else is used as given. `inline` needs Byte Buddy's agent (`--jvm
  -Djdk.attach.allowAttachSelf=true --jvm -XX:+EnableDynamicAgentLoading`) and mocks JDK types only: translated
  classes have no stack map frames, which a retransformed class needs (VerifyError). Final classes cannot be mocked
  (`MockitoTest`).
- In app code everywhere (the translator): `KeyStore.getDefaultType()` is "BKS", Android's, with Bouncy Castle added
  after the JDK's providers (`KeyStores`), and AES/GCM set up again with the same key and IV is allowed, as Android's
  provider allows it (`Ciphers`: unsafe, but what the app gets on a device); `android.net.ssl.SSLSockets` supports
  the JDK's TLS sockets, so OkHttp's Android platform sets ALPN and offers HTTP/2 (`ShadowSimSSLSockets`).
Not supported yet: `adb shell am instrument` of a test APK (only of a self-targeting instrumentation, see "adb device"), shell commands that need another process or the Host loop (input, screencap, `am instrument`), and
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
`docker/Dockerfile` builds a headless image for linux/amd64 (`scripts/docker-build.sh`; Colima with Rosetta on this
Mac) or linux/arm64 (`scripts/docker-build.sh TAG --platform linux/arm64`: native in Colima here, no Rosetta).
`HostOs` picks `.so` vs `.dylib` and the APK ABI (`x86_64` on x86_64 Linux, `arm64-v8a` on the Mac and arm64 Linux);
on Linux the window, Metal and VideoToolbox are skipped (headless, CPU shaders; MediaCodec's video decoders are
libavcodec there, see "Video"). `native/linux/` holds the
bionic→glibc shim and a JNI bridge; `elfloader.c` is shared, with `#ifdef`s. On arm64 Linux, glibc owns TPIDR_EL0, so
the loader rewrites Android code's `mrs xN, tpidr_el0` into `adrp` of a read-only page after each module (stack guard at
+0x28, TLSDESC offsets from it), and the shim boxes `pthread_mutex_t`/`pthread_attr_t` (glibc's are bigger there).
Robolectric's Android 15+ runtime is android-all's `libandroid_runtime`; our android-all build has none for
linux/aarch64, so arm64 images take it from its respin (`gradle linuxArm64Runtime` into `work/linux-arm64`, see
`build.gradle.kts`). Google's Android build tools are x86_64-only on Linux: arm64 CI jobs get the test APKs from an
x86_64 job, and have no adb. Use JDK 24 there: JDK 25's AOT record mode rejects translated classes. See docs/linux-docker.md.
zlib imports (`libz.so` is a system library to the loader) resolve in the host's `libz.so.1` (`bionic_lookup`'s last step): a Temurin JVM keeps
its zlib private, so `dlsym(RTLD_DEFAULT)` found none and inflate/deflate were stubs (issue 148). Not built or run on a Linux host yet.
Colima shares only the home folder with its VM, so mount folders under it (`work/...`), not the scratchpad in
/private/tmp: those mounts are empty in the container. Tag images you build for a check with a name of your own
(`apksim:ci` and `apksim:latest` are shared). Releases publish the image as `ghcr.io/aglose/apksim:VERSION`, both architectures under one tag (`release.yml`'s
`image` and `image-arm64` jobs push `VERSION-amd64` and `VERSION-arm64`; `:latest` moves on publishing), with `instrument.py`, `snapshot.py` and `drive.py` on its `PATH` for other
repositories' CI (`--entrypoint instrument.py`, `--entrypoint snapshot.py`). `.github/actions/linux-build` is the Linux build both workflows share.

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
GitHub ruleset requires a PR whose `linux-smoke` and `startup-gate` checks pass (`.github/workflows/ci.yml`: builds the
runtime and the Linux image, then runs the test app headless in parallel legs; launch times on a Mac), and blocks force-pushes and deleting `main`. No approving review is
needed. Work on a branch, push it, `gh pr create`, wait for the check, then squash it: `gh pr merge --squash`, one
commit per PR on `main` (`--rebase` when a PR's commits are worth keeping apart). Keep branches current by rebasing,
never by merging `main` in: `git rebase origin/main` and `git push --force-with-lease` on your own branch. A branch cut
from another PR's branch is rebased once that PR lands: `git rebase --onto origin/main OLD_PARENT_TIP` (the parent's
squash is a new commit, so a plain rebase would replay the parent's commits). Merged branches are deleted on GitHub.
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
  address (this checkout's local `git config user.email`), and so must squashed commits, whose author GitHub takes
  from the account, not the branch: merge with
  `gh pr merge --squash --author-email 7216340+aglose@users.noreply.github.com` (or `--rebase`, which keeps the
  branch's own authors).
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
- **Nothing but Host's loop may move the clock in a running instance.** Robolectric's SystemClock.sleep moves it by
  the time asked and returns at once, from any thread; Host never gives that time back, so app timers fire early by
  as much, and a worker polling with SystemClock.sleep raced the clock minutes ahead (an app's 10-minute inactivity
  CountDownTimer fired after ~30 s, most likely this). `ShadowRealSleep` makes it a real sleep outside test runs; on the main thread it also moves the clock by the time
  slept (Host's loop cannot meanwhile, and a deadline loop there would never end), which Host then does not add again. `stats` clockAheadMs
  shows drift; `scripts/timer-check.py` checks it.
- Vsync uses `ShadowChoreographer.setPaused(true)` + frame delay 1 ms. The loop only advances the clock to 120 Hz
  grid lines while a frame is wanted (`lineAtOrBefore`). Unpaused Robolectric vsync spins forever on infinite
  animations. Line k is at k * 1000 / hz ms rounded down, and `ms * hz / 1000` rounded down is not its inverse (8 ms
  gives line 0): until 2026-11-05 the loop took the line it stood on for the one before, waited for a line already
  passed, and spun the main thread for the rest of every frame (a core, headless, whenever anything animated).
  `lineAtOrBefore(ms, hz)`/`lineAfter` are exact for every rate (checked for 1-120 Hz).
- Some apps step their shader clocks at 30 Hz on purpose (the owner's does): idle shader screens at 30-60 fps can be
  by design. Measure 120 fps on interactions or the test app's Shader tab.
- Measurements are noisy when the machine is busy (WindowServer and the Claude app use CPU). Use A/B runs, not
  one-offs.
- macOS (16 KB pages) never makes a page writable and executable at once: `mprotect`/`mmap` refuse RWX, and MAP_JIT
  memory is writable or executable per thread, never both (checked: a thread executing a MAP_JIT page it may write
  faults). A 4 KB-aligned `.so` (an NDK before r27) has 16 KB pages holding the end of its code and what lld put
  after it, RELRO first: `elfloader.c` makes such pages read+execute once relocated, which a device's linker does to
  RELRO anyway. Writable `.data`/`.bss` on a page with code (`code_page_conflict`: a RELRO under ~12 KB, as in
  libevent's in a Flipper debug build, or `-z norelro`) is refused by default before anything is mapped: a catchable
  UnsatisfiedLinkError naming the APK entry, the rebuild (16 KB pages) and the switch. `--move-shared-data`
  (`-Dapksim.moveSharedData`, warm launches too; ElfLoader hands it to the native side before each load) moves it
  instead (`plan_data_move`): from its 4 KB page to the end of the image, the file's bytes are copied to pages after
  the image, a multiple of 16 KB on, so low 12 bits stay; the code's adrp (and adr/ldr-literal, if still within 1 MB)
  of moved pages is repointed (`patch_moved_refs`, executable sections only); relocations, symbol lookups and the
  dynamic section go through `at()`; dl_iterate_phdr's view follows the move (program headers, the dynamic section's
  address entries, moved symbols' st_value, then read as they are: `symbols_moved`); .eh_frame's PC-relative
  personality and LSDA pointers and each LSDA's type-table entries (typed catches) are repointed (`move_eh_frame`,
  `move_lsda`). What cannot be repointed (code after the moved data, an adr/ldr out of reach, an unwind pointer it
  cannot rewrite) refuses the library; a failed load gives its range back. A line on stderr names each moved library.
  Opt-in only because references with no relocation and no recognisable instruction cannot all be found: clang's
  relative lookup tables (offsets from a table in .rodata to globals), other link-time offsets in data, address
  differences folded into immediates, a one-past-the-end pointer at the moved boundary, data words in code that look
  like adrp. Each review of the move found another class. `--skip-library NAME` makes the app's
  `System.loadLibrary(NAME)` throw without trying (`ElfLoader.skipped`; not DT_NEEDED or dlopen). Every loader error
  names the library's APK entry (`lib/arm64-v8a/NAME.so`) (issue 137).
- A `.so`'s code pages are mapped from the extracted file (`elfloader.c` `load_segment`), not copied, so pages nothing
  patches stay clean (a large app's WebRTC library: 6.1 of 10.9 MB). macOS refuses `mmap(PROT_EXEC)` of a file but
  allows `mprotect` to read+execute of a private file mapping; if refused, the range is copied as before. Never rewrite
  an extracted `.so` in place (ElfLoader renames a new file over it).
- Phone, foldable and tablet report a 24 dp (48 px) status bar and gesture-nav inset (Robolectric's `SystemUi`, set
  in `SystemBars.apply`; desktop and TV have none; `--status-bar`/`--nav-bar` and `bars` override it per bar, and a bar
  the app hides reports 0), and `useDeviceGestureThresholds` replaces Robolectric's 16 dp touch slop with the framework's 8 dp.
  Without them layouts sat 26 px higher and every drag scrolled 16 px short of the emulator.
- `scripts/build.sh` writes the jar beside the old one and renames it, so running instances keep their classes.
- A RenderNode whose last parent goes away (removed from every renderer's tree, or its renderer destroyed) loses its
  display list in HWUI and draws nothing until recorded again. `GpuRenderNode` closes a content raster only a frame
  after the content is drawn in the screen's tree again; destroying a HardwareRenderer also deletes the display lists
  of everything it draws, so it empties its root first.
- Nothing may change the process locale once the JVM runs threads: macOS's `setlocale` swaps the global locale under
  any thread in `vsnprintf` (a JIT compiler thread crashed in `localeconv_l` while a standby booted, under load). The
  launcher sets `LC_NUMERIC=en_US.UTF-8` on macOS, the value libandroid_runtime's `JNI_OnLoad` sets, so its
  `setlocale` changes nothing. Check new native libraries for `setlocale` imports (`nm -u`).
- zsh is the shell: `==` and unquoted globs (`--include=*.kt`) break one-liners. Quote them, or use bash/python.

## Test APKs
- `testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk`: the main regression app.
- The owner's own app (R8 and debug builds, the latter 179 MB and exercising method splitting): see
  `~/.config/apksim/NOTES.md`.
- An app whose native libraries are 4 KB-aligned (a debug build with Flipper): libflipper and libc++_shared load, but
  libevent_core/extra need `--move-shared-data`; without it Flipper's uncaught UnsatisfiedLinkError ends the app (exit 10).
- `testapks/loader-fixture`: a native library of loader and bionic checks inside the test app (see "Instrumentation tests").

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
- MediaCodec has H.264, HEVC, AAC and MP3 (on Linux H.264 only, HEVC as a build option: no audio decoders or output there, and no
  H.264 for libwebrtc); VP9/AV1 video and Opus/Vorbis audio are not listed (the owner's app needs
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
- Network record/replay (docs/network.md) covers OkHttp (minified too) and HttpURLConnection; Cronet's native stack,
  Ktor CIO (NIO) and WebSocket replay are not covered.
- GLES and native audio (OpenSL ES/AAudio) libraries are stubs. Java AudioTrack plays (see "Video").
- Real mouse and keyboard in the window were first used by the owner on 2026-11-02. Scripted input goes through
  `--control` instead.
