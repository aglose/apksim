# Plan: remaining work (2026-11-03)

Where things stand: launch (1.3-1.7 s first frame with a warm class cache), real APKs (the owner's app, release and debug)
and the 120 fps goal (117-120 fps average, 15-35% of frames at 9-12 ms instead of 8.3 ms) are met or close.
Today added: the class cache saved on exit, GitHub CI on the Linux image, HEVC/AAC/MP3 and AudioTrack sound,
layer-content RenderEffects on Metal, and MediaCodec pictures in presentation order. This plan orders what is left by
value per effort. Each step ends in its own commit, measured A/B against the build before it (AGENTS.md: noisy machine,
interleave runs).

## Phase 0: the owner's app's device tests in CI (started 2026-11-03)

Goal: run the owner's app's instrumented suites on apksim in its CI instead of its two emulator jobs (~19 min a pull
request, 35 runner-minutes). Findings, numbers and commands: [ci-testbed.md](ci-testbed.md).

**Done:**
- UiAutomator in `--instrument` runs (`ShadowSimUiAutomation`), with tests in the test app.
- All of the app's suites run: 4,107 tests, all passing with two fixes to the app's tests (0c), 4.7 min on the Mac
  and 8.3 min in the Linux image (0a).
- Fixed on the way: two DEX translator miscompilations (exception-handler liveness: ints and floats typed as
  references), the test SDK level (an app targeting 37), test discovery breaking framework classes, library test
  APKs, and the device behaviour a test run lacked (`TestActivities`: back, finish, onNewIntent, Compose's UI-thread
  order, requested window sizes, touch slop, two native aborts).
- Offscreen GLES 3 on ANGLE (`dev.apksim.gl`, generated GLES20/30 shadows), for a 3D module's device test.
- Debugging tools: `scripts/jdi/Trace.java`, `scripts/jdi/Ret.java`, `scripts/translate-class.java`.

**Remaining, in order:**

**0a. Measure on a real CI runner.** Add a job to the app's CI beside its emulator jobs (not replacing them) that
builds the same test APKs, runs apksim's Linux image with `--instrument` on each, and uploads JUnit XML
(`scripts/instrument.py --xml`). Done when its wall time and failures are known on ubuntu-24.04; the projection in
ci-testbed.md (about 10 min, 10 runner-minutes) is only the Mac's test times plus CI's build time. Needs a pull
request to the app's repository and an image of this version (`scripts/docker-build.sh`).
- **Measured on the Mac's Linux VM instead (2026-11-03):** every suite in this version's image through
  `scripts/instrument.py --apksim "docker run ..."` on Colima (4 CPUs, 8 GB, x86 under Rosetta), from a clean clone's
  build: 8 min 17 s of tests with the journeys in one process (4.7 min natively on the Mac); with the journeys one
  process per test, 16.7 min, because each JVM start under Rosetta costs ~30 s. Projection: ~14 min and ~14
  runner-minutes a pull request at most, against 18.8 min and 34.9 (ci-testbed.md). It found two apksim bugs, both
  fixed: Mesa's EGL has no window configs and WebRTC asks for one (every journey with a live camera failed), and
  processes started together raced on extracting native libraries.
- **Done in the app's own CI (2026-11-04, apksim 0.5.0):** a job beside the emulator jobs pulls the released image
  and runs every suite: 4,111/4,111, none flaky, the same as the emulators in that run; 350 s of tests, 15.6 min for
  the job with its build (ci-testbed.md).

**0b. GLES on Linux.** `Gl` looks for `libEGL.so.1`/`libGLESv2.so.2`, but the image has neither, so the 3D test would
fail there. Add Mesa (`libegl1 libgles2 libegl-mesa0 libgl1-mesa-dri`) to `docker/Dockerfile`, try EGL's surfaceless
platform (`EGL_PLATFORM=surfaceless`) with a pbuffer, and run that test in the image. Watch the image size (488 MB).
- **Result (done 2026-11-03):** Mesa in the image with `EGL_PLATFORM=surfaceless`: EGL 1.5 on llvmpipe (LLVM 20),
  pbuffers work. The image is 565 MB (+77 MB, mostly LLVM in the DRI driver). The test app gained `GlesTest` (a
  pbuffer, a shader program, a triangle read back with glReadPixels), which passes on the Mac (ANGLE), in the image
  (Mesa) and on the API 36 emulator, and fails with GLES off. CI now builds the test APK and runs the test app's whole
  suite in the image (`scripts/instrument.py --apksim "docker run ..."`, JUnit XML in the artifact): 14 of 14, 17 s
  under Rosetta. On the way: the committed `AnimationClockTest` used ui-test's v2 API, which the test app's Compose BOM
  does not have (it needs AGP 9.1 and compileSdk 37), so the androidTest APK did not compile; that test now uses v1.
  The owner's 3D module's test in the image is part of 0a.

**0c. The app's 4 failures, upstream.** Not apksim's, but they block "all green": a feed test's title tap on API 36,
which a real API 36 emulator reproduces, and the journeys that reach the server address compiled into the app (let
tests replace it, so they are hermetic on a developer's Mac too). The app's code, so for its own sessions; apksim's
`--no-network` is not a substitute (it breaks the journeys that expect the remote route).
- **Result (2026-11-03): both fixed as patches to the app, tested, not applied to its repository** (the owner's notes
  say where they are). The feed test clicked the card's merged node, whose centre is the clip count's button, not
  the title: clicking the unmerged title passes here and on the API 36 emulator. The journeys now switch the
  compiled-in LAN address off for their run (one overridable value read where the constant was): without it a "no
  cameras" journey found the real server's cameras from this Mac; with both patches all 4,107 tests pass, on the Mac
  and in the Linux image. Applying them is a pull request to the app (owner's call).

**0h. Single-process journey runs on the Mac.** In one long run of all 76 journeys in one process, one to four
different journeys time out at 20 s each run, some with the app unable to connect to the journey's fake
server on 127.0.0.1. Never with one process per test (`--isolate`: 76/76 three times), never for a class run alone,
once in the Linux image. No descriptor leak (~460, flat).
- **Explained and fixed in the app (2026-11-04):** the sign-in screen re-seeded its server field with an earlier
  journey's stored connection, already closed (ci-testbed.md). With the app's fix, a whole run in its CI had no flaky
  journeys, and the suite took 96 s instead of 225-286 s.

**0i. Parallel runs (done 2026-11-04).** `instrument.py --shards N` (AndroidJUnitRunner's numShards/shardIndex),
suites side by side, `scripts/fleet.py` for N instances at once, the JIT stopped at C1 for test runs, and two macOS
fixes found under load (monotonic condition variables spinning, a crash at exit). The app's 4,134 tests: 167 → 58 s on
the Mac, 670 → ~135 s on 4 CPUs in the Linux image ([parallel.md](parallel.md)). The app's CI adopted it with 0.8.0
(2026-11-05): on its ubuntu-24.04 runner the device-test step went from a median 310 s to 140 s. What is left there is
the Gradle build of the test APKs, now most of the job, and one journey's 30 s real-time wait (the app's).

**0d. A translator regression test with R8's register reuse.** The two miscompilations needed R8-shaped code (a
register reused across int, float and object, a return inside a catch-all range); the test app's D8 debug build does
not produce it, so `TranslatorTest` only checks behaviour. Commit a small R8-built DEX fixture and a check that
translates it and runs it (or `selftest translator`), so a regression in `computeLiveness` fails CI.
- **Result (done 2026-11-03):** `testapks/translator-fixture`: plain Java (`build.sh`) built with R8 and with D8 in
  debug mode, both `.dex` files committed; `scripts/check-translator.sh` (`dev.apksim.dex.TranslatorCheck`) translates
  them, loads them in a plain JVM and runs `fixture.Checks` (well under a second; a CI step). Checked against
  translators with each old bug put back: a return counted as throwing fails the R8 and D8 builds (`putIfAbsent`: R8
  returns a constant in the lock's register, `const/4 v1, 1; return v1`, inside the catch-all whose handler does
  `monitor-exit v1`); a handler's registers live at every block of its range fails the D8 build (`unregister`, a
  return shared by an SDK-level path and a synchronized one, as D8 dexes AndroidX's LocationManagerCompat). R8 and D8's
  release mode do not show the second: they give each throwing call its own handler stub and duplicate returns. The
  Compose `animate` shape itself did not reproduce from Java. Of the test app's 20,882 classes, only
  LocationManagerCompat translates differently with that bug, and there `SDK_INT >= 30` became `null != null`.

**0e. Check normal runs after the translator fixes.** They change how every app translates. Launch, the test app's
drive and the Shader tab are unchanged (1.45 s first frame, 118 fps). Still to look at: the owner's app's animations
in a normal run, which may have been snapping in places (the same pattern), and play-services' `GoogleApiHandler`,
which no longer dies: what it starts (availability checks, geofencing) now runs.
- **Result (done 2026-11-03):** of the owner's release build's 11,199 classes, 17 translate differently than before
  the fixes, 20 methods in all, and in every one the old translation dropped primitives (`aconst_null`/`pop` where the
  DEX had an int or float): Typeface fallback building, play-services' availability cache, an I/O buffer's
  readByte/readShort/readInt/readLong, an executor, float-heavy animation code and others. All now translate as
  values. In a normal run the app signs in, its apps sheet animates open and closed (178 and 157 frames over 1.5 s,
  not a snap), and the log's only exception is the missing Health Connect service.

**0f. What a test run still lacks, when a suite needs it:** accessibility events for UiAutomator
(`executeAndWaitForEvent` reports a window change only), shell commands beyond the in-process shell's (input,
screencap) and global actions
other than Back; starting an activity that is not running (only recorded, as Robolectric does) and CLEAR_TOP;
`adb shell am instrument` of a test APK (a self-targeting one runs live, see AGENTS.md "adb device"); the test APK's
own resources and context.

**0g. CI cache.** Cache `work/cache/classes` between runs keyed on the runtime hash and the APKs' CRCs. Low value by
the measurement above (cold and warm the same); revisit if the Linux numbers say otherwise.

## Phase 1: check today's work on the owner's app (small, do first)

**1. Recorded clips in the owner's app.** Today's frame reordering and audio path were checked only on the test app's 3 s
clip. The owner's app's recorded clips (Media3/ExoPlayer from its server) now get both.
- How: launch the owner's app (sign-in is automatic from the saved autofill), open a camera's recorded clips and
  play one muted (`--jvm -Dapksim.mute=true`). Read `video` (rendered vs dropped, `pictures`, `audiotrack` counts)
  and `fps` during playback. Then compare with `--jvm -Dapksim.audio=false` and with the commit before `4d0c92d`.
- Done when: clips play at their frame rate with ~0 drops, audio and video stay in step (no clock race), and
  scrubbing/seeking works (flush clears held pictures). Anything broken becomes a fix in this phase.
- Risk: the server may serve HEVC or audio formats not yet listed (Opus): note them for phase 4.
- **Result (done 2026-11-03, recorded in AGENTS.md "Video"):** 4K H.264 at 20-25 fps plus 8 kHz AAC over HLS play at
  their frame rate, in step with the audio clock, and seek. Drops with sound on (0-7%) come from the stream's audio
  timestamp jumps at segment joins. No HEVC or Opus in its clips, so nothing for phase 4.

**2. Effect layers on the untested cases.** The subagent could not check nested effect layers, scaled or rotated
effect layers, or memory release when a node is collected without discarding its display list.
- How: add cases to `-Dapksim.effectSelfTest=true` (a scaled and a rotated layer; an effect layer inside another),
  compare against `-Dapksim.cpuLayerEffects=true` pixel for pixel. For memory, open and close the owner's app's weather and
  camera pages 50 times and watch footprint (`footprint PID`) and `shaders` (active layers).
- Done when: mean difference stays under ~1 level for every case and footprint levels off.
- **Result (done):** scaled, rotated and nested layers match HWUI's CPU effect (mean 0.006-0.012, max 2; 10 at the
  rotated layer's antialiased edges). Memory: content rasters close on discard, but every weather screen visit leaked
  ~16 MB of Metal textures: a `TrackedRuntimeShader` dropped mid-animation never collected its frame in flight
  (fixed: `sweep`). Weather screen visits are now flat (25 visits: 887 → 840 MB). Weather + camera rounds still climb
  ~50 MB each until a full GC (2.2 GB → 1.5 GB): native malloc behind Java objects (HWUI display lists, Bitmaps),
  which HotSpot frees only at a full GC (~156 ms pause with the serial collector). Not a leak (a GC returns it).
  Possible follow-up: an ART-like GC when the app goes idle after native memory grew by a few hundred MB.

**3. README shader count.** "20 of the app's 29 shaders translate" predates `uniform shader` support. Recount with
`shaders` after visiting every screen of the owner's app and fix the sentence. Minutes.
- **Result (done):** README says all 28 AGSL programs translate and compile (step 7 gave every one a GPU path).

## Phase 2: frame rate (the owner's open goal)

**4. Effect layers cost on busy screens.** The map screen is 30 fps (was 8.6): its two full-screen effect layers are each a
second HWUI frame (~5 ms raster) plus ~3 ms shader and readback, all before the screen's own frame.
- Options, in order to try: (a) run effect layers one frame behind, as `TrackedRuntimeShader` already does for
  plain shaders, so their raster overlaps the screen's; (b) render the shader output straight into the Bitmap's
  memory (MTLBuffer-backed texture) instead of reading back and copying; (c) one HardwareRenderer for all effect
  layers of a frame.
- Measure: `fps` and `shaders` ("layer effects:" timings) on the map screen, the weather screen and the camera page.
- Done when: the map screen is above 60 fps with no visible lag on effect content (check with the scrubbing lens).
- **Result (done):** Panning the map screen 26 → 64-73 fps (p50 11 ms). The time was not where the plan guessed: 74% of the
  render thread was Skia's CPU bilinear sampling of the map and heat tiles (`drawImage` scaled), and the 3.8 ms
  "shader" was the CPU untiling the Metal texture on readback. Fixes: `GpuBitmaps` (scaled bitmap draws resampled
  once on Metal, then blitted) and linear pooled render targets (shader 3.8 → 0.8 ms per layer, every GPU shader
  benefits). Weather screen scrolling 59 → 79 fps; camera page unchanged (~130). Option (a) was not needed: with both effect
  rasters and the screen raster on HWUI's one render thread, that thread is the limit, not the main thread.

**5. Display vsync pacing.** Built and off (`--jvm -Dapksim.vsync=display`); only its fallback was tested because the
display was asleep. Needs the Mac awake with the window visible.
- How: Shader tab and flings, `frames` with and without the flag, 5 interleaved runs each. The `screen` part
  counts per real vsync whether a new frame arrived.
- Done when: decided. Default it on if "new frame on N% of vsyncs" rises and the frame-interval spread narrows;
  otherwise remove it and record why.
- An earlier attempt saw "screen 0 Hz" in every run (display asleep), so both modes were on the grid.
- **Result (done 2026-11-03, now the default):** with the display lit, 5 interleaved runs each. Shader tab: the grid
  drifted against the display's vsync, a new frame on 99-100% of vsyncs in three runs and 69% in two (~146 frames
  shown twice, ~146 never shown); following the display, 98-99% in all five, p95 interval 10.1 against 11.3 ms.
  Flings: the grid drew ~140 fps of which 96-178 frames were replaced before being shown (84-97% new); the display
  ~118 fps, 3-5 replaced, 97% new in every run. `-Dapksim.vsync=grid` keeps the grid; it is also the fallback with
  no vsyncs (hidden, asleep, headless). Test app launch unchanged (1,479 ms median first frame). With it the Shader tab
  reads p95 9.6 ms and 99% new frames.

**6. CPU raster of busy frames.** The remaining 9-12 ms frames come from single-core HWUI raster (~6-7 ms on busy
full-screen frames). Partial redraw was tried and does not help Compose (findings in AGENTS.md). Left to try:
zero-copy present (HWUI renders into the IOSurface instead of a buffer copied each frame, ~0.5 ms) and moving more
paints to the GPU path (`GpuCanvas`). Profile first with `sample` + `scripts/prof.py` on the Shader tab and a fling
to see what the raster thread spends its time on; pick from that.

- **Result (2026-11-03, no change made):** on the Shader tab raster is now 1.95 ms and main-thread work ~3 ms a
  frame (the linear readback in step 4 took the copy off), yet "on 120 Hz pace" stays ~70%. That figure is the time
  between presents, and a present happens when the render thread finishes: frames that rasterize in 2 then 5 ms read
  as an 11 ms interval although both made their grid line (average exactly 120 fps, none dropped). Whether that
  jitter ever crosses a real display vsync is what `frames`' `screen` part counts, which needs display vsyncs: decide
  together with step 5. On the map screen the render thread still carries two content rasters plus the screen (~6 + 6 + 7 ms).
- **Decided with step 5:** following the display's vsync, the Shader tab puts a new frame on 99% of vsyncs, so that
  jitter no longer reaches the screen. What is left is busy screens whose raster takes longer than a frame (the map:
  two content rasters plus the screen on one render thread). Not worth zero-copy present (~0.5 ms) for those; one
  renderer for all effect layers, or rasterizing them on a second thread, is the lead if an app needs it.

## Phase 3: remaining CPU effects

**7. Effects still on the CPU.**
- `View.setRenderEffect` (framework RenderNodes are not substituted): route through the same `GpuRenderNode`
  logic from a `View` shadow or by substituting in the framework's `View.mRenderNode` at attach.
- Blur and chain effects (`createBlurEffect`, `createChainEffect`): a Metal blur (MPSImageGaussianBlur) on the same
  content raster; chains as successive passes.
- Shaders with `setInputShader` children drawn as a paint (the owner's app has one, with two image inputs):
  upload the child BitmapShader or Picture as a texture, the way layer content is.
- Done when: `shaders` shows none of the owner's app's programs on the CPU, each matched against the CPU output.

- **Result (done for the owner's app):** all 28 of its AGSL programs now have a GPU path. Paint shaders with image
  inputs (`setInputShader` BitmapShaders) bind the bitmaps as clamped textures; self-test
  "input shaders" matches HWUI's CPU result (mean 0.009, max 1). The owner's app uses neither `View.setRenderEffect` nor blur
  or chain effects, so those were left on the CPU; do them when an app needs them.

## Phase 4: media and services (when an app needs them)

**8. End-of-stream frames.** The video decoders lose the 1-3 pictures they hold when the stream ends, because
Robolectric gives one output per input. Fix by emitting the held pictures on the end-of-stream input's buffer and on
input buffers the codec still owns, or by returning DECODE_ONLY later inputs and keeping one picture back less.
Matters for short clips and thumbnails.

- **Result (done):** an input whose picture is held for reordering keeps its buffer; at end of stream those buffers
  carry the held pictures out in order before the end-of-stream buffer. `selftest clip.h264|.h265` now requires every
  unit: 60 of 60 (was 59). In async mode (ExoPlayer) Robolectric hands out one input buffer at a time, so a kept one is
  replaced from its free queue; the test app's clip plays through with 0 drops.

**9. More codecs.** VP9 and AV1 (VideoToolbox decodes both on Apple Silicon: VP9 since macOS 11, AV1 on M3 and
later), Opus and Vorbis (AudioToolbox has Opus; Vorbis needs a bundled decoder). Add when an app's media needs them;
phase 1 says whether the owner's app does.

**10. Health Connect stand-in.** The owner's app hides a card and its heart rate without it. A fake Health Connect service
(the SDK's `HealthConnectClient` talks to a provider app that does not exist here) with sample records would show
them. Needs the SDK's binder interface; medium effort.

**11. Native GLES and audio.** GLES via ANGLE-on-Metal and OpenSL ES/AAudio on CoreAudio would let games and
native audio apps run. Large; only if a target app needs it. Java's GLES20/30/EGL14 already reach ANGLE in test runs
(phase 0); native `libGLESv*.so` imports and presenting GLES in the window are what is left.

## Phase 5: failure bundles for test runs (spec 2026-11-05)

Goal: when a test fails in an `--instrument` run, CI keeps enough to see why without running it again: what was on
the screen, the accessibility tree, that test's log, the threads, the main queue, and a command that runs only that
test again. Today a failure leaves a stack trace in the JUnit XML and, for a process that died, the stderr tail, so
a red check usually means rerunning the job, or the test locally, to see anything.

**12a. Interface.**
- `./apksim --instrument ... --bundles DIR` (the launcher passes `-Dapksim.bundles=DIR`, resolved with
  `Storage.absolute`). `--bundles-on failed|all` (default `failed`; `all` is for debugging a passing test) and
  `--bundles-max N` (default 50 per process; later failures get `failure.txt` only, so a broken build failing 4,000
  tests does not write gigabytes).
- `instrument.py --bundles DIR` passes it to every process it starts (shards, `--isolate`, the reruns after a
  process died), links each bundle from the JUnit XML and writes `DIR/index.html`. With `--apksim CMD` (the image),
  DIR must be inside the folder mounted into the container, and is passed as the container sees it, with
  `--bundles-local PATH` for the same folder on the host.
- Off by default. Without the flag nothing is captured, no log tee is installed, and a run prints exactly what it
  prints today.

**12b. Layout.** `DIR/<Class>/<method>-<hash8>/`: the method name cut down to `[A-Za-z0-9._-]`, plus 8 hex digits of
the hash of the whole description, so Parameterized names (`test[0: x, y]`) stay unique and short. In it:
- `failure.txt`: test, status, exception and stack trace, the run's `-e` arguments, apksim version and runtime hash,
  device (size, density, qualifiers, dark mode, font scale, locale, time zone), simulated uptime and wall time at the
  capture, and which capture point wrote the bundle (12c), with any part that failed to capture and why.
- `screen.png`: every visible window drawn bottom to top as it was at the failure. `windows.txt`: each window's type,
  activity, bounds and focus.
- `nodes.txt`: `Nodes.dump` of the roots (text, hints, fields, clickables with bounds; Compose semantics through
  virtual nodes), the same format as the `nodes` control command.
- `logcat.txt`: the app's log lines (`AdbLog`) from this test's start to the capture, at most 5,000 (head and tail
  kept, the middle cut with a line saying how many went).
- `threads.txt`: every thread's stack, main thread first.
- `looper.txt`: the main looper's queued messages with their due times against the simulated clock, and the
  activity stack with each activity's lifecycle state: what an idling or timeout failure was waiting on.
- `repro.sh`: the `./apksim --instrument` command that runs this test alone with the run's `-e` arguments (without
  `class`, `notClass`, `numShards`, `shardIndex`), device, `--timezone`, `--realtime-clock` and `-D` switches. A test
  that fails in the run and passes alone has state carried in from earlier tests (app statics): `failure.txt` says
  how many tests ran in the process before it.

**12c. Capture points.** JUnit tells listeners about a failure only after the test's `@After` methods and rules
have run, and ActivityScenarioRule and createAndroidComposeRule close their activity in that teardown, so a capture
in `RawListener.testFailure` finds no windows. Hence:
1. **Test body.** `SimRunner` overrides `getHelperTestRunner` and wraps `methodInvoker` of the helper it gets from
   RobolectricTestRunner. A Throwable out of the test method (an assertion, Espresso's NoMatchingViewException,
   Compose's timeouts, the Timeout watchdog's interrupt) is captured there, on the main thread, before `@After` and
   rules run, then rethrown unchanged. An AssumptionViolatedException is not a failure and is not captured.
2. **Listener.** `RawListener.testFailure` covers everything else: failures in `@Before`, `@After` and rules, classes
   run by their own `@RunWith` (Parameterized, plain JUnit4) that do not go through SimRunner, and initializationError.
   It captures whatever is still there, and nothing when (1) already wrote this test's bundle.
3. **Watchdog.** When the `FailOnTimeout` watchdog is about to end the process because a test ignores interrupts, it
   first writes `failure.txt`, `threads.txt` and `logcat.txt` from its own thread. The main thread is held, so there
   is no screen, tree or queue.
4. **Process died.** For a test `instrument.py` reports failed with an exit status (native crash, abort, watchdog), it
   writes `process.txt` (exit status, stderr tail), and `failure.txt` if the folder has none.

A capture must not change the result: each part runs in its own try/catch with a time limit (2 s for the screen),
a part that fails is noted in `failure.txt`, and the test's own Throwable is always the one reported. A capture must
not move the simulated clock or run the main queue (no idle, no new frame or traversal), so it shows the state the
assertion saw.

**12d. Drawing the screen in a test run.** Test runs have no Host loop and no HwRenderer frame. Start from what
UiAutomation's `takeScreenshot` uses (Robolectric's ShadowUiAutomation, which ShadowSimUiAutomation keeps): it
composes the windows. Check it on a Compose screen, a Dialog over an activity, a `GpuRenderNode` effect layer and a
TextureView. Where layers come out blank, draw each root through a HardwareRenderer as `HwRenderer` does.

**12e. instrument.py and CI.** The test's status block gets an extra key, `INSTRUMENTATION_STATUS: apksim_bundle=PATH`
(ddmlib-based parsers keep unknown keys as test metrics; others ignore them). `instrument.py` puts each bundle in
the XML's testcase as `<system-out>[[ATTACHMENT|PATH/screen.png]]</system-out>` (GitLab's and Jenkins' JUnit
attachment convention) and `<properties><property name="apksim.bundle" value="PATH"/></properties>`, with paths
relative to the XML file so a downloaded artifact still works. `DIR/index.html` is one self-contained page with a card
per failure: screenshot, the failure's first line, links to the files. `.github/workflows/ci.yml` uploads DIR with
the test app's results.

**12f. Privacy.** A bundle holds the app's log and what it showed, so whatever the app logs or displays (tokens, a
test account's data) ends up in the CI artifact. The docs say so. `--bundles-redact REGEX` (repeatable) replaces
matches in `logcat.txt`, `nodes.txt` and `failure.txt` with `[redacted]`. A bundle never copies app data folders or
anything under `work/autofill`.

**12g. Checks.** The test app gets `FailureBundleTest`, skipped unless `-e failOnPurpose true` (as `ProcessDeathTest`
does with `crashOnPurpose`): an assertion failing on a Compose screen, Espresso's NoMatchingViewException in a View
activity, a failure with a Dialog open, an `@After` failure, a Parameterized case, and a timeout that ignores
interrupts. `scripts/bundle-check.py` (a CI step) runs it with `--bundles` and checks that each folder has the files
its capture point should write, that `screen.png` is not blank, that `nodes.txt` has the screen's text, that
`logcat.txt` holds only that test's lines, and that `repro.sh` fails the same way on its own. A passing run must
print the same output with and without `--bundles`. On the owner's app's suites `--bundles` must cost under 2% time
when nothing fails.

**Built (2026-11-05).** As specified, with these findings:
- Kotlin's `use {}` (and try-with-resources) closes an ActivityScenario before the exception leaves the test method,
  so the body capture found no windows for Espresso failures. The handler Espresso has at the start of each test
  body is now wrapped (captures, then delegates, so a test's own handler still decides); a test that expects an
  Espresso failure, catches it and passes loses that bundle.
- From review: no part may hold up the suite (deadlines, then the part is skipped for the run), the uptime is read
  without moving `--realtime-clock`, a test's first log lines survive the ring, `instrument.py` clears an earlier
  run's bundles and redacts its own notes, and `--bundles-on all` reconciles a body that passed with a teardown that
  failed.
- 12d needed no fallback: Robolectric's HardwareRenderingScreenshot draws Compose screens and a Dialog over an
  activity correctly. A capture of a full bundle takes ~150 ms (screen ~100 ms), only on failure.
- In the image apksim runs as root, so bundle folders are made writable by everyone there, for the host's
  `instrument.py` to add `process.txt`.
- `scripts/bundle-check.py` checks it on the Mac (all checks pass, 24 s) and is a CI step in the image. Not done
  yet: the owner's app's suites with `--bundles` (the under-2% cost check) and a GpuRenderNode effect layer and a
  TextureView in a screen capture.

**Done when:** `bundle-check.py` passes on the Mac and in the Linux image (`ci-smoke.sh`), CI uploads the bundles,
and docs/ci-testbed.md says how to read one. Size: medium, 3-4 days. Later: a filmstrip (a small frame at each
injected input, the last 20 kept) to show what the test did before it failed. Video does not fit test runs: there is
no frame loop, and the clock skips idle time.

## Phase 6: more features for teams (started 2026-11-05, each in its own session and branch)

Each has its own spec in its own doc, so the branches do not conflict in this file:
- **Affected tests and balanced shards** (docs/affected-tests.md): record which classes each test loads, run only the
  tests a diff can reach, and split shards by measured time.
- **Screenshot tests across devices and settings** (docs/snapshots.md): golden screenshots across devices, dark mode,
  locales and font scales, with a diff report.
- **Hermetic network** (docs/network.md): record and replay an app's HTTP(S) traffic, plus latency, loss and offline
  profiles.
- **Browser previews of builds** (docs/serve.md): `apksim serve`, a pool of standbys streaming instances to a browser
  tab per build.

## Not planned

- Rust rewrite of the native C-library shim: safety, not speed, and the shim is small. Revisit only if it keeps
  causing memory bugs.

## Order and size

| Step | Size | Needs |
|---|---|---|
| 0a Measure on a CI runner | measured on the Mac's Linux VM | a pull request to the app (owner's go-ahead) |
| 0b GLES on Linux | done | - |
| 0c The app's 4 failures | patches tested | applying them in the app (owner's go-ahead) |
| 0d Translator fixture test | done | - |
| 0e Normal runs after the fixes | done | - |
| 0f Test-run gaps | medium each | a suite that needs them |
| 0g CI class cache | small | 0a's numbers |
| 0h Single-process journey timeouts | small-medium | - |
| 1 Recorded clips | done | - |
| 2 Effect-layer edge cases | small | - |
| 3 README count | done | - |
| 4 Effect-layer speed | medium | - |
| 5 Vsync pacing | done (default on) | - |
| 6 CPU raster | decided (with 5) | an app whose busy screens need it |
| 7 Remaining CPU effects | medium | - |
| 8 End-of-stream frames | small | - |
| 9 More codecs | small each | an app that needs them |
| 10 Health Connect | medium | - |
| 11 GLES/native audio | large | an app that needs them |
| 12 Failure bundles | built; the owner's suites' cost check left | - |
| Phase 6 features | medium-large each | their own sessions |

4 and 7 both touch `gpu/` and should not run in parallel. What is left without the owner: 0h, and 0f/0g/9-11 when
an app needs them. 0a's CI job and 0c's patches are changes to the owner's app.
