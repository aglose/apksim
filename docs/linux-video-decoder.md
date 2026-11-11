# MediaCodec video on Linux (issue 229): decision and work plan

Status: T1 and T2 done (the native library and the Java side); T3-T5 to do. Delete this file (or fold it into docs/linux-docker.md) when the work lands.

## Decision

**libavcodec (FFmpeg), built from source in the image's build stage with only the H.264 and HEVC decoders, linked
statically into `libvtdecoder.so`.** Software decoding, one thread per codec.

Why not the alternatives:

- **OpenH264** (already in the image for the serve stream). It does decode Main and High with B-frames since 2.0, so
  it is not Baseline-only as the issue says. But it has no HEVC (the test app's own `clip.mp4` is HEVC), no interlaced
  or 10-bit, refuses frames over 1 MB compressed, and its release notes fix wrong B-slice pictures as late as 2.7.0,
  newer than the image's `libopenh264-7`. Not good enough for arbitrary HLS/DASH content.
- **Ubuntu's `libavcodec60` package.** 34 dependencies (x264, x265, cairo, librsvg, AV1 encoders...). A source build
  with `--disable-everything` is a few MB and one notice entry.

For the owner to confirm before the image ships: this puts H.264 and HEVC decoders (LGPL 2.1+, patent-encumbered
formats) in a public image. Ubuntu ships the same code; dropping HEVC is one configure flag.

## Design (what every task below assumes)

libavcodec gives pictures back in presentation order, later than the input that carried them, where VideoToolbox gives
one per input in decode order and Java reorders. So Linux gets its own three natives, and the Mac path is untouched.

New natives in `VideoToolbox.java` (Linux only; `VideoToolbox.REORDERS` is `!HostOs.MAC`):

```java
/** Sends one access unit (Annex B) or parameter sets; length 0 starts draining at end of stream. <0: error. */
static native int nativeSend(long handle, ByteBuffer data, int offset, int length, long ptsUs);
/** The next finished picture (a handle for nativePicture*/nativeReleasePicture), its pts in pts[0]; 0 when none. */
static native long nativeReceive(long handle, long[] pts);
/** A seek: drops everything inside the decoder (avcodec_flush_buffers). */
static native void nativeReset(long handle);
```

Unchanged and implemented on Linux too: `nativeCreate(hevc)`, `nativeRelease`, `nativeReleasePicture`,
`nativePictureWidth/Height`, `nativePictureToRgba(picture, w, h, out, bgra)` (scale and convert in one `sws_scale`,
colour matrix and range from the frame, BT.709 limited when unspecified at 720p and up, else BT.601).
A picture handle is an `AVFrame*` from `av_frame_clone`; release is `av_frame_free`.

`ShadowSimMediaCodec.Decoder` when `REORDERS`:

- `queue()`: `nativeSend` with the input's pts, then `nativeReceive` in a loop. Each picture goes into `held` with the
  decoder's pts, and `depth` is 0 (nothing more to reorder). Inputs flagged DECODE_ONLY add their pts to a set; a
  received picture whose pts is in the set is released, not held. CODEC_CONFIG inputs are sent with no pts.
- `held()` also counts what is inside the decoder (sent and not DECODE_ONLY, minus received), so
  `native_queueInputBuffer` reserves an input buffer for each picture still inside, as it does today for held ones.
  More than `MAX_DEPTH` (4) inside means the stream's last pictures are lost at end of stream, as today.
- `drain()` (end of stream): `nativeSend(length 0)`, then receive until 0, into the list it returns.
- `dropPictures()` (flush/seek): also `nativeReset`, and clear the DECODE_ONLY set and the inside count.
- The csd-0/csd-1 loop in `onConfigured` uses `nativeSend`.

Decoder threads: `thread_count = 1`. Frame threading adds `threads - 1` pictures of delay, which the 4 reserved
buffers cannot carry. Revisit only if T5 shows it is too slow.

Out of scope: libwebrtc's H.264 on Linux (`VtH264Decoder`, `SimVideoDecoderFactory` stay Mac-only: split
`VideoToolbox.available()` from a new `webrtcAvailable()` that is `HostOs.MAC`), AAC/MP3 on Linux, hardware decoding.

## Tasks

Each is one pull request against `main`, in this order. T1 has no dependency; T2 needs T1; T3 needs T2; T4 and T5
need T3. Rules for all: AGENTS.md "Git and pull requests" (no personal information, no AI attribution, merge
commits), and say in the PR what was run and what was not.

### T1. Native decoder and image build (mid-tier model)

Files: `native/linux/avdecoder_linux.c` (new), `docker/Dockerfile`, `THIRD-PARTY-NOTICES.md`, a small C harness
`native/linux/avdecoder_check.c` (new, built and run in the build stage only).

- Dockerfile build stage, in a layer **before** `COPY native` so it is cached: fetch a pinned FFmpeg release tarball
  (check its sha256), `./configure --disable-everything --disable-programs --disable-doc --disable-network
  --disable-autodetect --enable-pic --enable-static --disable-shared --enable-decoder=h264,hevc
  --enable-parser=h264,hevc --enable-swscale`, plus `nasm` on amd64. Both `linux/amd64` and `linux/arm64`.
- `libvtdecoder.so` from `avdecoder_linux.c`, FFmpeg's `.a` files linked in with `-Wl,--exclude-libs,ALL` and
  `-Wl,-Bsymbolic` (an APK may bring its own FFmpeg). JNI names are `Java_dev_apksim_video_VideoToolbox_*`.
- The harness decodes an Annex B file through the same C functions the JNI methods call: every picture arrives, pts
  strictly increasing, the size is right, the centre-left pixel of `testsrc2` is red after `PictureToRgba`.
- Done when: both images build; the harness passes in the build stage on a 2 s H.264 clip with B-frames and an HEVC
  one (commit two small clips under `testapks/media-fixture/`, made with `scripts/make-media.sh`'s commands, under
  100 KB each); the final image grows by under 10 MB; `ldd libvtdecoder.so` shows no libav*.

### T2. Java side (mid-tier model, then a review by a stronger model or the owner)

Files: `video/VideoToolbox.java`, `video/ShadowSimMediaCodec.java`, `video/SimVideoDecoderFactory.java`,
`video/VtH264Decoder.java`.

- As "Design" above. `VideoToolbox.load()` loads `libvtdecoder` on Linux too; a load failure keeps today's behaviour
  (no decoders listed, the one log line from PR 230).
- The Mac's code path must be byte-for-byte the same decisions as now: every new branch is behind `REORDERS`.
- Done when: in the arm64 image (`scripts/docker-build.sh TAG --platform linux/arm64`, a tag of your own),
  `selftest /work/clip.h264` and `selftest /work/clip.h265` over `--control` report every unit rendered and in order;
  on the Mac `selftest work/media/clip.h264` and `.h265` report the same as before the change, and
  `scripts/build.sh && scripts/train.sh` pass.
- This is the risky task. The reviewer checks: end of stream with pictures still inside the decoder, a seek
  (`native_flush`) mid-stream, DECODE_ONLY inputs after a seek, `--video blank`, and that `reserved` never leaks a
  buffer.

### T3. CI check (mid-tier model)

Files: `scripts/ci-smoke.sh`, possibly `scripts/video-check.py` (new).

- Against the running image: `selftest` on the two fixture clips, then `open apksim://video`, `video` twice 3 s apart:
  `rendered` goes up by at least 30 and `decodeErrors` stays 0 (the clip is 1080p60; do not assert 60 a second on a
  shared runner). Then the Settings tab's "Play clip" (HEVC + AAC): its line reads `clip: ended at 3.0 s ... video
  video/hevc`.
- Done when `linux-smoke` and `linux-smoke-arm64` pass with it, and it fails when `libvtdecoder.so` is removed from
  the image (run that once by hand and say so in the PR).

### T4. Docs (small model)

Files: `docs/linux-docker.md` (the table row from PR 230, and "H.264 (WebRTC)" stays "None yet"), `AGENTS.md`
("Video", "Linux / Docker", "Open work"), `docs/serve.md` if it mentions the gap, `README.md` limits if listed.
Remove this file. State only what T1-T3 and T5 measured.

### T5. Measure (small model, runs commands only)

`apksim://video` for 20 s in the arm64 image and in the amd64 image under Rosetta: `video`'s rendered and dropped per
second, `maxDecodeMs`, and CPU from `stats`. Same for a 4K H.264 clip if one is at hand. Report the numbers on the
issue's pull request; no code. If 1080p60 drops more than a few percent natively on arm64, open a follow-up for
slice threading rather than changing T2.

## Size

T1 about a day (the image build on two architectures is most of it), T2 half a day plus review, T3 half a day,
T4 and T5 an hour or two each. About 2.5 days in all, a day more than the first estimate, which missed the
end-of-stream buffer accounting, the seek reset and that the apt package is not usable.
