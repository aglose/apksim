# apksim on Linux (Docker, CI)

apksim also runs on Linux x86_64, headless, in a 488 MB image (`docker/Dockerfile`). The same image runs
natively on x86_64 CI runners and on Apple Silicon under Rosetta.

## Build

```bash
scripts/build.sh                     # the image takes work/apksim.jar as it is
scripts/docker-build.sh              # apksim:latest; trains the AOT cache inside the image
scripts/docker-build.sh apksim:dev --build-arg TRAIN=0   # quicker, no AOT cache
```

On a Mac this needs a Docker engine with Rosetta: `brew install colima docker docker-buildx`, then
`colima start --vm-type vz --vz-rosetta --cpu 4 --memory 8`. The build script runs Homebrew's buildx
directly, so `~/.docker/config.json` needs no plugin entry.

## Run

```bash
# Screenshot after the first frame
docker run --rm --platform linux/amd64 -v "$PWD:/work" apksim:latest --exit-after-first-frame --shot out.png app.apk

# Script it over the control channel (bound to all interfaces inside the container, published on loopback only)
docker run -d --rm --name sim --platform linux/amd64 -p 127.0.0.1:7400:7400 -v "$PWD:/work" \
  apksim:latest --control 7400 --control-bind 0.0.0.0 app.apk
sleep 10   # docker's port proxy accepts before the app listens: wait for the app, then connect
python3 scripts/drive.py 7400 'tap 733 1533' 'shot /work/after.png' 'quit'
```

Mount a volume at `/opt/apksim/work/cache` to keep app data and per-APK caches between runs. For no network,
use `docker run --network none` (`--no-network` is macOS-only).

## What differs from the Mac

| | Mac | Linux image |
|---|---|---|
| Window | Cocoa window | Headless only (`shot`, control channel) |
| AGSL shaders | Metal | CPU (correct, slower) |
| H.264 (WebRTC) | VideoToolbox | None yet |
| App native code | `lib/arm64-v8a` | `lib/x86_64` (most APKs ship it); 4 KB-aligned libraries load too |
| Sandbox | `sandbox-exec` | The container |
| JDK | 24 (Homebrew) | Temurin 24. JDK 25's AOT record mode verifies translated app classes and rejects them (missing stack maps), so training fails there |

Native libraries go through `native/elfloader.c` (shared with macOS, `#ifdef`s for x86_64 relocations and
TLS), `native/linux/bionic_linux.c` (bionic→glibc: only what differs, since both use the Linux kernel ABI and
SysV calls) and `native/linux/jnibridge_linux.c` (no varargs translation needed).

## Measured (M4 Pro, Colima 4 vCPU, Rosetta)

| | First frame |
|---|---|
| Test app, AOT cache | ~7.0 s (Mac native: ~2.0 s) |
| Test app, no AOT cache | ~8.0 s |
| the large app (loads `libsqliteJni.so` and WebRTC's `libjingle_peerconnection_so.so`) | ~6.7 s |

Most of the gap to the Mac is Rosetta translating the JVM. A native x86_64 runner has not been measured yet.

Checked in the container: test app taps, navigation and SQLite (Trips list); the large app sign-in screen and apps
sheet with CPU-rendered AGSL backgrounds. The device controls work headless too (checked in the container): `--appearance dark`,
`rotate`, `locale`, `fontscale`, `open URL`, `permission allow`, `notifications`, `location`, `clipboard`.

## CI sketch (GitHub Actions)

```yaml
jobs:
  ui-smoke:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: ./gradlew :app:assembleDebug
      - run: |
          docker run --rm -v "$PWD:/work" apksim:latest --exit-after-first-frame --shot shot.png \
            app/build/outputs/apk/debug/app-debug.apk
      - uses: actions/upload-artifact@v4
        with: { name: screenshot, path: shot.png }
```

The image is not published anywhere yet; push it to a registry (GHCR) for CI to pull.
