---
title: Features
---

<div class="page-hero" markdown>

# What apksim can do

<p class="sim-lead">Run unmodified Android APKs locally at native speed, share interactive builds in a browser, and run your instrumented tests in CI without a single virtual machine.</p>

<nav class="page-jumps" aria-label="Feature categories">
<a href="#run-develop">Run &amp; develop</a>
<a href="#drive-share">Drive &amp; share</a>
<a href="#test-ci">Test &amp; CI</a>
<a href="#record-explore">Record, replay &amp; explore</a>
<a href="#vs-emulator">vs. Android emulator</a>
</nav>

</div>

## Run &amp; develop {#run-develop}

<div class="sim-cards" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Speed</div>
**Sub-second launches**

Warm launches put the first frame on screen in 0.2–0.5 s and open the window in ~10 ms — no VM boot and no `adb install` step.

<div class="sim-card-link" markdown>[Performance →](performance.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Runtime</div>
**Real Android 16 framework**

Runs AOSP Android 16 (API 36), Skia/HWUI rendering, SQLite, your app's DEX code (translated lazily to JVM bytecode), and arm64 native libraries.

<div class="sim-card-link" markdown>[System design →](system-design.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Simulator UI</div>
**Full simulator window**

Live freeform window resizing, device skins, animated foldables, rotation, dark mode, locales, font scaling, location routes, biometrics, and deep links.

<div class="sim-card-link" markdown>[The simulator window →](simulator-window.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Hardware acceleration</div>
**Metal GPU &amp; VideoToolbox**

AGSL shaders, Compose layer effects, and scaled bitmaps run on Metal at up to 120 fps. H.264, HEVC, and VP9 video decode on VideoToolbox.

<div class="sim-card-link" markdown>[Performance →](performance.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Devices &amp; skins</div>
**Phones, tablets, foldables &amp; Wear OS**

Built-in profiles and skins for Pixel Fold, Galaxy S25/Z Fold/Flip/TriFold, tablets, desktop, TV, and Wear OS — plus custom device frames.

<div class="sim-card-link" markdown>[Devices &amp; options](devices-and-launch-options.md) · [Skins](using-skins.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Debugging</div>
**IDE breakpoints &amp; profiling**

Attach Android Studio or IntelliJ over JDWP (`--debug`) to step through Kotlin/Java sources, stream logcat, inspect layout bounds, and profile frame stages.

<div class="sim-card-link" markdown>[Debugging →](debugging.md)</div>
</div>
</div>

## Drive &amp; share {#drive-share}

<div class="sim-cards" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Scripting</div>
**Control channel &amp; fleets**

Drive one or dozens of headless instances over a simple line-based TCP channel (`drive.py`, `ctl.py`, `fleet.py`) with pixel taps, accessibility queries, and screenshots.

<div class="sim-card-link" markdown>[Automation →](automation.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Standard tooling</div>
**Built-in `adb` device**

Launch with `--adb` and your instance appears in `adb devices` — supporting `input`, `screencap`, `uiautomator dump`, `logcat`, `pm`, `am`, and file sync.

<div class="sim-card-link" markdown>[adb device →](adb-device.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">AI agents</div>
**Native MCP server**

`apksim mcp` lets AI coding agents launch APKs, interact by element ref or text, and read compact screen diffs (~18 tokens per action).

<div class="sim-card-link" markdown>[MCP server →](mcp.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Browser streaming</div>
**Run APKs in a browser tab**

`apksim serve` streams an interactive APK per tab over H.264/JPEG with audio, kept sessions, and view-only share links tested up to 1,500 concurrent viewers.

<div class="sim-card-link" markdown>[apksim serve →](serve.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Local acceleration</div>
**Beam on the visitor's Mac**

Open a `serve` link on an Apple Silicon Mac with apksim installed and run the app locally at 120 Hz inside the same browser tab — using zero server sessions.

<div class="sim-card-link" markdown>[Beam →](beam.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Code review</div>
**PR previews &amp; side-by-side diffs**

Post live preview links on every pull request with a GitHub Action, drive two builds simultaneously, and highlight pixel differences in real time.

<div class="sim-card-link" markdown>[PR previews →](pr-preview.md)</div>
</div>
</div>

## Test &amp; CI {#test-ci}

<div class="sim-cards" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Device tests</div>
**Instrumented test suites**

Run Compose UI, Espresso, ActivityScenario, and UiAutomator test APKs with `--instrument`, JUnit XML output, shards, and per-test process isolation.

<div class="sim-card-link" markdown>[Instrumented tests →](instrumented-tests.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">CI container</div>
**Headless Linux Docker image**

Multi-arch (`x86_64` and `arm64`) `ghcr.io/aglose/apksim` image runs on standard Linux CI runners with no KVM or macOS runners required.

<div class="sim-card-link" markdown>[Linux and Docker →](linux-and-docker.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Faster CI</div>
**Affected tests &amp; balanced shards**

Record class-level test dependencies with zero measurable overhead so PRs only run tests affected by changed files, balanced by measured duration.

<div class="sim-card-link" markdown>[Affected tests →](affected-tests.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Triage</div>
**Failure bundles &amp; flake hunter**

Every failing test saves a screenshot, accessibility tree, logcat, thread dump, and rerun script. `flake-hunt.py` diagnoses order vs. timing flakes automatically.

<div class="sim-card-link" markdown>[Failure bundles](instrumented-tests.md#failure-bundles) · [Flake hunter](flake-hunt.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Visual regression</div>
**Deterministic screenshot tests**

Capture byte-identical golden screenshots of real APKs across device, dark mode, locale, and font-scale matrices with an HTML diff report.

<div class="sim-card-link" markdown>[Screenshot tests →](screenshot-tests.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Coverage</div>
**Zero-rebuild code coverage**

Pass `--coverage` to measure method and line coverage of unmodified APKs and export JaCoCo XML, LCOV, or HTML reports — no JaCoCo agent needed.

<div class="sim-card-link" markdown>[Code coverage →](coverage.md)</div>
</div>
</div>

## Record, replay &amp; explore {#record-explore}

<div class="sim-cards" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Bug reports</div>
**Flight recorder (`.apkrec`)**

Continuously buffer the last minutes of input, screen video, logcat, HTTP exchanges, SQLite calls, and checkpoints into one shareable bundle.

<div class="sim-card-link" markdown>[Flight recorder →](flight-recorder.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Reproduction</div>
**Deterministic replay &amp; bisect**

Replay any `.apkrec` from its checkpoint with recorded network responses, bisect a folder of builds to find regressions, or export a node-based journey test.

<div class="sim-card-link" markdown>[Replay →](replay.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Debugging UI</div>
**Static browser trace viewer**

Scrub `.apkrec` recordings and CI failure bundles locally in your browser — synchronized timeline, screen frames with touch markers, network, logs, and SQLite.

<div class="sim-card-link" markdown>[Trace viewer →](trace-viewer.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Exploration</div>
**Automated screen crawler**

Walk an APK without writing tests to build a clickable screen map, diff two builds visually, or run resilience sweeps (process death, offline, RTL, large text).

<div class="sim-card-link" markdown>[Crawler →](crawl.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Network</div>
**HAR record, replay &amp; conditions**

Record HTTP(S) traffic into standard HAR 1.2 files, answer completely offline in dev or CI, or throttle connections to 3G, lossy, or offline at runtime.

<div class="sim-card-link" markdown>[Network →](network.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Peripherals &amp; multi-device</div>
**BLE, Health Connect &amp; scenes**

Script Bluetooth LE GATT peripherals, seed Health Connect records, or coordinate multiple linked app instances (e.g. chat users or phone + watch) in one script.

<div class="sim-card-link" markdown>[BLE](ble.md) · [Health Connect](health-connect.md) · [Scenes](scenes.md)</div>
</div>
</div>

## Compared with the Android emulator {#vs-emulator}

An emulator boots a full virtual device. apksim runs your app and Android's framework inside one native process and replaces system services outside the app with stand-ins — that trade-off is where both the speed and the boundaries come from.

| | apksim | Android emulator |
|---|---|---|
| **Runtime** | Single process: AOSP Android 16 framework, lazily translated DEX, native libs loaded directly | Full VM: Linux kernel, system server, ART, system apps |
| **First frame** | 0.2–0.5 s warm, 1.1–1.6 s cold (~10 ms window) | 8–9 s Quick Boot snapshot, ~18 s full boot |
| **Memory** | ~175–250 MB idle (350–500 MB on heavy bitmap/video screens) | 3–4 GB per instance |
| **Install step** | None (`apksim app.apk`) | `adb install` onto booted device |
| **Density** | 32 headless instances in ~7 GB on a 12-core Mac | A few multi-GB VMs |
| **Frame rate** | Up to 120 fps paced to display vsync | Depends on host GPU emulation |
| **CI device tests** | 4,107 tests in 4.7 min on Mac, 8.3 min in Linux container (~55% fewer CI minutes, no KVM) | ~19 min on two CI emulators (requires KVM or macOS runners) |
| **API levels** | Runs on Android 16 (API 36); apps targeting API 1–36 run with `targetSdk` compat gates | Any system image and API level |
| **System services** | Stand-ins (no Play Services; simulated camera, sensors, BLE, Health Connect) | Real system services & Google Play images |
| **Native libraries** | `arm64-v8a` on Mac & arm64 Linux (16 KB-aligned on macOS); `x86_64` on x86_64 Linux | Matches system image ABI (4 KB or 16 KB pages) |

### When to use which

<div class="sim-cards" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Best fit</div>
**Use apksim**

For fast inner-loop UI iteration, instrumented UI & logic tests in CI, golden screenshot matrices, hermetic network runs, browser PR previews, and multi-instance fleets.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Device fidelity</div>
**Keep an emulator**

For Google Play Services, real camera/sensor hardware, cross-app intents, older Android OS versions, real startup profiling, and final pre-release sign-off.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Side by side</div>
**Use them together**

Run apksim with `--adb` so existing `adb` tooling works against either target, or use `scripts/both.py` to mirror identical input to both and diff their screens.
</div>
</div>
