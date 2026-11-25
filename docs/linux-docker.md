# apksim on Linux (Docker, CI)

apksim also runs on Linux, headless, in a 565 MB image (`docker/Dockerfile`; 488 MB before Mesa), for x86_64
(linux/amd64) and arm64 (linux/arm64). Each runs natively on its own CPU: x86_64 CI runners, arm64 runners (GitHub's
`ubuntu-24.04-arm`, Graviton) and Apple Silicon Macs. The amd64 image also runs on Apple Silicon under Rosetta, at
about half the speed of the arm64 one there.

## Prebuilt image

Each release publishes the image as `ghcr.io/aglose/apksim:VERSION` (and `:latest`, moved when a release is
published), built and checked by `.github/workflows/release.yml`. The tag holds both architectures, so `docker pull`
gets the host's own; `VERSION-amd64` and `VERSION-arm64` name one. It holds the launcher, a trained startup cache,
Mesa for offscreen GLES, and `instrument.py`, `snapshot.py` and `drive.py` on the `PATH`, so another repository's CI needs nothing of
apksim but the image. Pin a version: a new release can change how apps translate.

```bash
# A whole androidTest suite in one container, JUnit XML beside the APKs (one process per test, 4 at a time)
docker run --rm -v "$PWD/out:/work" --entrypoint instrument.py ghcr.io/aglose/apksim:0.64.0 \
  app-debug.apk app-debug-androidTest.apk --isolate -j 4 --xml junit.xml
# A library's device-test APK (KMP androidDeviceTest) instruments itself: pass it as both
docker run --rm -v "$PWD/out:/work" ghcr.io/aglose/apksim:0.64.0 --instrument lib-androidTest.apk lib-androidTest.apk
```

A GitHub Actions job (on `ubuntu-24.04`, after the Gradle build of the APKs):

```yaml
      - name: Device tests on apksim
        run: |
          mkdir -p out && cp app/build/outputs/apk/debug/*.apk app/build/outputs/apk/androidTest/debug/*.apk out/
          docker run --rm -v "$PWD/out:/work" --entrypoint instrument.py ghcr.io/aglose/apksim:0.64.0 \
            app-debug.apk app-debug-androidTest.apk --xml junit.xml
      - uses: actions/upload-artifact@v4
        if: always()
        with: { name: apksim-tests, path: out/junit.xml }
```

`-e class|notClass|package|notPackage` and `-- --device tablet` pass through as on the Mac (see `instrument.py
--help`). On a 4-CPU runner, run three suites at once (a container each) rather than one after another, split the
longest with `--shards 2`, and avoid `--isolate` where the suite allows: [parallel.md](parallel.md) has the
measurements (a large app's tests: 670 s one suite at a time, about 135 s that way). Tests run the Robolectric way on API 36 (AGENTS.md, "Instrumentation tests"), so keep an emulator for
anything that measures a real device, such as startup time.

## Build

```bash
scripts/build.sh                     # the image takes work/apksim.jar as it is
scripts/docker-build.sh              # apksim:latest; trains the AOT cache inside the image
scripts/docker-build.sh apksim:dev --build-arg TRAIN=0   # quicker, no AOT cache
scripts/docker-build.sh apksim:arm64 --platform linux/arm64   # the arm64 image (native on Apple Silicon)
```

The arm64 build needs android-all's `libandroid_runtime` for linux/aarch64, which the android-all build the runtime
uses lacks: `docker-build.sh` fetches it once from that build's respin into `work/linux-arm64` (`gradle
linuxArm64Runtime`, see `build.gradle.kts`).

On a Mac this needs a Docker engine: `brew install colima docker docker-buildx`, then
`colima start --vm-type vz --vz-rosetta --cpu 4 --memory 8` (Rosetta for the amd64 image; the arm64 one needs none). The build script runs Homebrew's buildx
directly, so `~/.docker/config.json` needs no plugin entry.

## Run

```bash
# Screenshot after the first frame
docker run --rm -v "$PWD:/work" apksim:latest --exit-after-first-frame --shot out.png app.apk

# Script it over the control channel (bound to all interfaces inside the container, published on loopback only)
docker run -d --rm --name sim -p 127.0.0.1:7400:7400 -v "$PWD:/work" \
  apksim:latest --control 7400 --control-bind 0.0.0.0 app.apk
sleep 10   # docker's port proxy accepts before the app listens: wait for the app, then connect
python3 scripts/drive.py 7400 'tap 733 1533' 'shot /work/after.png' 'quit'

# The same, answering only clients that have the token (any process that can reach the published port otherwise can)
head -c 24 /dev/urandom | xxd -p > control-token
docker run -d --rm --name sim -p 127.0.0.1:7400:7400 -v "$PWD:/work" \
  apksim:latest --control 7400 --control-bind 0.0.0.0 --control-token-file /work/control-token app.apk
APKSIM_CONTROL_TOKEN_FILE=control-token python3 scripts/drive.py 7400 'tap 733 1533' 'quit'
```

Test runs work as on the Mac. `scripts/instrument.py --apksim` runs the image for each process, with the APKs in the
mounted folder (named relative to it) and the JUnit XML written on the host:

```bash
python3 scripts/instrument.py --apksim "docker run --rm -v $PWD/out:/work apksim:latest" \
  app.apk test.apk --xml out/junit.xml
```

A process that goes `--test-timeout` seconds (default 900) without a test starting or ending is killed, its container
with it: instrument.py adds `--cidfile` to `docker|podman run` and runs `docker rm -f` (killing the client alone leaves
the container running), reports that test timed out, and runs the rest in a new container. Each process gets a
cidfile of its own (docker refuses to run with one that exists, which would fail every shard but the first and any
relaunch), so a `--cidfile` given in `--apksim` is taken out of the command, and the ID of the last container to end
is written there.

Keep the cache between runs with a volume at `/opt/apksim/work/cache` (translated classes, the per-APK manifest and
native libraries, app data). Per-APK entries are keyed by the APK's contents and evicted past 2 GB or 14 days unused
(`-Dapksim.apkCacheMB`), never while a container on the volume uses one. The image's own cache holds one file, the
framework's inflated resource table (`cache/framework`, 17 MB, mapped by every launch); a volume mounted there hides
it, and the first run inflates it into the volume once. Without one every `docker run` translates the app's classes again: for a large app's
journey run alone (one container per test, `instrument.py --isolate`) that was 15.6 s a test against 10.4-10.6 s with
the cache warm, under Rosetta.

```bash
python3 scripts/instrument.py --isolate -j 4 \
  --apksim "docker run --rm -v $PWD/out:/work -v $PWD/out/cache:/opt/apksim/work/cache apksim:latest" \
  app.apk test.apk --xml out/junit.xml
```

Containers started together can share it: files are written under a unique temporary name and renamed into place.
Not by PID: the image's JVM is PID 1 in every container, so four at once used to write the same temporary files. For no network, use `docker run --network none` (`--no-network` is
macOS-only).

## What differs from the Mac

| | Mac | Linux image |
|---|---|---|
| Window | Cocoa window | Headless only (`shot`, control channel) |
| AGSL shaders | Metal | CPU (correct, slower) |
| H.264 (WebRTC) | VideoToolbox | None yet (libwebrtc's own software codecs only) |
| MediaCodec video | VideoToolbox: H.264, HEVC | libavcodec, software, one thread per codec: H.264; HEVC only in an image built with it (see "Video" below) |
| MediaCodec audio (AAC, MP3), AudioTrack | AudioToolbox, CoreAudio | Decoders: libavcodec's AAC (LC; HE-AAC untested) and MP3, software (`libsimaudio.so`, see "Audio" below). AudioTrack: a virtual clock (`VirtualOut`) that times writes and feeds serve's sound tap, nothing played |
| GLES (test runs, offscreen) | ANGLE on Metal | Mesa llvmpipe (software), EGL's surfaceless platform (`EGL_PLATFORM=surfaceless`): pbuffers; a window surface is a pbuffer of its size, as on the Mac |
| App native code | `lib/arm64-v8a` | `lib/x86_64` on amd64, `lib/arm64-v8a` on arm64 (most APKs ship both); 4 KB-aligned libraries load too |
| Sandbox | `sandbox-exec` | The container |
| Warm launches (standby) | On by default | Opt-in: `APKSIM_STANDBY=1` (see "Warm launches" below) |
| JDK | 24 (`scripts/jdk.sh`: a JDK 24 found on the Mac, else Temurin 24 in `work/jdk-24`) | Temurin 24. JDK 25's AOT record mode verifies translated app classes and rejects them (missing stack maps), so training fails there |

Native libraries go through `native/elfloader.c` (shared with macOS, `#ifdef`s for x86_64 relocations and
TLS), `native/linux/bionic_linux.c` (bionic→glibc: only what differs, since both use the Linux kernel ABI and
the same calling convention) and `native/linux/jnibridge_linux.c` (no varargs translation needed). On arm64, Android
code reads its stack-protector guard at `[TPIDR_EL0 + 0x28]`, which on Linux is glibc's thread pointer: the loader
rewrites each `mrs xN, tpidr_el0` into an `adrp` of a read-only page placed after the module, holding the guard (as the
Mac points the read at `TPIDRRO_EL0`), and TLSDESC accesses resolve against that page. glibc's `pthread_mutex_t` and
`pthread_attr_t` are larger than bionic's on arm64 (48 and 64 bytes against 40 and 56), so the shim keeps a glibc
object beside each, as the Mac shim does. Both Linux shims map `setjmp`/`sigsetjmp` to glibc's `_setjmp`: glibc's
`setjmp` also saves the signal mask, past the end of bionic's smaller `jmp_buf`.

## Warm launches

A standby (AGENTS.md "Warm launches") is a JVM that has booted the framework and waits for the next launch, which
then skips the JVM's and the framework's start. On the Mac every launch uses one; in the image only when
`APKSIM_STANDBY=1` is set (`apksim serve --warm-standby 1` sets it for its sessions, docs/serve.md), since a container
that runs one app or one suite has no next launch to keep it for. With it, a launch's instance starts the next standby
a second after its first frame (or on exit), and the next launch hands over to it.

It pays off only in a container that outlives one launch, e.g. one kept running and launched into:

```bash
docker run -d --init --name sim -e APKSIM_STANDBY=1 -v "$PWD:/work" --entrypoint sleep apksim:latest infinity
docker exec sim apksim --headless --exit-after-first-frame app.apk   # cold; starts a standby on exit
docker exec sim apksim --headless app.apk                            # from the standby
```

Measured (arm64 image, 4 CPUs, the test app, its class cache warm): first frame 0.94-1.7 s cold, 0.22-0.23 s from a
standby. An idle standby holds ~175 MB of anonymous memory (~290 MB resident with the shared AOT cache and libraries);
it quits when the container's memory runs short (under a tenth of its cgroup limit and under 256 MB left, the
inactive file cache counted free; a heuristic, which may count a large active file cache as used), and the next launch is cold. The instance a standby becomes is not the launching
process's child, so the container's PID 1 must reap it when it exits: run long-lived containers with an init
(`docker run --init`), as `apksim serve` does itself (it runs under the image's tini when it is PID 1).

## Video

Apps' recorded H.264 video (MediaCodec: Media3/ExoPlayer, MediaPlayer-style players) decodes in the image:
`apksim.avc.decoder` is listed as on the Mac, and pictures draw into the player's TextureView or SurfaceView. The
published image has no HEVC decoder (`apksim.hevc.decoder` is not listed, as on a device without one, and one
`[apksim] MediaCodec video/hevc: no decoder in this image` line is logged): H.264 and HEVC are both patent-licensed
formats, and HEVC's licensing is the less settled, so the image apksim distributes leaves it out. An image you build
yourself gets it with `scripts/docker-build.sh TAG --build-arg VIDEO_DECODERS=h264,hevc`; MediaCodec lists whichever
decoders the library has. The decoder is FFmpeg's libavcodec (7.1.5, LGPL 2.1+, see THIRD-PARTY-NOTICES.md), built in the image's
build stage from the release tarball (checksum pinned in `docker/Dockerfile`) with only the decoders and parsers named
in `VIDEO_DECODERS` and libswscale, and linked statically into `libvtdecoder.so` with its symbols private, so an APK that brings
its own FFmpeg does not meet it. OpenH264, which the image has for `apksim serve`'s stream, was not used: no HEVC, no
interlaced or 10-bit, and a 1 MB limit on a compressed frame. Ubuntu's libavcodec package brings 34 dependencies.

Software decoding, one thread per codec. Measured on an Apple Silicon Mac (Colima, 4 CPUs), the test app's
`apksim://video` (a 1080p60 H.264 clip looping in a TextureView) over 20 s, twice each:

| Image | Pictures drawn | Dropped | Decode per picture | Slowest decode | CPU, whole process |
|---|---|---|---|---|---|
| arm64 (native) | 60.1-60.2 a second | 0 | 1.7-1.8 ms | 5 ms | ~0.5 core |
| amd64 (Rosetta) | 60.1 a second | 0 | 2.8 ms | 16 ms | ~0.85 core |

Not measured: a Linux host, 4K. The image is about 3 to 4 MB bigger for it (`libvtdecoder.so`: 2.8 MB arm64, 3.6 MB amd64).
`scripts/video-check.py` (run by `ci-smoke.sh` in both CI jobs) checks `selftest` on a small H.264 clip, that
`apksim://video` draws pictures without decode errors, and that no HEVC decoder is listed; with `--hevc`
(`VIDEO_CHECK_ARGS=--hevc` for `ci-smoke.sh`, for an image built with HEVC) it checks the HEVC clip too and that
ExoPlayer plays the Settings tab's HEVC clip to its end.

Limits: a stream whose decoder holds back more than five pictures loses the ones past that at its very end (the Mac
has the same limit); no local sound (no audio output; an AudioTrack's PCM still reaches a serve tab, AAC and MP3 are decoded: see "Audio"); libwebrtc's H.264 is not hooked up, so WebRTC
video uses whatever software codecs the APK's libwebrtc has; VP9 only in an image built with `VIDEO_DECODERS=h264,vp9` (the published one logs `no decoder here` at start), no AV1. `--video blank|off` work as on the Mac.

## Audio

MediaCodec lists `audio/mp4a-latm` (`apksim.aac.decoder`) and `audio/mpeg` (`apksim.mp3.decoder`) on Linux as on the Mac
(issue 548), so a player's audio renderer gets a decoder and writes the PCM to its AudioTrack, whose `VirtualOut` clock and
`AudioTap` already carry it to a serve tab. The decoders are FFmpeg's `aac` and `mp3float`, built in the image's build stage
from the same tarball as the video ones (`--build-arg AUDIO_DECODERS=aac,mp3float`, the default; empty builds none, and
MediaCodec then lists no audio decoder, as before) and linked statically into `libsimaudio.so` (`native/linux/avaudio_linux.c`:
the Linux half of `dev.apksim.video.SimAudio`, its decoder natives only; the Mac's library also has the output) with its
symbols private. Output is 16-bit interleaved PCM (libavcodec's float or planar frames converted in the library). The
decoder takes the stream's AudioSpecificConfig (csd-0), so an HE-AAC config's doubled rate and parametric stereo's two
channels are in the output format the app is told; HE-AAC signalled only in the frames (ADTS, MPEG-TS) is decoded with SBR by libavcodec, and the app is told the new rate and channels
when the first PCM with them arrives (`ShadowSimMediaCodec`, from the decoder's own report; `SimAudio.nativeSniffSbr` answers 0 here). HE-AAC is not
checked on Linux yet: the fixtures are AAC-LC and MP3.
The build stage decodes a 440 Hz tone per codec (`native/linux/avaudio_check.c`: access units, rate, channels, pitch, a reset, and
that an absent decoder does not open); the clips are `testapks/media-fixture/clip.aac` and `clip.mp3`.

## Measured (M4 Pro, Colima 4 vCPU)

The same source built as both images, run interleaved on a busy Mac (load average 6-7), 2026-11-05. Each `docker run`
is a fresh container, so no class cache: first frames include translating the app.

| | arm64 image (native) | amd64 image (Rosetta) |
|---|---|---|
| Test app, first frame | 3.4-3.6 s | 11.0-11.7 s |
| A large R8 app (loads `libsqliteJni.so` and WebRTC's `libjingle_peerconnection_so.so`), first frame | 3.1-3.3 s | 11.0-11.4 s |
| Test app's androidTest suite (104 tests, `instrument.py`, one container) | 15-16 s | 39-41 s |

On Apple Silicon the arm64 image is 2.5-3.5 times faster: Rosetta translates the JVM and every method it compiles.
Earlier, on a quieter machine, the amd64 image's first frame was ~7.0 s (Mac native: ~2.0 s). On CI runners each
image runs natively, so there the difference is the runners' own CPUs, not yet measured side by side.

Checked in the container: test app taps, navigation and SQLite (Trips list); the large app's sign-in screen and bottom
sheet with CPU-rendered AGSL backgrounds. The device controls work headless too (checked in the container): `--appearance dark`,
`rotate`, `locale`, `fontscale`, `open URL`, `permission allow`, `notifications`, `location`, `clipboard`.

## Memory

An idle instance of the test app, 40 s after its first frame, with a warm class cache (2026-11-15, the same Colima
VM; `/proc/PID/smaps`). RSS is what `stats` reports on Linux (`footprintMb`); the private anonymous part is what the
process alone holds, the rest file pages (the startup cache, DEX files, libraries) that processes of one image share
and the kernel can drop:

| RSS, MB (private anonymous) | Before | Now |
|---|---|---|
| arm64 image, idle | 413 (324) | 330 (203) |
| arm64, an animation running (the Shader tab) | 475 (385) | 412 (284) |
| arm64, idle again 30 s later | 476 (386) | 361 (233) |
| amd64 image under Rosetta, idle | 595 (501) | 492 (362) |
| amd64 under Rosetta, the animation running | 680 (572) | 572 (441) |
| Four arm64 instances in one container, in all (PSS) | | 957 (1,085 with the cache moved) |
| A large R8 app, arm64, idle at its first screen | 432 (352) | 351 (233) |
| CI's x86-64 runner, a serve session at the end of its check | 593-619 | 523-545 |

Rosetta's translator adds 160-180 MB to an instance; on a real x86-64 host (CI's runner) a session is near the arm64
figures. Three things changed (docs/perf-plan.md, "Memory in the Linux image", has the A/B runs and what each costs,
which is nothing measurable in frames or launch time):

- The Java heap's unused pages go back to the kernel 3 s after the screen goes still (`HeapTrim`, `madvise`;
  `--jvm -Dapksim.heapTrim=false` turns it off).
- The startup cache is mapped where it was built for (`-XX:ArchiveRelocationMode=0`; the JDK's default address is
  free on Linux), so ~38 MB of it stay file pages shared between instances instead of a private copy in each. It
  gives up the random placement of the JVM's class metadata: `-e APKSIM_AOT_RELOCATE=1` keeps the JDK's relocation,
  for a server whose sessions run APKs you do not trust.
- glibc's malloc is trimmed every 5 s (`-XX:TrimNativeHeapInterval=5000`; `--jvm -XX:TrimNativeHeapInterval=0` turns
  it off): it keeps what was freed in each thread's arena otherwise, about 35 MB after a launch and more after a busy
  screen.

Test processes (`--instrument`) get the last two; the heap trim is the running instance's.

On a machine with 128 GB of memory or more, the JVM's default heap (a quarter of it) would turn compressed pointers
off, and with them the startup cache; the launcher's `-XX:+UseCompressedOops` caps that default at ~30 GB instead.
An `--jvm -Xmx` of 32 GB or more still turns the cache off (31 GB keeps it).

## CI (GitHub Actions)

`.github/workflows/ci.yml` runs on every push to `main` and on pull requests, on `ubuntu-latest`: it fetches the
libraries (`gradle deps`), compiles the runtime with JDK 24 (`scripts/build.sh` takes `JAVA_HOME` on Linux), builds
the test app and the image (`scripts/docker-build.sh`) in its `build` job, then hands the image to five `linux` legs
that run `scripts/ci-smoke.sh` (split with `SMOKE_PARTS`: `app`, `device`, `replay`, `crawl`, `serve`) and the test
suite side by side. The smoke test checks the first frame, then over the control channel switches to Trips, renames a
trip (a SQLite write) and checks the list shows the new name, and goes on from there. Screenshots and logs are uploaded
as each leg's `smoke-LEG` artifact. `linux-smoke-arm64` runs every part with the arm64 image on `ubuntu-24.04-arm`,
without the adb device check (adb is x86_64-only on Linux), plus the test app's suite; its APKs come from `build`,
since Google's Android build tools are x86_64-only on Linux too. Run the same test locally (all parts unless
`SMOKE_PARTS` names some, e.g. `SMOKE_PARTS="app serve"`):

```bash
scripts/docker-build.sh apksim:ci
scripts/ci-smoke.sh apksim:ci testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk work/smoke
scripts/docker-build.sh apksim:ci-arm64 --platform linux/arm64   # the arm64 image; ci-smoke.sh runs its platform
```

Keep the output folder under your home folder: Colima shares only that with its VM. The workflow covers the Linux
path only; the Mac window, Metal, VideoToolbox and launch timings are not checked in CI.
