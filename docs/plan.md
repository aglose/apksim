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
- All of the app's suites run: 4,106 tests, 4,102 pass, in about 4 min of test time on the Mac. The 4 left are the
  app's: one fails on an API 36 emulator too, three reach a server on the owner's network from a Mac on it.
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

**0f. What a test run still lacks, when a suite needs it:** accessibility events for UiAutomator
(`executeAndWaitForEvent` reports a window change only), its `executeShellCommand` (empty output) and global actions
other than Back; starting an activity that is not running (only recorded, as Robolectric does) and CLEAR_TOP;
`adb shell am instrument`; the test APK's own resources and context.

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
- **Still blocked (2026-11-03):** 5 interleaved A/B runs gave identical numbers (Shader tab 120.0 fps, fling ~160),
  but every run reported "screen 0 Hz": no display vsyncs reached the window (display asleep or window hidden), so
  `-Dapksim.vsync=display` was on its grid fallback both times. One earlier run on the owner's app did see 120 Hz vsyncs, so
  it works when the window is on a lit screen. Needs a run with the owner at the Mac.

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

## Not planned

- Rust rewrite of the native C-library shim: safety, not speed, and the shim is small. Revisit only if it keeps
  causing memory bugs.

## Order and size

| Step | Size | Needs |
|---|---|---|
| 0a Measure on a CI runner | small | a pull request to the app, this version's image |
| 0b GLES on Linux | done | - |
| 0c The app's 4 failures | small | the app's own sessions |
| 0d Translator fixture test | done | - |
| 0e Normal runs after the fixes | small | the owner's app, a look at its animations |
| 0f Test-run gaps | medium each | a suite that needs them |
| 0g CI class cache | small | 0a's numbers |
| 1 Recorded clips | small | the owner's app (autofill), network |
| 2 Effect-layer edge cases | small | - |
| 3 README count | minutes | - |
| 4 Effect-layer speed | medium | - |
| 5 Vsync pacing | small | Mac awake, window visible |
| 6 CPU raster | medium-large | profiling first |
| 7 Remaining CPU effects | medium | - |
| 8 End-of-stream frames | small | - |
| 9 More codecs | small each | an app that needs them |
| 10 Health Connect | medium | - |
| 11 GLES/native audio | large | an app that needs them |

Steps 1-3 can run in one session; 4 and 7 both touch `gpu/` and should not run in parallel; 5 waits for the display.
