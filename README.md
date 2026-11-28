# apksim

[![ci](https://github.com/aglose/apksim/actions/workflows/ci.yml/badge.svg)](https://github.com/aglose/apksim/actions/workflows/ci.yml)
[![release](https://img.shields.io/github/v/release/aglose/apksim)](https://github.com/aglose/apksim/releases)
[![license](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)

**Run unmodified Android APKs on an Apple Silicon Mac as one ordinary process: no emulator, no virtual machine, no install step.**

Your app is on screen ~0.2–0.5 s after you press Enter, renders at up to 120 fps in a resizable native window, and can be driven by hand, from scripts, or with standard `adb` tooling. The same runtime executes instrumented test suites on macOS or headless in a Linux container several times faster than an emulator.

**Full documentation, features, guides and reference: [apksim.web.app](https://apksim.web.app)**

## Quick start

```bash
brew tap aglose/apksim && brew trust aglose/apksim && brew install apksim
apksim app-debug.apk
```

Run an instrumented test suite:

```bash
apksim --instrument app-debug-androidTest.apk app-debug.apk
```

See the [quick start](https://apksim.web.app/latest/quickstart/) and [installation guide](https://apksim.web.app/latest/installation/) for the install script, the headless Linux Docker image (`ghcr.io/aglose/apksim`), and startup cache details.

## At a glance

| | apksim | Android emulator |
|---|---|---|
| **To first frame** | ~0.2–0.5 s warm, ~1.1–1.6 s cold | 8–9 s from snapshot, ~18 s full boot |
| **Idle memory** | ~175–250 MB | 3–4 GB |
| **Device tests** | 4,107 tests in ~4.7 min (Mac) / 8.3 min (Linux) | ~19 min on two CI emulators |
| **What runs** | AOSP Android 16 framework, translated DEX, and native libs in one process | Full virtual device (kernel, system server, ART, system apps) |

Measured on an M4 Pro. See [performance](https://apksim.web.app/latest/performance/) and the [full comparison](https://apksim.web.app/latest/features/).

## Documentation and use cases

Visit **[apksim.web.app](https://apksim.web.app)** for detailed guides and reference:

- **Interactive simulator** — phone, foldable (with 3D fold animations), tablet, desktop, TV and Wear OS profiles, custom device skins, live resizing, rotation, dark mode, locale, location, biometrics, notifications and deep links ([simulator window](https://apksim.web.app/latest/simulator-window/), [devices & options](https://apksim.web.app/latest/devices-and-launch-options/), [skins](https://apksim.web.app/latest/using-skins/)).
- **Instrumented tests & CI** — Compose, Espresso, ActivityScenario and UiAutomator suites with JUnit XML, sharding, affected-test selection, failure bundles, code coverage, flake hunting and a headless Linux image ([instrumented tests](https://apksim.web.app/latest/instrumented-tests/), [Linux & Docker](https://apksim.web.app/latest/linux-and-docker/)).
- **Automation, `adb` & AI agents** — line-based control channel, standard `adb` device support, and an MCP server (`apksim mcp`) ([automation](https://apksim.web.app/latest/automation/), [adb device](https://apksim.web.app/latest/adb-device/), [MCP server](https://apksim.web.app/latest/mcp/)).
- **Browser previews & Beam** — `apksim serve` streams builds in a browser tab with side-by-side compare, view-only sharing, network inspection and PR preview comments; Beam runs builds locally at 120 Hz on a visitor's Mac ([apksim serve](https://apksim.web.app/latest/serve/), [Beam](https://apksim.web.app/latest/beam/), [PR previews](https://apksim.web.app/latest/pr-preview/)).
- **Network, snapshots & replay** — HAR [network record/replay and throttling](https://apksim.web.app/latest/network/), deterministic [screenshot tests](https://apksim.web.app/latest/screenshot-tests/), [flight recorder](https://apksim.web.app/latest/flight-recorder/), [replay](https://apksim.web.app/latest/replay/), [trace viewer](https://apksim.web.app/latest/trace-viewer/), [crawler](https://apksim.web.app/latest/crawl/), and scripted [BLE peripherals, Health Connect & multi-device scenes](https://apksim.web.app/latest/agents-ble-scenes/).
- **Debugging & configuration** — JDWP breakpoints in app Kotlin/Java sources, logcat, layout bounds and frame profiling ([debugging](https://apksim.web.app/latest/debugging/), [configuration reference](https://apksim.web.app/latest/configuration-reference/)).

## How it works

Three kinds of code share one address space:

- **Android framework** — real AOSP Android 16 (API 36) with Skia, HWUI and SQLite, and Robolectric shadows standing in for system services.
- **App code** — a lazy DEX-to-JVM bytecode translator paired with HotSpot JIT and a pre-booted standby JVM for warm launches.
- **Native code & GPU** — direct arm64 ELF loading with a bionic-to-macOS shim, Metal for AGSL shaders and layer effects, and VideoToolbox for video decode.

[![apksim system design](docs/architecture.svg)](https://apksim.web.app/latest/system-design/)

Read [system design](https://apksim.web.app/latest/system-design/) and [how it was built](https://apksim.web.app/latest/how-it-was-built/) for the architecture and engineering details.

## Limitations

apksim is a fast UI and logic simulator, not a replacement for a real device. Everything runs as API 36 with stand-in system services (no Play Services, simulated camera and sensors, no other installed apps), and native libraries must match the host ABI (arm64-v8a on macOS, generally 16 KB page-aligned). See [limitations](https://apksim.web.app/latest/limitations/) before relying on it for release verification.

## Building from source

Needs an Apple Silicon Mac and the Xcode command-line tools (`xcode-select --install`):

```bash
git clone https://github.com/aglose/apksim && cd apksim
scripts/setup.sh
./apksim app.apk
```

See [building and contributing](https://apksim.web.app/latest/building-and-contributing/) and [AGENTS.md](AGENTS.md) for rebuild commands and rules, with per-subsystem architecture notes in [docs/internals](docs/internals).

## License

Apache License 2.0 — see [LICENSE](LICENSE), [NOTICE](NOTICE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). Provided as is, with no warranty. Not affiliated with Google or Apple.
