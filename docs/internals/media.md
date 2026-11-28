# Video, audio, camera and per-instance costs

Agent reference notes, moved out of AGENTS.md so they are read only when needed. A section another doc or a
code comment cites as `AGENTS.md "Name"` is in one of these files: AGENTS.md's index says which.

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
codecs by name; a miss falls back to Robolectric's 512-byte fake). The AAC decoder takes its format from the stream's
AudioSpecificConfig (csd-0), not the app's rate: AudioToolbox lists the layers it describes and the richest it plays is
decoded (HE-AAC v2, HE-AAC, AAC-LC), and the codec's output format (`getOutputFormat`, and Media3's asynchronous
output-format callback, which Robolectric rebuilds from the configure call's keys) gets that layer's rate and channels,
as a device's decoder reports them. Players read only an HE-AAC config's AAC-LC core (22050 Hz, mono for v2), and before
(2026-11-15) apksim decoded just that core: half the rate, no SBR band, no parametric stereo (`play aac-he*.m4a` in
`scripts/media-protocols-check.py`). ADTS and MPEG-TS can signal HE-AAC only in the frames (implicit: an AAC-LC
config at up to 24 kHz): the first frame goes, behind an ADTS header, to AudioToolbox's own ADTS parser
(`SimAudio.nativeSniffSbr`), which finds SBR and parametric stereo in it; found, the decoder is made again from the
explicit config that stands for (`explicitHe`) and the app gets a new output format before any PCM
(`outputFormatChanged`: the next dequeue's INFO_OUTPUT_FORMAT_CHANGED, or the callback Robolectric posts in async mode),
as a device's decoder does once it sees SBR. Up to 8 frames are tried (a low-bitrate stream's first frame can be too
short to show it: `aac-he-24k.adts`). A genuine 22.05 kHz AAC-LC stream stays at its rate. Limits: the parser lists a PS
layer for every mono core with SBR, so mono HE-AAC v1 is taken as v2 (stereo out); and in synchronous mode a thread
already blocked in dequeueOutputBuffer gets the first PCM before the format change. Robolectric allocates a codec's 10 input buffers,
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
through a CoreAudio AudioUnit (on Linux a `VirtualOut` clock, nothing played), and its playback head moves only as frames are played: Robolectric's counts everything
written as played at once, which would let ExoPlayer's audio clock race. `--jvm -Dapksim.mute=true` keeps the timing
and plays silence; `--audio off` turns sound and the audio decoders off (`--audio mute`, `--jvm -Dapksim.mute=true` and
`-Dapksim.audio=false` still work). `--video blank|off` and `video on|blank`: see "Per-instance costs". Bitmaps here are BGRA in memory (Skia's macOS order),
not Android's RGBA: write pixels in `VtH264Decoder.BITMAP_BGRA` order.
The framework `MediaPlayer` in running instances (`ShadowSimMediaPlayer`, issue 488): a data source nobody registered
with Robolectric's addMediaInfo() (path, Uri, fd + offset + length, AssetFileDescriptor, MediaDataSource) gets the
duration its container gives (`MediaProbe`: WAV, MP3 by Xing/Info less the LAME delay and padding or by CBR, ADTS
frames, MP4/M4A mvhd, Ogg Vorbis/Opus, FLAC; else 1 s, as for http(s) and a MediaDataSource, whose reader is never
called) and plays silently, so onPrepared and onCompletion come on time; Robolectric threw "Don't know what to do
with dataSource" and the app died. A local file that cannot be opened throws FileNotFoundException, as on a device.
Sources this class registered are probed again at each call (Robolectric's keys are coarse), one registered by anyone
else is left alone. The probe reads the app's descriptor in place, never closes it, and clears an interrupt flag for
the read (an interrupted channel read would close the app's descriptor). No sound yet; test runs keep Robolectric's
strict shadow; SoundPool is still Robolectric's. `apksim://mediaplayer?src=NAME` (`ci-smoke.sh`),
`scripts/check-media-probe.sh` (CI, `MediaProbeCheck`).
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
(`VideoToolbox.webrtcAvailable()`), and Linux has AAC/MP3 decoders (libavcodec, docs/linux-docker.md "Audio") but no audio output. The build stage runs
`native/linux/avdecoder_check.c` on `testapks/media-fixture/clip.h264` (B-frames, pts, size, colours, a reset; `.h265`
too when HEVC is built, else it checks HEVC does not open).
Control commands: `video` (decoded/drawn counts, surfaces, `codecsConfigured` (all video codecs so far), each view's own `pictures=N` and `no codec attached` until a codec (configured with it or moved to it) or a publisher reaches it; MediaCodec counters with decode/draw ms, the video decoders
MediaCodecList lists (`listed`), and each live
codec's output: a view size, or `untracked` when the player moved it to its own placeholder surface, i.e. it has no
view to draw to; once a video codec has been configured, a laid-out view still with neither after 3 s gets one stderr
line, once per view, from the daemon thread `apksim-video-watch`), `webrtclog [info|verbose]`,
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
the accessibility tree, fills them, checks they stuck, presses `submit`, and logs field names only; a `restart` makes it
look for the form again for 10 s (`idle: ...` after that), and the `autofill` command answers where it is (`waiting`, `filling`, `done: filled A, B;
pressed X`, `gave up: ...`, or none saved / `--no-autofill`). `crawl.py --autofill` waits on it before reading a screen
with a password field (docs/crawl.md "Signed-in walks"; the test app's `apksim://login`, `tester` / `apksim-test`,
`LoginActivity`, checked by `crawl-check.py`); without it the crawler passes `--no-autofill`. Never read,
print or edit the owner's file, and never type the owner's password: if it is missing, ask the owner.

## Camera
`--camera fake` (`Cameras`, issue 219; `fake=IMAGE` for a still image, resolved like any path) declares
android.hardware.camera, camera.any and camera.front and lists two cameras in CameraManager, as an emulator: "0" back
(sensor 90) and "1" front (270), LIMITED, in YUV_420_888, JPEG and PRIVATE at 30 fps: 1920x1080, 1440x1080, 1280x720,
1024x768, 960x720 and 640x480 (`FakeCamera.sizes`; 16:9 alone made a CameraX bind that requires a 4:3 size with
FALLBACK_RULE_NONE throw before any openCamera, which an app may retry for ever: issue 414), or exactly the sizes
`--camera-sizes WxH[,WxH...]` names (`-Dapksim.cameraSizes`, warm launches too; the sensor array is their bounding box).
A preview Surface's pictures have its buffer size when the camera lists it (a TextureView's setDefaultBufferSize, kept by
`video.ShadowSimSurfaceTexture`; a SurfaceView's fixed or own size: CameraX's PreviewView lays it out at the resolution),
else 1280x720. `camera` lists the sizes. CameraX counts a size larger than the display (1440x1080 on the phone's
822x1782) as a RECORD stream, so two such use cases are no LIMITED combination, as on a device with that display. A front
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

## Code notes
- `AudioFx`: `android.media.audiofx.Visualizer` (issue 514): Robolectric leaves its natives unshadowed, so `getCaptureSizeRange()` answered null and `getMaxCaptureRate()` 0, and an app sizing its capture from them died on the first play. They answer as AOSP's (128..1024, 20000 mHz, 44.1 kHz); a visualizer's setters return SUCCESS and only the capture size reads back (enabled and the modes read as defaults); it gets no data, and a size change while enabled is not refused. `VisualizerTest`.
- `video/VirtualOut`: see `AudioTap` below. In the Linux image MediaCodec's AAC and MP3 decoders are libavcodec's (`native/linux/avaudio_linux.c` → `libsimaudio.so`, the decoder half of `SimAudio`; `SimAudio.decodersAvailable()` vs `available()`, which is output and Mac only; `AUDIO_DECODERS` build arg; checked in the Dockerfile by `avaudio_check.c`: docs/linux-docker.md "Audio", issue 548).
- `video/AudioTap` (`audiowatch`, issue 532): the app's AudioTrack output as 16-bit PCM `u PRESENTED_US TRACK RATE
  CHANNELS BASE64` lines for a serve tab that asked (`/ws?audio=stream`, `Session.watchAudio`; docs/serve.md "Sound";
  `scripts/audio-tap-check.py`, `serve-audio-check.py`). On the Linux image the tracks run on `video/VirtualOut`, a clock with no sound device (issue 545): the head moves at the track's rate from `play()`, writes take what fits the buffer and a blocking one waits for room, so the tap and a player's pacing see the Mac's timing; running instances only (test runs and `--audio off` keep Robolectric's); `-Dapksim.virtualAudio=true` uses it on a Mac; `scripts/check-virtual-out.sh` (CI). The image's AAC/MP3 decoders are libavcodec's (issue 548), so a player's compressed audio reaches the tap too.
- `Cameras`, `camera/` (`FakeCamera`, `Scene`, `ShadowSimCameraCaptureSession`, `ShadowSimImageReader`,
  `ShadowSimCameraManager`): the simulated cameras (`--camera fake`, see "Camera").
