# apksim serve, apksim beam and the MCP server

Agent reference notes, moved out of AGENTS.md so they are read only when needed. A section another doc or a
code comment cites as `AGENTS.md "Name"` is in one of these files: AGENTS.md's index says which.

## apksim serve (APKs in a browser tab)
`./apksim serve [--port 8080] [--bind ADDR] [--token FILE] [--max 4] [--idle 600] [--apps DIR|FILE]... [--max-upload MB] [--allow-share]`:
an HTTP server whose page (one self-contained file) runs a listed or uploaded APK per tab and streams it; docs/serve.md
is the spec, the security model and the measurements. Each tab is an ordinary launch (`--headless --fresh --control
auto`, warm when the standby is ready: on the Mac by default, on Linux with `--warm-standby 1`, issue 561), read from its stderr (`[apksim] control port N`;
the rest goes to `STATE/serve/logs/<session>.log`). Serve opens two control connections: commands, pipelined, and
`stream`, after which `FrameStream` sends each presented frame that differs from the last as a JPEG (Android's encoder,
on its own thread; `redraw` makes the Host draw one for a new subscriber), or as H.264 when the tab asked
(`codec=h264`, sent by the page when WebCodecs decodes it) and the instance has an encoder (VideoToolbox; OpenH264
in the image), answered `c h264`/`c jpeg`: no reordering, keyframes only at the start, a size change or a `key`
request, and a still screen encoded twice more to sharpen it; `--bitrate` (4000 kbps), `--codec jpeg` turns it off.
Serve relays frames with at most two in flight per tab (the page acknowledges each one decoded and shows it at its
next display refresh, from a queue of at most two, pacing the instance to that display with `vsync`; with H.264 a tab that
falls behind drops to the next keyframe, which serve asks for; when that repeats, the tab decoding slower than the app
draws, serve sends the instance `pace MS` and `FrameStream` encodes at most one new frame per MS, the newest, so the
stream stays a delta chain at the tab's rate instead of freezing on keyframes: `serve.Pacer`, issue 232, checked on a
simulated clock by `scripts/check-pacer.sh` (CI) and end to end by `serve-check.py`'s slow tab (Mac only: the Linux
image's CPU shader is too slow to outrun a tab; a tab acking nothing for 2 s is stalled, not slow: its drops set no pace and its first late ack resets it, issue 401); `s` stats `keyDrops`, `paceMs`, `busyMs`, `tabFrameMs`), and passes on only input and device commands
(`Session.check`): never `shot`, `record`, `pick` or anything else that reads or writes files. Loopback by default;
another `--bind` needs `--token`; WebSocket upgrades and POSTs from another Origin are refused, and on loopback a
non-loopback `Host` header (DNS rebinding). Instances are never given `--adb` or `--control-bind`. A session ends when
its tab closes, after `--idle` seconds without input, or when the app exits; its data slot is then emptied. Instances
run with `--no-autofill` (never the owner's saved sign-ins) and `--data-slots serve` (`Storage.dataDir`: slots
`serve-N`, so `--fresh` never empties an ordinary launch's `default`), and the control log shows typed text only as its
length.
A tab that sends `hold` before reloading gets a one-time token, and its session outlives the closed socket for
`--rejoin-grace` seconds (15; 0 off) until `/ws?rejoin=ID&hold=TOKEN` takes it back with the app still running (issue
529, docs/serve.md "Rejoining after a reload"; `Session.awaitRejoin`/`rejoin`, `Serve.rejoinSocket`; the agent's
route also wants the session's origin and key; serve.html's `window.apksimHold()` and `tryRejoin`; `serve-check.py
--rejoin`, `beam-check.py --only rejoin`). `/api/health` needs no token (503 with the reason when a session could not start). `--allow-origin ORIGIN` lets a
trusted page frame it (`/?embed=1&app=ID&token=...`: screen and device controls only, the token kept in memory and sent
as `?token=`, since a cross-site frame gets no SameSite cookie) and open its WebSockets. `POST /api/apps {"url",
"headers", "options"}` downloads an APK in the background (adds of one URL and headers share a fetch in progress and
reuse a finished one for 5 minutes: `Catalog.sources`, keyed by a hash, issue 162) and answers its id at once (`GET /api/apps/ID` for its state, with the build's `sha256` once ready; a POST with a known `"sha256"` is answered `ready` without a fetch, the hash being the authorisation rather than the URL and headers);
a session opened on it meanwhile shows `p downloading N%`); uploads may be chunked; catalog lines take ` -- ` launch
options. Apps' launch options are limited to `Catalog.LAUNCH_OPTIONS` (activity, Build fields, settings...: nothing
that touches files, ports or the JVM). Behind a reverse proxy (issue 143): `--trust-proxy` takes X-Forwarded-Host/-Proto
from a loopback peer as the browser's (Origin is checked against them, and the loopback Host check is skipped),
`--public-origin URL` accepts that origin as serve's own and prints links with it, `--base-path /sim` serves everything
under a prefix (a proxy that strips it works too); the page builds every URL relative to its folder, and the token
redirect is relative. WebSocket pings go to every session every 20 s.
`--allow-beam` (docs/beam.md): the page offers "On this Mac" when the visitor runs `apksim beam`, which runs the app in
the beam sandbox and streams it into the same canvas; the server hands the page a signed ticket (`BeamTicket`) with
which the agent downloads the APK itself (`GET /api/beam/apk`, no token), the tab relaying it when that fails. A move
to the Mac "With its data" sends `handoff` on the server's socket: the app quits, its slot is packed, and the agent
downloads it with a ticket (`GET /api/beam/data`) into the slot the app starts in there.
Files in and recordings out (issue 187, docs/serve.md): `POST /api/sessions/ID/files?path=PATH` writes the body into the
running app's storage (`--max-push MB` a file, default 16; 64 MB a session); the server reads the upload and sends the
instance `devpush` (`TabFiles`), so no host path is named. With `--allow-rec` (off by default: a recording holds the
app's network calls and logs) a tab may send `rec on|off [CHANNEL...]`, `rec checkpoint now` and `rec save [SECONDS]`,
never a file name: the server writes `STATE/serve/logs/rec-ID/RID.apkrec`, kept a week with the logs, and `GET
/api/sessions/ID/recordings/RID` streams it. `apksim beam` has the same routes under `/beam/sessions/ID/...` with the
page's `Origin` and a per-session `key`, recording always allowed; those were not run when this landed.
`scripts/tabfiles-check.py URL --token FILE` checks the serve routes.
The app's `android.util.Log` in a session's log (issue 554): a tab's `logcat on|off` (or `/ws?logcat=1`) is passed on as the control command, on `apksim beam` always and on serve with `--allow-logcat` (`Session.logcatAllowed`; the `i` message's `logcat`). `setprop log.tag.TAG LEVEL` (adb shell, a tab's full shell) sets the level `Log.isLoggable` answers for that tag (`LogTags.ShadowSimLog`, liblog's order: `log.tag.TAG`, `persist.log.tag.TAG`, `log.tag`, `persist.log.tag`; a value not starting with a level letter is passed over; S silences); only `isLoggable` changes, every line is still written (Robolectric's `ShadowLog.setLoggable` would also drop lines, so it is not used); native liblog's `__android_log_is_loggable` does not read them.
A session's log and why it ended (issue 418, docs/serve.md "When a session ends badly"): `GET /beam/sessions/ID/log?key=KEY`
on the agent is serve's `/api/sessions/ID/log` for the page that started the session (its `Origin` and its `f KEY`; `Session.writeLogAccess`
keeps both in `beam/logs/ID.access` for after the session), and before `x TEXT` a tab gets `e {"reason":"crash|exit|idle|busy|no-app|error|other",
"text","exception"}` (`Crash.endJson`, the reason passed to `Session.stop(reason, why)`); `beam-check.py` and `serve-check.py --crash-app` check them.
Shell commands (issue 397, docs/serve.md "Shell commands"): a tab's `shell ID LINE` runs an adb shell line in its
session without an adb device (`Session.shellCommand` → the instance's `tabshell`, on a control connection of its own, at
most four at once), answered `a {"id","status","out"}`: `am start`, `am broadcast`, `settings get|put` and `cat` of one file in the app's own files/cache folders (issue 408) for the app's own
package, anything else status 126; `--no-shell` turns it off; the `i` message says `"shell": "off|limited|full"`. On
`apksim beam` with the owner's `apksim beam config allow-adb on` (read again at every start and command), a start with
`"adb": true` gets the whole adb shell and an adb device on loopback (`--adb auto`, through the agent's `--beam-adb` launch marker, never saved
with a kept session) whose port the tab hears as `d {"adb": PORT}`. The page has an "adb shell" card and
`window.apksimShell(LINE)`. `scripts/tabshell-check.py URL [--token FILE] [--off]` (CI: `ci-smoke.sh`),
`scripts/shell-page-check.py URL` (the card, in Chrome), `scripts/beam-check.py APK --only adb` (the beam side, with the
`adb` client when there is one). AdbServer now closes a connection whose packet header is not adb's (the command's
complement in its last word), so a browser's request to the port is never read as a command.
Shared sessions (docs/serve.md "Shared sessions"): with `--allow-share` (off by default) a session's tab is told a key
(`i`'s `share`) and its link `/?join=ID&key=KEY` lets others watch, view only, without the server's token (the page and
`/ws?join=` only; `Serve.joinTarget`, `joinSocket`); `--max-viewers N` (100) a session. `serve/Viewers`: every frame
`Session.readFrames` reads is handed to the viewers too (the same bytes: no second encode or instance stream), by a
few shard threads, never the tab's reader thread (waking a viewer's sender is ~7 us: a thousand viewers at 120 fps took
that thread and the tab's frame rate); each viewer has its own queue, window of 4 frames in flight and sender thread,
drops to the next keyframe when 8 behind (asked of the instance at most once a second for all), and is dropped after
30 s without an ack. A viewer may send only `ack`, `key` and `t`. The tab's `maxfps` under 30 (a hidden tab's 1) is
held back while anyone watches. Viewers' sockets give their place among the 128 connections back once joined.
`scripts/share-check.py URL [--token FILE] [--viewers N] [--server-max N] [--stall] [--off]` checks the rules, one
viewer and N at once (`scripts/share-swarm.java`, a thread each, or `--swarm python`; CI: 40 in `ci-smoke.sh`); a frame's
time is not its order (a picture sent again on request carries the time it was sent), so the chain check orders frames
by the viewer that got the most. Measured on one Mac, viewers included: 300 at 120 fps with serve at 1.7 cores, 1,000 at
60-113 fps and 4 Gbit/s with serve at 3.9 cores, the check's own Python tab being what fell behind. The switch: `POST /api/share?allow=on|off`
(token; `GET` for the state) turns sharing on or off while the server runs (`Serve.setShare`; every non-beam session has
a `Viewers` whether or not the server shares): on, each running tab hears `d {"share": KEY}`; off, viewers are dropped
("sharing was turned off"), tabs hear `d {"share": ""}` and each session takes a new key, so old links stay dead
(share-check.py turns it both ways; `--no-switch` leaves a server others watch alone). Not done: revoking
one link, handing control over, a JPEG side stream, viewers through an HTTPS proxy. On two cloud sandboxes (server in
the Linux image, viewers on the other, ~1 Gbit/s): 1,500 viewers passed at the image's 9 fps (510 Mbit/s); 2,000 failed
with the link nearly full (0.82-0.89 Gbit/s).
Kept sessions (issue 221, docs/serve.md "Kept sessions"): with `--keep-max N` (0 by default, off) and `--keep-days N`
(14), `/ws?keep=KEY` runs the app in a seed slot (`ks<hex>-0`, which beam's sweep never takes) of the server's naming and, when the session ends, packs
it with SlotPack into `STATE/serve/kept/<HMAC of KEY under the token>/<package>/` (`KeptStore`); the next session with
that key on the same package starts on it (`reopened`, or `copied` for another build with the same signer, `refused`
for another signer), told to the tab as `d {"kept":...}`. One session per key (`busy`, or `keepBusy=refuse`: 409);
`GET`/`DELETE /api/kept/KEY`. `scripts/kept-check.py URL --state DIR` checks it.
`scripts/serve-check.py URL [--token FILE]` drives it as a tab would and prints first-frame time, latency, fps and
memory; `ci-smoke.sh` runs it against the image. Measured 2026-11-05 (test app): Mac first frame 0.26-0.45 s warm,
tap to frame ~25 ms, Shader tab streamed at 120 fps (4 ms encode, ~100 KB a frame), 370-620 MB resident per session;
Linux image (Rosetta) first frame 6-10 s cold, tap to frame 44-61 ms, ~700 MB a session. The page's latency figures
match the tab's clock to the server's (`t` messages), so frame times compare across machines.

## MCP server
`apksim mcp --apps DIR [--out DIR]` (`scripts/mcp.py`, stdlib Python, protocol 2026-07-28 and the handshake revisions): tools to launch and drive instances over `--control`; each action answers with the node changes since the last answer. Allow-listed launch options, output folder only, `--no-autofill`. docs/mcp.md; `scripts/mcp-check.py` checks it.

## Code notes
- `serve/`: `apksim serve` itself (`Serve`, `Session`, `Catalog`, `Http`, `WebSocket`; `Beam`: `apksim beam`, the
  loopback agent that runs a browser session's app on the visitor's Mac, docs/beam.md, with the sites its owner
  allowed, the consent dialog (`/beam/hello`'s `asking` while it is up, issue 178) and the addresses it may download a
  build a page names from (`apksim beam config allow-download`, a start's `source`, issue 177); `SharedDataLibs`: the agent adds
  `--move-shared-data` itself for a build with a 4 KB-aligned library macOS cannot map as it is (issue 171); `BeamSandbox` (in `dev.apksim`): a beamed instance's
  sandbox (`apksim beam config allow-loopback PORTS` opens named loopback ports in its profile, issue 428; `LoopbackPorts`), its own standbys (`b`) and caches per build, docs/beam.md "The beam sandbox"; `BeamStore`: what the agent
  keeps and for how long, builds held `keep-days` after their last session, sessions kept on request, `apksim beam
  saved|run|to-device|forget|config|sweep|status`, and a saved session reopened from its site's tab on a copy written back when
  it ends, or a newer build of the same app started on a copy (`/beam/start`'s `saved`/`dataFrom`, `GET /beam/saved`,
  issue 181; `ApkSigners`: the same key, as Android requires of an update), and the flight recorder channels a beam
  session starts with (`apksim beam config rec input|all|off|CHANNELS`, `input` by default; an app's own `--rec`
  wins), and the agent's session limit (`apksim beam config max N`, 1-64, also written by `beam install --max N`; default 4, an explicit `apksim beam --max N` wins; `/beam/hello` says `max`, issue 569); `DeviceHandoff`: `apksim beam to-device ID [-s SERIAL] [--data] [--settings KEYS|all] [--install
  replace|skip] [--yes] [--dry-run]`, a saved session carried on to an adb device (the build compared by hash and
  signer, the device's data backed up before the slot is written through `run-as`, settings without identity keys, an
  undo script; docs/beam.md "Moving a session to a device", issue 191; no real device has run it yet); `DeviceTarget`, `DeviceBridge`: device sessions (issue 626, docs/beam.md "Device sessions"), a real adb device as a session's screen and input where the owner allows it (`apksim beam config devices on`, hello's `devices`, `GET /beam/devices`, a start's `device`/`uninstall` and its `need: "uninstall"`), the device mirrored and driven through scrcpy's server (`brew install scrcpy`) by a bridge JVM that serves the instance's control and frame-stream protocol, so `Session` relays it unchanged; `scripts/beam-device-session-check.py APK [--serial SERIAL]`; `BeamLaunchd`: `apksim beam install|uninstall`, launchd starting the agent
  when a page asks, `--launchd` taking the socket on stdin; `SlotPack`: a session's data slot and `.device` folder
  zipped by the server (`handoff`) and unpacked by the agent into a `bm<12 hex>-0` slot, docs/beam.md phase 5; the page is
  `resources/dev/apksim/serve/serve.html`). Runs on a plain JVM with only apksim.jar: no Robolectric or Android there.
- `FrameStream`: the screen as JPEG or H.264 frames for `apksim serve` (see "apksim serve"; H.264 is
  `video/H264Encoder`: VideoToolbox in `native/h264enc.m`, OpenH264 opened at run time in
  `native/linux/h264enc_linux.c`, `libh264enc`). `Control` serves each connection
  on its own thread, so one can stream while another drives.
- `TabShell`: `tabshell limited|full BASE64`, an `adb shell` line a browser tab sent (`apksim serve` and `beam`,
  issue 397; docs/serve.md "Shell commands", docs/beam.md "adb commands in a session"), run by `AdbShell.runForTab` on
  the control connection's own thread (the shell's commands wait for Host's loop) and answered `ok STATUS BASE64`.
  `limited`: one command, only `am start`, `am broadcast` and `settings get|put` and `cat` of one file in the app's own folders (issue 408) for the app's own package
  (`TabShell.refusal`; anything else is status 126); `full` (a beamed session that asked for adb while its Mac's owner allows
  it, `allow-adb on`, read at each use): the whole shell, nothing streaming, a minute at most. Only the server sends it: it is not a command a tab may type.
- `TabFiles`: `devpush DEVICE_PATH BASE64`, a file a browser tab sent (`apksim serve` and `beam`, issue 187), written
  where `adb push` could write it (the app's `files/`, `cache/`, `shared_prefs/`, `databases/`, `/sdcard`,
  `/data/local/tmp`) and moved into place whole. Only the server sends it: it is not a command a tab may type.
- `net/TabRelay`, `RelaySocket`, `RelayConnection`: the beam tab relay (issue 276, docs/beam.md "The tab relay"): with
  `--tab-relay` (the agent adds it for a start with `"relay": true`, protocol 3), plain-HTTP requests to
  `page.apksim.invalid` are carried to the browser tab as `y ID {json}` text messages and answered by it. In-JVM (the
  beam sandbox denies loopback): `SocketFactory.createSocket()` in app code goes to `Net.createSocket`, which returns a
  `RelaySocket` while the relay is on (the factory's own socket for any other address), `URL.openConnection` a
  `RelayConnection`; the instance's `relaywatch` control connection is the link to `Session`. `scripts/check-tab-relay.sh` (CI).
