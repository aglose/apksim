---
title: Performance
---

<div class="page-hero" markdown>

# Performance

<p class="sim-lead">Benchmarked on an Apple Silicon M4 Pro (24 GB) using <code>scripts/bench.py</code> — median of 5 runs from pressing Enter to the app's first rendered frame.</p>

</div>

<div class="stat-strip">
<div class="stat-card">
<div class="stat-value">0.20 s</div>
<div class="stat-label">Warm first frame</div>
<div class="stat-note">Small Compose app (0.36 s large R8 app)</div>
</div>
<div class="stat-card">
<div class="stat-value">10 ms</div>
<div class="stat-label">Window open</div>
<div class="stat-note">Handed off to a pre-booted standby JVM</div>
</div>
<div class="stat-card">
<div class="stat-value">~195 MB</div>
<div class="stat-label">Idle footprint</div>
<div class="stat-note">vs. 3–4 GB for one Android emulator VM</div>
</div>
<div class="stat-card">
<div class="stat-value">117–120 fps</div>
<div class="stat-label">Display vsync</div>
<div class="stat-note">Normal UI, AGSL shaders, and sheet animations</div>
</div>
</div>

## Launch time &amp; memory

| Target | Window open | Warm first frame | Cold first frame | Idle memory |
|---|---|---|---|---|
| **apksim** — small Compose app | 0.01 s | 0.20 s | 1.15 s | ~195–250 MB |
| **apksim** — large app (R8 release, 12 MB DEX, SQLite & WebRTC) | 0.01 s | 0.36 s | 1.5 s | ~185–245 MB |
| **apksim** — large app (179 MB debug APK) | 0.01 s | 0.48 s | 1.6 s | — |
| **Stock emulator** — Quick Boot snapshot | — | — | 8.3–9.0 s | 3–4 GB |
| **Stock emulator** — full boot | — | — | 18 s | 3.3–4 GB |

- **Warm launch** (default): the launcher hands the APK to a background standby JVM that has already booted the Android framework — similar to Android's zygote.
- **Cold launch** (`APKSIM_STANDBY=0`, `--debug`, or `--instrument`): starts a fresh JVM from the trained AOT cache.
- **Linux container (arm64):** ~330 MB resident idle; 4,107 device tests finish in 8.3 min in Docker vs. 4.7 min on Mac and ~19 min on two CI emulators.

## Why it stays fast &amp; lean

<div class="sim-cards" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Startup</div>
**AOT cache + standby JVM**

The JVM AOT cache stores the Android framework ahead of time, framework calls bind directly to shadows, and a standby JVM pre-boots in the background so your next launch starts in ~10 ms.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Rendering</div>
**120 Hz Metal &amp; VideoToolbox**

Frames pace to the display's vsync. AGSL shaders, Compose layer effects, and bitmap scaling run on Metal, while H.264/HEVC/VP9 decode on VideoToolbox. HWUI rasterises on one CPU core (~6–7 ms on heavy full-screen frames; occasional misses land at 9–12 ms rather than dropping).
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Memory</div>
**Shared pages &amp; idle heap trim**

The AOT cache maps at a fixed address so ~60 MB of read-only metadata stays shared across processes, resource tables are memory-mapped, and unused Java heap pages return to the OS after 3 seconds idle.
</div>
</div>

## Profiling your own app

- **Startup breakdown:** pass `--jvm -Dapksim.timing=verbose` to print per-stage launch timings.
- **Frame cost:** send `frames`, `fps`, or `shaders` over the [control channel](automation.md) to inspect frame-interval percentiles, per-stage draw cost, and GPU vs. CPU shader execution.
- **Scale headless fleets:** cap frame rate with `--max-fps 30` (or disable decoding with `--video blank` / `--audio mute`) when running dozens of instances in parallel. See [Parallel runs](parallel.md).
