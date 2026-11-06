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
docker run --rm -v "$PWD/out:/work" --entrypoint instrument.py ghcr.io/aglose/apksim:0.13.0 \
  app-debug.apk app-debug-androidTest.apk --isolate -j 4 --xml junit.xml
# A library's device-test APK (KMP androidDeviceTest) instruments itself: pass it as both
docker run --rm -v "$PWD/out:/work" ghcr.io/aglose/apksim:0.13.0 --instrument lib-androidTest.apk lib-androidTest.apk
```

A GitHub Actions job (on `ubuntu-24.04`, after the Gradle build of the APKs):

```yaml
      - name: Device tests on apksim
        run: |
          mkdir -p out && cp app/build/outputs/apk/debug/*.apk app/build/outputs/apk/androidTest/debug/*.apk out/
          docker run --rm -v "$PWD/out:/work" --entrypoint instrument.py ghcr.io/aglose/apksim:0.13.0 \
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
```

Test runs work as on the Mac. `scripts/instrument.py --apksim` runs the image for each process, with the APKs in the
mounted folder (named relative to it) and the JUnit XML written on the host:

```bash
python3 scripts/instrument.py --apksim "docker run --rm -v $PWD/out:/work apksim:latest" \
  app.apk test.apk --xml out/junit.xml
```

A process that goes `--test-timeout` seconds (default 900) without a test starting or ending is killed, its container
with it: instrument.py adds `--cidfile` to `docker|podman run` and runs `docker kill` (killing the client alone leaves
the container running), reports that test timed out, and runs the rest in a new container.

Keep the cache between runs with a volume at `/opt/apksim/work/cache` (translated classes, the per-APK manifest and
native libraries, app data). Without one every `docker run` translates the app's classes again: for a large app's
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
| H.264 (WebRTC) | VideoToolbox | None yet |
| GLES (test runs, offscreen) | ANGLE on Metal | Mesa llvmpipe (software), EGL's surfaceless platform (`EGL_PLATFORM=surfaceless`): pbuffers; a window surface is a pbuffer of its size, as on the Mac |
| App native code | `lib/arm64-v8a` | `lib/x86_64` on amd64, `lib/arm64-v8a` on arm64 (most APKs ship both); 4 KB-aligned libraries load too |
| Sandbox | `sandbox-exec` | The container |
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

## CI (GitHub Actions)

`.github/workflows/ci.yml` runs on every push to `main` and on pull requests, on `ubuntu-latest`: it fetches the
libraries (`gradle deps`), compiles the runtime with JDK 24 (`scripts/build.sh` takes `JAVA_HOME` on Linux), builds
the test app and the image (`scripts/docker-build.sh`), then runs `scripts/ci-smoke.sh`. That checks the first
frame, then over the control channel switches to Trips, renames a trip (a SQLite write) and checks the list shows
the new name. Screenshots and logs are uploaded as the `smoke` artifact. `linux-smoke-arm64` does the same with the
arm64 image on `ubuntu-24.04-arm`, without the adb device check (adb is x86_64-only on Linux), plus the test app's
suite; its APKs come from the `testapp` job, since Google's Android build tools are x86_64-only on Linux too. Run the
same test locally:

```bash
scripts/docker-build.sh apksim:ci
scripts/ci-smoke.sh apksim:ci testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk work/smoke
scripts/docker-build.sh apksim:ci-arm64 --platform linux/arm64   # the arm64 image; ci-smoke.sh runs its platform
```

Keep the output folder under your home folder: Colima shares only that with its VM. The workflow covers the Linux
path only; the Mac window, Metal, VideoToolbox and launch timings are not checked in CI.
