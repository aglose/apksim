---
title: Trace Viewer
---

<div class="page-hero" markdown>

# Trace viewer

<p class="sim-lead">Inspect any <code>.apkrec</code> flight recording or CI test failure bundle in one static browser page — with a synchronized timeline, frame-by-frame screen playback, touch markers, network calls, logs, and SQLite queries.</p>

<div class="sim-pills">
<span class="sim-pill">100% client-side</span>
<span class="sim-pill">Nothing uploaded</span>
<span class="sim-pill">Virtualized for 50k+ logs</span>
<span class="sim-pill">Works offline</span>
</div>

</div>

## Open a recording or failure bundle

```bash
apksim rec view session.apkrec       # opens the recording in your default browser
apksim rec view                      # opens the viewer; drag and drop any .apkrec or bundle zip
apksim rec view --no-open FILE       # prints the loopback URL without launching a browser
```

`apksim rec view` serves a single static HTML page on `127.0.0.1` and exits automatically after 2 minutes of inactivity (`--linger`).

## What you can inspect

Clicking or dragging anywhere on the timeline — or stepping frames with <kbd>←</kbd> / <kbd>→</kbd> — synchronizes every panel to that exact millisecond:

<div class="sim-cards" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Overview</div>
**Synchronized timeline**

Color-coded tracks for input events (blue), HTTP exchanges (green), logcat warnings/errors (amber/red), SQLite queries (purple), and replay checkpoints (orange).
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Visual playback</div>
**Screen &amp; touch markers**

Decodes video frames on demand from `screen.avi` and overlays recent touch locations directly onto the screenshot so you can see exactly where each tap landed.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Traffic</div>
**Network inspector**

Browse every recorded HTTP/1.1, HTTP/2, and gRPC request with status, duration, headers, pretty-printed JSON bodies, and image previews.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Logs</div>
**Filterable Logcat**

Filter tens of thousands of log lines instantaneously by level, tag, or substring using virtualized scrolling.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Database &amp; state</div>
**SQLite &amp; checkpoints**

Inspect SQLite statements, timings, row counts, and each checkpoint's activity stack and device configuration.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">CI triage</div>
**Failure bundles**

Drop a zipped [failure bundle](instrumented-tests.md#failure-bundles) (or a zip of multiple bundles) to view `failure.txt`, `screen.png`, `nodes.txt`, `threads.txt`, `looper.txt`, `logcat.txt`, and `repro.sh`.
</div>
</div>

!!! info "Private &amp; safe by design"

    The viewer unpacks zips entirely inside your browser using native `DecompressionStream` — no data is sent to any external server. All untrusted recording data renders via `textContent` under a strict Content Security Policy (`default-src 'none'`).
