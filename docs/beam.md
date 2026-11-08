# Beam: a browser session's app run on the visitor's own Mac (plan, 2026-11-07)

`apksim serve` runs the app on the server and streams its screen to the tab (docs/serve.md). Beam keeps the tab
and the page exactly as they are, but when the visitor has apksim installed, the APK goes to their Mac and runs
there: their CPU and GPU (Metal shaders, VideoToolbox, 120 Hz), their speakers, no server session used. The tab
still shows the app in the same place, at the same size, embedded or not, and follows the browser window and the
simulator window when either is resized.

This page is the plan: what "seamless" has to mean, the design, then phases with what finishes each.

## Status (2026-11-08)

| Phase | State |
|---|---|
| 0 Browser feasibility | Done for Chrome 156 (works behind a one-time `loopback-network` prompt, no speed cost). Safari, Firefox not checked: beam is offered only in Chromium browsers |
| 1 Agent, start on the Mac | Done: `apksim beam`, trust and prompt (answered by the owner), `serve --allow-beam`, the page's menu, `beam-check.py`. Left: the launchd agent (it runs in the foreground) |
| 1b Parity with a local launch | Done (see "Phase 1b as built"): the tab shows a new frame on as many display refreshes as the window, in every scenario measured; one refresh more latency (the encode) |
| 1c What the Mac keeps, and for how long | Done (see "Phase 1c as built"): builds held 2 days (configurable) after their last session, sessions erased unless kept, `apksim beam saved|run|forget|config|sweep` |
| Beam sandbox | Done (see "The beam sandbox"): beamed APKs reach nothing of the Mac's but their own slot, scratch and build caches |
| 2 The agent downloads the APK itself | Done (see "Phase 2 as built"): with the server's ticket, from the page's own site; the tab relays it only when that fails |
| 3 Sizing, Show in Simulator | Not started |
| 4 Moving a running session | Not started |
| 5 The app's data moves with it | Not started (optional) |
| 6 Polish, Linux agent | Not started |

## What seamless means (the acceptance checks)

1. Same place: the app draws in the page's own canvas, in the tab, in the iframe of an embedding dashboard, at the
   size and position serve's page gives it today. No second window appears unless the visitor asks for one.
2. No blank frame: going from the server to the Mac (or starting on the Mac) never shows an empty canvas; the last
   frame stays up until the local first frame replaces it.
3. Sizes follow both ways: resizing the browser window resizes the Android display (in "Fill" mode) or rescales the
   canvas (in "Device" mode, today's behaviour), and resizing the simulator window, when it is shown, is mirrored in
   the tab within a frame. Neither view fights the other.
4. Pixel exact: in Fill mode one Android pixel is one physical screen pixel (density = `devicePixelRatio`, so one dp is
   one CSS pixel), including after the browser moves to a display with another pixel ratio.
5. Input, keyboard, clipboard and deep links work as in a remote session, with the same page code.
6. Nobody without apksim sees a prompt, a probe or a difference. A visitor with apksim chooses once per site.
7. A web page can never make a Mac run an APK without its owner's consent, and never reaches anything on the Mac
   but the agent's own API.

## Design

### Approach: a local serve, streamed into the same canvas

The page already draws a stream (JPEG or H.264 through WebCodecs) and sends control-channel input over one
WebSocket (`/ws`). Beam points that WebSocket at a **local agent** on `127.0.0.1` instead of the server. The agent is
`apksim serve` itself in a loopback-only mode, so sessions, flow control, the frame protocol, the input allowlist,
crash reporting, downloads and cache warming are the code that already exists and is checked by `serve-check.py`.
What changes in the page is the WebSocket's address and a few new messages; the drawing code does not change.

Locally this costs little: the serve measurements on the Mac (docs/serve.md, 2026-11-07) already have the page in
Chromium drawing the Shader tab at 120 fps with H.264 at ~7 ms from presented to on screen, encode ~5 ms on
VideoToolbox, decode in hardware. On loopback the bit rate can be much higher than a network's (proposal: 20 Mbps,
or JPEG at quality 95 for still screens), so text is as sharp as a native window's.

Rejected: a borderless native window laid over the browser where the canvas is. A page cannot know its canvas's
position on the screen reliably (browser toolbars, page zoom, scrolling, iframes, CSS transforms), gets no event when
the browser window moves, and cannot keep a native window in the right place in the window order (other windows,
tabs, Spaces, full screen, Mission Control); the browser's own popovers, menus and devtools would end up under it.
Tracking another app's windows needs Accessibility permission. Every one of these is a way it stops looking like it
is in the browser.

### Parts

```
 browser tab (page from https://serve.example)                 the visitor's Mac
 +-------------------------------------------+      +--------------------------------------------+
 | serve.html                                |      | apksim-beamd (launchd, socket-activated)   |
 |  canvas  <-- frames -- ws://127.0.0.1:P --+----->|  = apksim serve --beam                     |
 |          --- input  -->                   |      |    trust store, consent prompt, APK cache  |
 |  remote ws (serve.example) until cutover  |      |    Session -> apksim instance (standby)    |
 +---------------------+---------------------+      |      --window hidden: "Show in Simulator"  |
                       |  POST /api/beam -> ticket  +---------------------+----------------------+
                       v                                                  |
              apksim serve (server) <---- GET /api/beam/apk?ticket ------+   (or the tab relays the bytes)
```

1. **Local agent: `apksim serve --beam`.** Binds `127.0.0.1` only, on a fixed port (proposal 47474, `--port` to
   change; `hello` identifies it, so a port taken by something else is detected). Instances run as serve's do but
   with `--window hidden` instead of `--headless` (so the Mac window can be shown later), `--data-slots beam`,
   `--no-autofill` (a site chose this APK: never the owner's saved sign-ins), no `--adb`, the launch options
   limited to `Catalog.LAUNCH_OPTIONS` as today. One session per tab, ended when the tab's WebSocket closes, as now.
   New endpoints:
   - `GET /beam/hello`: `{apksim, protocol, trusted}` (whether the calling `Origin` is already allowed). Readable by
     any origin (CORS), and says nothing else to an origin that is not trusted. Chrome 156 sent no Private Network
     Access preflight (phase 0); answering one costs nothing, for older browsers.
   - `POST /beam/start {ticket}`: checks the origin (consent below), finds the APK by its SHA-256 in its cache or
     fetches it (below), and answers `{app, token}` for the tab's `/ws?app=...&token=...`. A tab's token is good for
     one session from that origin only.
   - `POST /api/upload` (exists) for the relayed bytes, accepted only with a start's token, checked against the
     ticket's hash.
2. **Discovery and start.** A LaunchAgent the cask installs (`~/Library/LaunchAgents/dev.apksim.beam.plist`) with a
   `Sockets` entry for the port, so launchd listens and starts the agent on the first connection (nothing runs, no
   memory used, until a page asks). The agent takes the socket with `launch_activate_socket` (a JNI call beside
   `standby.c`), exits after 10 minutes with no session, and starts the warm standby on its first `start`, not on
   `hello`. Without the LaunchAgent (a source checkout), `apksim beam` runs it in the foreground.
3. **Trust and consent.** The `Origin` header of the agent's requests and WebSocket upgrades is the decision's input:
   browsers set it, pages cannot. A new origin's first `start` shows a native prompt (a small Cocoa helper in
   `native/`, so the Java agent needs no window server): "serve.example wants to run *App name* (12 MB, SHA-256
   abcd...) on this Mac. Allow once / Always for this site / Don't allow". Choices are kept in
   `STATE/beam/trust.json`; `apksim beam trust list|revoke ORIGIN`. Refused origins get the same answer as unknown
   ones. As for any serve: Host must be a loopback name (DNS rebinding), at most N sessions (`--max`, default 2), and
   only the tab's own commands pass `Session.check`. No URL scheme carries a request: a custom scheme cannot tell who
   opened it, so `apksim-beam://` (if added) only wakes the agent.
4. **Getting the APK there.** The server issues a **beam ticket** (`POST /api/beam {session|app}`): app id, name,
   size, SHA-256, the app's launch options, the session's state (device, orientation, appearance, locale, font
   scale, last deep link: what "Copy link" carries), a download URL, an expiry of 5 minutes, signed with an HMAC key
   serve makes at its start. The agent then:
   - uses its cache when it already has that SHA-256 (the next visit, another session of the same build), else
   - downloads `GET /api/beam/apk?ticket=...` from the server itself, which is fastest and keeps the bytes out of
     the tab, else, when that fails (the server sits behind single sign-on or a VPN proxy the browser passes and the
     agent cannot, so a 401/403 or an HTML page comes back),
   - answers `need-upload`, and the tab fetches the APK with its own credentials and posts it to the agent
     (streamed with `duplex: "half"` where the browser supports it, else as a Blob).
   In every case the agent checks the SHA-256 before anything runs. Serving an APK's bytes is new for serve
   ("no file is ever served" today), so it is opt-in: `apksim serve --allow-beam`, and only for an app a tab could
   already open, with a ticket that names it.
5. **The page.** `serve.html` gets a beam client:
   - Detection only with consent: a "Run on this Mac" button where the platform could run apksim (macOS on Apple
     Silicon: the WebGL renderer string names the chip). The page first asks
     `navigator.permissions.query({name: "loopback-network"})`, which never prompts: `granted` (a returning
     visitor) probes `/beam/hello` at once, `denied` hides the button, `prompt` probes only after the click. So a
     visitor without apksim never sees Chrome's prompt or the button off a Mac. While the prompt is up the probe
     stays pending (it neither fails nor times out), so the page says what to allow ("apps on this device") and
     keeps the server's stream meanwhile.
   - The page's CSP must name the agent for `fetch` (`connect-src 'self' ws: wss: http://127.0.0.1:PORT`): serve's
     present policy lets the WebSocket through (`ws:`) but blocks `hello` and `start` (phase 0).
   - Start on the Mac from the outset (the main path): when beam is available and chosen, the page opens the local
     session and never starts a server one. The server is only asked for the ticket.
   - Moving a running session (the secondary path): the local session starts beside the remote one on a hidden
     canvas and decoder; the remote stream and input carry on meanwhile, with a chip "Moving to this Mac:
     translating classes 4200" from the existing `p` messages. On the local first frame the page draws local frames
     into the visible canvas, sends input there, and closes the remote WebSocket (the server's session ends and its
     slot is erased, as on a closed tab). The app restarts at its deep link in v1, since in-memory state does not
     move; phase 5 moves its data.
   - A local session that ends badly shows the agent's `x` reason and offers "Run on the server instead"; a dropped
     local WebSocket (the Mac slept) reconnects to the same session for 30 s before giving up.
   - Protocol: `hello` gives a version; a page whose protocol the agent does not speak stays remote and says
     `brew upgrade apksim`. The page served by the server is the one that runs, so the agent must accept the
     protocols of the last few releases.
   - Embedding: the frame's origin (serve's) is what the agent trusts, but Chrome refuses a cross-site frame
     outright, with no prompt, unless the embedding page delegates the permission:
     `<iframe allow="loopback-network; local-network-access" ...>` (phase 0: either token works in Chrome 156,
     `local-network` alone does not). docs/serve.md's "Embedding" has to say so, and the embedded page shows "Run on
     this Mac" only when the query above does not answer `denied`.

### What the Mac keeps, and for how long

A beamed build and a beamed session are the visitor's to keep or not. By default nothing outlives the page for long;
keeping a session to use later is the visitor's choice.

- **Builds** (`STATE/beam/uploads/<sha256>.apk`, found by their hash): kept while any session of that build is open.
  When its last session ends (the page is gone: tab closed, navigated away, Stop, the idle limit, a crash), the
  build is marked deletable and the clock starts; it is deleted `keep-days` days later (default **2**, any number,
  `0` for as soon as the session has ended). A session of the same build from any site meanwhile finds it by its
  hash and stops the clock until that one ends too.
- **What goes with a build**: its apksim cache entry, `STATE/cache/<sha256>.apk-<key>` (manifest, extracted `.so`
  files, inflated DEX; only this build uses it, being named after the stored file), removed through `ApkCache`'s own
  rules (moved to its trash under the global lock, never while a process holds its `in-use.lock`). Not the translated
  class packs (`cache/classes/<runtime>/<dex crc>.pack`): they are shared by DEX content with any other launch of the
  same build and keep their own rules.
- **Sessions**: by default a session's data slot (`beam-N` and its `.device`: app data, /sdcard, Settings) is
  erased when it ends, as now. Saving one is opt-in, two ways:
  - per session, "Keep on this Mac" in the page while it runs there (a `keep on|off` message the agent's `Session`
    takes for itself, never passed to the instance; only from the tab that owns the session);
  - for every session, the agent's `save-sessions` setting.
  A saved session's slot is renamed when its instance has exited, to `k<12 hex>-0` (and `.device`) beside it, a name
  no beam session takes, and recorded in `STATE/beam/saved/<id>.json`: app name, SHA-256, launch options, device,
  appearance, locale, font scale, the site it came from, when, and the package (from the slot's folder).
- **A saved session holds its build**: the build sweep keeps any build a saved session names, whatever its age, for
  as long as the session is saved (`keep-saved-days` days after its last use, default: no limit), so it can be
  recovered. A saved session whose build is gone anyway (deleted by hand, or the cache emptied) waits for that build:
  beaming it again (same hash) brings it back, and `run` says so meanwhile.
- **Recovering it locally**:
  - `apksim beam saved`: each saved session's id, app, site, when, size, and whether its build is still there.
  - `apksim beam run ID [launch options]`: opens it in apksim's own window as an ordinary local launch of the stored
    build: `--data-slots k<id>` (its saved data, no `--fresh`), its launch options and device, `--no-autofill`.
    What it does then is kept in the same slot.
  - `apksim beam forget ID`: erases its data; its build then gets the ordinary `keep-days` hold from now.
  - Later: reopening a saved session in the tab (`/beam/start {"saved": ID}` from the site it came from).
- **Settings**: `apksim beam config [keep-days N] [save-sessions on|off] [keep-saved-days N|never]`, kept in
  `STATE/beam/config.json` and read at the agent's start (launchd starts it with no options, so command-line flags
  are not the place); the same as flags on `apksim beam` for a run in the foreground.
- **When it is swept**: at the agent's start, hourly while it runs, and as each session ends (so `keep-days 0`
  deletes at once). An agent that is not running deletes nothing: the LaunchAgent (phase 1) also runs `apksim beam
  sweep` once a day (`StartInterval`).
- **Not kept by default, and why**: a beamed build is another site's APK on the visitor's disk, and a saved session
  can hold that app's sign-ins and data. So the default is to delete, and the "Keep on this Mac" control says what it
  keeps.

### Sizing: one display, two views

The instance's display size is the one truth. The tab and the Mac window are both views of it; each sends a size
only when the user resizes that view, and follows the other by scaling without sending one back, so they never
loop. (The Mac window already tells its own resizes from the user's apart: `selfResizing` in `simwindow.m`.)

- **Device mode** (default for phone and foldable, today's behaviour): the display keeps the device's size and the
  canvas is scaled to fit the page's stage (`fit()`), at most one device pixel per physical pixel.
- **Fill mode** (a toggle; default for desktop and custom sizes): a `ResizeObserver` on the stage sends
  `size WxH` in dp (stage CSS pixels), at most every 100 ms while it changes and once at the end, as the Mac
  window's drag does. Density is `devicePixelRatio`, so the canvas's backing store is the frame's pixels and its CSS
  size is that over the ratio: no scaling at all. A `matchMedia("(resolution: Ndppx)")` listener catches the window
  moving to a display with another ratio and sends `device WxH@RATIO`. Between a resize and the frame at the new
  size the last frame stays pinned top left, as in the Mac window, so the page never shows a stretched frame. The app
  gets a configuration change as in a freeform window (recreated, or resized in place with `screenSize` in
  configChanges), and adaptive layouts switch class as the stage crosses 600 dp. Below 160 dp a side the canvas
  scales instead.
- **The simulator window**: "Show in Simulator" sends `window front`; the window shows the same frames (FrameStream
  reads the frame the Host presents, whichever view is up), with its vsync pacing the Host again. Dragging its
  corner resizes the display (existing path); frames at the new size reach the tab, which, being in Fill mode,
  marks the size as the window's (a "Fit to tab" button takes it back on the next stage resize or a click) and
  letterboxes it meanwhile. Rotating there swaps the frame's sides and the tab refits. Closing that window hides it
  while a tab is attached (new: today closing the window ends the instance), and Quit there ends the session, which
  the tab is told.
- `Session.check` in `--beam` mode accepts `size WxH` and `device WxH@D` within the `--device-size` limits
  (160-4096 dp a side, at most 4096 px), `window front|hide` and `maxfps N`. Remote serve keeps refusing sizes a
  client spells out (they cost the server memory); allowing them there is a separate decision.

### What comes for free, or changes, on the Mac

- Sound plays through the Mac (serve has none). It follows the tab: when the page is hidden it sends `maxfps 1`
  and stops acknowledging frames, and `maxfps 120` + `redraw` when it is back; audio keeps playing as an app in the
  foreground would. Whether a hidden tab should also pause the app (`home`) is left to measure on real apps.
- The Mac clipboard syncs both ways, `http(s)` links open in the Mac's browser, notifications and permission prompts
  appear as today in the page, file pickers stay `pick`-less as in serve until there is a page-side picker.
- Networking is the visitor's: the app reaches what their Mac reaches, inside apksim's sandbox (`sandbox-exec`: no
  writes outside `STATE`, no credential reads). The consent prompt says so.

## Phases

Each phase ends with its check passing in `scripts/beam-check.py` (new: a server and an agent on two loopback
ports, the agent's trust store in a temporary `STATE`, driven as `serve-check.py` drives a tab) and, for the page,
the manual checks listed.

0. **Browser feasibility.** Chrome done 2026-11-07 (results below): it works, behind a one-time permission
   prompt, at the same speed as a same-origin page. Safari and Firefox were not tested (Safari skipped: its
   WebDriver needs "Allow remote automation" in its Developer settings; Firefox is not installed here). Until they are, the page offers beam only where
   `navigator.permissions.query({name: "loopback-network"})` answers, i.e. Chromium browsers that have the
   permission Chrome's results are for; elsewhere the query throws and the page stays on the server. A browser
   that later turns out to refuse gets a fallback: a locally trusted TLS certificate for `127.0.0.1`, or top-level
   navigation to the agent's own copy of the page, which loses embedding.
1. **Agent and start-on-the-Mac.** Built 2026-11-07 (see "Phase 1 as built" below): `apksim beam`, the trust store
   and prompt, `serve --allow-beam`, the page's "On this Mac" choice, `scripts/beam-check.py`. Left from the plan: the
   LaunchAgent with socket activation in the cask (the agent runs in the foreground for now), and instances with a hidden window instead of `--headless` (with "Show in Simulator", phase 3).
1b. **Parity with a local launch.** Built 2026-11-07 (see "Phase 1b as built"). The goal: once beamed, the app is as fast as `apksim app.apk` in its own
   window. Already the same: the app's process (the same launcher and JVM, Metal, VideoToolbox, the Mac's memory: the
   app never runs in the browser, whose tab holds only a video decoder and a canvas) and a warm start (0.37 s to the
   first frame, against ~0.3 s for a warm launch). What differs is only how a frame reaches the screen:
   - **Encode and decode**: each frame is H.264-encoded (~5.6 ms, VideoToolbox, waited for on the encoder thread),
     sent over loopback and decoded in the tab: ~7 ms more from presented to on screen than the window's direct
     IOSurface present, and 4 Mbps loses detail in fine full-screen motion.
   - **Two clocks**: a headless instance paces on its own 120 Hz grid, the browser shows frames on the display's
     vsync. The window's display pacing put a new frame on 98-99% of vsyncs where the grid got 69-100% (AGENTS.md,
     vsync pacing); the tab has not been measured that way.
   - **Drags**: serve measured 72 fps with H.264 against 82 with JPEG while dragging (the encode waited for); not yet
     compared with the window under the same input.
   Work, in order: (1) measure the tab against the window with the same scripted input (Shader tab, fling, drag,
   typing): new frames per display vsync (the window's `frames`; the page counting new frames per
   requestAnimationFrame), input to screen, CPU of instance, agent and browser, memory; (2) loopback bit rates
   (20-50 Mbps) and quality; (3) the encode pipelined instead of waited for; (4) the instance paced by the browser's
   vsync (the page sends its frame times, Host's grid locks to them as it locks to CADisplayLink). Done when the tab
   is within a few percent of the window on each measure and input to screen within one frame (8 ms) of it.
1c. **What the Mac keeps.** Built 2026-11-07 (see "Phase 1c as built"). The policy above: builds held `keep-days` (default 2) after their last session
   ends and deleted with their cache entry; sessions erased unless saved ("Keep on this Mac", `save-sessions`);
   saved sessions holding their build; `apksim beam saved|run|forget|config|sweep`. Done when `beam-check.py`
   checks: a build kept while its session runs and deleted with its cache entry once `keep-days 0` passes after the
   session ends (and not before, with a nonzero hold); a second session within the hold finding it by hash; a saved
   session's slot renamed and listed; `run` reopening it with the data it left (the test app's `apksim://slot`
   "Write" values); a build a saved session names surviving the sweep; `forget` erasing the data and starting the
   build's hold; `config` values read at start.
2. **APK transport.** Built 2026-11-08 (see "Phase 2 as built"). Tickets, `--allow-beam`, `GET /api/beam/apk`, the hash cache, `need-upload` and the
   tab's relay. Done when: the second visit transfers nothing; a server behind an auth proxy (a stand-in in
   `beam-check.py` that wants a cookie) beams through the tab; a wrong hash, an expired or altered ticket, and an app
   the ticket does not name are refused.
3. **Sizing (~1 week).** Fill mode, density from the pixel ratio, the two-views rule, "Show in Simulator", close as
   hide, the `Session.check` additions. Done when: in Chromium, the canvas's backing size equals the stage's CSS size
   times the ratio and equals the frame's size after every resize (a script through the browser's devtools
   protocol); a window drag to another display re-densities; dragging the simulator window resizes what the tab
   shows within one frame; 100 rapid resizes from both sides end with both views agreeing and no feedback loop
   (count `size` commands); the test app's top bar reads the stage's size in dp.
4. **Moving a running session (~3 days).** Parallel start on a hidden canvas, cutover on the local first frame,
   remote close, the progress chip, reconnect after sleep, fallback to the server. Done when: no blank frame across
   the cutover (frame log), input before the cutover reaches the server and after it the Mac, and a killed agent
   mid-session offers the server.
5. **State that moves with it (~1 week, optional).** The server stops its instance (`quit`, so data is flushed),
   packs its data slot and `serve-N.device` into the ticket's download, and the agent unpacks it into its `beam-N`
   slot before launching without `--fresh`. Done when: the test app's Trips rename and the Settings values made on the
   server are there after the move. In-memory screen state (back stack, scroll) stays out: Robolectric's saved state
   is not a format to ship between machines.
6. **Polish.** Hidden-tab throttling, version mismatch messages, `apksim beam status`, the agent's log in
   `STATE/beam/logs`, a Linux agent (the Linux build is headless already; OpenH264 and CPU shaders), docs/serve.md
   and AGENTS.md updated.

## Phase 0 results (2026-11-07)

A loopback `apksim serve` on 127.0.0.1 (this branch's build, test app), probed from `https://example.com` (a real
public origin) and from a cross-site `https://example.org` iframe inside it, in Chrome 156.0.8078.81 with a fresh
profile, driven over the DevTools protocol (Mac, macOS 26.1). Permission states are what
`navigator.permissions.query` answered; "pending" is a request that neither succeeded nor failed in 8 s, i.e. one
waiting on the permission prompt (Chrome draws it, a script cannot see it).

| From | Permission | `fetch` 127.0.0.1 / localhost | `ws://127.0.0.1` |
|---|---|---|---|
| top level | not asked yet (`prompt`) | pending | pending |
| top level | granted | 200 in 1-2 ms | open in 1-5 ms |
| cross-site iframe, no `allow` | `denied` | fails at once | fails at once |
| iframe `allow="local-network"` | `denied` | fails at once | fails at once |
| iframe `allow="loopback-network"` or `"local-network-access"` | `prompt` | pending | pending |
| iframe with either token | granted | 200 | open |

- The permission for 127.0.0.1 is `loopback-network` (granted, it works; `local-network` and the older
  `local-network-access` names answer `denied` then). It covers WebSockets as well as `fetch`. One permission per
  origin, so a visitor answers once per site.
- No mixed-content block: an https page reaches `http://127.0.0.1` and `ws://127.0.0.1` (both count as
  potentially trustworthy), and `localhost` too. `[::1]` failed only because serve binds 127.0.0.1: the agent
  should be addressed as `127.0.0.1`.
- No Private Network Access preflight was needed: serve sends no `Access-Control-Allow-Private-Network` and the
  requests passed.
- Serve's own CSP (`connect-src 'self' ws: wss:`) on an https page: the WebSocket to 127.0.0.1 opens, `fetch` is
  refused; adding `http://127.0.0.1:47474` lets it through.
- Speed: serve's real page streaming the test app's Shader tab (H.264, WebCodecs), loaded from serve's own origin
  against the same page written into `https://example.com` with its URLs pointed at 127.0.0.1, two interleaved runs
  each:

  | | from 127.0.0.1 (same origin) | from https://example.com |
  |---|---|---|
  | Shader tab | 120 fps (10 of 10 samples 119-121) | 120 fps (10 of 10 samples 119-120) |
  | Frame presented → on screen | 7.2-7.4 ms | 7.3-7.4 ms |
  | Encode | 5.4-5.6 ms | 5.6 ms |
  | Frame size | 4.1 KB | 4.1-4.2 KB |
  | Start to first frame | 0.37-0.42 s | 0.38 s (1.16 s for the first session after serve started) |

  So the page's origin costs nothing measurable. The build had no AOT cache (`train.sh` not run), which affects
  start times, not the stream.
- Not measured: the prompt's wording and how a visitor reads it (no screen recording here), Safari, Firefox.

## Phase 1 as built (2026-11-07)

- **`apksim beam [--port N] [--max N]`** (launcher `beam)`, `Serve --beam`, `Beam.java`): serve in an agent mode on
  127.0.0.1 only (default port 47474, 2 sessions), with no page, catalog or token of its own (it refuses `--bind`,
  `--token`, `--apps` and the proxy options). State in `STATE/beam/` (`trust.json`, `uploads/`, `logs/`); instances in
  data slots `beam-N`, erased when their session ends unless kept (phase 1c), `--headless --no-autofill` as serve's. Builds were kept 14 days
  after their last use (serve's uploads: a day); since 1c, `keep-days` after their last session.
  - `GET /beam/hello` → `{"apksim", "protocol": 1, "trusted"}`, readable by any origin.
  - `POST /beam/start {"sha256", "name", "size", "options"}` → `{"app", "token"}` when the agent has that build, else
    `{"need": "upload", "upload"}`. Options go through serve's launch option list. An origin not yet allowed gets the
    prompt first (`osascript`'s dialog, brought to the front: Don't Allow / Allow Once / Always Allow); "never" and a
    "Don't Allow" from the last 10 minutes refuse without asking (403). No answer in 2 minutes refuses that start
    only, so the page may ask again.
  - `POST /beam/upload?upload=GRANT` with the APK as the body: refused before reading it unless the grant is this
    origin's; an APK whose SHA-256 is not the one the start named is refused and not kept.
  - `GET /ws?app=ID&token=GRANT`: serve's session, protocol and all, for that grant only.
  - Every grant is for one origin and one use and lasts 2 minutes. No Origin header: 403; a Host that is not a
    loopback name: 403. CORS preflights are answered for any origin (with `Access-Control-Allow-Private-Network`).
  - `apksim beam trust [list | allow ORIGIN | deny ORIGIN | revoke ORIGIN]` edits `trust.json`; the agent reads it on
    each request. `APKSIM_BEAM_CONSENT=allow|deny` (or `-Dapksim.beam.consent`) answers the prompt for checks.
- **`apksim serve --allow-beam [--beam-port N]`**: `GET /api/beam` → `{"port", "protocol"}` (404 without the
  option, which is how the page knows), `GET /api/apps/ID/beam` → `{"id", "name", "size", "sha256", "options"}` (409
  while an added app still downloads), `GET /api/apps/ID/apk` → the APK, both behind the server's token as
  everything is. The page's CSP adds `http://127.0.0.1:PORT` to `connect-src`.
- **The page**: an "On the server / On this Mac" menu in the header, shown on a Mac when the server offers beam and
  `navigator.permissions.query({name: "loopback-network"})` answers other than `denied`. With the permission
  already granted it asks `/beam/hello` at once and picks "On this Mac" when the visitor chose it before
  (localStorage) or this site is trusted there; otherwise picking it is what triggers the browser's prompt. Start
  then asks the server for the app's hash, the agent for a grant, sends the APK through the tab when the agent asks
  for it (`fetch` of `/apk`, posted to `/beam/upload`), and opens the agent's WebSocket; any failure says why in the
  log and runs the session on the server instead. Changing the menu during a session restarts it on the other side.
- **Not as planned**: the tab relays the APK in every case (the agent never reaches the server, so no tickets yet:
  phase 2 adds the direct download); the platform check is the user agent's "Mac", not the chip; no LaunchAgent.
- **Checked**: `scripts/beam-check.py APK` (starts a server with `--allow-beam` and two agents on loopback ports,
  30 checks, all pass: the server's endpoints and CSP; hello and preflights; no Origin, a rebinding Host, a site not
  allowed, a disallowed launch option, a mismatched hash, another site's upload or session grant, a grant used twice,
  a revoked site and a site saved as refused all refused; the APK sent once and found by hash after; a session
  streaming, taking a tap and its instance ending with the tab; "Always Allow" kept). `serve-check.py` against an
  `--allow-beam` server still passes (48 checks).
- **In Chrome 156** (serve's page served as `https://example.com` through DevTools' Fetch domain from a local
  `serve --allow-beam`, with its own headers and CSP; `loopback-network` granted as the prompt's Allow would; the
  site allowed with `trust allow`): the menu showed and chose "On this Mac" by itself, Start ran the test app on the
  agent with no server session, the Shader tab streamed at 119-120 fps, 7.2 ms from presented to on screen, H.264.
  Start to first frame: 0.76 s the first time (the 36 MB APK went through the tab), 0.37-0.38 s after (found by its
  hash, nothing sent). No console errors; Stop ended the agent's session.
- **The prompt, answered by the owner** (2026-11-07, a start from a new origin): the first try opened the dialog
  behind the window in use and nobody saw it (2 minutes, refused, and logged as if refused: fixed by activating
  `osascript` first and logging no answer apart). Then it came to the front, was answered "Always Allow" in 4 s,
  the site was saved (`trust list`) and the start answered a session grant; the next start from that site took
  21 ms with no prompt.
- **Not tried**: a cross-site embedded page, a server behind a token (the page's requests carry it as for everything
  else).

## Phase 1c as built (2026-11-07)

- **Builds** (`Catalog.sweepBuilds`, `BeamStore.sweep`): a build's file's mtime is when it was last started or stored,
  and `uploads/<sha256>.released` holds when its last session ended (a start or an upload deletes it: the clock
  stops). The sweep keeps a build a running session, an unexpired grant or a saved session names; any other goes
  once `keep-days` have passed since its `.released` time, or, with none (started but never run: the grant expired),
  since its last start plus 3 minutes, so a start's grant is never left without its build even with `keep-days 0`.
  A deleted build's ids go with it, and its cache entry through `ApkCache.remove` (moved to the trash under the
  global lock, kept while a process holds its `in-use.lock`; one left that way goes at a later sweep).
- **When**: at the agent's start, hourly, after each `/beam/start` answered from the store, and as each session
  ends (on its own thread, once its instance has gone and its slot is saved or erased).
- **One agent per state folder**: the agent holds `STATE/beam/agent.lock` (its port inside) while it runs; a second
  refuses to start, and `apksim beam sweep` (for launchd's daily run) does nothing while one runs.
- **Saved sessions** (`BeamStore.save`): with keep on (the tab's `keep on|off`, answered `k on|off`; the `i` message
  says `"keep"` on the agent only, which is how the page knows to show "Keep on this Mac"), the session's end renames
  `beam-N` and `beam-N.device` to `k<12 hex>-0` under the slot's lock and writes `saved/<id>.json` (app, SHA-256,
  package, device as launched, appearance, locale, font scale, permissions, site, launch options, saved, lastUsed). A
  slot it cannot rename is erased as before. `run` checks the launch options against serve's list again (the record
  is a file), launches `apksim --data-slots k<id> --no-autofill ...` with the extra options given, and notes lastUsed
  before and after; a build that is gone says which site to run it from again.
- **Settings**: `apksim beam config` writes `STATE/beam/config.json`, read at the agent's start (the agent's log
  line `[beam] keeps: ...` says what it uses); `apksim beam --keep-days N --save-sessions on|off --keep-saved-days
  N|never` override it for one run. `keep-days` takes fractions.
- **Checked**: `scripts/beam-check.py APK` now runs in a state folder of its own (`APKSIM_HOME`, removed after), so
  the Mac's own agent and its sites are never touched, and adds the 1c checks listed in its docstring.

## Phase 1b as built (2026-11-07)

- **The bench**: `scripts/beam-parity.py APK [--runs N] [--seconds S] [--agent-opt=--bitrate=N]`. Interleaved runs of
  the app in its own Mac window (`--window nofocus`) and beamed into a Chrome tab (a serve with `--allow-beam`, an
  agent, Chrome with a fresh profile kept from throttling while covered), driven with the same commands on each
  instance's own control port, so only the frame's way to the screen differs. It reports per scenario: new frames on
  N% of display refreshes while animating and frames replaced before being shown (the window counts its display
  link, `frames`' screen part; the page counts `requestAnimationFrame` ticks the same way, `apksimPacing`), the
  instance's own fps, CPU of the instance (and agent and Chrome), present → on screen, encode, frame size, tap → on
  screen.
- **Before**, test app, Mac (120 Hz display), 2 runs of 5 s each:

  | | window | tab |
  |---|---|---|
  | Shader tab | 99, 99 | 100, **91** (57 replaced) |
  | Flings | 89, 90 | 91, **82** (70 replaced) |
  | Slow drags | 100, 99 at **~170 fps** | 97, **91** (51 replaced) at ~165 fps |

- **Why, and what changed**:
  1. Slow drags drew ~170 frames a second at 120 Hz, in the window and headless alike (the window threw 100-140 a
     drag away). A timed drag's move went out every 8 ms of real time, out of step with the vsync: on the headless
     grid, the window it dirtied was drawn on that loop turn and again at the vsync (143 frames in 2.4 s drawn
     between vsyncs, counted); in the window, a vsync asked for after the last frame ran at once, since the loop's
     deadline for the display's vsync had already passed. Now (`Host`): a frame is drawn only on a turn that ran a
     vsync or when none is pending (`frames` counts any `held for their vsync`); a vsync asked for since the last
     frame waits for the display's next one; while a timed drag runs, the loop turns at each vsync and sends the move
     there (as a device consumes batched touch input in the vsync's frame); and other input goes out 7 ms apart
     rather than 8, since vsync turns 8.3 ms apart give or take a wakeup's jitter skipped an event at 8. Drags: 120
     fps, 99% new, 1-2 replaced (were 163-175 fps, 98%, 103-131 replaced); flings unchanged (94-96%).
     `-Dapksim.offVsyncFrames=true` restores the old loop, for A/B runs.
  2. The tab drew each frame the moment it was decoded, and the stream's jitter (raster, encode, transport, decode:
     5-12 ms between frames sent every 8.3) put two within one browser refresh and none in the next. Measured per
     refresh with the instance on its own grid: of 361, 162 had one frame, 100 none and 99 two. Now the page shows
     decoded frames at `requestAnimationFrame` from a queue of at most two, as the window shows its frames at the
     display's vsync; that costs no time, since a canvas draw is shown at the next refresh either way.
  3. The headless instance paced on its own 120 Hz grid, which drifts against the browser's display. The page now
     measures where in its refresh interval frames are decoded (against when its `requestAnimationFrame` callbacks
     run, not their timestamps: a frame decoded after the callback waits a whole refresh) and every 500 ms sends
     `vsync PERIOD_US SHIFT_US`, its period to the microsecond (over the refreshes in its last 120 ticks: a median
     alone rounds to 8.3 ms, and 0.03 ms a refresh drifts 4 ms a second, which corrections twice a second never
     caught) and 0.6 of the phase error. The instance takes that as its display's vsync (`SimWindow.remoteVsync`,
     through the same display-following path as the window) until 1.5 s pass without one. With the queue it is what
     sets the latency: present → on screen 11-16 ms paced, 17 ms on the grid.
  4. The agent streams at 20 Mbps by default (`--bitrate` to change): its stream never leaves the Mac. The Shader tab
     gets 16 KB a frame against 4, for ~0.6 ms more encode, with the same pacing and latency.
- **After**, same bench (2 runs of 5 s):

  | | window | tab | tab present → on screen |
  |---|---|---|---|
  | Shader tab | 99, 99 | 100, 99 | 11.4, 15.9 ms |
  | Flings | 88, 90 | 89, 88 | 14.7, 11.6 ms |
  | Slow drags | 99, 99 | 99, 99 | 14.4, 11.7 ms |

  The instance's fps, CPU (0.4-0.7 of a core) and memory are the same in both; the tab adds the agent (0.02 of a core)
  and Chrome (~0.3). Tap → on screen in the tab: 32-34 ms. First frame: 0.17 s in the tab (a warm standby, the build
  cached), 0.28 s for a warm launch of the window.
- **What is left**: about one refresh more latency than the window, which presents at the next vsync after raster.
  Most of it is the H.264 encode (5.4-6.2 ms, on its own thread, so it never slows the app) and the decode.
- **Tried, not kept**: VideoToolbox's `PrioritizeEncodingSpeedOverQuality` with an expected 120 fps made the encode
  23 ms (from 5.6). Pacing without the queue (measured against the refresh timestamps, the period from the median):
  73-90% new frames, worse than none, as the period was 0.03 ms short and the phase drifted.

## The beam sandbox (2026-11-08)

A beamed APK is a web site's code on the visitor's Mac. apksim's ordinary sandbox (the launcher's `sandbox-exec`
profile) keeps an app out of credentials and lets it write only the state folder, which was not enough here: that
folder holds the agent's `trust.json` (an app allowed once could allow its site for good), other apps' data slots and
saved sessions, saved autofill sign-ins, and the caches every launch loads code from (a translated class or an
extracted `.so` planted there would run in another app, the owner's own included). So beamed instances run in a
sandbox of their own (`APKSIM_BEAM_SANDBOX`, set by the agent; standbys named with `b`, `BeamSandbox`):

- **Writes**: only its own scratch (`STATE/tmp/<pid>`: the JVM keeps the launcher's PID, so the profile can name it),
  its own out folder (`STATE/beam/out/<pid>`) and `beam-N` data slots with their `.device` folders.
- **Reads**: nothing in the home folder but apksim, its libraries and the JDK (metadata only for the rest: resolving a
  path stats the folders above it; listing is refused); in the state folder not the agent's folder (but the uploaded
  builds and its build caches), serve's files, autofill, screenshots or `cache/` (other apps' data, the shared class
  and APK caches), except `beam-N` slots.
- **Network**: what the Mac reaches (the consent prompt says so), but no loopback connection (other instances' control
  ports, the agent: a non-browser client could forge an `Origin`) and no standby's socket (it would run an APK in the
  ordinary sandbox).
- **Caches without sharing**: a beamed instance starts from its build's caches the agent keeps
  (`STATE/beam/cache/<sha256>/`: translated classes read in place, its APK cache entry cloned with `cp -c`), which it
  may read but not write, and writes what it makes to its out folder; once it has exited the agent moves that into
  the same build's cache, regular files and folders only, so what an app writes reaches only later launches of the
  same build. Deleted with the build.
- **Standbys**: a standby's sandbox is the one it started in, so an instance never starts the next (its rules name its
  own PID); the agent starts beam standbys itself, at its start and a second after each session's first frame.
- **Cost**: none measured. First frame in the tab 0.17 s, as before (0.29 s while the APK cache entry was rebuilt in
  scratch each launch, 0.5 s while a read-only seed's packs were dropped by a write of their modification time).
- **Checked**: `beam-check.py`'s sandbox section runs shell probes inside the profile (17: its scratch, slot, the jar
  and the internet allowed; the trust file, autofill, another app's data, the shared class cache, another process's
  scratch, the home folder, a listening loopback port and a standby socket refused) and asks a running beamed instance
  to write a screenshot into the agent's folder (refused) and into its scratch (works); its 1c checks find nothing a
  beamed instance wrote in the shared caches.
- **Not covered**: two beamed sessions at once can read each other's `beam-N` slot (a standby's profile cannot name
  the slot a launch will take); an APK can read other builds' APKs in `beam/uploads` (other sites' published apps);
  `--no-sandbox` is refused for beamed instances.

## Review fixes (2026-11-08)

- An upload's hash is checked while the file is still staged (`Catalog.upload` with the expected SHA-256): with
  `--warm on` the warmer was told of an upload, and ran it, before its hash was compared.
- Keeping a session: the `.device` folder moves first, while the slot still holds its name (once renamed, a new
  session could take `beam-N` and empty its `.device`); a failure puts both back, or deletes what cannot go back, so
  nothing is left under a name nothing lists; saving shares the sweep's lock, so a sweep never misses both the running
  session and its record.
- `apksim beam run` drops the app's own `--device` (the session's recorded one stands), as a session does.
- The page: a start on this Mac counts as running while it waits for the agent (Stop works, changing the menu starts
  it again where asked), and a Mac session that ends before its first frame (the agent's sessions all in use, a
  refused upgrade, an instance that fails to start) runs on the server instead. `scripts/beam-page-check.py` checks
  both in Chrome.

## Phase 2 as built (2026-11-08)

- **Tickets** (`BeamTicket`): `GET /api/apps/ID/beam` (behind the token) answers a `ticket` too:
  `v1.<app>.<sha256>.<expiry>.<HMAC-SHA256>`, signed with a key serve makes at its start (tickets die with it), good
  for 5 minutes (`APKSIM_BEAM_TICKET_SECONDS` for checks). `GET /api/beam/apk?ticket=T` needs no token: it serves the
  APK of the app the ticket names while that app's file is still the build it names, and refuses an altered, expired
  or unknown ticket (403). Not the session's state yet (device, appearance...), which the start sends as before.
- **The agent** (`Serve.beamFetch`): `/beam/start` takes `ticket` and `url` (the page sends
  `new URL("api/beam/apk", location.href)`, so a base path or a proxy's prefix holds). For a build it lacks it
  downloads `url?ticket=T` itself, but only from the page's own origin (the request's `Origin`: a page cannot make
  the agent fetch what it could not fetch itself) and only that path, follows no redirects (a single sign-on page is a
  failure, not a place to go), wants an APK's content type, keeps within `--max-upload`, and checks the SHA-256 the page
  named before the file is stored (`Catalog.upload` with the expected hash). The answer says `"via": "cache"` or
  `"download"`. Any failure answers `need: upload` with `why` (in the page's log), and the tab relays the APK as in
  phase 1.
- **Checked** (`beam-check.py`'s download section, 15 checks, a server and agent of their own): the ticket alone
  downloads the APK, altered / missing / other-build / expired tickets are refused; behind a stand-in single sign-on
  (401 without its cookie) the agent asks the tab, whose upload starts the session; from the page's server the agent
  downloads the build itself and the second visit transfers nothing; a hash the download does not have is not kept;
  another site's address and another path of the page's site are not fetched; with an expired ticket the agent asks
  the tab. In Chrome with serve's page: "this Mac downloaded the app from this server", then found by its hash.

## Measurements to keep (as serve's table)

Click to first local frame (warm standby, cold standby, first launch of an APK); cutover gap in frames; tap to frame
and present to screen locally; stream fps on the Shader tab and while dragging; CPU and memory of the agent idle
and per session; bytes moved on a first and a second visit; resize to frame at the new size, from the tab and from
the simulator window.

## Risks

- Browsers restricting pages' access to local services keep tightening. Chrome 156 allows it behind a
  `loopback-network` prompt (phase 0); Safari's and Firefox's rules are not checked yet and can still change the
  transport for them. The rest of the design does not depend on which one wins.
- A large app's first launch translates its classes for minutes. The tab keeps the server's stream (moving) or shows
  progress (starting) meanwhile; the agent can warm an APK as soon as its ticket arrives, before the visitor clicks
  anything once they chose "always".
- The page that runs is the server's, the agent is the visitor's installed version: the protocol needs versions from
  the first release (`hello`).
- Serving APK bytes is a new permission for token holders (`--allow-beam`, off by default).

## Not planned

Beaming back from the Mac to the server; other operating systems than macOS and Linux; one session viewed by several
tabs; a native window placed over the browser (rejected above).
