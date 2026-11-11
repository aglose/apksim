# Trace viewer: a flight recording or a failure bundle in a browser tab

One static page, `runtime/src/main/resources/dev/apksim/serve/trace-viewer.html` (inside `apksim.jar`, beside
`serve.html`). It has no server, no external script, font or request. It opens a `.apkrec` flight recording
(docs/flight-recorder.md) or a zipped failure bundle from a test run (`--bundles`, docs/plan.md phase 5) by file
picker or drag and drop, and reads the zip in the browser (central directory, then each entry on demand;
`DecompressionStream` for deflate).

```
apksim rec view session.apkrec     # serves the page and that file on a loopback port, opens the default browser
apksim rec view                    # the page alone: drop a file on it
apksim rec view --no-open FILE     # prints the URL only
```

`rec view` is `serve/TraceView`, a plain-JVM class (like `serve` and `skin`): it binds 127.0.0.1 on a random port,
answers only a `Host` of its own address (DNS rebinding), hands the page one file under an unguessable path, and exits
once the page has been quiet for 2 minutes (`--linger`). The page also works from `file://`, or from any static host.
**Where it lives:** inside the jar because releases already ship the jar and `serve` serves its siblings from there
(the serve lane can add a route with `getResourceAsStream("trace-viewer.html")`); a `tools/` folder would have needed
a change in `package-release.sh` and the Homebrew cask.

## What it shows

Selecting a moment (click or drag on the timeline, click a row in any list, Left/Right for a frame) lines every pane
up on it.

- **Timeline**: a canvas with rows for input (blue), network exchanges as bars (green), warnings and errors (amber, red),
  SQLite calls (purple) and checkpoints (orange).
- **Screen**: the frame at that moment, decoded on demand from `screen.avi` (the page indexes its `00dc` chunks and
  makes a blob only for the frame shown; 40 are cached). Touches within the last 600 ms are drawn on it at their
  position (device pixels from the manifest), the ones inside the frame's own 200 ms slot highlighted.
- **Input**: every event and device command, with its time from the window's start.
- **Network**: the list, then on click the request, the response headers and body (JSON pretty-printed, images shown,
  text cut at 1 MB), and the request body.
- **Logcat**: level, tag and text filters with a count. The log has no year or zone, so the page puts it where the
  window is (the nearest quarter-hour offset); a viewer in another zone sees the same alignment.
- **SQLite**, **Checkpoints** (activity stack, device, why), **Manifest**.
- A failure bundle: `failure.txt`, `screen.png`, `windows.txt`, `nodes.txt`, `threads.txt`, `looper.txt`, `logcat.txt`
  (same filters), `repro.sh`, `process.txt`. A zip of several bundles gets a selector.

Every list is virtual: only the visible rows exist in the DOM, so 50,000 log lines or 20,000 input events cost the
same as 50.

## Untrusted input

A recording can hold the app's network calls, its logs, its screen and what was typed (docs/flight-recorder.md
"Privacy"), and the page says so at the top. Everything read from the file reaches the DOM through `textContent` or a
blob URL; there is no `innerHTML`, `eval`, or handler built from data. A `<meta>` CSP allows only the page's own script and
style by SHA-256 hash (`default-src 'none'`; images from `blob:`; `connect-src 'self'` for `rec view`). After editing
the script or style run `scripts/trace-viewer-check.py --update-csp`; the check (and CI's smoke job, `--csp-only`)
fails if the hashes are stale. Limits: zip64 is refused; the log's zone is a guess; the page reads the whole `.avi`
and `network.har` into memory (a full 64 MB screen ring is the largest part).

## Check

`scripts/trace-viewer-check.py APP.apk TEST.apk [OUT]` makes a recording with `scripts/rec-check.py` and a bundle with
`instrument.py --bundles` (the test app's `FailureBundleTest`), then, in headless Chrome over the DevTools protocol:
the recording through `apksim rec view`, the bundle through the file picker on `file://`, asserting frames, a touch
marker on the frame, a network row with headers and body, log lines and filters, SQLite calls, the checkpoint, the
bundle's tabs, no CSP violation, a hostile recording (markup in the package, a log line, a URL and a body) that
renders as text and runs nothing, and a large synthetic one. Without Chrome it parses both formats with a short
script.

Measured on this Mac (Chrome, headless, one run, other sessions running, so read as an order of magnitude):

| File | Size | Content | Load to timeline |
|---|---|---|---|
| Real recording (test app, `--rec all`) | 201 KB | 90 frames, 14 inputs, 2 exchanges, 62 log lines, 10 SQL calls | 29 ms |
| Synthetic large | 659 KB zipped | 3,000 frames, 20,000 inputs, 50,000 log lines | 519 ms; a scrub step 8 ms |

Not measured: a recording near its rings' budgets (64 MB of frames and bodies).

## Hooks for other lanes

- `serve/`: a route that returns the page needs `Http` to send `getResourceAsStream("trace-viewer.html")` as
  `text/html` (the way `serve.html` is sent); a recording's download link can then open `…/trace-viewer.html?src=URL`
  if the page's `connect-src` is widened to that origin (today `'self'`, and `src` must be `/rec/<token>`).
- `scripts/instrument.py`'s `index.html` of bundles: zip each bundle's folder and link it, or give the viewer the folder
  zipped; the one-line change is writing `<bundle>.zip` beside the folder and linking it with "open in the trace viewer".
