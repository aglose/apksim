---
title: apksim
hide:
  - navigation
  - toc
---

<div class="hero" markdown>
<div markdown>

# Run Android APKs on a Mac without an emulator

<p class="lead">apksim runs an unmodified APK as one ordinary macOS process: no virtual machine and no install
step. Your app is on screen in about a quarter of a second, and device tests run several times faster than on an emulator.</p>

```bash
brew tap aglose/apksim && brew trust aglose/apksim && brew install apksim
apksim app-debug.apk
```

[Quick start](quickstart.md){ .md-button .md-button--primary }
[Explore features](features.md){ .md-button }

<p class="hero-release">Release <a href="https://github.com/aglose/apksim/releases/tag/v@VERSION@">@VERSION@</a> · <a href="changelog/">Changelog</a></p>

</div>
<div class="device-wrap" markdown>
<figure class="device" markdown>
![The apksim test app's home screen in Material 3 Expressive: a search field, a counter card, a list of places and a navigation bar with Home, Trips, Settings and Shader](site-assets/app-light.png){ .only-light }
![The apksim test app's Trips screen in dark mode: three trip cards and a note field](site-assets/app-dark.png){ .only-dark }
</figure>
<p class="hero-note">Captured directly by apksim, headless.</p>
</div>
</div>

<div class="stat-strip">
<div class="stat-card">
<div class="stat-value">0.2–0.5 s</div>
<div class="stat-label">Warm first frame</div>
<div class="stat-note">Window opens in ~10 ms; 8–18 s on a stock emulator</div>
</div>
<div class="stat-card">
<div class="stat-value">~200 MB</div>
<div class="stat-label">Idle memory</div>
<div class="stat-note">3–4 GB for a single Android emulator VM</div>
</div>
<div class="stat-card">
<div class="stat-value">120 fps</div>
<div class="stat-label">Metal &amp; VideoToolbox</div>
<div class="stat-note">AGSL shaders, layer effects, and H.264/HEVC/VP9</div>
</div>
<div class="stat-card">
<div class="stat-value">4× faster</div>
<div class="stat-label">CI device tests</div>
<div class="stat-note">4.7 min on Mac vs. ~19 min on CI emulators</div>
</div>
</div>

## What you can do with it

<div class="grid cards" markdown>

-   **Native simulator window**

    Resizable freeform window, device skins, animated foldables, dark mode, locale, font scaling, location, biometrics, and deep links right from the macOS menu bar.

    [The simulator window →](simulator-window.md)

-   **Instrumented tests & CI**

    Run Compose UI, Espresso, ActivityScenario, and UiAutomator suites without an emulator — with JUnit XML, time-balanced shards, coverage, and per-failure bundles.

    [Instrumented tests →](instrumented-tests.md)

-   **Script, adb & AI agents**

    Drive instances from a line-based control port, attach standard `adb` tooling, or connect AI agents over the built-in MCP server.

    [Automation](automation.md) · [adb device](adb-device.md) · [MCP server](mcp.md)

-   **Share builds in a browser**

    `apksim serve` streams an APK per tab for QA, PMs, and PR previews with side-by-side diffs — or runs locally at 120 Hz on a visitor's Mac with Beam.

    [apksim serve](serve.md) · [Beam](beam.md) · [PR previews](pr-preview.md)

-   **Headless Linux & Docker**

    Multi-arch (`x86_64` and `arm64`) CI image with no KVM required, preloaded with a trained startup cache and test, snapshot, and crawler tools.

    [Linux and Docker →](linux-and-docker.md)

-   **Flight recorder & replay**

    Save the last minutes of input, screen, logs, SQLite, and HTTP into a `.apkrec` bundle. Replay deterministically, bisect builds, or scrub in a local trace viewer.

    [Flight recorder](flight-recorder.md) · [Replay](replay.md) · [Trace viewer](trace-viewer.md)

-   **Hermetic network & conditions**

    Record HTTP(S) traffic to HAR 1.2, replay completely offline with zero backend, or simulate 3G, packet loss, latency, Wi-Fi, and cellular radios.

    [Network →](network.md)

-   **Screenshot tests & crawler**

    Capture pixel-deterministic golden screenshots across device/locale matrices, or crawl an APK automatically to map screens and find regressions.

    [Screenshot tests](screenshot-tests.md) · [Crawler](crawl.md)

</div>

## Against the Android emulator

| | apksim | Stock Android emulator |
|---|---|---|
| **App on screen** | 0.2–0.5 s warm, 1.1–1.6 s cold | 8–9 s from snapshot, 18 s from boot |
| **Idle memory** | ~175–250 MB | 3–4 GB |
| **4,107 device tests** | 4.7 min on Mac, 8.3 min in Linux container | ~19 min on two CI emulators |
| **What runs** | AOSP Android 16 framework + app DEX & native libs in one process | Full virtual device: Linux kernel, system server, system apps |

Measured on an M4 Pro. See [Performance](performance.md) for full benchmarks and [Features](features.md) for the complete comparison. apksim has no Play Services and runs apps on Android 16 (API 36) — read [Limitations](limitations.md) before relying on it for device-specific behaviour.

## How it works

Three kinds of code share one native address space:

- **The app's DEX code** is translated lazily to JVM bytecode, class by class, and JIT-compiled by HotSpot.
- **The Android framework** is AOSP's own Android 16 code, with stand-ins where a device has a system server or other apps.
- **The APK's arm64 native libraries** are mapped directly by an ELF loader and execute natively on the CPU.

[![apksim system design: ways in, the launch, what runs inside the one process, what comes out, and the hosts it runs on](architecture.svg)](architecture.svg)

Read the full walkthrough in [System design](system-design.md) or [How it was built](how-it-was-built.md).
