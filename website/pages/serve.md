---
title: apksim serve
---

<div class="page-hero" markdown>

# apksim serve: an APK in a browser tab

<p class="sim-lead">Let anyone on your team — QA, designers, PMs, or PR reviewers — open a link and interact with a live Android build straight from their browser, with zero Android tools installed.</p>

<div class="sim-pills">
<span class="sim-pill">~0.3 s first frame</span>
<span class="sim-pill">H.264 / JPEG + Audio</span>
<span class="sim-pill">Up to 1,500 view-only viewers</span>
<span class="sim-pill">Beam-ready for Mac visitors</span>
</div>

</div>

## What it offers

<div class="sim-cards" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Zero setup</div>
**Instant interactive builds**

Point `apksim serve` at a folder or catalog of APKs (local paths or `https://` URLs). Each browser tab gets an isolated, fresh Android instance streamed over WebSocket.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Smooth streaming</div>
**H.264 video &amp; live audio**

Streams H.264 (via WebCodecs) or JPEG with ~30 ms tap-to-frame latency on a Mac, plus live app audio on both macOS and Linux.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Collaboration</div>
**Shared view-only links**

Enable `--allow-share` so anyone can watch a live session from one encoded stream — tested with 1,000+ viewers on a Mac and 1,500 remote viewers.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Continuity</div>
**Kept sessions &amp; reload rejoin**

Opt into `--keep-max N` to preserve app sign-in state across visits, and `--rejoin-grace` so reloading the page reattaches to the running session seamlessly.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Local speed</div>
**Works with Beam**

Pass `--allow-beam` so visitors on Apple Silicon Macs with apksim installed can run the build locally at 120 Hz inside the same tab, consuming zero server sessions.

<div class="sim-card-link" markdown>[Beam →](beam.md)</div>
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Code review</div>
**PR previews &amp; live diffing**

Pair with `scripts/pr-preview.py` to comment live preview links on pull requests, drive two builds side by side, and highlight pixel differences.

<div class="sim-card-link" markdown>[PR previews →](pr-preview.md)</div>
</div>
</div>

## Quick start

=== "Local folder"

    ```bash
    apksim serve --apps builds/
    ```

    Opens on `http://127.0.0.1:8080`. Pick any APK from `builds/` or drag-and-drop one into the browser.

=== "Authenticated team server"

    ```bash
    # Binding beyond loopback requires --token FILE
    apksim serve --bind 0.0.0.0 --port 8080 --token /etc/apksim/token \
      --apps builds/ --max 8 --allow-beam --allow-share
    ```

    Open `https://sim.example.com/?token=...` once (sets an `HttpOnly` cookie and strips the token from the URL), or send `Authorization: Bearer TOKEN` from scripts and reverse proxies.

=== "Catalog with per-app options"

    Create `apps.txt` with one APK path or `https://` URL per line, optional display name, and launch options after `--`:

    ```text
    builds/app-debug.apk Debug build -- --activity .DebugLauncher --build MODEL=Pixel_9
    https://ci.example.com/nightly.apk Nightly -- --setting global:onboarding_done=1
    ```

    ```bash
    apksim serve --apps apps.txt
    ```

=== "Docker (Linux)"

    ```bash
    docker run --rm -p 127.0.0.1:8080:8080 -v "$PWD/builds:/apps:ro" \
      --entrypoint apksim ghcr.io/aglose/apksim:latest \
      serve --bind 0.0.0.0 --token /apps/token --apps /apps --warm-standby 1
    ```

## Common options

| Option | Default | What it does |
|---|---|---|
| `--port N`, `--bind ADDR` | `8080`, `127.0.0.1` | Listen port and bind address (non-loopback requires `--token`) |
| `--token FILE` | — | Shared secret read from the first line of `FILE` |
| `--apps DIR\|FILE` | — | Folder of APKs or catalog file (repeatable) |
| `--max N`, `--idle SEC` | `4`, `600` | Concurrent session limit and inactivity timeout |
| `--device-size NAME=WxH@D` | — | Register custom device profiles for the page's Device menu |
| `--skins DIR` | — | Additional folders of device [skins](using-skins.md) to draw in the tab |
| `--codec auto\|jpeg` | `auto` | `auto` uses H.264 when supported; tune with `--bitrate` / `--quality` |
| `--warm auto\|on\|off` | `auto` | Pre-warm APK class caches in the background (on by default on Linux) |
| `--warm-standby 0\|1` | `1` Mac, `0` Linux | Keep one standby JVM pre-booted for ~0.3 s session starts |
| `--allow-beam` | off | Allow visitors with local apksim to run sessions via [Beam](beam.md) |
| `--allow-share`, `--max-viewers N` | off, `100` | Enable view-only share links for running sessions |
| `--keep-max N`, `--keep-days N` | `0`, `14` | Persist kept session data slots across visits |
| `--allow-rec`, `--issue-url TPL` | off | Enable flight recorder & one-click bug report zip in the tab |
| `--inspect-network [optional]` | off | Enable live HTTP(S) inspector and mock/patch rules in the tab |
| `--allow-logcat` | off | Allow tabs to stream `android.util.Log` to the session log |
| `--allow-origin ORIGIN` | none | Allow embedding in an `<iframe>` on `ORIGIN` (repeatable) |
| `--trust-proxy`, `--public-origin`, `--base-path` | — | Configure when running behind nginx, Caddy, or an OAuth proxy |

## Security defaults

Every session runs inside its own isolated `--fresh` data slot (`serve-N`), never touches your local `default` app data or saved autofill credentials (`--no-autofill`), and deletes its slot when the tab closes. Sensitive capabilities — flight recordings (`--allow-rec`), logcat (`--allow-logcat`), network inspection (`--inspect-network`), view-only sharing (`--allow-share`), and host remapping (`--network-host-allow`) — are all **off by default**.

<p class="spec-note">Need the full HTTP API, WebSocket wire protocol, reverse-proxy headers, or reload-rejoin contract? Read the complete technical spec in <a href="https://github.com/aglose/apksim/blob/main/docs/serve.md">docs/serve.md on GitHub</a>.</p>
