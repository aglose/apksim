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
step. The app is on screen about a quarter of a second after you press Enter, and its device tests run several
times faster than on an emulator.</p>

```bash
brew tap aglose/apksim && brew trust aglose/apksim && brew install apksim
apksim app-debug.apk
```

[Quick start](quickstart.md){ .md-button .md-button--primary }
[What it can do](features.md){ .md-button }

<p class="hero-release">Release <a href="https://github.com/aglose/apksim/releases/tag/v@VERSION@">@VERSION@</a>: <a href="changelog/">what changed</a>.</p>

</div>
<div class="device-wrap" markdown>
<figure class="device" markdown>
![The apksim test app's home screen: a list of items, a bottom bar with Home, Trips, Settings and Shader](site-assets/app-light.png){ .only-light }
![The apksim test app's Trips screen in dark mode: three trips and a note field](site-assets/app-dark.png){ .only-dark }
</figure>
<p class="hero-note">A screenshot apksim took of its test app, headless.</p>
</div>
</div>

## Against the Android emulator

| | apksim | Stock Android emulator |
|---|---|---|
| App on screen | 0.2-0.5 s on a warm launch, 1.1-1.6 s cold | 8-9 s from a snapshot, 18 s from boot |
| Memory, idle | about 175-250 MB | 3-4 GB |
| A large app's 4,107 device tests | 4.7 min on a Mac, 8.3 min in the Linux image | about 19 min on two CI emulators |
| What runs | The AOSP Android 16 framework and the app's own code, in one process | A whole device: kernel, system server, other apps |

Measured on an M4 Pro. [Performance](performance.md) has the method and more figures, and
[Features](features.md) the full comparison. An emulator is still the device: apksim has no Play Services, and runs apps
of every target on Android 16. [Limitations](limitations.md) lists where it differs.

## What you can do with it

<div class="grid cards" markdown>

-   **Use it like a simulator**

    Device types, live window resizing, rotation, dark mode, locale, text size, location, biometrics, permissions
    and deep links, right from the macOS menu bar.

    [The simulator window](simulator-window.md)

-   **Run device tests without a device**

    Compose UI tests, Espresso, ActivityScenario and UiAutomator suites run as `am instrument` would, with JUnit
    XML, shards, coverage and a failure bundle for each failing test.

    [Instrumented tests](instrumented-tests.md)

-   **Script it**

    A line-based control channel, an adb device that standard tools drive, and an MCP server for AI agents.

    [Automation](automation.md), [adb device](adb-device.md), [MCP server](mcp.md)

-   **Put a build in a browser tab**

    `apksim serve` streams an APK per tab, so people without Android tools can try a build, compare two builds
    side by side, or open a preview from a pull request.

    [In a browser](browser-tab.md)

-   **Run it in CI**

    A headless Linux image for x86_64 and arm64, with the test, screenshot and crawler scripts on its `PATH`.

    [Linux and Docker](linux-and-docker.md)

-   **Record and replay a session**

    A flight recorder keeps the last minutes of input, network, logs and frames. A recording replays, bisects a
    list of builds, and opens in a trace viewer.

    [Record & replay](replay-trace-crawler.md)

-   **Control the network**

    Record every HTTP exchange into a HAR, answer from it with no backend, or run the app on 3G, a lossy link or
    offline.

    [Network](network-overview.md)

-   **Screenshot-test real APKs**

    Screens across a device, appearance, locale and font-scale matrix, compared with goldens in an HTML report.

    [Screenshot tests](screenshot-tests.md)

</div>

## How it works

Three kinds of code share one address space:

- **The app's DEX code** is translated to JVM bytecode, class by class, and JIT-compiled like any Java program.
- **The Android framework** is AOSP's own, with stand-ins where a device has a system server and other apps.
- **The APK's arm64 native libraries** are mapped by an ELF loader and run on the CPU as they are.

[![apksim system design: ways in, the launch, what runs inside the one process, what comes out, and the hosts it runs on](architecture.svg)](architecture.svg)

[System design](system-design.md) reads the diagram from the top, and [How it was built](how-it-was-built.md)
covers the order it came together in and what was hard.
