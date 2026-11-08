# Beam: a browser session's app run on the visitor's own Mac (plan, 2026-11-07)

`apksim serve` runs the app on the server and streams its screen to the tab (docs/serve.md). Beam keeps the tab
and the page exactly as they are, but when the visitor has apksim installed, the APK goes to their Mac and runs
there: their CPU and GPU (Metal shaders, VideoToolbox, 120 Hz), their speakers, no server session used. The tab
still shows the app in the same place, at the same size, embedded or not, and follows the browser window and the
simulator window when either is resized.

This page is the plan: what "seamless" has to mean, the design, then phases with what finishes each.

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
   LaunchAgent with socket activation in the cask (the agent runs in the foreground for now), the live prompt seen
   by a person, and instances with a hidden window instead of `--headless` (with "Show in Simulator", phase 3).
2. **APK transport (~3 days).** Tickets, `--allow-beam`, `GET /api/beam/apk`, the hash cache, `need-upload` and the
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
  data slots `beam-N`, erased when their session ends, `--headless --no-autofill` as serve's. Builds are kept 14 days
  after their last use (serve's uploads: a day).
  - `GET /beam/hello` → `{"apksim", "protocol": 1, "trusted"}`, readable by any origin.
  - `POST /beam/start {"sha256", "name", "size", "options"}` → `{"app", "token"}` when the agent has that build, else
    `{"need": "upload", "upload"}`. Options go through serve's launch option list. An origin not yet allowed gets the
    prompt first (`osascript`'s dialog: Don't Allow / Allow Once / Always Allow, gives up after 2 minutes); "never"
    and a "Don't Allow" from the last 10 minutes refuse without asking (403).
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
- **Not tried**: the prompt with a person answering it (the checks answer it through `APKSIM_BEAM_CONSENT`), a
  cross-site embedded page, a server behind a token (the page's requests carry it as for everything else).

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
