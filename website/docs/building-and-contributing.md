# Building from source and contributing

```bash
git clone https://github.com/aglose/apksim && cd apksim
scripts/setup.sh          # once: finds or fetches JDK 24, builds the runtime and the test app, trains the cache
./apksim app.apk
```

This needs the Xcode command-line tools (`xcode-select --install`).

- **JDK:** `setup.sh` uses a JDK 24 it finds, or downloads Eclipse Temurin 24 (checksum verified). Later JDKs run apksim but can't build its startup cache yet.
- **Android SDK:** needed only to build the test app (`testapks/composeapp`); without one, setup downloads the released test app.
- **Rebuilding:**
    - After a Java change: `scripts/build.sh && scripts/train.sh`.
    - After a native change: `scripts/build-native.sh`.
    - After moving the folder: `scripts/train.sh`, because the cache is tied to the folder's location.

Changes reach `main` through pull requests, and each needs the `linux-smoke` check to pass. CI builds the Linux image
once and runs, in parallel jobs, the test app headless, its androidTest suite in both clock modes, the DEX translator
check and a privacy check; `linux-smoke` passes when all of them did. `linux-smoke-arm64` runs the smoke test and the
suite in the arm64 image. On an Apple Silicon runner, `startup-gate`
fails a pull request whose launches are slower than their budget against a fixed release
([docs/startup-gate.md](https://github.com/aglose/apksim/blob/main/docs/startup-gate.md)). CI can't cover the other Mac-only parts (the window, Metal,
VideoToolbox), so say in the PR what you checked locally. [AGENTS.md](https://github.com/aglose/apksim/blob/main/AGENTS.md) is the guide to building,
running and measuring, and [docs/internals](https://github.com/aglose/apksim/tree/main/docs/internals) has the notes on how each part works.

`scripts/package-release.sh VERSION` packages a release. Pushing a `v*` tag builds it and makes a draft release, and
publishing the draft updates the Homebrew tap and the image.
