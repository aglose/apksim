# apksim serve: an APK in a browser tab

`apksim serve` lets someone with no Android tools try a build: they open a link, pick or upload an APK, and use the
app in their browser. apksim runs the app; the browser shows its screen and sends input. One build gets a link
anyone with the token can open, so QA, PMs and designers can try a pull request's APK without Android Studio or an
emulator.

This page is the spec, then how it is built, then what was measured.

## Spec

### Command

```
apksim serve [--port N] [--bind ADDR] [--token FILE] [--max N] [--idle SECONDS] [--apps DIR|FILE]...
             [--max-upload MB] [--max-push MB] [--allow-rec] [--quality Q] [--bitrate KBPS] [--codec auto|jpeg] [--in-flight N] [--no-network]
             [--allow-origin ORIGIN]... [--warm auto|on|off] [--core-dumps] [--move-shared-data] [--trust-proxy]
             [--public-origin URL] [--base-path /PATH] [--device-size NAME=WxH@DENSITY]...
             [--allow-beam] [--beam-port N] [--keep-max N] [--keep-days N]
```

- `--port` (default 8080) and `--bind` (default `127.0.0.1`). Binding anywhere but loopback needs `--token`, or
  serve refuses to start.
- `--token FILE`: a shared secret, read from the file (first line). With it, every request must carry it: the page
  is opened once as `/?token=...`, which sets an HttpOnly cookie and redirects to strip it from the URL; scripts use
  `Authorization: Bearer ...`.
- `--max N` (default 4): sessions at once. One more gets "all N sessions are busy".
- `--device-size NAME=WxH@DENSITY` (repeatable): a named device size besides the built-in ones, for an app built for one
  fixed panel (`--device-size hd720=1280x720@1.5` is 1920x1080 px). `WxH` is in dp (160-4096 a side), density a scale
  factor (0.75-4; 1, 1.5, 2, 3; 2 if left out), at most 4096 pixels a side; the name is lower case (`[a-z][a-z0-9-]*`) and
  not a built-in one. The page's Device menu lists each (`GET /api/devices`), and `/ws?device=NAME` and the tab's
  `device NAME` accept it. `device` stays a checked name: a size spelled out by a client, or any other name, is refused
  (`/ws` falls back to the app's default device, else the phone; a command is answered `r refused`).
- `--idle SECONDS` (default 600): a session with no input for that long is stopped and its tab told so.
- `--apps`: the catalog. A folder lists its `*.apk` files; a file lists one APK per line, a path or an `https://`
  URL (downloaded once, at first use), optionally followed by a display name and then ` -- ` and launch options of
  that app's own (below). Repeatable. For example:

  ```
  builds/app-debug.apk Debug build -- --activity .DebugLauncherActivity --build MODEL=Pixel_9
  https://ci.example.com/artifacts/app.apk Nightly -- --setting global:onboarding_done=1
  ```
- `--max-upload MB` (default 300): uploads larger than this are refused before they are stored.
- `--max-push MB` (default 16, at most 32): the largest file a tab may push into its app (below); a session takes 64 MB
  of them in all.
- `--allow-rec`: a tab may use the flight recorder (below). Off by default: a recording holds the app's network calls
  and logs. (`apksim beam` always allows it: the instance is the visitor's own.)
- `--keep-max N` (default 0: none) and `--keep-days N` (default 14): kept sessions (below). At most N apps' data kept
  in all, the least recently used dropped first, and any not used for `--keep-days` deleted (checked hourly and at start).
  Off by default: kept data outlives the session it came from. One server per state folder keeps sessions (a lock on
  `serve/kept/.lock`, taken once its port is bound); another started on the same folder keeps none and deletes none
  (its tabs are told `off`), and a server with `--keep-max 0` never touches what was kept.
- `--quality Q` (default 80): the JPEG quality of the stream.
- `--bitrate KBPS` (default 4000): the H.264 stream's average bit rate (see "Stream").
- `--in-flight N` (default 8, 1-32): the most frames in flight to a tab (below); 2 is the old fixed window.
- `--codec auto|jpeg` (default `auto`): `auto` gives a tab H.264 when it asks and the instance has an encoder;
  `jpeg` always streams JPEG.
- `--no-network`: instances run with `--no-network` (Mac only, as for a launch).
- `--warm auto|on|off` (default `auto`): warm each APK's cache in the background (below). `auto` is on for Linux,
  where there is no warm standby, and off on the Mac, where a launch is already fast and a warm-up would take the
  standby a session wants.
- `--core-dumps`: let a crashed instance write a core file. Off by default (instances run with
  `-XX:-CreateCoredumpOnCrash`, set through `APKSIM_NO_CORE=1`, which the launcher reads): a large app's core is
  gigabytes and a few crashes fill a volume. The JVM's crash report is kept in any case, as below.
- `--move-shared-data`: every instance (sessions and warm-ups) runs with `--move-shared-data` (macOS, experimental: a
  4 KB-aligned native library whose writable data shares a 16 KB page with its code is loaded by moving that data). An
  app may also carry it as its own launch option (below). Does nothing on Linux.
- `--allow-origin ORIGIN` (repeatable, e.g. `https://dashboard.example.com`): pages on that origin may embed serve's
  page in a frame and open its WebSockets and POSTs (see "Embedding"). Without it, nothing may frame the page.

### Sessions

- One session is one browser tab and one apksim instance: the APK, the device (phone, foldable, tablet, desktop,
  TV, watch, watch-small, watch-large: a round watch's stream has its corners cleared, and the wheel turns the crown), appearance, locale and font scale the tab asked for, and optionally a deep link opened once the app is up.
- The instance is a normal launch: `apksim --headless --fresh --no-autofill --data-slots serve --control auto
  [options] APK` (`--no-autofill`: never the owner's saved sign-ins from `work/autofill`, which would sign a visitor
  in as them; `--data-slots serve`: app data in `serve-0`, `serve-1`..., never the `default`/`instance-N` slots an
  ordinary launch keeps, which `--fresh` would otherwise empty). On the Mac it is taken
  by the warm-launch standby when one is ready (serve starts one at its own start, and each instance starts the next
  after its first frame); on Linux there are no standbys, so each session is a cold launch.
- Each instance takes its own app data slot (`serve-N`), emptied first (`--fresh`), and serve deletes the slot's
  contents, with its `serve-N.device` (the slot's /sdcard and Settings values), when the session ends (the instance prints the slot as soon as it takes it, so this holds even for an app
  that never finishes starting), so one session never sees another's data through the app.
- Settings and input a tab sends before its instance is up (while the APK downloads or the app launches) are kept,
  in order, and sent once it is.
- Closing the tab while the APK downloads or the app launches ends the session too, and a stopping session counts
  against `--max` until its instance has exited.
- A session ends when its tab closes (WebSocket closed), on `--idle`, or when the app exits (Back on its last
  activity). The instance is asked to `quit`; if it is still running 5 s later, it is killed.

### Kept sessions (issue 221)

With `--keep-max N`, a session can keep its app's data for a later one, so a slow sign-in is done once rather than every
session. It is as `apksim beam`'s saved sessions (docs/beam.md), on the server.

- **Keeping:** `/ws?app=ID&keep=KEY` (the page passes on `keep` and `keepBusy` from its own link, so an embedding page
  or a shared link can name them). `KEY` is the client's: 1-128 letters, digits and `. _ ~ -`, starting with a letter or digit (`/api/kept/..`
  would not reach the server as written), for example a hash of a
  user and a profile name; anything else is `400` before the upgrade. When the session ends, its data slot and
  `.device` folder (the app's files, databases and preferences, its /sdcard and Settings values) are packed as a
  hand-off is (`SlotPack`: no links or special files, at most 50,000 files and 1 GB) into
  `STATE/serve/kept/<id>/<package>/`, replacing what the key kept for that package, instead of being erased. A new
  pack is written beside the old one and becomes the kept one in a single rename of its metadata, so a save that
  fails or a server that stops mid-save leaves the previous data as it was.
- **Reopening:** a later session with the same key, on an APK of the same package, starts on that data: the same build
  is `reopened`; another build is `copied` only when the same key signs both, as Android keeps an update's data, and
  otherwise `refused` (it starts empty and keeps nothing, so what was kept stays). The tab is told which after the APK
  is ready and before the app launches, as `d {"kept":"new"|"reopened"|"copied"|"refused"|"failed"|"busy"|"off","why"}`
  (`restored` when `restore=` data came with it, which wins and is then kept). Kept data is labelled with the build's
  SHA-256 and signers as read when its session started, so replacing a catalog file meanwhile changes nothing. A session that ends with its data kept
  says so in its log.
- **One session per key:** a start whose key is in use first waits (before the upgrade) up to 3 s for the session on
  it to begin ending, then for as long as it is ending (its data being packed: 60 s at most), so the page's Start again
  or a reload starts on what the last session kept. Past that it runs without the kept data and keeps nothing (`busy`),
  or, with `keepBusy=refuse`, gets `409`.
- **Scope:** the store's `<id>` is an HMAC of the key under the server's token, so a key reaches only what was kept
  under the same token (another token, or a changed one, finds nothing), and the key itself is stored nowhere and never
  shown in `/api/status`. Everyone who holds one token shares its keys: make them unguessable.
- `GET /api/kept/KEY`: `{"inUse", "packages": [{"package", "sha256", "size", "saved", "used"}]}`.
  `DELETE /api/kept/KEY` forgets everything that key kept (`{"forgot": N}`; `404` for nothing, `409` while a session
  has it). Both need the token, and DELETE an allowed `Origin`, as a POST does; a server that does not own the store
  answers `503`.
- What is kept is the app's own data, not the session: the app starts cold on it (its activity stack, the device and
  the tab's settings are the new session's). A session that crashed is kept as it ended; `DELETE` starts the key over.
- The instance runs in a slot the server names (`ks<12 hex>-0`, not `serve-N`; an `apksim beam` agent sharing the state folder never sweeps `ks` slots), and the session holds an exclusive lock on `serve/seeds/<name>.lock` from naming
  the slot until it has packed and erased it; a server's sweep of leftover `ks` slots (hourly, on every server sharing the
  folder) deletes one only with that lock, unlocked by any instance and an hour untouched, and only that slot is packed, whatever
  the instance prints.

### When a session ends badly

- The tab gets `x the app exited (status N)` followed, when it can be found, by why: the first exception line of a
  `FATAL EXCEPTION` the instance logged (`uncaught exception java.lang.IllegalStateException: ...`), the signal and
  problematic frame of a JVM crash (`crashed: SIGILL (0x4) in [libz.so+0x1234] deflateInit2_+0x10`, from the
  `hs_err_pid<N>.log` header), or the instance's own `exit N: REASON` / last `[apksim]` message (an `--activity` the
  manifest lacks). An instance that dies while starting is explained the same way.
- Before that `x` message, an instance that died (or could not start) sends its last 50 log lines as `r log: LINE`
  messages, which serve's page shows in its log under the screen. A tab that left, idled out or ended the session
  normally gets none.
- **Files in** (issue 187): `POST /api/sessions/ID/files?path=PATH` with the bytes as the body (token required, like
  everything but health) writes a file into the running app's own storage and answers
  `{"path":"/data/data/PKG/files/x.json","size":7}`. `PATH` is a device path or one relative to the app's data
  (`files/x.json`); it may name only the app's `files/`, `cache/`, `shared_prefs/` and `databases/`, `/sdcard` and
  `/data/local/tmp` (what `adb push` reaches, less the rest of the data folder), in letters, digits and `. _ @ + = , / -`
  (no `..`, no spaces). Refused: `400` a bad path, `422` outside those folders or through a link, `413` over
  `--max-push` or the session's total, `409` before the app is up. The server reads the body itself and the app writes it
  (`devpush`, not a command a tab may send), so no host path is ever named. A file is moved into place whole, so an app
  holding the old one open keeps reading it. On `apksim beam` the same is `POST /beam/sessions/ID/files?path=&key=`, with
  the page's `Origin` and the `key` the session sent that tab on its socket (`f KEY`).
- **Recordings out** (issue 187): with `--allow-rec`, the tab may send `rec on|off [input|logcat|net|db|db+values|screen|all]...`,
  `rec checkpoint now` and `rec save [SECONDS]` (never a file name). `rec save` is answered `r rec: saved RID` and the
  server writes `STATE/serve/logs/rec-ID/RID.apkrec`; `GET /api/sessions/ID/recordings/RID` streams it (`404` until it
  is written) and it is kept with the logs, a week, after the session ends. Redaction is the recorder's own.
- `GET /api/sessions/ID/log` (token required, like everything but health): the tail of that session's log as
  `text/plain`, at most 200 lines and 64 KB, each line cut at 400 characters and control characters replaced. `ID` is
  the session's id (the `i` message, the `id` in `/api/status`), which only its tab and whoever the tab tells know.
  Logs are kept in `STATE/serve/logs` for a week (swept hourly), so the reason stays readable after the session is gone;
  `404` after that or for an id that never was. It is bounded log text only: no file is ever served, and the id is
  matched as 12 hex digits and never used as a path.
- The JVM writes `hs_err_pid<N>.log` to its working directory, which is the logs folder. When the instance has gone,
  serve copies the report's `#` header (the signal, the frame, the JVM's own notes: no registers, memory map or
  environment variables) into the session's log as `[hs_err]` lines and deletes the file. Leftover `core*` and
  `hs_err_pid*` files an earlier server run left are removed at start and hourly once a day old.

### Warming an APK's cache

The first launch of a large APK translates its classes and extracts its libraries (a ~220 MB APK with ~13k classes took
4-5 minutes on 4 CPUs in the Linux image; later launches ~10 s), and Linux has no standby to hide that. So serve warms
the cache itself:

- Each APK stored by an upload or a URL add, and each APK a `--apps` catalog lists (a URL one once downloaded), is queued,
  and warmed one at a time at low priority (`nice -n 10`): an ordinary headless launch (`--exit-after-first-frame`,
  with the app's own launch options) that leaves the same caches a session finds. It does not count against `--max`
  and takes a data slot (`serve-N`) while it runs, as a session does; the slot is erased when the warm-up ends, fails or
  is cut short. Stopping serve ends a running warm-up (and erases its slot) and drops the queue. It stops with a failure after 15 minutes.
- A session opened on an APK while it is warming waits for it instead of translating the same classes beside it, and its
  tab shows `p warming the app's cache (translating classes: 4200)` meanwhile. One opened while the APK is still queued
  takes it off the queue and warms it itself. A session that translates for itself (nothing warmed it) also sends
  `p translating classes: N` every second or so, from the instance's log (the number of classes translated so far; the
  total is not known before the app has run once, so it is a count, not a percentage).
- `GET /api/apps/ID` has `"cache"`: `none`, `queued`, `warming`, `ready` or `failed` (and `"cacheDetail"`, the progress
  or the reason), and `GET /api/status` lists `"apps"` with the same for the catalog's apps only (`--apps`; an upload's or URL add's id is an unlisted capability and never shown there). A failed warm-up
  only means no head start (an uploaded file that is not a real APK fails it).
- Keep the cache across container restarts with a volume at `/opt/apksim/work/cache` (translated classes and extracted
  libraries, keyed by the APK's content). Uploads under `STATE/serve/uploads` are lost on a restart without a volume
  there too, and their ids stop working; mount `/opt/apksim/work/serve` as well if uploaded builds must outlive the
  container.
- On the Mac the standby serve starts, and the ones its instances start, carry the no-core setting in their name (`k`:
  a standby's JVM fixes its flags at its start), so a session is only handed to a standby started with it, never to an
  ordinary launch's (which may allow cores); with none ready it launches cold and starts one. macOS writes no core by
  default anyway.
- Not done: a warm standby for the most recently used APK on Linux.

### Behind a reverse proxy

A web app on its own hostname can forward a path to serve, so the browser's page and WebSocket stay on the app's
origin. Proxies usually rewrite `Host` to the upstream, keep the browser's `Origin`, and add `X-Forwarded-Host` and
`X-Forwarded-Proto`.

- `--trust-proxy`: a request from a loopback peer (a proxy beside serve, in the same pod or host) that carries
  `X-Forwarded-Host` is taken as addressed to that host. Its `Origin` is checked against `X-Forwarded-Proto://X-Forwarded-Host`
  (the first value of each, for a chain of proxies), and the loopback `Host` check (DNS rebinding) does not apply to
  it, and an `Origin` matching only serve's upstream `Host` is refused. The headers are ignored from any other peer, so
  a client cannot claim them. Serve itself then listens on loopback. It needs `--token`: any loopback connection is
  taken for the proxy, so without one a DNS-rebinding page could pass as it.
- `--public-origin URL` (e.g. `https://app.example.com`): the origin browsers reach serve at. It is accepted as serve's
  own for WebSockets and POSTs whatever the headers say, its host passes the loopback `Host` check (a proxy that keeps
  `Host`), and the link serve prints at startup uses it. The simpler choice when there is one public origin.
- `--base-path /sim`: the page, `/ws` and `/api/*` are served under `/sim/` (`/sim` redirects there). A proxy that
  strips the prefix itself works too, with or without the option. The path may not have `.` or `..` segments, nor be
  one of serve's own (`/ws`, `/api`, `/api/...`): behind a stripping proxy those requests would look prefixed. The page builds every URL relative to its own
  folder, the token link's redirect is relative, and "Copy link" uses the page's own address, so links keep the
  proxy's origin and prefix.
- Keepalive: serve sends a WebSocket ping every 20 s to every session (the browser answers by itself), so proxies and
  load balancers that drop idle WebSockets (often after 60-100 s) keep it. A client may also send `t <its time>` at
  any time as a keepalive of its own: serve answers it with its clock and nothing else.

### Launch options per app

A catalog line (after ` -- `) or a URL add (`"options"`) can give its app launch options that every session of it
gets, after the session's own (device, appearance, locale...) and before the APK: what an app may need to start at all.
Only these are accepted, each with one plain value (no value starting with `-` except a number): `--activity`,
`--build`, `--setting`, `--sdcard-file` (a file written to `/sdcard` before the app starts: `PATH=text:TEXT` or `PATH=BASE64`,
inline, at most 64 KB a file and 72 KB in all, `..` refused), `--feature`, `--timezone`, `--clock`, `--anim-scale`, `--serial`, `--bluetooth`,
`--status-bar`, `--nav-bar`, `--max-fps`, `--video`, `--audio`, `--biometrics`, `--camera` (`off` or `fake`, not
`fake=IMAGE`: no host files), `--network-profile`, `--device`, and the flags
`--soft-keyboard` and `--move-shared-data` (macOS: load a 4 KB-aligned native library whose data shares a page with its
code, experimental; it only changes how the app's own libraries are mapped, and does nothing on Linux).
`serve --move-shared-data` gives it to every app, since apps rarely control how their third-party libraries are aligned. A
session that ends because the loader refused such a library says so in the tab, and names these two places to set it (the
loader's own advice, "launch with --move-shared-data", is not something a tab can do). A beamed session needs neither:
`apksim beam` adds the flag itself for a build with such a library (docs/beam.md, "4 KB-aligned libraries"), so a
catalog written for a Linux serve, where these libraries load as they are, also runs on a visitor's Mac. Never options that read or write files, open ports or change the JVM (`--jvm`, `--network record`,
`--adb`, `--no-sandbox`...): serve refuses a catalog with one at startup and a URL add with one with a 400. `--device` takes a built-in
device or a custom size `WxH[@density]` (`--device 1280x720@1.5`, validated as for `--device-size`; a bad one is refused
at startup or with a 400): it is that app's default device, used when a tab names none (the page's Device menu then
shows it, and the tab may switch to it again by that name), and replaces the one a session would otherwise get. A bad value
(an activity the manifest lacks) ends that session with the launcher's message, as a launch would.

### Adding apps at run time

- `POST /api/upload`: the APK as the body, with `Content-Length` or `Transfer-Encoding: chunked` (a `fetch` with a
  streamed body, `curl -T -`); `--max-upload` holds either way, while reading. `X-Filename` (URL-encoded) names it.
  Answers `{"id", "name"}`.
- `POST /api/apps` with a JSON body `{"url": "https://...", "name": "...", "headers": {...}, "options": [...]}` (only
  `url` is required): serve downloads the APK itself and answers `{"id", "name", "state": "downloading"}` at once.
  `headers` go with the download only (an artifact store's `Authorization`) and are never stored; `options` are the
  app's launch options (above). The download is stored by content hash as uploads are, and the id follows the same
  rules: listed nowhere, deleted a day after its last use. Adds of one URL with the same `headers` share one download: while it runs they wait on it (and show its progress),
  and for 5 minutes after it finished a later add reuses the stored APK, each add keeping its own id and `options` (so
  variants of one APK cost one fetch; a failure is never reused, and header values are only hashed into the key). A
  failed download is reported for an hour. At most 4
  real downloads run at once (a shared one counts once); another add gets `503` with `Retry-After`. At most 100 failures are kept (the oldest go).
- `GET /api/apps/ID`: `{"id", "name", "state": "downloading"|"ready"|"failed", "error", "received", "total"}` (the
  last three while it is downloading or has failed).
- A tab (or `/ws?app=ID`) may open the id while it is still downloading: the session waits for it and sends `p
  downloading N%` messages (at most one a second) before `p launching ...`.

### Embedding

A trusted page (a team dashboard that picks the build, e.g. the APK CI built for a pull request) can show a session
inside itself:

- Start serve with `--allow-origin https://dashboard.example.com` (written as a browser sends it in `Origin` or not:
  serve compares the canonical form, so `https://dashboard.example.com:443` works too). That origin is added to the page's
  `frame-ancestors` (with `'self'`; `X-Frame-Options` is then not sent, since it cannot name an origin), and its
  WebSocket upgrades and POSTs are accepted like serve's own. Responses to its requests carry
  `Access-Control-Allow-Origin`, and its CORS preflights (`OPTIONS`, which carry no token) are answered; the requests
  themselves still need the token.
- Frame `/?embed=1&app=ID[&device=...&appearance=...&locale=...&fontscale=...&open=...&orientation=...]`. The embedded
  page shows only the screen, the device controls and permission prompts (no catalog, upload, start/stop or numbers),
  and starts the session at once. `&controls=0` leaves out the device controls as well.
- With a token, put it in the frame's URL (`&token=...`): an embedded page is served, not redirected, keeps the token
  in memory, takes it out of its address and sends it with its own requests as `?token=`. A cookie would not do: the
  `SameSite=Strict` cookie is not sent to a frame on another site. (A host page on the same site, e.g.
  `serve.example.com` inside `dashboard.example.com`, could use the cookie too.) The host page needs the token, so
  only embed serve in a page whose viewers may have it.
- To draw the stream with your own client instead, the protocol is the page's (`serve.html`): open `/ws?app=ID` from
  an allowed origin and speak it as above.

### Stream

- Frames are encoded in the instance from the frame the Host presents (the raster thread's buffer, as the AVI
  recorder takes it), sent only when a new frame is presented and differs from the last one sent. Two codecs:
  - JPEG (the default, and what a page without WebCodecs or an older page gets): each frame on its own, with
    Android's encoder.
  - H.264, when the tab asks for it (`/ws?...&codec=h264`: the page does when `VideoDecoder.isConfigSupported`
    says it decodes `avc1`; `?codec=jpeg` in the page's URL keeps it on JPEG) and the instance has an encoder:
    VideoToolbox on the Mac, OpenH264 on Linux (the image installs `libopenh264-7`; without it the stream stays
    JPEG). Serve tells the tab which it got (`c h264` or `c jpeg`) before the first frame. Low-latency settings: no
    frame reordering (no B-frames), each frame out as it goes in, an average of `--bitrate` kbps, keyframes only for
    a new viewer, a size change (rotation, device), or when asked (`key` from the tab, after a decoder error). BT.709,
    limited range, named in the stream. Each frame is one access unit in Annex B, the SPS and PPS in front of every
    keyframe, so the page configures its decoder from the keyframe itself (codec string from the SPS). Once the screen
    stops changing, the last frame is encoded twice more, 120 ms apart, to sharpen what the bit rate left out of it.
- The instance sends them over its control channel: a `stream [QUALITY] [h264 [KBPS]]` command turns that connection
  into a frame stream (loopback only, like every control connection), answered `ok stream` (or `ok stream h264|jpeg`
  when a codec was named). Serve relays them to the tab over a WebSocket. Each binary message is the frame's width,
  height, present time (epoch µs) and encode time (µs), big-endian, then the JPEG, or for H.264 a byte (1 for a
  keyframe) and the access unit.
- Flow control: serve keeps a window of frames in flight to a tab, and the tab acknowledges each frame once decoded.
  Two frames in flight cap a stream at about 2 frames per round trip (~20 fps at 100 ms however fast the app renders),
  so the window is sized from what is measured: `clamp(ceil(rtt / frame_interval) + 1, 2, --in-flight)`, where `rtt` is
  the quickest of the last 8 WebSocket ping round trips (serve pings every 0.5 s while frames flow; a browser's pong
  does not wait for the page, so a tab slow to decode cannot inflate it) and `frame_interval` the smoothed gap between
  the instance's frames, ignoring gaps over 250 ms (a still screen sends none). Past the first two frames a frame is sent only while the bytes in flight stay under
  1 MB, so a slow link sees current frames rather than a backlog. A slow tab or network sees fewer frames, never older
  ones: frames that arrive meanwhile replace the waiting one. The `s` stats message has `window` (effective), `pingRttMs`, `ackRttMs`
  (-1 before the first ack; it includes the tab's decode and draw) and `frameMs`, so a client can tell whether the
  network or the app is the limit. H.264 frames each need the one before, so none is skipped: up to three wait, and a tab further
  behind drops everything to the next keyframe, which serve asks the instance for at once (the instance does the same
  for serve, past 30 waiting). A keyframe is several times a delta, so a tab that decodes a little slower than the
  app draws would repeat that cycle and the video would freeze and jump (issue 232): after a drop serve sends the
  instance `pace MS` (about 1.15 times the tab's measured gap between acks, at most 250 ms), and the encoder makes at
  most one new frame per MS, the newest at that time, so the stream stays a valid chain at the rate the tab manages.
  After 8 s without a drop the pace is tried 15% quicker (a faster tab gets its rate back); a drop during that trial
  puts the pace that held back and doubles the wait before the next one (128 s at most), so a tab at its limit sees a
  keyframe ever more rarely. A drop under a pace that was not a trial makes it a quarter slower.
  The `s` stats message has `keyDrops` (drop-to-keyframe cycles so far), `paceMs` and `ackGapMs`. The window and byte
  budget apply to both codecs.
- Each frame carries the time it was presented and how long it took to encode, so the tab can show the stream's fps,
  present→screen delay and input→screen latency.
- The page shows decoded frames at its display's refresh (`requestAnimationFrame`), as the Mac window shows its own at
  the display's vsync, from a queue of at most two: two frames the stream's jitter brings within one refresh are
  shown one each, not the first overwritten (docs/beam.md, "Phase 1b as built"). Its present→screen and input→screen
  figures therefore end at that refresh.
- Pacing to the tab's display: while frames come, the page measures where in its refresh interval they are decoded
  and every 500 ms sends `vsync PERIOD_US SHIFT_US` (its refresh period, and how far to move the instance's frames so
  they land mid-interval). The instance then times its frames to that display instead of its own 120 Hz grid
  (`SimWindow.remoteVsync`), and goes back to the grid 1.5 s after the last one (a hidden tab sends none). It is not
  answered and does not count as input for `--idle`.

### Input

The tab sends commands in the control channel's own words, and serve passes on only these, with checked arguments:

| Browser | Control command |
|---|---|
| pointer down / drag / up / cancel (mouse, pen, touch) | `touch down\|move\|up\|cancel X Y` (moves at most one per animation frame) |
| wheel, trackpad scroll | `wheel X Y AMOUNT` (summed per animation frame) |
| the tab hidden / shown again | `maxfps 1` / `maxfps 120` (a hidden tab shows nothing; not answered, not input for `--idle`) |
| printable keys, paste, phone keyboards (through a hidden text field: IME, dictation) | `type TEXT`, `key SPACE` |
| a phone keyboard's delete or return (input events with no key) | `key DEL`, `key FORWARD_DEL`, `key ENTER` |
| Backspace, Delete, Enter, Tab, arrows, Home/End, Page Up/Down; Ctrl/Cmd and Shift with them | `key NAME 1 [ctrl] [shift] [alt]` |
| Escape, Back button | `back` (Shift+Esc instead returns the keyboard to the page's own controls) |
| Home button / click on the home screen | `home` / `resume` |
| Rotate buttons | `rotate left\|right` |
| Dark mode toggle | `appearance dark\|light` |
| Device menu | `device phone\|foldable\|tablet\|desktop\|tv\|watch\|watch-small\|watch-large` (also `rotary N`, `ambient on\|off`), a `--device-size` name or the app's own default size |
| Open URL field | `open URL` (a URL without spaces) |
| Locale, text size | `locale xx-YY`, `fontscale N` |
| Mute audio, blank video, animation speed | `audio on\|mute`, `video on\|blank` (new codecs only), `animscale 0\|0.5\|1\|1.5\|2\|5\|10\|toggle`; not `audio off` / `video off`, which change the decoder list and are launch-only |

Anything else (`shot FILE`, `record`, `pick FILE`, `quit`, ...) never reaches an instance from a tab: those read or
write files on the machine.

"Copy link" puts a URL on the clipboard that opens a new session in the same state: the app, device, orientation,
appearance, locale, text size and the last deep link opened. It does not carry what the app did since (navigation inside the app
that no deep link names, data entered).

### Status

- `GET /api/status`: limits, the standby (Mac), each APK's cache state (above), and each session: app, device, PID,
  resident memory (RSS), Java heap in use, time to first frame, idle time, frames sent.
- `GET /api/health`: needs no token, for HTTP readiness and liveness probes and load balancers. `200 ok`, or `503 not
  ready: REASON` when a session could not start: no launcher, `STATE/serve` or its `logs`, `uploads` or `downloads`
  folder not writable, or less
  free disk there than 512 MB plus `--max-upload`. It says nothing else; `/api/status` stays behind the token. An `--allow-origin` page can read it.
- The tab shows its own session's numbers in a bar under the screen, and the stream's fps and latency.

### Security

- Loopback by default; any other bind address needs `--token`. With a token, every request but `/api/health` needs it
  (cookie, `Authorization: Bearer`, the one-time `?token=` link, or `?token=` on an embedded page's own requests).
- Instances' control channels stay on loopback, and serve never starts them with `--adb` (the adb device has no AUTH).
  In a container, only serve's port is published.
- At most 128 connections at once (open sessions' included), taken before anything is allocated for one; a request's
  head must arrive within 10 s in all, so idle or trickling connections cannot use up memory or descriptors. (They
  can still keep others out while they last: put a server open to the internet behind a proxy that limits per client.)
- The page cannot be framed by another site (`frame-ancestors 'none'`, `X-Frame-Options: DENY`): no clickjacking of
  its controls. `--allow-origin` lets only the origins it names frame it.
- What a visitor types is not kept: the instance logs `type (N characters)`, not the text (passwords, pasted tokens).
- WebSocket upgrades and POSTs must come from serve's own page or an `--allow-origin` one: any other `Origin` is
  refused (a web page elsewhere cannot drive a local session), and on a loopback bind the `Host` header must name a
  loopback address (DNS rebinding).
- `POST /api/apps` makes serve fetch a URL anyone with the token names, from serve's network (http and https only).
  That is no more than an app run there can do, but on a server whose network reaches internal services, keep the
  token to people trusted with that. Launch options are limited to the list above.
- Uploads: at most `--max-upload` MB, must be a ZIP (an APK), stored by content hash under `STATE/serve/uploads`,
  reachable only through an unguessable id that is not listed anywhere (only the uploader's tab, and links it copies,
  know it). Uploads unused for a day are deleted.
- The log endpoint serves what the instance printed (its own and the app's log lines), to token holders only, and
  only that text; sessions' logs can hold whatever an app logs, so treat the token as access to them.
- The app data slot is erased at the end of a session (above), unless the session was started with `keep=KEY` on a
  server with `--keep-max` (kept sessions, above): then it is kept, under that key and the server's token, until
  `--keep-days` unused, `--keep-max` or a `DELETE`. Apps run in apksim's sandbox on the Mac
  (`sandbox-exec`: no writes outside apksim's state folder, no reads of credentials), and the container is the
  sandbox on Linux. This keeps sessions' data apart for ordinary apps; it is not a boundary between hostile APKs run
  by different people on one machine, which can read the state folder: give each trust boundary its own serve (its
  own container).

### Run on this Mac (`--allow-beam`)

With `--allow-beam` the page offers "On this Mac" to a visitor whose Mac runs `apksim beam` (docs/beam.md: the
agent, its prompt, its protocol and what was checked): the app runs there, on their hardware, and the tab draws its
stream as it draws the server's, so no server session is used. Serve adds `GET /api/beam` (the agent's port,
`--beam-port`, default 47474), `GET /api/apps/ID/beam` (name, size, SHA-256, launch options) and `GET /api/apps/ID/apk`
(the APK, which the tab passes on when the agent does not have that build), behind the token like everything else,
and lets the page reach `http://127.0.0.1:PORT` in its CSP. The beam answer also carries a ticket (HMAC-signed with
a key serve makes at its start, for that app and its SHA-256, good for 5 minutes), and `GET /api/beam/apk?ticket=T`
serves that one APK to whoever holds the ticket, without the token: the visitor's agent downloads the build itself
with it, and only when that fails (a sign-in it cannot pass, a firewall) does the tab relay the APK. Off by default: it
lets token holders, and the agents they hand a ticket to, download the APKs they can run. A session on the visitor's
Mac also offers "Fill the page" (the display sized to the stage at the browser's pixel ratio) and "Show in
Simulator", which only the agent accepts (docs/beam.md, phase 3). Changing where the app runs during a session moves
it: the other side starts beside it and takes over on its first frame, with no blank frame between (phase 4).
With "With its data" the move takes the app's data too (phase 5), relayed by the tab when the visitor's agent cannot
download it itself (a sign-in in front of the server). While the visitor's agent asks its owner whether this site may
run apps there, the page says so ("apksim is asking on this Mac"). A page that draws the stream itself follows
docs/beam.md, "Beam client protocol", and may also name where else its build can be downloaded (`source`, an artifact
store), which the agent uses only for addresses its owner allowed (`apksim beam config allow-download`, docs/beam.md
"Builds from an artifact store").

### Not in scope (yet)

- Audio, the Mac's own camera (an app launch option `--camera fake` gives a session the test pattern, and a tab may
  send `camera pattern` or `camera off`; never `camera image`, which names a host file), the soft keyboard (a hardware keyboard counts as connected, as in the Mac window), two-finger
  gestures from the browser, file pickers (`pick`), multiple tabs sharing one session.
- A pool of more than one warm standby (one per apksim folder today, refilled ~2 s after each launch: a second
  session within that time launches cold).
- Other video codecs (VP8/VP9/AV1), a bit rate that adapts to the link, and sending only the changed region of a
  JPEG frame.

## How it is built

- `dev.apksim.serve.Serve` (in `apksim.jar`, run by the launcher's `serve` subcommand on a plain JVM, no
  Robolectric): an HTTP/1.1 server with WebSocket (RFC 6455) written on `java.net` sockets, one virtual thread per
  connection. `serve.html` (a resource in the jar) is the whole page: no external scripts, styles or fonts.
- `Session`: starts `apksim` as a child process, reads `[apksim] control port N` from its stderr (the rest goes to
  `STATE/serve/logs/<session>.log`), opens two control connections (commands, pipelined; and the stream), and
  relays both ways.
- In the instance: `FrameStream` (raster thread → latest frame → encoder thread → each subscriber; one H.264
  encoder shared by every H.264 subscriber, `dev.apksim.video.H264Encoder`: `native/h264enc.m` on the Mac,
  `native/linux/h264enc_linux.c` in the image, which opens libopenh264 with `dlopen`), `Control` serves
  each connection on its own thread (before: one client at a time), and the `stream` and `redraw` commands. `stats`
  reports the PID and data slot.
- Clock: the tab sends `t <its time>` and serve answers with its own, so the tab can put frame times on its clock.
- Uploads: stored by content hash, so the same APK uploaded twice shares apksim's per-APK caches (translated
  classes, extracted libraries). No quota beyond `--max-upload` per file: a server open to many people needs its own
  disk limit.

## Checking it

`scripts/serve-check.py URL [--token FILE] [--upload APK]` drives a running server as a tab does (WebSocket, frame
acknowledgements, the same commands) with the test app: it checks the token, a cross-origin WebSocket, a refused
command, the upload limits, then taps the FAB, drags, scrolls, types, switches tabs, turns on dark mode and rotates,
checking that frames follow each, and that closing the tab ends the session. It also checks `/api/health` without the
token, a chunked upload, and that URL adds with a disallowed launch option or a non-http URL are refused; with
`--allow-origin ORIGIN` (one the server allows) that origin's WebSocket and framing and the embedded token link; URL may
include a path (`http://host:port/sim` for `--base-path` or a proxy's prefix); with
`--add-url URL [--add-options JSON]` it runs the session on that APK, added with `POST /api/apps`. It checks that custom
`--device` values outside the rules are refused and `/api/devices`; with `--device-size NAME=WxH@density` (as the server
was started) that a session on NAME shows that many pixels, that switching to the tablet and back resizes it, and that a
spelled-out size or unknown name is refused; with `--app-default-size WXxHY` (pixels, on an app whose launch options have
`--device`) that a session naming no device shows it. Then a second tab
asks for H.264 (`--h264 required|optional|skip`; CI requires it): the first frame is a keyframe with SPS, PPS and IDR,
a drag gives delta frames only, a `key` request on a still screen brings a keyframe, a tab that stops acknowledging
gets at most two frames, and once it acknowledges again the stream resumes at a keyframe. It prints the numbers
below.
`scripts/ci-smoke.sh` runs it against the Linux image with a token on a non-loopback bind, as CI does.
`scripts/kept-check.py URL --state DIR [--token FILE]` checks kept sessions against a server started with
`--keep-max N --max 2` or more (DIR is its state folder, where it looks at the running app's data slot): a key's
pushed files and /sdcard are there for the next session on that key and not for another key, a key in use gives `busy`
or `409`, a reopened session's change is kept in turn, a restart on the same key starts on the last change, and
`DELETE` forgets it. `--exec "docker exec NAME"` reads the slot inside a container; `ci-smoke.sh` runs it against the
Linux image.

## Measured (2026-11-05, test app, phone 822x1782)

Mac: M4 Pro, 12 cores, the server and the client on the same machine. Linux: the image on Colima (4 CPUs, 8 GB, x86-64
under Rosetta), the client on the Mac through the published port.

| | Mac | Linux image |
|---|---|---|
| Session start to first frame in the tab | 0.26-0.45 s (warm standby, 5 runs) | 6.3-9.9 s (cold launch; a plain cold launch in the image: 11.4 s) |
| Tap to the next frame received | 24-26 ms | 44-61 ms |
| Frame presented to received (clocks matched) | 5-6 ms | 7-10 ms |
| JPEG encode, quality 80 | 4.1-4.4 ms (tablet 2560x1600: ~12 ms) | 5.5-6.8 ms |
| Frame size | 40 KB (list), 74 KB median over a session, 99 KB (Shader tab) | the same |
| Stream rate, endless animation (Shader tab) | 118-120 fps | 1-2 fps: the shader runs on the CPU there, the app's own rate |
| Stream rate while dragging (one move every ~11 ms) | 85-89 fps | 70-79 fps |
| Memory per session (resident) | 370-620 MB | 680-770 MB |
| The server itself, idle | 86 MB | 92 MB (the container) |

- Each frame is sent only when the screen changed: a still screen costs nothing after its first frame.
- Four sessions started at once on the Mac: the one that took the standby showed its first frame after 0.9 s, the
  three cold ones after 2.7-2.8 s, each ~400 MB; the stream kept 77-104 fps on the Shader tab in all four.
- Bandwidth: ~3-5 MB/s while scrolling, up to ~12 MB/s on a full-screen animation at 120 fps. On a slow link the
  acknowledgements hold the server back to what the tab draws, so the tab sees fewer, current frames rather than a
  growing delay.

### H.264 against JPEG (2026-11-07, Mac, test app, phone 822x1782, `--bitrate 4000`)

`serve-check.py`'s two tabs on one server, and the page itself in a Chromium tab (WebCodecs, hardware decoding):

| | JPEG (quality 80) | H.264 |
|---|---|---|
| Dragging the list (one move every ~8 ms) | 3.1 MB/s, 82 fps | 0.08 MB/s, 72 fps |
| Shader tab (full-screen noise, 120 fps) | 11.3 MB/s, 96 KB a frame | 0.50 MB/s, ~4 KB a frame |
| Average frame over a session | 72 KB | 3.5 KB (the first keyframe ~10-40 KB) |
| Encode, phone | 3.7 ms | 5.3-5.6 ms (VideoToolbox, waited for) |
| Encode, tablet 2560x1600 | ~12 ms | ~6 ms |
| Page in Chromium, Shader tab | | 120 fps drawn, frame presented → on screen 7 ms |

- 40x less while scrolling, 20x less on the animation. Text and edges on still screens come out as sharp as JPEG's
  (41.7 dB PSNR against a screenshot after a scroll, with the two sharpening passes); fine noise at 120 fps is
  visibly blockier at 4 Mbps (`--bitrate` raises it).
- VideoToolbox's encode is waited for on the encoder thread, ~2 ms longer than JPEG on a phone screen, which costs
  some frames while dragging (72 against 82 fps); on a tablet screen it is half JPEG's time.
- Linux: OpenH264 in software (the image, `SCREEN_CONTENT_REAL_TIME`), from CI's run of the check (x86-64 image on
  a GitHub runner, 2026-11-08): dragging 3.1 MB/s, 75 fps with JPEG, against 0.07 MB/s, 51 fps with H.264; encode
  17 ms median (JPEG 8 ms). The Shader tab runs on the CPU there (a few frames a second), so it says nothing about
  the stream. The encode is single-threaded (`iMultipleThreadIdc` 1); not measured on Colima here.
- The latency figures in the tab (and the check) compare the instance's clock with the tab's after matching them
  over the WebSocket (the quickest of a few round trips), so they hold across machines.
