# Plan: remaining work (2026-11-03)

Where things stand: launch (1.3-1.7 s first frame with a warm class cache), real APKs (the owner's app, release and debug)
and the 120 fps goal (117-120 fps average, 15-35% of frames at 9-12 ms instead of 8.3 ms) are met or close.
Today added: the class cache saved on exit, GitHub CI on the Linux image, HEVC/AAC/MP3 and AudioTrack sound,
layer-content RenderEffects on Metal, and MediaCodec pictures in presentation order. This plan orders what is left by
value per effort. Each step ends in its own commit, measured A/B against the build before it (AGENTS.md: noisy machine,
interleave runs).

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
native audio apps run. Large; only if a target app needs it.

## Not planned

- Rust rewrite of the native C-library shim: safety, not speed, and the shim is small. Revisit only if it keeps
  causing memory bugs.

## Order and size

| Step | Size | Needs |
|---|---|---|
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
