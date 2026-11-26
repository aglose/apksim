---
title: Flight Recorder
---

<div class="page-hero" markdown>

# Flight recorder

<p class="sim-lead">Continuously buffer the last minutes of input, screen video, logcat, HTTP(S) traffic, SQLite queries, and replay checkpoints — then save everything into one shareable <code>.apkrec</code> bundle.</p>

<div class="sim-pills">
<span class="sim-pill">Zero-friction bug reports</span>
<span class="sim-pill">Auto-saves on crash</span>
<span class="sim-pill">Checkpointed for replay</span>
<span class="sim-pill">Opens in Trace Viewer</span>
</div>

</div>

## Turning it on

=== "At launch"

    ```bash
    # Record all channels (default 120 s ring buffer, checkpoints every 30 s)
    apksim --rec all app.apk

    # Pick specific channels and customize window/checkpoint cadence
    apksim --rec input,logcat,net --rec-window 300 --rec-checkpoint 15 app.apk
    ```

    `--rec*` options still use warm standby launches.

=== "While running (`rec` command)"

    ```text
    rec                            # one-line status: channels, counts, checkpoints
    rec on [CHANNEL...]            # enable channels (omit list for all)
    rec off [CHANNEL...]           # stop channels (buffered data stays until aged out)
    rec save [SECONDS] [FILE]      # write .apkrec bundle in the background
    rec checkpoint now             # take an immediate replay checkpoint
    rec checkpoint every SECONDS   # periodic checkpoint interval (default 30)
    rec clear                      # clear ring buffers
    ```

    In the Mac window, press <kbd>⌘</kbd><kbd>⇧</kbd><kbd>S</kbd> (**File > Save Replay**) anytime. Bundles save to `~/.apksim/screenshots/<package>-<time>.apkrec` (or `work/screenshots/` in a checkout) and auto-save when the app crashes.

## Channels

| Channel | What it captures | Ring budget |
|---|---|---|
| `input` | Every touch, pinch, wheel, key, Back, resize, and device command (`rotate`, `appearance`, `open`, `network`, etc.) with timestamps | 16 MB |
| `logcat` | App `android.util.Log` lines with wall-clock timestamps | 20k lines |
| `net` | Passive capture of HTTP/1.1, HTTP/2, gRPC, WebSockets, TLS/ALPN info, and failures as HAR 1.2 (auth/cookie headers redacted) | 64 MB |
| `db` | Framework SQLite queries & transactions (Room, SQLDelight, `SQLiteOpenHelper`) with timing and scrubbed literals | 16 MB |
| `db+values` | Same as `db`, plus bound parameter values and raw SQL literals | 16 MB |
| `screen` | Presented frames up to 5 fps (max 960 px long side, JPEG Q70) | 64 MB |

## Checkpoints &amp; `.apkrec` bundles

To make a recording replayable, apksim snapshots a **checkpoint** (typically 7–13 ms on APFS) whenever the app is at rest:

- **Activity stack &amp; saved state:** every activity's intent and `onSaveInstanceState` bundle.
- **App data:** SharedPreferences, files, `/sdcard`, Settings, and consistent SQLite snapshots (instant copy-on-write clone on APFS).
- **Device configuration:** display size, density, appearance, locale, font scale, timezone, and clock.

The saved `.apkrec` file is a plain zip archive containing `manifest.json`, `checkpoint/`, `input.jsonl`, `network.har`, `logcat.txt`, `db.jsonl`, and `screen.avi`.

!!! warning "Privacy"

    A `.apkrec` bundle contains a snapshot of the app's data folder (including databases and sign-in state), logs, screen frames, and network bodies. Passwords typed into password fields are omitted from `input.jsonl`, and `Authorization`/`Cookie`/`Set-Cookie` headers are redacted by default (`--network-redact` adds more) — still treat saved bundles as sensitive.

## Next steps

<div class="sim-cards sim-cards--2" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Play it back</div>
**Replay, bisect &amp; export tests**

Replay a `.apkrec` deterministically, bisect a folder of APKs to find regressions, or turn a recording into a node-based journey test.

<div class="sim-card-link" markdown>[Replay →](replay.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Inspect visually</div>
**Open in the Trace Viewer**

Run `apksim rec view session.apkrec` to scrub frames, touches, network calls, logs, and SQLite queries on one synchronized browser timeline.

<div class="sim-card-link" markdown>[Trace viewer →](trace-viewer.md)</div>
</div>
</div>
