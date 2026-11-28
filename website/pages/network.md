---
title: Record, Replay, Conditions
---

<div class="page-hero" markdown>

# Network: record, replay &amp; conditions

<p class="sim-lead">Capture an app's HTTP(S) traffic into a standard HAR 1.2 file, replay it hermetically with zero backend in local dev or CI tests, and test offline, 3G, lossy, Wi-Fi, and cellular states on demand.</p>

<div class="sim-pills">
<span class="sim-pill">Standard HAR 1.2</span>
<span class="sim-pill">Works on R8 minified builds</span>
<span class="sim-pill">Zero-overhead recording tap</span>
<span class="sim-pill">Wi-Fi &amp; cellular radio simulation</span>
</div>

</div>

## Three capabilities

<div class="sim-cards" markdown>
<div class="sim-card" markdown>
<div class="sim-card-label">Observe</div>
**Non-intrusive HAR recording**

Reads connections passively via a tap without altering TLS, certificate pinning, HTTP/2, or gRPC. Adds ~2 µs per request and automatically redacts `Authorization`, `Cookie`, and `Set-Cookie`.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Hermetic</div>
**Offline replay in app &amp; CI**

Answers requests from a recorded HAR with nothing leaving the process. Replays the server's real certificate chain so hostname checks and TLS pins pass.
</div>
<div class="sim-card" markdown>
<div class="sim-card-label">Simulate</div>
**Conditions &amp; radios**

Switch between `online`, `offline`, `3g`, `lossy`, custom latency/bandwidth, and independent Wi-Fi / cellular radios (with signal strength) at launch or live while running.
</div>
</div>

## Quick start

=== "Record & replay"

    ```bash
    # 1. Record real traffic into api.har
    apksim --network record api.har app.apk

    # 2. Replay completely offline (no backend needed)
    apksim --network replay api.har app.apk

    # 3. Hermetic instrumented tests
    apksim --network replay api.har --instrument app-androidTest.apk app.apk
    ```

=== "Simulate conditions"

    ```bash
    # Launch with throttled or offline network
    apksim --network-profile 3g app.apk
    apksim --network-profile latency:400 app.apk
    apksim --wifi off --cellular on app.apk
    ```

=== "Local fixture server"

    ```bash
    # Redirect a hardcoded API host to a local mock server with a self-signed cert
    apksim --network-host api.example.com=127.0.0.1:8443 \
           --network-trust dev-cert.pem app.apk
    ```

=== "Fetch missing entries"

    ```bash
    # Answer known requests from base.har; fetch new ones live and save them to delta.har
    apksim --network replay base.har --network-unmatched fetch:delta.har app.apk
    ```

## Launch options

| Option | What it does |
|---|---|
| `--network record FILE` | Record every HTTP(S) exchange into `FILE` (HAR 1.2) |
| `--network replay FILE` | Answer requests from `FILE`; nothing leaves the process |
| `--network-profile NAME` | Start with `online`, `offline`, `3g`, `lossy`, `latency:MS`, or `bandwidth:KBPS` |
| `--wifi on\|off`, `--cellular on\|off` | Set initial Wi-Fi (default `on`) and mobile data (default `off`) states |
| `--wifi-signal DBM\|level:N` | Set initial Wi-Fi RSSI in dBm (`-126..-1`, default `-50`) or level `0–4` |
| `--network-redact NAMES` | Extra comma-separated header names to redact (`-Name` un-redacts a default) |
| `--network-match FIELDS` | Replay match keys: `method,url,body` (default), optionally adding `headers` |
| `--network-ignore-query NAMES` | Ignore volatile query parameters (timestamps, cache busters) when matching |
| `--network-loose` | Answer a request no entry matches from the one that differs only in query values (`apksim replay --loose`) |
| `--network-ignore-header NAMES` | Ignore specific headers when matching includes `headers` |
| `--network-unmatched POLICY` | Unmatched replay policy: `fail` (default: 599 + test failure), `respond` (599), or `fetch:OUT` |
| `--network-replay-timing MODE` | `none` (instant, default) or `recorded` (preserve original response timing) |
| `--network-host H=ADDR[:PORT]` | Redirect host `H` to a fixture server (repeatable) |
| `--network-trust FILE\|inline:B64` | Trust an extra PEM/DER certificate for local fixture servers |

## Live control commands

Change network state while the app runs via the [control channel](automation.md), **Features > Network** menu, `adb shell apksim network ...`, or `UiAutomation.executeShellCommand`:

| Command | Effect |
|---|---|
| `network` | Print current mode, profile, exchange counts, and Wi-Fi / cellular status |
| `network online\|offline` | Toggle full connectivity (DNS fails, sockets close, `ConnectivityManager` fires `onLost` / `onAvailable`) |
| `network 3g\|lossy` | Apply built-in mobile throttle or packet-loss profiles |
| `network latency MS`, `network bandwidth KBPS` | Inject custom round-trip latency or bandwidth cap |
| `network wifi [on\|off\|toggle\|signal DBM]` | Toggle Wi-Fi or adjust signal strength (`signal level 0–4`) |
| `network cellular [on\|off\|toggle]` | Toggle metered cellular network (`isActiveNetworkMetered` updates when default) |
| `network unmatched [clear]` | List or clear unmatched replay requests |
| `network save` | Flush the HAR recording to disk immediately |

## Client compatibility

apksim hooks standard JDK networking primitives that R8 never renames, so release/minified builds work without app changes:

| Client stack | Record | Replay | Conditions / Offline |
|---|---|---|---|
| **OkHttp 3–5** (Retrofit, Ktor OkHttp, Coil) | Yes | Yes | Yes |
| **`HttpURLConnection`**, Volley, Ktor Android | Yes | Yes | Yes |
| **WebSockets &amp; HTTP/2-only (gRPC on OkHttp)** | Yes | — | Yes |
| **Cronet (native) &amp; Ktor CIO** | — | — | Offline only |

<p class="spec-note">For full tap-vs-engine architecture details and benchmarks, see <a href="https://github.com/aglose/apksim/blob/main/docs/network.md">docs/network.md on GitHub</a>.</p>
