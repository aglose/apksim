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
| 1 Agent, start on the Mac | Done: `apksim beam`, trust and prompt (answered by the owner), `serve --allow-beam`, the page's menu, `beam-check.py`; the launchd agent (`apksim beam install`, see "The launchd agent"), checked with launchd itself |
| 1b Parity with a local launch | Done (see "Phase 1b as built"): the tab shows a new frame on as many display refreshes as the window, in every scenario measured; one refresh more latency (the encode) |
| 1c What the Mac keeps, and for how long | Done (see "Phase 1c as built"): builds held 2 days (configurable) after their last session, sessions erased unless kept, `apksim beam saved|run|forget|config|sweep` |
| Beam sandbox | Done (see "The beam sandbox"): beamed APKs reach nothing of the Mac's but their own slot, scratch and build caches |
| 2 The agent downloads the APK itself | Done (see "Phase 2 as built"): with the server's ticket, from the page's own site, or from an artifact store its owner allowed (see "Builds from an artifact store"); the tab relays it only when that fails |
| 3 Sizing, Show in Simulator | Done (see "Phase 3 as built"): Fill mode at the browser's pixel ratio, the simulator window as a second view, close hides it |
| 4 Moving a running session | Done (see "Phase 4 as built"): server to Mac and back with no blank frame; a refused move stays; a dead agent offers the server. Left: reconnecting after sleep |
| 5 The app's data moves with it | Done (see "Phase 5 as built"): a move from the server to the Mac takes the app's data slot and /sdcard and Settings values ("With its data"); the app quits on the server first |
| 6 Polish, Linux agent | Partly (see "Phase 6 so far", "Versions"): hidden tabs throttled, `apksim beam status`, the launchd agent, versions. Left: a Linux agent (scoped in "A Linux agent: scope") |

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
5. Input, keyboard and deep links work as in a remote session, with the same page code. The clipboard does not
   follow the Mac's ("The beam sandbox": a site's app would read what the visitor copies).
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
   limited to `Catalog.LAUNCH_OPTIONS` as today (including `--sdcard-file PATH=text:TEXT|BASE64`, a file written to the
   session's `/sdcard` before the app starts, over what a reused slot had; 64 KB a file, 72 KB in all, and `--broadcast`, one broadcast to the app's own package after `Application.onCreate`). One session per tab, ended when the tab's WebSocket closes, as now.
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
  - From the tab, by the site it came from: reopening it, or starting a newer build of the same app on a copy of its
    data (issue 181: "Saved data from the tab").
- **Settings**: `apksim beam config [keep-days N] [save-sessions on|off] [keep-saved-days N|never] [rec input|all|off|CHANNELS]`, kept in
  `STATE/beam/config.json` and read at the agent's start (launchd starts it with no options, so command-line flags
  are not the place; `rec` is the flight recorder channels a session starts with, `input` by default, so a bug can be saved with `rec save` and replayed (docs/flight-recorder.md)); the same as flags on `apksim beam` for a run in the foreground.
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
- The Mac clipboard does not follow the app's either way (unlike an ordinary instance: see "The beam sandbox"),
  `http(s)` links open in the Mac's browser, notifications and permission prompts appear as today in the page, file
  pickers stay `pick`-less as in serve until there is a page-side picker.
- Networking is the visitor's: the app reaches what their Mac reaches over IPv4, but not loopback, inside the beam
  sandbox (writes only to its own slot, scratch and out folder; "The beam sandbox"). The consent prompt says so.

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
   and prompt, `serve --allow-beam`, the page's "On this Mac" choice, `scripts/beam-check.py`; the LaunchAgent with
   socket activation on 2026-11-08 (`apksim beam install`, opt-in rather than in the cask; see "The launchd agent").
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
3. **Sizing.** Built 2026-11-08 (see "Phase 3 as built"). Fill mode, density from the pixel ratio, the two-views rule, "Show in Simulator", close as
   hide, the `Session.check` additions. Done when: in Chromium, the canvas's backing size equals the stage's CSS size
   times the ratio and equals the frame's size after every resize (a script through the browser's devtools
   protocol); a window drag to another display re-densities; dragging the simulator window resizes what the tab
   shows within one frame; 100 rapid resizes from both sides end with both views agreeing and no feedback loop
   (count `size` commands); the test app's top bar reads the stage's size in dp.
4. **Moving a running session.** Built 2026-11-08 (see "Phase 4 as built"; not the reconnect after sleep). Parallel start on a hidden canvas, cutover on the local first frame,
   remote close, the progress chip, reconnect after sleep, fallback to the server. Done when: no blank frame across
   the cutover (frame log), input before the cutover reaches the server and after it the Mac, and a killed agent
   mid-session offers the server.
5. **State that moves with it (~1 week, optional).** Built 2026-11-08 (see "Phase 5 as built"; the server's to the Mac only). The server stops its instance (`quit`, so data is flushed),
   packs its data slot and `serve-N.device` into the ticket's download, and the agent unpacks it into its `beam-N`
   slot before launching without `--fresh`. Done when: the test app's Trips rename and the Settings values made on the
   server are there after the move. In-memory screen state (back stack, scroll) stays out: Robolectric's saved state
   is not a format to ship between machines.
6. **Polish.** Partly built (see "Phase 6 so far"). Hidden-tab throttling, version mismatch messages, `apksim beam status`, the agent's log in
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

- **Writes**: everything denied first, then only its own scratch (`STATE/tmp/<pid>`: the JVM keeps the launcher's PID,
  so the profile can name it), its own out folder (`STATE/beam/out/<pid>`), `beam-N` and `bm<12 hex>-N` data slots with their
  `.device` folders (and making an app's folder for them, create only: a write on the folder would let it rename another
  app's folder into its scratch; and a folder only (`vnode-type DIRECTORY`): a link named for an app would lead the
  agent, which unpacks a moved session's data there, into the home folder, so the agent also refuses an app folder that
  is a link or resolves elsewhere), its standby's socket, Metal's shader cache and `/dev/null`: not apksim's own files wherever it is installed
  (a Homebrew install is outside the home folder), the temp folder (the launcher's link to its libraries is there),
  `/tmp`, or anything another process runs or loads.
- **Reads**: nothing in the home folder but apksim, its libraries and the JDK (metadata only for the rest: resolving a
  path stats the folders above it; listing is refused); in the state folder not the agent's folder (but the uploaded
  builds and its build caches), serve's files, autofill, screenshots, `cache/` (other apps' data, the shared class
  and APK caches) or `tmp/` (another instance's scratch, where files it shares are copied), except `beam-N` slots and
  its own scratch.
- **Network**: what the Mac reaches over IPv4 (the consent prompt says so), but no loopback connection (other
  instances' control ports, the agent: a non-browser client could forge an `Origin`), no IPv6 (an IPv6 socket reaches
  loopback as `::ffff:127.0.0.1`, which the sandbox's `localhost` rule does not match; the JVM runs with
  `java.net.preferIPv4Stack`, so Java still reaches IPv4 hosts; IPv6-only hosts are out of reach) and no standby's
  socket (it would run an APK in the ordinary sandbox), nor any other local socket but the resolver's
  (`mDNSResponder`) and `syslog`: not an `ssh-agent`'s (signing with the visitor's keys) or a container engine's.
- **Clipboard**: the app's clipboard does not follow the Mac's (an ordinary instance's does, both ways): a site's app
  would read whatever the visitor copies, passwords included, and could write to it. The profile also refuses the
  pasteboard service itself, so native code or reflection past apksim's own check reaches nothing; Cmd+V in the shown
  simulator window does not paste either (the tab's keyboard types text in).
- **No terminal**: `/dev/tty` and `/dev/ttysN` are neither read nor written (the profile once allowed writing
  `/dev/tty`). A cold-launched instance is in the agent's terminal session, so a site's app could otherwise read what
  the visitor types in the terminal running `apksim beam` and write escape sequences into it. Its stdin is
  `/dev/null` and its output a pipe either way.
- **Signals and other systems**: signals only to itself (`target self`: no other process of the visitor's, the agent
  included, and no other beamed instance: they share one sandbox, as each spawns the next standby inside its own, so
  `same-sandbox` let one site's app end another's session). The launcher's standby check, which runs inside it, takes
  `kill -0`'s "not permitted" for a standby that is there. `apksim beam` runs only on macOS: there is no sandbox for it elsewhere yet, and the launcher refuses a
  beamed instance without one.
- **Caches without sharing**: a beamed instance starts from its build's caches the agent keeps
  (`STATE/beam/cache/<sha256>/`: translated classes read in place, its APK cache entry cloned with `cp -c`), which it
  may read but not write, and writes what it makes to its out folder; once it has exited the agent moves that into
  the same build's cache, regular files and folders only, so what an app writes reaches only later launches of the
  same build. Deleted with the build.
- **Deleting what an APK could write**: the agent runs outside any sandbox and erases slots, `.device` folders,
  out folders and moved data a beamed app wrote, while a process the app left running may still write there. Each
  is deleted through folder handles from its parent down (unlinkat on a SecureDirectoryStream, `SlotPack.deleteTree`
  and `emptyTree`), never by path: a folder swapped for a link between a walk and a delete by path led that delete
  outside (a probe racing swaps against the old code lost a file of another folder's within 107 rounds; none in
  3,460 now). A link anywhere, the slot itself included, is removed alone.
- **Fail closed**: the launcher runs a beamed instance in the beam profile or inside one, and refuses it otherwise.
  It used to skip the sandbox whenever `APKSIM_SANDBOXED` (meaning "already in one") or `APKSIM_NOCACHE` (training)
  was set, so an agent started from a shell that had either ran sites' APKs unsandboxed, silently. A standby spawned
  inside is told it is (`APKSIM_SANDBOXED=beam`), and the launcher checks that rather than trusting it: inside, a
  write to `STATE/beam` is refused. The agent also strips those variables from its instances' environment.
- **Standbys**: a standby's sandbox is the one it started in, so an instance never starts the next (its rules name its
  own PID); the agent starts beam standbys itself, at its start and a second after each session's first frame.
- **Cost**: none measured. First frame in the tab 0.17 s, as before (0.29 s while the APK cache entry was rebuilt in
  scratch each launch, 0.5 s while a read-only seed's packs were dropped by a write of their modification time).
- **Checked**: `beam-check.py`'s sandbox section runs shell probes inside the profile (25: its scratch, slot, a new app
  folder, the jar
  and the internet allowed; the trust file, autofill, another app's data, the shared class cache, another process's
  scratch (writing and reading), the home folder, apksim's own files, the temp folder, `/tmp`, a listening loopback
  port over IPv4 and as `::ffff:127.0.0.1` over IPv6, a standby socket, and renaming or removing another app's data
  folder refused) and asks a running beamed instance
  to write a screenshot into the agent's folder (refused) and into its scratch (works); its 1c checks find nothing a
  beamed instance wrote in the shared caches.
- **Not covered**: two beamed sessions at once can read and write each other's `beam-N` and `bm<12 hex>-N` slots
  (a standby's profile cannot name the slot a launch will take; a moved session's `bm` name is random, and no beamed
  app can list `cache/data`, so it would have to guess 48 bits); an APK can read other builds' APKs in `beam/uploads` (other sites' published apps);
  services on this Mac that listen on every interface stay reachable at its own network address (and the local
  network with them): the profile language names hosts only as `*` or `localhost` ("host must be * or localhost"),
  so only loopback can be kept out, and the consent prompt says the app reaches what the Mac reaches;
  `--no-sandbox` is refused for beamed instances.

## Review fixes (2026-11-08)

Sixth review (its summary): the beam profile refuses signals beyond its own sandbox and the pasteboard service; the
agent and the launcher refuse to run beamed apps off macOS, which has their only sandbox.

Fifth review (its summary): serve and the agent end only the JVM the kernel reports on an instance's control port
(else one whose own launch.info names this APK), never a PID the app could print (it named any "java" process, the
IDE's or the agent's own); the beam profile refuses local sockets but the resolver's and syslog's (ssh-agent's or a
container engine's were open).

Fourth review (its summary, no inline issues): beamed instances, which have a window since phase 3, no longer share
the Mac's clipboard; `trust.json` is changed under a lock the agent and `apksim beam trust` share.

Third review: package folders are create-only in the beam profile (renaming one into scratch read it); the agent's
identity for an instance's files is the process the kernel reports on its control port (`lsof`), not a PID the app
could print; `beam run` holds a lock `forget` and the expiry sweep need exclusively; a kept session records the
appearance, locale and font scale its tab last set, which `run` applies after the app's own options; the benches make
sure a session is on this Mac and read the state folder the launcher uses. A beamed JVM writes its crash report in its
scratch (`-XX:ErrorFile`), which the agent renames into its own folder before reading its header (never through a
link), so a crash still reaches the tab ("crashed: SIGSEGV ...", `beam-check.py`).

Second review: the beam profile denies every write but its own (above), reads of other instances' scratch, and IPv6
(mapped loopback); the agent renames an instance's out folder into its own `beam/import/` before reading it, so a link
planted there (or a folder swapped by a child process the app left running) cannot point it at the shared caches; the
agent makes `STATE/tmp` before starting an instance in it; a new vsync period rebases its anchor on the latest vsync
of the old one; a start cancelled while the server answered never reaches the agent (its prompt); the benches find
their instance by the session's PID; a stream viewer coming before the window (a cold launch) no longer calls into the
window's library before it is loaded.

Later review: taking a session's grant marks its build and moved data as taken before the grant goes (`Beam.take`).
Removed first, an hourly or session-end sweep running between the two found no owner for the moved data and erased
it just before the session started with it.
The consent prompt's output is read on a thread of its own and its process ended after 150 s with no answer: read
first, an `osascript` that never ended held the one-prompt-at-a-time lock, and with it every later start, for good.
A build's kept APK cache entry is swapped in by renames once a session ends, so a session of the same build starting
then never clones it half deleted.
The beam profile denies the apksim folder's own `work/` sign-ins, app data, scratch, and serve's and the agent's
files as well as the state folder's: with `APKSIM_HOME` elsewhere (as `beam-check.py` runs) the folder's read rule let a
beamed app read a checkout's `work/autofill` and `work/cache/data` (two probes now). The package a moved session's data
is checked against is matched at the manifest element's start only, with the decoder escaping names: searched for
anywhere, a manifest whose names repeated `<manifest` took the agent quadratic time (18 s for 350k characters; the
decoded element is also capped at 1M characters now).
A launch given `--jvm -Djava.net.preferIPv4Stack=...` launches cold: the JDK reads it once at JVM start, so a
standby took the launch and ignored it. A beamed instance's `=true` is its standby's own, set as that JVM starts.
An agent's cache warm-up (`apksim beam --warm on`) starts from its build's cache and leaves what it translated and
extracted there once it has exited, as a session does (`Session.keepBeamCaches`): before, it ran in the beam sandbox,
wrote only its out folder, and the first session translated everything again (`beam-check.py`'s warm checks).
That package's name is refused past 256 characters before it is checked: the check's repeated group recursed per
segment, so a manifest package of 10k characters ("a.a.a...") overflowed the stack, an Error that ended the request.
The page's `apksimStats().frames`, which the Chrome checks wait on, counts the frames shown from the current socket:
it was the length of the last 240 frame times, so a count compared with an earlier one stopped growing at 240, and a
moved or restarted session's `frames > 0` held before the new socket had shown anything.
The agent's sweep keeps a build while a cache warm-up of it is queued or running (`Warmer.holds`), and its hold starts
when the warm-up ends: a large app's warm-up runs past a new build's 3-minute grace, and with `keep-days 0` the sweep
could delete the APK and its cache under it.
Frames that follow a display's vsyncs (the window's, or a viewer's `vsync`) come at most 120 a second
(`Host.vsyncFrameAt`): on a display faster than 120 Hz (from 120.3 Hz: one measured a hair fast is left alone) a vsync
before a 120 Hz budget is skipped. The test app's Shader tab, told a viewer's 125 / 144 / 240 Hz display: 144 / 234
frames a second before at 144 / 240 Hz, 119.1 / 119.3 / 120.0 now; 120 and 60 Hz unchanged.

First review:

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

## Phase 3 as built (2026-11-08)

- **Beamed instances have a window**, hidden (`--window hidden` instead of `--headless`), so the same display has a
  second view. While it is hidden there is no display vsync and the tab's `vsync` paces the instance (phase 1b); shown,
  the display's vsync paces it again.
- **The page** (a session on this Mac only, shown when the session's `i` message says `keep`, i.e. the agent's): a
  "Device size / Fill the page" menu (remembered), "Show in Simulator" (`window front`) and "Fit to tab".
  - Fill: the stage's CSS size in dp at `devicePixelRatio` (`device WxH@D`; 160-4096 dp, at most 4096 px a side, the
    ratio rounded to 0.01), sent from a `ResizeObserver` at most every 100 ms and once 120 ms after the last change,
    and again when a `matchMedia("(resolution: Rdppx)")` listener sees the window move to another ratio. The canvas's
    CSS size is the frame's pixels over the ratio, pinned top left: one frame pixel per screen pixel, and a frame from
    before a resize is shown as it is, never stretched. Not sent again while unchanged.
  - The two views: the tab sends a size only for its own stage, never in answer to a frame. A frame of a size it did not
    send, once its own resizes have settled (500 ms), is the simulator window's (dragged, rotated): shown as it is,
    with "Fit to tab", which sends the tab's size again (as does the next stage resize).
  - Device size sends the menu's device again; a new session starts in the remembered mode.
- **`Session.checkBeam`** (the agent only): `device WxH@D` within those limits (not answered to the tab: one a resize
  step), `window front|hide`, `maxfps N`. A remote serve still takes only named sizes.
- **Closing the simulator window** while a stream viewer is attached hides it (`windowShouldClose`,
  `SimWindow.closeHides`, set as FrameStream gains and loses subscribers); the session goes on in the tab. Quit (Cmd+Q)
  ends it, and the tab is told. Without a viewer the close button ends the instance as before. `window close` does
  what the close button does, for checks.
- **Checked** (`scripts/beam-size-check.py`, Chrome, 25 checks): after each of four browser window sizes the canvas is
  the stage times the ratio and the frames that size, shown unscaled, and the test app's top bar reads the stage's size
  in dp; a pixel ratio of 1 (DevTools' device metrics) re-densities the display and back; `window show` then a drag to
  420x760 points shows 840x1520 in the tab within a moment with no size sent back and Fit to tab offered, which takes
  it back; 50 browser resizes interleaved with 50 window drags end with no size sent after them and both views on the
  tab's size; the close button with the tab attached hides the window and the session streams on; Device size brings
  the phone back. Without a viewer, `window close` still ends an instance (exit 0).
- **Not done**: Fill as the default for desktop and custom sizes (it is a remembered choice), rotating in Fill (the
  frame turns and Fit to tab is offered), letterboxing the window's size in the tab's middle (it is pinned top left).

## Phase 4 as built (2026-11-08)

- **The page** (`moveSession`): changing "On the server / On this Mac" while a session shows frames moves it rather
  than restarting it. The other side's session starts beside the running one (on the Mac through the same grant,
  download or relay as a start, `macGrant`; on the server a WebSocket of its own), with the same query: app, device,
  appearance, locale, font scale and the last deep link, so the app starts there again where its link points (what it
  holds in memory does not move; its data is phase 5). The running session goes on streaming and taking input
  meanwhile, and a chip says "Moving to this Mac: translating classes…" from the new side's `p` messages. On the new
  side's first frame (a keyframe) the page takes it over (`promote`): the old socket's handlers stop drawing, it is
  closed (the server's session ends and its slot is erased, as for a closed tab), the new side's buffered `i`/`c`
  messages are replayed (its device, the keep and display controls, the codec), the decoder starts on that keyframe,
  and input goes there. The canvas is never cleared: the old side's last frame stays up until the new one is drawn.
- **Fallbacks**: a move the other side refuses or that fails before its first frame (`x`: the agent's sessions all in
  use; a closed connection; a grant refused) is abandoned, the menu set back, and the session stays where it was. Stop,
  Start or another move abandon a move under way. A session on this Mac whose agent goes away mid-session (quit,
  killed) offers "Run on the server instead".
- **Checked** (`scripts/beam-move-check.py`, Chrome, 13 checks, the test app on its Shader tab): server to Mac in
  ~0.5 s (warm standby, build cached) with the server's frames going on until 16-17 ms before the Mac's first and
  nothing covering the canvas, the server's session then gone and input reaching the Mac's; Mac to server the same
  way (10-17 ms); with another tab holding the agent's one session a move is refused and the session streams on on the
  server; with the agent killed mid-session the page offers the server, which runs it.
- **Not done**: reconnecting to the same Mac session after the Mac slept (the plan's 30 s): the agent ends a session
  when its WebSocket closes, so a drop ends it and the page offers the server; moving a session whose screen is still
  (no frames) waits for the new side's first frame as well, which a still screen sends at once.

## Phase 5 as built (2026-11-08)

- **The server** (`Session.handoff`): a server session's own socket sends `handoff` (only with `--allow-beam`; never
  the agent's). The app is told to `quit` and its JVM's exit awaited (the attested PID, as for ending an instance), so
  what it wrote is flushed; its data slot and `serve-N.device` folder (/sdcard and the Settings values, `SlotDevice`)
  are zipped (`SlotPack`: `slot/...`, `device/...`, a `package` entry; regular files and folders, empty ones too, not the
  slot's `.lock`; at most 50,000 entries, files and folders alike, and 1 GB, the server's own count stopping at that
  limit; at most 100,000 entries looked at in all, links and pipes included, each folder's listing counted as it
  is read) into `STATE/serve/handoff/<id>.zip`, and the tab gets `h {"ticket","size"}`: a signed ticket (as
  phase 2's, 5 minutes) for `GET /api/beam/data?ticket=T`, which needs no token and serves that pack once its hash
  matches the ticket's. The session then ends without a word (its slot erased as always); packs older than the ticket
  go with a sweep. A session that has no data slot yet answers `h {"error"}` and runs on; a pack that fails answers
  the error and ends.
- **What the server packs**: the slot path comes from the instance's output, which app code (attachBaseContext)
  can write before apksim does, so it is taken only when it is one of this server's slots and the session's own JVM
  holds its `.lock` open (lsof on the Mac, `/proc/<pid>/fd` on Linux, checked before the app quits; the JVM is the
  process listening on its control port, or on Linux, which has no standbys, the process serve started). Else the
  app could name any folder (the server user's, another visitor's slot) for the tab to download. The pack is read
  through open folder handles from the state folder down, each opened without following a link
  (SecureDirectoryStream): a process the app left running may still write there, and a file or folder swapped for a
  link between a check and an open would otherwise be packed through it. Links and special files are left out, counted, and said: the tab gets `h {"ticket", "size", "skipped"}` and its log
  "its data moves without N links or special files in it". A file swapped for a named pipe between the check and the open blocks that open (uninterruptibly) until something
  writes to it, so the pack runs on a thread of its own, watched: no progress for 15 s and it is interrupted (which
  ends a pipe read that never finishes) and its pending open is released by opening the same name for writing; still
  stuck 15 s later, the handoff fails and the session ends anyway. The release fails when the app keeps swapping the
  name (the open then waits on a pipe nothing can name: 32 of 32 times under a swapping loop), so the give-up is what
  bounds it; that thread stays blocked, holding only its folder handles (its zip is closed and deleted).
  Checked on the Mac; the Linux path (`/proc`, the started process) is not run by a check yet.
- **The agent** (`Serve.beamSeed`): `/beam/start` takes `data: {"ticket","url"}` beside phase 2's fields; the address
  must be the page's own (same origin and base path as the APK's, no redirects). It downloads the pack, unpacks it
  into a slot of its own, `cache/data/<package>/bm<12 hex>-0`, and starts the instance on it with `--data-slots` and
  without `--fresh`; the grant says `data: true`. Unpacking takes what a site sent, so only regular files under the two
  roots, no absolute, `.` or `..` paths, no links, 50,000 entries (every one counted, none skipped unread, and each folder made on the way to one too) and 1 GB at
  most, a name at most 4,096 characters and 64 folders deep, the `package` entry first and at most 256 bytes, no folder entry with contents, into folders made fresh for it; anything else refuses the start (502), as does an altered or expired ticket, and so
  does a pack naming another package than the build's (read from its manifest, `ApkCache.manifestPackage`: at most 1 MB, where real ones are 21-39 KB; only the first
  element decoded, by Axml, which checks every length, offset and count against its input and caps what it decodes): a site's
  files never land in another app's data folder, the owner's own apps' included. The
  slot belongs to the grant until a session takes it: a grant that expires (checked every minute) or is refused, a
  session that cannot start (no such app, all sessions in use) and, hourly, any `bm` slot no grant or session owns and
  no instance holds erase it. Then it is the session's: erased when it ends unless kept (`keep on`, then `apksim beam saved` lists it as any other).
  The beam sandbox allows `bm<12 hex>-N` slots as it does `beam-N` (spelled out: the profile language's regex has no
  `{n}`, and `{12}` silently matched nothing, so the instance could not take its slot's lock).
- **The page**: "With its data" (on by default, shown for a server session when beam is offered) makes a move to this
  Mac send `handoff` first, then start the Mac's session with the ticket; the server's last frame stays up meanwhile
  (still, since its app has quit: ~1.2 s to the Mac's first frame against ~0.5 s for a live move). Unticked, a move is
  phase 4's live one. If the server cannot hand off, the move goes on without the data (logged); if this Mac then
  fails to take the session (its sessions all in use, a refused grant, an instance that does not start), the page
  opens the server's session again with the hand-off's ticket (`restore=`): the server, which keeps the pack until the
  ticket expires (5 minutes), checks the ticket and that the data is this app's, unpacks it into a slot of the new
  session's own (`bm<12 hex>-0`, erased with it as any other), deletes the pack (used once), says so in an `r` line,
  and the app starts there with what it had saved. Past the ticket's life it starts without it, and says so.
  A move from the Mac to the server never takes data (the agent offers no handoff: what a site's app wrote on the
  visitor's Mac stays there).
- **Behind a sign-in** (issue 173, protocol 2): as for the APK in phase 2, the agent's own download of the pack fails
  behind a single sign-on proxy (a 401/403 or a redirect, which it does not follow). To a page in protocol 2 the start
  (or the APK upload after `need: upload`) then answers `{"need": "data", "dataUpload", "dataWhy"}` (with `via` when the
  build came in that start), and the tab fetches `api/beam/data?ticket=T` with its own credentials and posts it to
  `POST /beam/upload-data?upload=G`, which answers the session grant with `data: true`. The pack goes through every
  check a downloaded one does (at most 1 GB, the `package` entry, the build's own package, the unpack's limits); the
  grant is one origin's, used once, and holds the build for the agent's sweep until then. A page in protocol 1 would
  read that answer as a grant, so it still gets the 502. If the tab cannot fetch the pack either, the move fails as
  any other (the page restores it on the server).
- **Checked**: `beam-check.py` (data part: Write on the server's slot screen, `handoff` answers a ticket and the
  server's session ends, an altered ticket brings nothing, the agent's session starts with `setting=written` and
  `sdcard=written` at app start in a `bm` slot of its own, erased when the session ends; behind a stand-in sign-in
  (401), a protocol 1 start still refused, a protocol 2 one asking the tab for the APK and then the data, a data grant
  refused for another site and used once, a pack that is not one refused, and the app starting with the data the tab
  sent) and `beam-move-check.py`
  (Chrome: the same through the page's menu, nothing covering the canvas, then back to the server; and with the
  agent's one session taken by another tab, a move with data refused after the hand-off, the app back on the server
  with what it had saved). `beam-check.py` also restores a hand-off on the server directly and checks a pack is used
  once.
- **Not done**: what the app holds in memory (its screen, back stack, scroll) does not move, as planned. The plan's
  Trips rename is the same path (its database is a file of the slot) but is not checked separately.

## Phase 6 so far (2026-11-08)

- **Hidden tabs**: a tab in the background shows nothing (`requestAnimationFrame` stops, and with it the page's
  presenting), so the page sends `maxfps 1` when it is hidden (or opens its socket hidden) and `maxfps 120` when it is
  back; the app goes on otherwise (timers, sound, network). Any tab may send `maxfps 1-120` (it only slows its own
  session), so a remote serve saves the drawing and encoding too: the Shader tab's instance draws ~1 frame a second
  hidden against 120 in front (`beam-page-check.py`). Not answered (it is not input either).
- **`apksim beam status`**: whether the agent runs (its lock and port), its sessions (app, site, device, kept or not,
  pid, idle time, from `GET /beam/status`, which answers only a request without an `Origin`: never a page), the sites
  allowed and refused, the builds kept and their hold, saved sessions and the settings (`beam-check.py`).
- **Reconnecting after sleep, not done, on purpose**: the agent and the browser are on the same Mac and sleep
  together, and a loopback connection does not break across sleep, so the dropped socket the plan foresaw is a crash
  of one side, which the page already meets by offering the server. Not tested with a real sleep.
- **Found on the way**: the beam profile's stricter writes stopped the agent's standbys (binding the socket under a
  temporary name, then removing its `.pid` file as it took a launch, were both refused), so every beamed launch went
  cold (1.7 s to the first frame) and one failed; `beam-check.py` now checks a beamed session is a warm hand-off.
  The parity bench measured the first frame from a click that came after the page had already started the app (the
  0.17 s above was the end of a start, not a start); it now loads the page without `?app=` and times one Start.

## Versions (2026-11-08)

The page that runs is the server's build and the agent is whatever release the visitor installed, so they can be
months apart in either direction.

- **Each says the range it speaks**: `GET /api/beam` → `{"port", "protocol", "minProtocol"}` (the page's own: it is
  the server's build), `GET /beam/hello` → `{"apksim", "protocol", "minProtocol", "trusted"}` (the agent's).
  `protocol` stays the newest, as the first release's page read it. `Beam.PROTOCOL` / `MIN_PROTOCOL` set them.
- **The page uses the newest both speak** and names it: `protocol` in `/beam/start`'s body and in `/ws`'s query. The
  agent refuses one outside its range with 409 and which side to update; a request naming none is protocol 1 (the
  first release's page). A refused `/ws` leaves the grant usable.
- **No protocol in common**: "On this Mac" in the menu is disabled and says why, the session runs on the server, and
  the log gives both ranges. The Mac's apksim older than the page's oldest: "On this Mac: update apksim (brew upgrade
  apksim)"; the page older than the agent's oldest: "On this Mac: this site's apksim is older".
- **Policy**: a change the other side would misread raises `PROTOCOL` (and the page and agent then speak both until
  the old one is dropped); `MIN_PROTOCOL` is raised only once no release that far back still serves pages, keeping at
  least the last three releases' protocols. Additive fields need no new protocol: both sides ignore what they do not
  know.
- **Protocols so far**: 1, the first release's; 2 (issue 173), a start may answer `need: "data"` (see "Phase 5 as
  built"). 0.20.0 and later speak 1-2. Additive since, in both protocols: hello's `asking` and the start's
  `source` (issues 177, 178); the start's `saved` and `dataFrom` and `GET /beam/saved` (issue 181, 0.21.0 and later).
  3 (issue 276): the start's `"relay": true`, hello's `"relay": true` and the `y` text messages of the tab relay
  ("The tab relay"). A start in protocol 2 or 1 that asks for the relay is refused with 409.
- **Checked**: `beam-check.py` (the ranges in both answers; a start in 99 refused with `brew upgrade apksim`, in 0 as
  the site's being older; a `/ws` in 99 refused and the same grant then opening in 1) and `beam-page-check.py`
  (Chrome, agents started with `APKSIM_BEAM_PROTOCOLS=2-3` and `0-0`: the menu's option disabled with each reason, the
  app on the server).

## Beam client protocol

What a page that starts sessions on the visitor's Mac does, for a page that draws the stream itself (an embedding
dashboard with its own canvas: serve.html refuses framing). serve.html is one implementation (`beamSetup`,
`macGrant`, `moveSession`); this is the supported surface, and a change to it raises the protocol ("Versions").
`SERVER` is serve's page address (its folder, under any `--base-path` or proxy prefix), `AGENT` is
`http://127.0.0.1:PORT`. Every request to the agent carries the page's `Origin` (a browser sets it); its answers are
readable by any origin. Requests to the server carry its token as everything else (cookie, `?token=` or
`Authorization: Bearer`), except the two ticket downloads.

1. **Is beam offered?** `GET SERVER/api/beam` → `{"port", "protocol", "minProtocol"}` (404: not offered). Chrome needs
   the `loopback-network` permission for `AGENT`: ask `/beam/hello` only once it is granted or the visitor chose the
   Mac, as the browser prompts on that request.
2. **Is there an agent, and which protocol?** `GET AGENT/beam/hello` → `{"apksim", "protocol", "minProtocol",
   "trusted", "asking"}`. Use `P = min(page's protocol, agent's protocol)` if it is at least both minimums; else stay
   on the server and say which side to update. `trusted`: this origin will not be prompted. `asking`: the consent
   dialog for this origin is up now (0.20.0 and later; absent before): poll it while a start is pending (step 4)
   to tell the visitor the Mac waits for them ("The consent wait").
3. **The app's build.** `GET SERVER/api/apps/ID/beam` → `{"id", "name", "size", "sha256", "options", "ticket",
   "ticketSeconds"}` (409 while an added app still downloads).
4. **A grant.** `POST AGENT/beam/start` with JSON `{"protocol": P, "relay"?: true (3: see "The tab relay"), "sha256", "name", "size", "options", "ticket",
   "url": SERVER + "api/beam/apk"}`, plus `"data": {"ticket", "url": SERVER + "api/beam/data"}` for a move with data
   (step 7), and optionally `"source"`: where else the build can be downloaded without credentials (an artifact
   store), which the agent uses only if its owner allowed that address ("Builds from an artifact store"; ignored by
   releases before 0.20.0). Or, for the site's saved data (step 8; 0.21.0 and later): `"saved": ID` (its own build: `sha256` may be left
   out) or `"dataFrom": ID` beside a newer build's fields. The agent may first prompt its owner (up to 2.5 minutes: keep
   the request open; `asking` in step 2 says it is). Answers:
   - `{"app", "token", "via"}` (`via`: `cache`, `source` or `download`; `"data": true` when it took the data, with
     `"saved"` or `"dataFrom"` naming the saved session it used): go to step 5.
   - `{"need": "upload", "upload": G, "why"?}`: the agent could not get the build itself. Fetch
     `SERVER/api/apps/ID/apk` (with the token) and `POST AGENT/beam/upload?upload=G` with it as the body. The answer is
     again one of these (a grant, or `need: data`).
   - `{"need": "data", "dataUpload": D, "dataWhy", "via"?}` (protocol 2): the agent could not get the data itself.
     Fetch `SERVER/api/beam/data?ticket=T` (the data ticket from step 7, with the page's own credentials) and `POST
     AGENT/beam/upload-data?upload=D` with it as the body; the answer is the grant, with `"data": true`.
   - Errors are plain text: 403 (this origin refused by the Mac's owner, or a grant not this origin's or used), 409
     (protocol: which side to update; or saved data that cannot be used: open elsewhere, another package or key), 404
     (no such saved session from this site), 413 (too large), 400 (a bad body or an upload that does not match), 502
     (in protocol 1, the data could not be taken). Each grant lasts 2 minutes and is used once.
5. **The session.** `ws://127.0.0.1:PORT/ws?app=APP&token=TOKEN&protocol=P` plus the same session parameters as
   serve's own `/ws` (device, appearance, locale, fontscale, open, codec...): the stream and input protocol are serve's
   (docs/serve.md, "Stream" and "Input"), byte for byte. An upgrade refused with 409 (protocol) leaves the grant
   usable; one refused with 403 (no such grant) does not.
6. **Moving a running session** (phase 4): open the other side's session beside the running one, keep drawing and
   sending input to the old one, and switch on the new side's first frame (a keyframe), replaying its text messages
   (`i`, `c`, ...); an `x` or a close before it abandons the move.
7. **With its data** (phase 5, server to Mac only): send `handoff` on the server session's WebSocket. It answers
   `h {"ticket", "size", "skipped"?}` (the app quit and its data was packed; the server's session then ends) or
   `h {"error"}` (with no data slot yet the session runs on; any other error ends it). Then step 4 with `data`. If
   the Mac does not take it, open the server's session again with `&restore=TICKET`: an `r` line says whether the data
   came back (the pack is kept until the ticket expires, 5 minutes, and used once).
8. **Saved data** (issue 181): `GET AGENT/beam/saved` → `[{"id", "app", "package", "sha256", "build", "open", "saved",
   "lastUsed"}]`, this origin's saved sessions (404 from an agent before this). Step 4 with `"saved": ID` reopens one
   (what the app writes is kept in it), with `"dataFrom": ID` starts a newer build of the same app, signed by the same
   key, on a copy of it ("Saved data from the tab").

## Skins (2026-11-12, issue 246)

The agent lists the visitor's own skins (docs/skins.md: `~/.apksim/skins`, `APKSIM_SKINS`) as serve does (docs/serve.md
"Skins"): `GET /beam/skins` and `GET /beam/skins/NAME/frame.png` (`frame-land.png`, `mask.png`), with an ETag. Only a
site the agent trusts gets them (`403` otherwise): they may be private frames, and they never leave the Mac. The page
lists them in its Skin menu as "(this Mac)" after the server's, and draws them around a session's canvas the same way.
A start on this Mac takes `skin=NAME` (the agent's skins) and the launch option `--skin` as serve does, for the session's
device. A server's app whose default is a server skin reaches the agent with that skin's `--device` instead (the Mac may
not have the skin; the page draws the server's frame), and the agent drops a `--skin` it does not have rather than
refusing the start, so an error never lists the visitor's skins to a page. The page draws the session's own endpoint's
skin when both have one of the same name; the beamed instance is not given `--skin`, as its sandbox reads nothing in the home folder, so the app there is
not told the skin's cutout and corners (the frame is drawn all the same). It gets the skin's kind as `--device-kind KIND`
(a launch option that needs no skin store), so a custom-size watch skin's session is a watch. `apksim beam run ID --skin NAME` opens a kept
session with a skin around its window, on the skin's device unless `--device` is given too; a recorded `--skin` this Mac
no longer has is dropped.

## The tab relay (2026-11-11, issue 276)

A page behind a sign-in proxy (single sign-on) can serve the app on the visitor's Mac what only its signed-in tab can
fetch: a dev server's API, a document the app polls, events it posts back. The agent cannot pass the proxy, and the
page's options were to open an unauthenticated path for the app or not to offer beam for that feature. (Issues 173
and 177 solved the same for the build and the data pack.) With the relay, the app's plain-HTTP requests to a reserved
host are carried to the tab, which answers them with its own credentials.

- **Opt in**: `POST /beam/start` with `"protocol": 3` and `"relay": true` (a start in protocol 1 or 2 that asks is
  refused, 409). `GET /beam/hello` says `"relay": true` on an agent that has it. The agent adds the launch flag
  `--tab-relay` itself (a page cannot name it in `options`, and a saved session's options do not carry it over: the
  start asks each time). Without it the host resolves to nothing, as any `.invalid` name.
- **The host**: `http://page.apksim.invalid` (`.invalid` never resolves anywhere else). Plain HTTP only: `https://` to
  it is refused. An app whose network security config denies cleartext still reaches it, since apksim applies no such
  config; keep the host exempt if it ever does.
- **Messages** (text; the tab's binary messages are dropped), both carrying the same id:
  - agent to tab: `y ID {"method","path","headers":{...},"body":"<base64>"}`, `path` keeping the query, `headers` the
    app's (repeats joined with ", "; without Host, Content-Length, Transfer-Encoding, Expect and the other hop-by-hop
    ones).
  - tab to agent: `y ID {"status","headers":{...},"body":"<base64>"}`. A header value may be an array. The agent sets
    Content-Length and `Connection: close` itself and drops the answer's Content-Encoding (a browser's `fetch` has
    decoded the body already).
- **Limits**: GET, HEAD, POST, PUT, PATCH and DELETE (405 otherwise); bodies up to 512 KB each way (413 on the app's
  side; a longer answer is a 502 at once, checked on the decoded body), a request's line and headers up to 64 KB
  (431), so a `y` message stays under the WebSocket's 1 MiB and none is dropped on the way (a tab message over 1 MiB
  ends the session, as any message that long does); an answer that is not JSON is a 502 for that request; 15 s for the tab to answer
  (504); 16 requests in flight (503); anything pending when the tab goes is a 502; one request per connection. The
  app's own read timeout counts too: OkHttp's default 10 s ends a 12 s answer with a SocketTimeoutException.
- **The page decides what to answer.** The agent filters no path or header: a page should relay only to its own origin
  and only the paths it means to expose, and take care what an app's `Authorization` header or cookies carry.
- **Where it hooks**: the beam sandbox denies loopback, and a warm standby fixes its sandbox before any session
  exists, so a per-session port was no option; the hop stays inside the JVM. `NetResolver` gives the host a placeholder
  address; app calls to `SocketFactory.createSocket()` (a plain factory: OkHttp's) are translated to `Net.createSocket`,
  which with the relay on returns a `RelaySocket`: the factory's own socket until it connects, then, for the host, a
  pair of in-memory pipes (`TabRelay` parses one HTTP/1.1 request on them: declared or chunked body, `Expect:
  100-continue`) and for any other address that real socket, connected through `Net` as before (TLS is layered on the
  real one). `URL.openConnection` gets a `RelayConnection` (HttpURLConnection; redirects are not followed). The
  requests go over a control connection of the instance, `relaywatch` (lines `y ID {json}` both ways), which the
  agent's Session passes to the tab as text messages. A `new Socket()` the app connects itself is not carried; Ktor CIO
  and other NIO clients are not either. Relayed requests do not pass the network engine, so the flight recorder's net
  channel and `--network record` do not hold them.
- **Checked**: `scripts/check-tab-relay.sh` (CI: a stand-in tab on the link; both clients' paths, bodies, the limits, a
  read timeout), `beam-check.py` (hello, the protocol gate, a session without it and with it, the test app's Relay
  buttons through OkHttp and HttpURLConnection answered over the WebSocket), and by hand the test app's 12 s answer
  against OkHttp's read timeout.

## Builds from an artifact store (2026-11-08, issue 177)

Behind a sign-in proxy the agent's download from the page's server always fails, so the tab relays every build it
lacks: the whole APK through the proxy into the tab, buffered as a Blob (Chrome streams request bodies only over
HTTP/2, and the agent is HTTP/1.1 on loopback), then to the agent. Many deployments keep builds where the visitor's
Mac reaches them directly and anonymously (CI artifacts), and the page knows that address and the hash.

- **The page names it**: `/beam/start` takes `"source": URL` beside `sha256`. serve.html sends none (its builds are
  its own); an embedding page with an artifact store does.
- **The owner allows it**: `apksim beam config allow-download https://artifacts.example.com/builds/` (and
  `disallow-download`), kept in `STATE/beam/downloads.json` and read at every start, so it takes effect without a
  restart; `apksim beam config` and `apksim beam status` list them. A prefix is https (http only on 127.0.0.1,
  localhost or [::1]), a host, no user, query or fragment; a source matches on that site exactly (no subdomains) and
  at the prefix's path or below it as whole segments (`/builds` covers `/builds/x`, never `/builds-other`). A source
  with a user, a fragment, a `.` or `..` segment, or `%2E`, `%2F`, `%5C` or a backslash in its path is never allowed
  (a server could read it as another folder). The query is the source's own (signed addresses).
- **The download** (`Serve.beamFetchSource`): tried first, before the page's server; no credentials, no cookies; a
  redirect (up to 5) is followed only to an address that is allowed too; an APK's content type or
  `application/octet-stream` (what artifact stores send: the hash, checked before anything is stored, says what the
  file is); within `--max-upload`. Its answer says `"via": "source"`. When it is not allowed or fails, the page's
  server is tried with the ticket, then the tab relays the APK (`need: upload`, its `why` naming the source's host
  and the reason, never its path or query).
- **What it lets a page do**: make the agent fetch an address on the visitor's network, which the owner's prefixes
  bound; what it fetches is kept only if its hash is the one the page named. The consent prompt does not offer to
  allow a download host (its dialog has room for three answers only); a team's setup instructions can add the
  `config` line beside `apksim beam install --trust` ("The consent wait").
- **Checked** (`beam-check.py`'s source section, with a stand-in artifact store): no plain-http or user-bearing
  prefix; an address not allowed is not fetched and the tab is asked; an allowed one downloads with a store's
  octet-stream type and is found by its hash the next time; a redirect within the prefix is followed (a query-only one
  keeping the path: `URI.resolve` drops its last segment), one out of it is not; `/builds-other`, `..`, `%2e%2e` and `%2F` are not under `/builds`; a sign-in page is not a build; a hash
  that does not match is not kept; `disallow-download` takes effect at once.

## The consent wait (2026-11-08, issue 178)

A new site's first `/beam/start` holds its request open while the agent's dialog waits for the Mac's owner (up to 2
minutes), right after Chrome's own prompt for apps on this device, and a dialog behind other windows or on another
Space is easy to miss: the page looked stuck, then the start was refused.

- **`/beam/hello` says `"asking": true`** to the origin whose dialog is up now, and only to it (another page learns
  nothing about which sites ask). serve.html polls it once a second while a start is pending (every start: the
  owner may revoke a site while its tab is open), and says "apksim is asking on this Mac: choose Allow in its dialog (it may be behind other windows)" in the
  start's overlay (or the move's chip), and in its log.
- **A notification** (`display notification`, with a sound) comes with the dialog, so it is noticed when the dialog
  is not; it is not waited for, and nothing happens if it fails.
- **`apksim beam install --trust ORIGIN`** (repeatable) allows a site as `apksim beam trust allow` does, so a team's
  setup instructions can pre-allow their own dashboard and skip the dialog entirely; the Homebrew cask's caveats
  mention it.
- **Checked**: `beam-check.py` (hello's `asking` true for the asking origin and false for another while a stand-in
  dialog waits, `APKSIM_BEAM_CONSENT=allow-after-2s`, for checks only; false after; `install --trust` with
  `--dry-run` listed by `trust list`, a non-origin refused) and `beam-page-check.py` (Chrome: a start for a site not
  yet allowed says so in the page while the stand-in dialog waits, then runs on the Mac).

## Saved data from the tab (2026-11-08, issue 181)

Some apps are useful only after a one-time setup (a sign-in, a device enrolled with a code approved elsewhere), and a
page that runs a new build per pull request made the visitor repeat it for every build. A saved session (keep on, or
`save-sessions on`) can now be used from the page that saved it.

- **`GET /beam/saved`** → `[{"id", "app", "package", "sha256", "build", "open", "saved", "lastUsed"}]`: the saved
  sessions that came from the calling origin (none for a site the owner refused), newest use first. `build`: its own
  build is still on this Mac; `open`: a tab has it open now.
- **Reopening one**: `POST /beam/start {"protocol", "saved": ID}`, from its own site only (any other gets the 404 an
  unknown id gets). `sha256` may be left out (its own build is used); one naming another build is refused (400: that is
  `dataFrom`). Its own build may be gone: with `sha256`, `ticket` and `url` as in any start it comes back the usual way.
  The session runs with the launch options, device (a Fill tab's `WxH@D` size too, within the bounds a beam tab's
  `device` command has), appearance, locale, font scale and permissions it was saved with, unless the page names them;
  either way they win over the app's own launch options, as with `apksim beam run`. The answer is the usual grant with `"data": true, "saved": ID`.
  - It runs on a copy, not in the saved slot: the slot (`k<id>-0` and its `.device`) is packed as a hand-off is
    (`SlotPack`, through folder handles, links left out) and unpacked into the session's own `bm<12 hex>-0`, so the beam
    sandbox still lets an instance touch only `beam-N` and `bm` slots. Every beamed instance shares one sandbox, so
    allowing `k` slots there would have let any site's app read every other site's saved sign-ins.
  - When the session ends (however: the tab closed, the idle limit, a crash), the copy takes the saved slot's place
    (`BeamStore.writeBack`), as a device keeps what the app wrote. It holds `saved/<id>.lock` exclusively (the session
    gives up its shared hold first), so no `run` is open and none can start and take `k<id>-0` while that name is free
    between the renames; then both slots' locks; the copy must still be there (its app may have deleted it), the old
    slot is moved aside (to `beam/writeback/`, which no sweep empties) and deleted only once the copy is in its place,
    and every rename is undone if one fails (what cannot be is left there and named in the log). A `run` stamps the
    record's `changed` as it opens the saved session and again as it ends; a copy made before a stamp it did not see is
    not put back over what that run may have written. If it cannot be put back (a `run` has it open or opened it
    meanwhile, it was forgotten, the copy is gone), the copy (if there) is kept as a new saved session instead, so
    nothing is lost.
  - One tab at a time (409 for a second while a grant or session holds the first), and no `dataFrom` copy of it
    meanwhile (409, at an upload too); the session holds `saved/<id>.lock` shared, as `run` does, so `forget` and the
    `keep-saved-days` sweep leave it. Inside the agent every hold on that lock goes through one channel per saved
    session (`BeamStore.hold`: shared holds counted, an exclusive one alone), since closing any channel to a file
    releases every lock the process has on it; copies of one saved session are read one at a time, and each re-reads
    the record once held (it may have been forgotten just before). It runs only once it has that lock (waited for up to 10 s: a copy for a newer
    build holds it while it reads); without it the session ends at once with why and its copy is erased, rather than
    run as a copy nothing would put back. The page cannot turn
    keeping off for it (`keep off` is answered `k on`): only the Mac's owner erases a saved session.
- **A newer build on its data**: `POST /beam/start` with the new build's `sha256`, `ticket`, `url` (or `source`) as
  any start, plus `"dataFrom": ID`. The saved slot is copied as above into the new session's own slot only if the new
  build has the same package (its manifest) and the same signers as the saved build, Android's own condition for an
  update that keeps an app's data; else 409 with why. `ApkSigners` takes the newest scheme the APK has and verifies it
  as a device's package manager does before naming a signer (a certificate is public: copied into another APK, it says
  nothing): v3 (signers covering API 36) or v2, each signer's signature over its signed data with its own key, which
  must be its certificate's, and the content digest recomputed over the APK's entries, central directory and end record
  (1 MB chunks); else v1 through the JDK's verifying JarFile (every entry outside META-INF signed, by the same signers,
  each signer's own certificate from its certificate path, not a CA's). The answer is each signer certificate's SHA-256. A
  saved record notes its build's signers (`signers`), so this works after the saved build itself is gone; a record
  saved before this release needs its build still here. The copy is an ordinary moved session's slot: erased when the
  session ends unless kept, which saves it as a new session; the saved one stays as it was, a template for the next
  build. The answer says `"data": true, "dataFrom": ID`.
- **Errors**: 404 (no such saved session from this site), 400 (`saved`, `dataFrom` and `data` together, or a reopen
  naming another build), 409 (open in another tab or in `run`, another package, another key, a build whose signature
  does not verify, or a copy that failed). `data`'s ticket must be as the server mints them (`[A-Za-z0-9._-]`), so a
  site's ticket is never read as the agent's own marker for saved data.
  Both work after `need: upload` too (the tab sends the build, then the agent copies the data). Additive: an agent
  before this ignores `saved` and `dataFrom` and starts the app with no data, so a page checks the answer's `saved` /
  `dataFrom` (and `/beam/saved`'s 404 tells it the agent is older).
- **Not done**: key rotation (v3's proof-of-rotation lineage) is not followed, so a build signed with a rotated key is
  refused; a versionCode downgrade is not refused (Android refuses one unless the app is debuggable); serve keeping a
  hand-off's pack past its 5-minute ticket (the issue's optional part 4: the operator would have to opt in, since a
  pack can hold sign-ins); serve.html offers none of this yet (an embedding page uses the protocol below).
- **Checked**: `beam-check.py` (the saved session listed to its site and not another; reopened by its site with no
  build named, not by another, not twice; running on a `bm` copy with the saved data at app start; `keep off` answered
  `k on`; what it wrote back in the saved slot after it ends, the copy gone, still one saved session; `apksim beam run`
  opening it while a tab has it open, after which the tab's copy is kept as a new saved session; and, with the
  Android SDK's apksigner and keytool, the test APK signed with a key of the check's own saved after a Write, a newer
  build of it (one asset more, same key) sent by the tab and starting on a copy with the saved data, its copy erased
  and the saved sessions unchanged, and refused: that build with a ZIP comment added after signing (its certificate
  kept), signed by another key, and on the debug-signed saved session). `ApkSigners` also checked by hand against
  apksigner on v1-, v2- and v3-signed copies, and with a byte flipped in each (refused).

## A Linux agent: scope (2026-11-08, not started)

What it would take for `apksim beam` on a Linux desktop (the page offers beam only on macOS today, by its user agent):

- **A Linux host install first.** apksim on Linux exists only as the Docker image; nothing builds or runs it on a
  Linux host (AGENTS.md "Linux / Docker"). The agent needs a tarball (or package) with the launcher, the native
  libraries and a JDK, as the Mac release has.
- **A sandbox of its own.** The launcher refuses a beamed instance without one. The Mac profile's rules map to
  bubblewrap (unprivileged user namespaces): a mount namespace holding only apksim, its libraries, the JDK, the
  build's caches read-only and the slot and scratch read-write (no home folder, no other slots, no `beam/uploads`);
  `--unshare-pid` (signals only inside); `--new-session` (no terminal, so no TIOCSTI injection); `--die-with-parent`.
  The network is the hard part: in the host's namespace loopback is reachable (other instances' control ports, the
  agent), and `--unshare-net` removes everything; the internet without loopback needs a user-mode stack (pasta, with
  the host's loopback not mapped). Landlock (kernel 5.13+) could replace the mount rules without namespaces, but its
  network rules (6.7+) are per port, not per address, so they cannot keep loopback out.
- **No standbys** on Linux: every beamed launch is cold (1.5-3 s to the first frame against 0.35 s on the Mac) until
  the standby is ported.
- **What the Mac has and Linux would not**: Show in Simulator (no window there), VideoToolbox (OpenH264 encodes),
  Metal shaders (CPU), the launchd agent (a systemd user socket unit would be its equivalent).
- **Checks**: the sandbox probes in `beam-check.py` are shell commands, so they can run under bubblewrap in the
  Linux image (Colima here, CI there); a browser on a Linux desktop end to end has no machine here yet.
- Estimate: the install and the sandbox with its probes are a few days; the network rules and a desktop check decide
  the rest.

## The launchd agent (2026-11-08)

- **`apksim beam install [--port N] [--agent-idle S] [--trust ORIGIN]...`** (`BeamLaunchd`) writes two LaunchAgents to
  ~/Library/LaunchAgents and loads them (`launchctl bootstrap gui/UID`); `apksim beam uninstall` boots them out and
  deletes them; `apksim beam status` says whether it is installed. Opt-in, never done by the installer: an agent any
  page can reach (behind its consent prompt) is the visitor's choice. The Homebrew cask says how to install it.
  - **Removed with apksim, kept across upgrades**: each job runs the launcher through a guard (`BeamLaunchd.guarded`):
    once the launcher is gone (looked at twice, a minute apart, so an upgrade relinking `bin/apksim` meanwhile does not
    count) it deletes both jobs' files and boots both out, at the latest at the next daily sweep. The cask's
    `uninstall` cannot do it: `brew upgrade` runs a cask's uninstall `launchctl` and `delete` too (Homebrew skips only
    `signal` on upgrade), which took the visitor's opt-in away at every upgrade. `brew uninstall --zap` removes them at
    once.
  - `dev.apksim.beam`: launchd listens on 127.0.0.1:PORT (47474) and starts `apksim beam --launchd` on a page's first
    connection, handing it the listening socket on stdin (`inetdCompatibility` with `Wait`), which the agent takes as
    `System.inheritedChannel()`: no native code. It exits after `--agent-idle` seconds (600) with no session, no
    request in flight and no page's request (`apksim beam status` asking does not count), and launchd starts it again
    for the next page: nothing runs, no memory is used, until a page asks. Logs to `STATE/beam/logs/agent.log`.
  - `dev.apksim.beam.sweep`: `apksim beam sweep` once a day, so builds past their hold go while the agent never runs.
  - The job names the launcher as it was run (Homebrew's `bin/apksim`, which an upgrade keeps; the install folder it
    leads to changes with each version), and `APKSIM_HOME` when set.
- **Checked** (`scripts/beam-launchd-check.py`, 13 checks, the last that no agent is left once it ends): both property lists valid (`plutil`), the agent's
  socket-activated on its port with the socket handed over inetd's way; with the script standing in for launchd
  (holding the socket and starting `--launchd` when a connection waits): nothing runs until a page asks, the first
  request starts the agent in 0.1 s and is answered, a session streams, with no page for its idle time it exits, the
  next request starts it again. `--real` installs the jobs with launchd itself (a test label, removed after) and runs
  the same (the test site allowed first: launchd's agent has none of the script's environment, so it would show its
  consent dialog): 13 checks passed in three runs in a row, the agent up in 0.1 s on a page's first request through
  launchd. An idle agent once seemed never to exit, and `bootout` then found it still up: `apksim beam status`
  connects while the agent's lock is held, and a poll arriving as the agent ended queued on launchd's socket, which
  started a new agent at once (the check polls every second, so it kept one up for good). Now an idle agent first
  writes "exiting" into its lock file (status says so and does not connect), answers what is already on its way for
  one more 5 s round, then ends; a page's request in that round calls the exit off. The idle exit takes 11-16 s for
  an 8 s idle time, and the check waits until status says "not running", so the next request is a fresh start.

## 4 KB-aligned libraries (2026-11-08, issue 171)

An app in a Linux serve's catalog runs there with a library linked for 4 KB pages (Linux maps 4 KB pages), but macOS
maps 16 KB pages: a library whose writable data outside RELRO shares a page with its code is refused by the native
loader unless the launch has `--move-shared-data` (AGENTS.md, "Hard-won rules"). A catalog written for a Linux serve has
no reason to say so, and the beamed app exited at its first `loadLibrary` (a `NoClassDefFoundError` for that class on
the next try); the page fell back to the server each time, and the operator never knew.

So the agent reads the program headers of the build's `lib/arm64-v8a/*.so` before each launch (`SharedDataLibs`, the
test of `code_page_conflict` in `native/elfloader.c`, remembered per build) and adds `--move-shared-data` itself when
one has such data, with a line in the agent's log and the session's naming the libraries. The agent's cache warm-up
(`--warm`) and `apksim beam run` of a kept session do the same (its record keeps the app's own options, not what the agent added). A library whose headers
cannot be read is taken as fine: the loader says what is wrong with it if the app loads it. The flag changes only how
those libraries are mapped (any other loads as before), and the instance is in the beam sandbox. A library whose
data cannot be moved (code after it) is still refused, with the loader's own message. What the move cannot promise
(references it cannot find, AGENTS.md) holds here too; without it the app does not start on the Mac at all. An app
that catches the refusal and runs without the library now gets it loaded, moved. `beam-check.py` checks the line
appears for the test app (whose `libpagefixturenorelro.so` is such a library), that the session then streams, and
that `run` of its kept session adds the flag too.

## Moving a session to a device (2026-11-09, issue 191)

`apksim beam to-device ID [-s SERIAL] [--data] [--settings KEYS|all] [--install replace|skip] [--yes] [--dry-run]`
(`serve/DeviceHandoff.java`) carries a saved session on to a real device over `adb`, so the app there carries on from the
session's state rather than the device's own:

1. **Build.** The device's base APK is pulled and compared with the session's by hash. If they differ, the signers
   (`ApkSigners`) must match, or it stops: replacing a build signed by another key means uninstalling it, which erases the
   device's data. `--install replace` runs `install -r -d`; the default `skip` leaves the device's build alone. A package
   that is not installed needs `--install replace`.
2. **Data** (`--data`). `run-as PKG` (a debuggable build) writes: the app is stopped, the device's data folder is saved to
   `STATE/beam/device-backup/<serial>-<time>-<random>.tar` (a tar, not a zip: it comes straight from the device's `tar` and goes back
   the same way, and the command prints how), cleared (the system's `lib` link stays), and the slot written over it (its
   `.lock` left out). Files made through `run-as` belong to the app's uid and take the folder's label; `restorecon` runs
   where it is allowed. A build that is not debuggable stops before anything is cleared; `adb root` on a userdebug device
   is not supported yet.
3. **Settings** (`--settings`). `ns/key,...` or `all`, from the slot's `settings.properties` and the session's `--setting`
   options. Identity keys (`secure/android_id`, `global/device_id`, the device name, Bluetooth address, adb and developer
   switches) are never put, even when named, and the device controls (font scale, animation scale, Bluetooth) are left to
   their own handling. Each old value is read first and, with the data restore and the old appearance, font scale and locale, saved in `<serial>-<time>-<random>-undo.sh` before anything is changed; `sh` it to put everything back (the app is stopped and its data cleared first).
4. **Launch options.** Appearance (`cmd uimode night`), font scale and locale (`cmd locale set-app-locales`, Android 13) are
   applied, the app is force-stopped and its `--activity` (else its launcher activity) started. Said as not carried over:
   the device size, in-memory state, the back stack, network state, `/sdcard` files.

It asks before overwriting (`--yes` skips the question; with no terminal it refuses without it), `--dry-run` only prints the
plan, and it holds the saved session and its slot lock while it reads it (so `beam run` is not writing it and `forget` cannot take it), packing the slot to a temporary file before the device is touched. The device is the only attached one or named with
`-s`. `scripts/beam-device-check.py` runs it against a stand-in `adb` (backup before clear, quoting, identity keys,
refusals); a real device is not part of it.

Not done: the same for a plain instance (a `handoff adb SERIAL` control command) and a "Move to device" item in the page's
menu; both would call this code.

## Measurements to keep (as serve's table)

Click to first local frame (warm standby, cold standby, first launch of an APK); cutover gap in frames; tap to frame
and present to screen locally; stream fps on the Shader tab and while dragging; CPU and memory of the agent idle
and per session; bytes moved on a first and a second visit; resize to frame at the new size, from the tab and from
the simulator window.

Measured 2026-11-08 (test app, Apple Silicon Mac, 120 Hz display, Chrome 156; `beam-parity.py`, `beam-move-check.py`,
`beam-size-check.py`, the session logs):

| | Value |
|---|---|
| Start on this Mac to first frame, warm standby, build cached | 0.35-0.39 s from the click (a warm launch of the window: 0.27-0.28 s) |
| The same, cold (no standby ready) | 1.6-1.7 s |
| First visit of a 17 MB build: downloaded by the agent itself | included in the start; the tab sends nothing |
| Moving a session server → Mac / Mac → server | ~0.5 s; 10-17 ms between the old side's last frame and the new side's first, nothing blank |
| Moving a session to this Mac with its data | ~1.2 s from the choice to the Mac's first frame, the server's last frame still meanwhile |
| Tap to on screen in the tab | 31-41 ms |
| Present to on screen in the tab | 11-16 ms (the window: up to one refresh) |
| New frame per display refresh, tab / window | Shader tab 99-100 / 99-100%, flings 88-91 / 88-90%, slow drags 99-100 / 99-100% |
| Instance CPU, tab / window | Shader tab 0.54-0.60 / 0.51-0.54 of a core; agent 0.02; Chrome 0.27-0.32 |
| A hidden tab's instance | 1 fps (119.5 in front) |
| Stream at 20 Mbps | Shader tab 16 KB a frame, encode 5.4-6.2 ms |
| Fill: a browser resize to frames at the new size | within the 100 ms send interval plus one frame |
| A simulator window drag to the tab | within a moment (the next frame), no size sent back |

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
