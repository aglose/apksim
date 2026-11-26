---
title: Limitations
---

<div class="page-hero" markdown>

# Limitations

<p class="sim-lead">apksim is a fast UI and logic runtime, not a full Android device. Know where it simulates system behaviour before relying on it for release verification.</p>

</div>

!!! tip "Audit your APK in seconds"

    Run `scripts/subsystem-audit.py app.apk` to scan which Android subsystems your APK references (Play Services, camera, sensors, Bluetooth, NFC, telephony, Health Connect, etc.) along with apksim's support status and relevant flags for each.

## What is real vs. simulated

| Area | How apksim handles it |
|---|---|
| **Android framework & UI** | Real AOSP Android 16 (API 36), Skia, HWUI, and SQLite. |
| **Google Play Services** | Not present. Apps that require Play Services at launch must guard or mock those calls. |
| **Other apps & intents** | No other apps are installed. `http(s)` and `mailto` open on the Mac; sharing uses macOS share sheet; a stand-in browser answers OAuth browser checks. |
| **Camera** | Simulated (`--camera fake`): 30 fps test pattern or static image via Camera2/CameraX, not the Mac's webcam. |
| **Sensors** | Simulated (`sensor` command): accelerometer follows rotation; light, proximity, steps, hinge angle, and shake can be scripted. |
| **Health Connect & BLE** | Scripted via `--health` and `--ble` presets/files rather than real hardware. |
| **Background work** | Supported: started/bound services, JobScheduler, WorkManager, alarms, and simulated FCM pushes (`push`). |

## Key technical constraints

<div class="sim-cards sim-cards--2" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Android version</div>
**Single OS version (Android 16 / API 36)**

Apps targeting any `targetSdk` run on Android 16, with `targetSdk`-gated compatibility flags answered per app. Older Android system images (e.g. API 30) are not bundled — use an emulator to test OS-version-specific quirks.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Native code</div>
**ABI &amp; 16 KB page alignment on macOS**

Libraries must match the host CPU (`arm64-v8a` on Apple Silicon & arm64 Linux; `x86_64` on x86_64 Linux). On macOS, 4 KB-aligned libraries (pre-NDK r27) fail with a catchable `UnsatisfiedLinkError` if writable `.data`/`.bss` shares a 16 KB page with executable code. Rebuild for 16 KB pages, or use `--skip-library NAME` / experimental `--move-shared-data`.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Native graphics &amp; C shim</div>
**Partial bionic &amp; native stubs**

`libjnigraphics` (`AndroidBitmap_*`) works (no hardware bitmaps). Native GLES, OpenSL ES, and AAudio are stubbed — Java `AudioTrack` plays audio, and offscreen GLES works in test runs, but native GLES does not present to the window. Unsupported libc symbols log their name when called.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Rendering &amp; test threading</div>
**CPU rasterisation &amp; main-thread tests**

AGSL shaders, layer effects, and scaled bitmaps run on Metal, while HWUI rasterises on one CPU core (busy full-screen frames can take 6–7 ms). Instrumented tests run Robolectric-style on the main thread; `Thread.sleep` and `runBlocking` pump the main looper while waiting, and `--realtime-clock` switches from simulated to wall-clock time.
</div>
</div>

## Security &amp; sandboxing

An APK executes native and translated bytecode inside the apksim process. On macOS, `sandbox-exec` confines writes inside your home directory to apksim's state folder and blocks reads of `~/.ssh`, `~/Documents`, `~/Desktop`, keychains, and browser profiles.

Treat the sandbox as a guardrail for your own builds — not a security boundary for untrusted or hostile APKs. In Linux Docker containers there is no `sandbox-exec`, so pass `docker run --network none` when you want network isolation.
