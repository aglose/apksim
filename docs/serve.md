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
             [--max-upload MB] [--quality Q] [--no-network]
```

- `--port` (default 8080) and `--bind` (default `127.0.0.1`). Binding anywhere but loopback needs `--token`, or
  serve refuses to start.
- `--token FILE`: a shared secret, read from the file (first line). With it, every request must carry it: the page
  is opened once as `/?token=...`, which sets an HttpOnly cookie and redirects to strip it from the URL; scripts use
  `Authorization: Bearer ...`.
- `--max N` (default 4): sessions at once. One more gets "all N sessions are busy".
- `--idle SECONDS` (default 600): a session with no input for that long is stopped and its tab told so.
- `--apps`: the catalog. A folder lists its `*.apk` files; a file lists one APK per line, a path or an `https://`
  URL (downloaded once, at first use), optionally followed by a display name. Repeatable.
- `--max-upload MB` (default 300): uploads larger than this are refused before they are stored.
- `--quality Q` (default 80): the JPEG quality of the stream.
- `--no-network`: instances run with `--no-network` (Mac only, as for a launch).

### Sessions

- One session is one browser tab and one apksim instance: the APK, the device (phone, foldable, tablet, desktop,
  TV), appearance, locale and font scale the tab asked for, and optionally a deep link opened once the app is up.
- The instance is a normal launch: `apksim --headless --fresh --no-autofill --control auto [options] APK`
  (`--no-autofill`: never the owner's saved sign-ins from `work/autofill`, which would sign a visitor in as them). On the Mac it is taken
  by the warm-launch standby when one is ready (serve starts one at its own start, and each instance starts the next
  after its first frame); on Linux there are no standbys, so each session is a cold launch.
- Each instance takes its own app data slot (`default`, `instance-N`), emptied first (`--fresh`), and serve deletes
  the slot's contents when the session ends, so one session never sees another's data through the app.
- A session ends when its tab closes (WebSocket closed), on `--idle`, or when the app exits (Back on its last
  activity). The instance is asked to `quit`; if it is still running 5 s later, it is killed.

### Stream

- Frames are JPEG, encoded in the instance with Android's encoder from the frame the Host presents (the raster
  thread's buffer, as the AVI recorder takes it), sent only when a new frame is presented and differs from the last
  one sent.
- The instance sends them over its control channel: a `stream [QUALITY]` command turns that connection into a frame
  stream (loopback only, like every control connection). Serve relays them to the tab over a WebSocket.
- Flow control: serve keeps at most two frames in flight to a tab, and the tab acknowledges each frame once drawn.
  A slow tab or network sees fewer frames, never older ones: frames that arrive meanwhile replace the waiting one.
- Each frame carries the time it was presented and how long it took to encode, so the tab can show the stream's fps,
  present→screen delay and input→screen latency.

### Input

The tab sends commands in the control channel's own words, and serve passes on only these, with checked arguments:

| Browser | Control command |
|---|---|
| pointer down / drag / up / cancel (mouse, pen, touch) | `touch down\|move\|up\|cancel X Y` (moves at most one per animation frame) |
| wheel, trackpad scroll | `wheel X Y AMOUNT` (summed per animation frame) |
| printable keys, paste, phone keyboards (through a hidden text field: IME, dictation) | `type TEXT`, `key SPACE` |
| a phone keyboard's delete or return (input events with no key) | `key DEL`, `key FORWARD_DEL`, `key ENTER` |
| Backspace, Delete, Enter, Tab, arrows, Home/End, Page Up/Down; Ctrl/Cmd and Shift with them | `key NAME 1 [ctrl] [shift] [alt]` |
| Escape, Back button | `back` (Shift+Esc instead returns the keyboard to the page's own controls) |
| Home button / click on the home screen | `home` / `resume` |
| Rotate buttons | `rotate left\|right` |
| Dark mode toggle | `appearance dark\|light` |
| Device menu | `device phone\|foldable\|tablet\|desktop\|tv` |
| Open URL field | `open URL` (a URL without spaces) |
| Locale, text size | `locale xx-YY`, `fontscale N` |

Anything else (`shot FILE`, `record`, `pick FILE`, `quit`, ...) never reaches an instance from a tab: those read or
write files on the machine.

"Copy link" puts a URL on the clipboard that opens a new session in the same state: the app, device, appearance,
locale, text size and the last deep link opened. It does not carry what the app did since (navigation inside the app
that no deep link names, data entered).

### Status

- `GET /api/status`: limits, the standby (Mac), and each session: app, device, PID, resident memory (RSS), Java heap
  in use, time to first frame, idle time, frames sent.
- The tab shows its own session's numbers in a bar under the screen, and the stream's fps and latency.

### Security

- Loopback by default; any other bind address needs `--token`. With a token, every request needs it (cookie,
  `Authorization: Bearer`, or the one-time `?token=` link).
- Instances' control channels stay on loopback, and serve never starts them with `--adb` (the adb device has no AUTH).
  In a container, only serve's port is published.
- The page cannot be framed by another site (`frame-ancestors 'none'`, `X-Frame-Options: DENY`): no clickjacking of
  its controls.
- What a visitor types is not kept: the instance logs `type (N characters)`, not the text (passwords, pasted tokens).
- WebSocket upgrades and POSTs must come from serve's own page: an `Origin` other than serve's own is refused (a web
  page elsewhere cannot drive a local session), and on a loopback bind the `Host` header must name a loopback address
  (DNS rebinding).
- Uploads: at most `--max-upload` MB, must be a ZIP (an APK), stored by content hash under `STATE/serve/uploads`,
  reachable only through an unguessable id that is not listed anywhere (only the uploader's tab, and links it copies,
  know it). Uploads unused for a day are deleted.
- The app data slot is erased at the end of a session (above). Apps run in apksim's sandbox on the Mac
  (`sandbox-exec`: no writes outside apksim's state folder, no reads of credentials), and the container is the
  sandbox on Linux. This keeps sessions' data apart for ordinary apps; it is not a boundary between hostile APKs run
  by different people on one machine, which can read the state folder: give each trust boundary its own serve (its
  own container).

### Not in scope (yet)

- Audio, camera, the soft keyboard (a hardware keyboard counts as connected, as in the Mac window), two-finger
  gestures from the browser, file pickers (`pick`), multiple tabs sharing one session.
- A pool of more than one warm standby (one per apksim folder today, refilled ~2 s after each launch: a second
  session within that time launches cold).
- Video codecs for the stream (H.264 over WebCodecs would cut bandwidth several-fold on busy screens).

## How it is built

- `dev.apksim.serve.Serve` (in `apksim.jar`, run by the launcher's `serve` subcommand on a plain JVM, no
  Robolectric): an HTTP/1.1 server with WebSocket (RFC 6455) written on `java.net` sockets, one virtual thread per
  connection. `serve.html` (a resource in the jar) is the whole page: no external scripts, styles or fonts.
- `Session`: starts `apksim` as a child process, reads `[apksim] control port N` from its stderr (the rest goes to
  `STATE/serve/logs/<session>.log`), opens two control connections (commands, pipelined; and the stream), and
  relays both ways.
- In the instance: `FrameStream` (raster thread → latest frame → encoder thread → each subscriber), `Control` serves
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
checking that frames follow each, and that closing the tab ends the session. It prints the numbers below.
`scripts/ci-smoke.sh` runs it against the Linux image with a token on a non-loopback bind, as CI does.

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
- The latency figures in the tab (and the check) compare the instance's clock with the tab's after matching them
  over the WebSocket (the quickest of a few round trips), so they hold across machines.
