# Rendering: HWUI, AGSL on Metal, GPU bitmaps, GLES

Agent reference notes, moved out of AGENTS.md so they are read only when needed. Quoted section names
("Video", "Warm launches") are sections of these files: AGENTS.md's index says which file has each.

## Code notes
- `HwRenderer`: Android's real HWUI pipeline (CPU Skia). A window ViewRootImpl did not make hardware accelerated
  (no FLAG_HARDWARE_ACCELERATED: every window of an app targeting API 13 or lower, `android:hardwareAccelerated="false"`)
  has no ThreadedRenderer, so `updateDisplayListIfDirty` records nothing for it and it drew blank; `displayList`
  records it with `View.draw` into a node of its own while it is dirty (the views keep their software path, the canvas
  records), and HWUI draws it with the rest. Test app: `apksim://software` (`SoftwareWindowActivity`,
  `scripts/software-window-check.py`, in `ci-smoke.sh`). Dimming is one layer, drawn just under the top-most drawn window with `FLAG_DIM_BEHIND` at its `dimAmount`, as the window manager's Dimmer keeps it (issue 507). `syncAndDraw` runs on its own thread; the main thread
  waits only for HWUI's sync (the `FrameDrawingCallback`), so recording overlaps raster.
- `gpu/`: AGSL → Metal. `AgslTranslator` puts uniforms and functions in one MSL struct. `TrackedRuntimeShader`
  renders on the GPU one frame ahead (`submit`/`collect`) and is drawn as a BitmapShader; a shader not drawn for a
  second has its frame collected by `sweep` (each one left behind mid-animation held a full-size Metal texture for
  good: the owner's app grew ~16 MB per visit to a shader screen). One shader drawn at several places in a frame (a
  shared RuntimeShader whose uniforms change between cards) gets a slot per draw site, keyed by the RenderNode and
  its draw's order in the frame, each with two bitmaps (HWUI may still be drawing the last frame's); past 32 draw sites
  of one shader in a frame, and in test runs past 32 draws (no frame boundary moves slots on), HWUI's CPU shader
  draws the rest. A software Canvas (`Canvas(bitmap)`) draws at once and shares one bitmap per shader; a Picture's canvas,
  which records for later, goes to the CPU. A node that does not clip to its bounds is not clipped to them (a glow past
  a card's edge), but a clip the app set inside it still bounds the shaded area (a clip that contains the node's
  bounds, mapped back through the canvas matrix, counts as none); strokes are padded by half their width times the miter limit or sqrt(2) for square caps.
  Self-test `shared+bleed shaders` (three unclipped cards of one shader): mean 0.144 max 2 against HWUI's CPU, the
  bleed edges' sampling. `uniform shader` inputs
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
  frame. sRGB ARGB_8888 only (other colour spaces and configs stay HWUI's, counted under `config`). A draw off whole pixels is left to HWUI, except a big one (256K device pixels or more: a full-width
  CENTER_CROP header, 1782 x 1002.4) which is snapped to the pixel grid, edges moving half a pixel at most and the size by under 0.2% (for the clipped CENTER_CROP case maxDelta 4 of 255
  against HWUI's own sampling; an unclipped fractional edge becomes a hard one; raster 8.3 → 2.2 ms on `apksim://bitmaps?header=1` in landscape, issue 491;
  `-Dapksim.gpuBitmapsSnap=false` keeps them HWUI's). A copy dropped from the
  cache stays alive in display lists still drawing it. `-Dapksim.gpuBitmaps=false` turns it off; `shaders` shows
  hits and why draws went to HWUI (`small`, `config` by Bitmap.Config, `new`, `budget`, `unaligned`). Metal targets
  are linear (buffer-backed, pooled): reading back a tiled texture made the CPU untile it, ~3 ms per full screen.
- `gpushader.m`: Metal pipelines for translated AGSL.
- `gl/`: GLES20/30 and EGL14 on a real GLES (ANGLE) for offscreen rendering in test runs (see "GLES").

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
The legacy `javax.microedition.khronos.egl.EGL10` (`EGLContext.getEGL()`) works too (issue 574): apksim.jar carries its
own `com.google.android.gles_jni.EGLImpl` and `EGLContextImpl` ahead of the framework's (that jar's EGLImpl is not
instrumented, so its natives cannot be shadowed, and its class initializer, like GLImpl's, called one nothing
implements: touching EGL10 ended the app). EGLImpl is EGL10 over `android.opengl.EGL14`, so a real EGL where
`ShadowSimEGL14` has one and Robolectric's stand-ins otherwise; a window surface is EGL14's pbuffer (512 square for a SurfaceTexture: its
default buffer size is not read). Not done: pixmap surfaces, `eglCopyBuffers`, `EGLContext.getGL()` (null; the GL10/GL11 interface). `GlesTest`
renders through it.

## Rules
- Some apps step their shader clocks at 30 Hz on purpose (the owner's does): idle shader screens at 30-60 fps can be
  by design. Measure 120 fps on interactions or the test app's Shader tab.
- A RenderNode whose last parent goes away (removed from every renderer's tree, or its renderer destroyed) loses its
  display list in HWUI and draws nothing until recorded again. `GpuRenderNode` closes a content raster only a frame
  after the content is drawn in the screen's tree again; destroying a HardwareRenderer also deletes the display lists
  of everything it draws, so it empties its root first.
