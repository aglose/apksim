---
title: "Beam: On the Visitor's Mac"
---

<div class="beam-hero" markdown>
<div class="beam-hero-copy" markdown>

# Beam: run browser sessions on your Mac

<p class="sim-lead">When you open an <code>apksim serve</code> link on an Apple Silicon Mac with apksim installed, <strong>Beam</strong> runs the Android app locally on your own machine — right inside the same browser tab.</p>

You get your Mac's CPU, Metal GPU shaders, VideoToolbox video decode, 120 Hz refresh rate, and local audio — without consuming a server session or leaving the webpage.

<div class="sim-pills">
<span class="sim-pill">120 Hz Metal &amp; VideoToolbox</span>
<span class="sim-pill">Zero server load</span>
<span class="sim-pill">Seamless live handoff</span>
<span class="sim-pill">Isolated sandbox</span>
</div>

</div>
<div class="beam-visual" markdown>
<div class="beam-browser-mock" markdown>
<div class="beam-chrome">
<span class="beam-dots"><i></i><i></i><i></i></span>
<span class="beam-url">serve.example/app</span>
<span class="beam-badge">120 fps · Local</span>
</div>
<div class="beam-stage" markdown>
<figure class="beam-stage-shot" markdown>
![ComposeApp streaming inside a browser tab via Beam](site-assets/win-skin.png)
</figure>
<div class="beam-stage-side">
<div class="beam-node beam-node--active">
<div class="beam-node-k">Browser tab</div>
<div class="beam-node-v">Same canvas &amp; iframe</div>
</div>
<div class="beam-arrow">⇅ 127.0.0.1 · H.264</div>
<div class="beam-node">
<div class="beam-node-k">Your Mac</div>
<div class="beam-node-v">Sandboxed apksim · Metal GPU</div>
</div>
<div class="beam-arrow">↓ one-time ticket</div>
<div class="beam-node beam-node--muted">
<div class="beam-node-k">apksim serve</div>
<div class="beam-node-v">0 server sessions used</div>
</div>
</div>
</div>
</div>
</div>
</div>

## What Beam offers

<div class="sim-cards" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Performance</div>
**Native 120 Hz, Metal & audio**

Runs on your Apple Silicon CPU and GPU with Metal AGSL shaders, VideoToolbox decode, and sound through your Mac's speakers. Loopback bitrates up to 20 Mbps keep text and animations as sharp as a native window.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Live handoff</div>
**Move between server and Mac**

Start directly on your Mac, or move a running server session over with zero blank frames. The last frame stays up during cutover, and the app's data slot, `/sdcard` files, and `Settings` move with it.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">One display, two views</div>
**Tab, Simulator, or both**

Use the app inside the browser tab (Device mode or pixel-exact Fill mode matching your `devicePixelRatio`), click **Show in Simulator** to open the native macOS window alongside it, or **Detach** to keep it running after the tab closes.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Instant repeats</div>
**SHA-256 & sibling caches**

Builds are cached by hash, and a new build of the same signed app automatically reuses translated classes and load hints from earlier builds — putting the first frame on screen in about 0.17 s.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Continuity</div>
**Save or push to a real device**

Sessions are erased on close by default. Opt in with **Keep on this Mac** to reopen locally (`apksim beam run`), or transfer the session's data and settings straight to a physical phone over `adb` (`apksim beam to-device`).
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Developer tools</div>
**Local backends, inspector & adb**

Inspect HTTP(S) traffic live, forward app requests to a backend running on your Mac's `localhost`, capture flight recordings, and optionally attach `adb` directly to a beamed session.
</div>
</div>

## How it works

<div class="beam-steps" markdown>
<div class="beam-step" markdown>
<div class="beam-step-num">1</div>
**Silent when not installed**

Visitors without apksim never see a browser prompt or extra UI. In Chromium on an Apple Silicon Mac, a **Run on this Mac** control appears and connects only when clicked.
</div>
<div class="beam-step" markdown>
<div class="beam-step-num">2</div>
**Native macOS consent**

The first time a site asks to run an APK, macOS displays a native dialog with the site origin, app name, size, and SHA-256 hash (*Allow once*, *Always for this site*, or *Don't allow*).
</div>
<div class="beam-step" markdown>
<div class="beam-step-num">3</div>
**Zero-idle LaunchAgent**

The background agent is socket-activated by `launchd`: it uses 0 MB of memory until a page connects, wakes in ~0.1 s, and exits automatically after 10 minutes idle.
</div>
</div>

## Get started

=== "On your Mac (visitor)"

    Install the socket-activated LaunchAgent once. It uses 0 MB when idle, wakes when a browser tab connects, and survives `brew upgrade`:

    ```bash
    apksim beam install
    apksim beam status
    ```

    Prefer running it only when you want? Start it in the foreground instead:

    ```bash
    apksim beam
    ```

=== "On the server (operator)"

    Opt in on `apksim serve` with `--allow-beam`:

    ```bash
    apksim serve --apps builds/ --allow-beam
    ```

    If you embed `serve` inside a cross-site dashboard `<iframe>`, delegate local network access so Chromium can reach the visitor's agent:

    ```html
    <iframe allow="loopback-network; local-network-access"
            src="https://serve.example/..."></iframe>
    ```

## Security and the beam sandbox

A beamed APK comes from a website, so Beam runs every instance inside a dedicated, fail-closed macOS `sandbox-exec` profile — stricter than a normal local launch.

<div class="beam-security" markdown>
<div class="beam-sec-col" markdown>
<div class="sim-card-label">Locked down by default</div>

- **No home folder or credentials:** cannot read your personal files, saved autofill sign-ins, other apps' data, or the agent's `trust.json`.
- **No clipboard access:** the Mac pasteboard is blocked both ways so a website's app can never read what you copy.
- **No loopback or local sockets:** cannot connect to `127.0.0.1`, IPv6 loopback, `ssh-agent`, or other running instances unless you explicitly open a port.
- **Isolated caches & auto-cleanup:** writes stay inside per-build caches. Temporary session data is erased when the tab closes, and cached builds expire after 2 days by default.
</div>
<div class="beam-sec-col" markdown>
<div class="sim-card-label">Under your control (opt-in)</div>

- **Per-site trust:** review or revoke site permissions anytime with `apksim beam trust list` and `apksim beam trust revoke ORIGIN`.
- **Local backend forwarding:** allow a beamed app to hit your local dev API with `apksim beam config forward-allow http://127.0.0.1:8080/api` (or approve a one-time native prompt from a trusted site).
- **Network inspector & adb:** both are off by default; enable on demand with `apksim inspect` (sets `inspect-network optional` and stops the agent and its sessions, for launchd to start again) or `apksim beam config allow-adb on`.
- **Retention settings:** tune `keep-days`, `save-sessions`, and `keep-saved-days` in `apksim beam config`.
</div>
</div>

## CLI reference

| Command | What it does |
|---|---|
| `apksim beam install` / `uninstall` | Install or remove the socket-activated `launchd` agent and daily cache sweep |
| `apksim beam` | Run the local Beam agent in the foreground |
| `apksim beam status` | Show whether the agent is installed, running, and list active sessions |
| `apksim beam stop [--force]` | Stop the running agent (`--force` also ends detached sessions) |
| `apksim beam saved` | List sessions you chose to keep on this Mac |
| `apksim beam run ID` | Reopen a saved session in apksim's native Simulator window |
| `apksim beam to-device ID` | Transfer a saved session's build, data, and settings to a connected Android device over `adb` |
| `apksim beam forget ID` / `--all` | Erase saved session data (add `--builds` to clear unused cached APKs) |
| `apksim beam trust list` / `revoke` | Inspect or revoke sites allowed to launch apps on your Mac |
| `apksim beam config [KEY VALUE...]` | Configure `max`, `keep-days`, `save-sessions`, `inspect-network`, `forward-allow`, `allow-loopback`, `allow-adb`, and `rec` |
| `apksim beam sweep` | Clean up expired cached builds and unused caches immediately |
| `apksim use [X.Y.Z\|latest\|default]` | Switch to another release, older or newer, without reinstalling (`default` goes back) |
| `apksim inspect [optional\|on\|off]` | Set the network inspector and stop the agent and its sessions (launchd starts it again; one started by hand, start again) |

See [apksim serve](serve.md) for hosting builds and [Pull Request Previews](pr-preview.md) for automated PR links.
