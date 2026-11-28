# Open work, and ideas measured and not kept

Agent reference notes, moved out of AGENTS.md so they are read only when needed. Quoted section names
("Video", "Warm launches") are sections of these files: AGENTS.md's index says which file has each.

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
- MediaCodec has H.264, HEVC, VP9 (profile 0, `apksim.vp9.decoder`, issue 340: VideoToolbox's supplemental VP9 decoder
  with a `vpcC` format description built from each key frame's header, as WebM gives no codec data; libavcodec in a Linux
  image built with `VIDEO_DECODERS=h264,vp9`; `selftest work/media/clip.ivf`; listed on the Mac without a probe, as a trial session costs ~85 ms of a cold launch: a
  codec's configure makes it and logs when the system has none), AAC and MP3 (on Linux H.264 only for video, HEVC and VP9 as build options; AAC and MP3 are libavcodec's there, with no audio output, and no
  H.264 for libwebrtc); AV1 and VP8 video and Opus/Vorbis audio are not listed (the owner's app needs
  none of them).
- Layer-content RenderEffects run on Metal (`GpuRenderNode`). On the owner's app, fps with them on the CPU → on
  Metal: a camera page 49 → 130, a weather screen 23 → 86, a map screen 8.6 → 30, and panning the map 26 → 64-73 once
  scaled bitmaps went to the GPU and readback stopped untiling (2026-11-03). What is left is the content raster: each
  effect layer is a second HWUI frame on the one render thread, serialized after the screen's frame and waited for on
  the main thread (the map: two full-screen layers, ~6 ms raster + 0.8 ms shader each). Not
  tried: one frame behind (it would not help much: the render thread, not the main thread, is the limit), one
  renderer for all layers. Effects set with View.setRenderEffect (framework RenderNodes) and chained/blur effects stay
  on the CPU.
- Network recording (docs/network.md) covers OkHttp (minified too, HTTP/2, gRPC, WebSockets) and HttpURLConnection;
  Cronet's native stack and Ktor CIO (NIO) are not covered, nor is replay of WebSockets or gRPC. Not tried: the tap
  skipping the copy of bodies it will not keep (video segments: the decoder would have to tell the app's thread
  which bytes of an HTTP/2 stream are DATA), and an engine that speaks HTTP/2 to the app, so a rule or a replay would
  not cost the mocked host its protocol.
- GLES and native audio (OpenSL ES/AAudio) libraries are stubs. Java AudioTrack plays (see "Video").
- Real mouse and keyboard in the window were first used by the owner on 2026-11-02. Scripted input goes through
  `--control` instead.
