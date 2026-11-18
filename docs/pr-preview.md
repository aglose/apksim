# Previews and compare mode for pull requests

A reviewer should be able to open a pull request, try its build in a browser tab, and see what it changed next to the build
it replaces. This page covers the two pieces that do that on top of `apksim serve` (docs/serve.md): **compare mode**, a page
with two apps side by side and the pixels that differ, and **previews**, a script and a GitHub Action that put a pull
request's build on a server and keep one comment on the pull request with the links.

## Compare mode

`/?compare=A,B` (two app ids: a catalog's, or an upload's or URL add's) starts a session for each and shows them side by side
with a third screen that marks the pixels that differ.

```
http://localhost:8080/?compare=BASE_ID,NEW_ID&labels=Base,PR&appearance=dark&device=tablet
```

- **Two sessions, in one tab.** Each is an ordinary session (`/ws?app=ID`): it takes a place under `--max` and its own data
  slot, runs a launch with the app's own launch options, and is erased when it ends. The server does not know they belong
  together; the page does. A server with room for one session says so (`a comparison needs two sessions...`) and starts
  neither. The page's other query parameters, `device`, `appearance`, `locale`, `fontscale`, `open`, `orientation`, apply to
  both. `labels=A,B` names the two columns; `codec=jpeg` keeps the page off H.264.
- **Input goes to both.** A tap, drag, scroll, key, text, Back, Home, rotation, dark mode, device, text size, locale or deep
  link goes to both sessions, from either screen (or the third). A pointer position is a fraction of the screen it is over,
  and each session turns it into its own pixels, so two devices of different shapes get the same touch at the same relative
  place. `Drive: A only` / `B only` sends to one side, to bring screens that have drifted apart back in line. Screens that
  drift apart (a different flow in each build, a timing difference) are shown as they are, not resynchronised.
- **One ends, both end.** When either session ends (the app exits, it crashes, it idles out, its socket closes), the page
  closes the other and says which ended. "Start again" starts both. Input goes to both, so both stay awake together, except
  while one side is the only one driven: the other then idles out after `--idle` seconds, which ends the comparison.
- **The difference.** The page decodes each stream itself (JPEG, or H.264 with WebCodecs, as the main page does), so both
  codecs work, and compares the two canvases pixel by pixel. A pixel counts when a colour channel differs by more than the
  threshold (default 16; the menu has exact, 8, 16 and 32), because the stream is lossy. The third screen shows the count's
  pixels in red over a dimmed copy of A, B or nothing, with a yellow box around all of them, and the footer gives
  "N of TOTAL pixels differ (P%)". Two screens of different sizes (one side on a tablet) are reported as that and not compared.
- **A moving screen has no meaningful count.** The count is for the two frames on screen now, so it is right once both
  have settled. H.264 sharpens a still screen with two more frames 120 ms after the last change: two identical screens
  showed 63 differing pixels before the sharpening and 0 after (threshold 16).
- Scripts read `window.apksimCompare(recompute)`: each side's app, frames, size and codec, and `diff`
  (`count`, `total`, `pct`, `box`, `sameSize`).

## Previews

`scripts/pr-preview.py` (standard library only) puts a pull request's APK on a running `apksim serve` and prints, or posts,
one comment:

```
scripts/pr-preview.py --serve https://sim.example.com --apk app-debug.apk --base-apk base-debug.apk \
    --name "Fix the login screen" --repo OWNER/NAME --pr 123          # GITHUB_TOKEN and APKSIM_SERVE_TOKEN in the environment
scripts/pr-preview.py --serve https://sim.example.com --base-apk x --dry-run   # the comment, with placeholder ids; adds nothing
```

1. It adds the build (`POST /api/upload`, or `POST /api/apps` for `--apk-url`, which the server downloads) and, with
   `--base-apk` / `--base-apk-url`, the base build, each **with a time to live** (below), and waits until the server has them.
2. It writes the comment: links to the build, to the two next to each other (`/?compare=BASE,NEW`), and the dark-mode and
   tablet variant of each.
3. With `--repo` and `--pr` it posts the comment, or updates the one it posted before. The comment carries a hidden marker
   (`<!-- apksim-preview:KEY -->`, `--key`), so a pull request has one comment however often it is pushed to, and several apps or
   jobs can each keep theirs. Without them it prints the comment.

`--public-url` is the address the links use when it differs from `--serve` (the runner reaches the server directly, reviewers
through the proxy). `--option` gives the build launch options of the server's own list (docs/serve.md "Launch options per app"),
with `--apk-url` only: an upload carries none.

### Time to live

A build added for a preview expires. `POST /api/apps` takes `"ttl": SECONDS` and `POST /api/upload` a `?ttl=SECONDS` query
(60 to 604800, else `400`). After that time `find` no longer knows the id (new sessions, `GET /api/apps/ID`: `404`) and the hourly
sweep deletes it and its APK, whether it was used or not: an ordinary id lives a day past its last use. Sessions already running
go on, and the stored APK stays while one (or a warm-up) uses it: a running app may open its APK again by path (`scripts/check-ttl.sh`, CI). `GET /api/apps/ID` has `expiresInS` while one is set. The deadline is a file beside the id's (`ID.expires`), so a
restart keeps it. `pr-preview.py --ttl` (default 3 days) sets it, and the comment says when the builds expire; a new push
adds new builds and rewrites the same comment.

### Credentials: none in the comment, none in a link

The server's token never appears in a comment or a link: the script reads it from `APKSIM_SERVE_TOKEN` or `--token-file`, sends it
only to `--serve` as a bearer token, and refuses to post a comment that contains it. The links are plain: they work for a reviewer
who is already let in. That is the job of whatever puts reviewers in front of serve ("Behind a reverse proxy" in docs/serve.md): a
proxy with the team's single sign-in that, after it, adds `Authorization: Bearer TOKEN` to what it forwards (nginx
`proxy_set_header Authorization "Bearer ..."`, an OAuth proxy's upstream headers), and with `--trust-proxy`, `--public-origin` and
`--base-path` as that page describes. Reviewers then never see the token, and a link someone pastes elsewhere opens nothing for a person
the proxy does not let in. A server open to the internet with only a token should not take previews.

**A narrower credential, if one is wanted (not built).** A reviewer's link could carry a *viewer* credential in place of the
proxy: an HMAC over an app id pair and an expiry, made by the server for a build when it is added (the way a beam ticket is made,
docs/beam.md), accepted only for `GET /`, `/ws` and `/api/apps/ID` of exactly those ids, and for nothing else: no upload, no
add, no `/api/status`, no logs, no recordings, no kept data. It would expire with the build, and still reach only a session's screen
and input. It would put a bearer secret in a pull request's comment (anyone who can read the pull request could use it), so it is only
right for a private repository whose readers are the reviewers. Until that choice is made, previews rely on the proxy.

## Report button and network inspector

Two more things for the person trying a build in a tab, both off unless the server allows them, and neither
posts anything with credentials of the server's own.

### Report a problem (`--allow-rec`, `--issue-url TEMPLATE`)

With `--allow-rec` the page starts the flight recorder's `input` and `logcat` channels when a session begins and shows a
**Report a problem** card (`?report=0` in the page's address leaves both out). A note and **Download report** make one zip in
the browser:

| file | what |
|---|---|
| `note.txt` | what the reviewer typed |
| `settings.json` | app, session id, device, appearance, locale, text size, last deep link, screen size, stream, time, the page's address (no token) |
| `screenshot.png` | the screen as drawn |
| `recording.apkrec` | `rec save 300`: the last five minutes of input and log and a checkpoint, which is **a copy of the app's data** (databases, preferences, files: sign-in state too) (docs/flight-recorder.md), served by `GET /api/sessions/ID/recordings/RID` as before. The card says so and has a box to leave it out |
| `network.json` | the exchanges the inspector listed, when it is on |

With `--issue-url 'https://github.com/OWNER/REPO/issues/new?title={title}&body={body}'` the page then opens that address in
a new tab of the reviewer's own browser (opened at the click and pointed at the address once the report is made; a blocked tab leaves a link), with `{title}` the note's first line and `{body}` the note and the settings in a few
lines, URL-encoded (at most 4,000 characters), and says to attach the zip there. The template is checked at the server's start: https,
no credentials, a `{title}` or `{body}`. The server only announces it in the session's `i` message (`rec`, `issueUrl`); the page does
the rest, in the reviewer's session, so there is no token or account of the server's in any request to a tracker. The recording
holds the app's logs and a copy of its data: treat the zip like the token.

### Network inspector (`--inspect-network`)

Off by default, because it shows the app's traffic. With it, instances run with `--network-inspect`, which keeps the network
tap's in-memory capture on (as `rec on net`: docs/network.md "Two mechanisms") and reports; the page shows a **Network** card
that opens a panel. Looking changes nothing for the app: its connections are its own, over the protocol its servers pick
(HTTP/2, gRPC), and only read as they pass. A **rule** is the one thing that puts connections through the network engine, and
only those to the host its pattern names (`https://api.example.com/v1/*` concerns `api.example.com`; a pattern that names no
host, such as `/v1/items`, concerns every host): those hosts are then spoken to over HTTP/1.1 while the rule exists, and adding
or removing a rule closes the connections it concerns, so the app reconnects the other way. The panel:

- **Requests**, as they happen: time, method, status, URL, milliseconds (`*`: a rule added some), a hint with sizes and the rule that
  answered. A request that failed is a row with status 0 and the reason (a reset stream, a timeout, a connection closed early), as is
  a connection that could not be made (`DNS`/`CONNECT`, status 0) and one that is not HTTP (`CONNECT`, with its byte counts). Rows over
  HTTP/2 say so (`"http": "HTTP/2"` in the event; gRPC's trailers are in the detail). Click one for its headers and
  bodies (at most 256 KB of each, binary shown as a size; a row shows 1,500 characters of a long URL, the detail all of it; rows are buttons a keyboard reaches; replayed answers are rows too). Headers are redacted exactly as a recording's are (`Authorization`,
  `Cookie`, ... and `--network-redact`); bodies and URLs are as sent.
- **Rules**: a method (or any), a URL pattern (part of the address, `*` for anything), and a status (200-599) with a content type and body
  (512 KB at most), a latency in ms, or both. A request a rule takes is answered by the engine with that status and body, after the latency,
  and never reaches the server: the app sees the rule's answer, and the row says which rule. A latency alone slows the real exchange.
  While a rule with a status exists, a host that does not resolve or answer (a backend not deployed yet) is still answered by it.
  Rules are per session, 32 at most (`serve --inspect-max-rules N`, 1 to 256; the session's `i` message says it as
  `inspectMaxRules`, and the panel shows "3 of 32"; the rules' bodies are 16 MB together at most, whatever the limit), applied in the order added. A rule that answers may carry response headers
  beside its content type (`ETag`, `Retry-After`, a pagination or debug header: 20 at most, 2 KB together, printable ASCII); the
  names the engine writes itself are refused (`Content-Length`, `Transfer-Encoding`, `Content-Type`, `Connection`, `Keep-Alive`,
  `Upgrade`, `Trailer`, `TE`, `Proxy-*`, `X-Apksim-Rule`). In recordings and details a rule's `Set-Cookie` is redacted as a server's is.
- **Forward rules** (no panel form yet: the tab's `inspect rule add` and `inspect rule set` only): instead of a fixed status and body, a matched request is
  sent to an upstream http(s) URL and that server's answer (status, headers, body) goes back to the app, so a page can run a
  stateful mock per request. The upstream gets the request at the rule's URL exactly, with the app's method, body (16 MB at most) and
  headers, less the ones recordings redact (`Authorization`, `Cookie`, ...) and the connection's own (`Host`, `Content-Length`, ...),
  plus `X-Apksim-Original-Url` with the address the app asked for (path and query included). With `rebase` (issue 427; `inspect rule add METHOD PATTERN forward LATENCY_MS rebase URL64`, or `"rebase": true` beside `"forward"` in a set) the
  target is a base instead: the request goes to its scheme, host, port and path followed by the app's own path and query, so
  pattern `https://api.example.com/v1/items/*` with target `http://127.0.0.1:8001/mock` sends `/v1/items/42?x=1` to
  `http://127.0.0.1:8001/mock/v1/items/42?x=1`, for an upstream that routes on the path. The allow-list judges the base, and an app path that
  the list could not judge (dot segments, `//`, `;`, `\\`, an encoded `.`, `/` or `\\`) is a 502, so what is sent is always under an allowed prefix; a
  rebase target has no query. `Host` is the target's in either mode (the client sets it), so a server that routes on it sees its own name. Redirects are not followed (the app gets
  the 3xx). An upstream that cannot be reached, does not answer within 30 s, or whose answer is over 16 MB or takes over 60 s to arrive, is a 502 to the app whose body and
  `X-Apksim-Error` header say why. The row has `"forwarded":true` beside `rule`. The instance sends the request itself, so the
  target must be reachable from where instances run, and the server must allow it: `serve --inspect-forward-allow URL_PREFIX`
  (repeatable; none by default, so a tab cannot use the server as a proxy into its network). A prefix is an http(s) URL; a target
  needs its scheme, host and port exactly and its path or one below it (`https://mocks.internal/api` allows `/api/answer?x=1`, not
  `/apix`); targets with user info, a fragment, `..`, `//`, `;` or an encoded dot or slash in the path are refused. Not allowed: `n r
  rule error: forward target not allowed (...)`. Not done: the opt-in to send redacted headers on, and upstreams by name while replaying or for a host `--network-host` maps: such a name resolves to a placeholder here, so the
  rule answers 502 at once (as for a name that does not resolve); an IP address or localhost target works. CI does not run the
  forward round trip (its image check runs a server without the inspector): `inspector-check.py --forward-port` is run by hand.
  In an `apksim beam` session (issue 426, docs/beam.md "Forward rules") the list is the Mac's owner's, `apksim beam config
  forward-allow URL_PREFIX`, and the agent sends the request, not the sandboxed instance, so a backend on that Mac's
  loopback can be the target; bodies are limited to 4 MB each way there.

Wire: the instance gets `netwatch` on a control connection of its own (`dev.apksim.net.Inspector`: the last 300 exchanges, then
each as one JSON line); the server relays them to the tab as `n e {json}`. A tab may turn that down (issue 498, opt-in: a socket that
never sends it gets `n e` as before): `inspect watch off` stops the per-exchange messages, the server still capturing and sending
`n c {"total": N, "errors": M}` (every exchange since the session began; errors are those with an `error` or a status of 0 or 400-599)
at most once a second while the count changes; `inspect watch on` sends the last 300 exchanges not yet sent to the tab (by id), then everything that arrived,
as `n b [{json}, ...]` every 250 ms (a long backlog is a few messages of ~200 KB). The first `inspect watch ...` makes the socket's
delivery batched, `on` or `off`. Rules apply either way. The tab's commands are `inspect rule add METHOD PATTERN
STATUS LATENCY_MS TYPE BODY64 [HEADERS64]` (HEADERS64: base64 of `Name: value` lines), `inspect rule set JSON`, `inspect rule
remove ID|clear|list` and `inspect detail ID`, which the server checks (a
pattern is 1-300 URL characters, a status 0 or 200-599, a body base64) and passes on as `network rule ...` / `network detail ID`;
answers are `n r rule|detail ...`. `inspect rule set [{"method", "pattern", "status", "latencyMs", "type", "body" (base64),
"headers": [[name, value], ...]}, ...]` (all but the pattern optional; a field of the wrong kind or an unknown name is refused) replaces every rule of the session with those, in that
order, in one step, and answers the new list (new ids, hit counts from 0): the instance swaps one immutable list for another, so
a request is matched against the old rules or the new ones, never some of each, and a set with a rule that is refused (`n r rule
error: rule 2: ...`) changes nothing. The whole command is one message of at most 800,000 characters, bodies included. A forward rule is `inspect rule add METHOD PATTERN forward LATENCY_MS - URL64` (`rebase` for `-`: see above; the target URL in
base64 where a body would be), or in a set `{"pattern", "forward": URL}` with an optional `"rebase": true`, method and latencyMs and nothing else; the instance's own
`network rule add|set` takes the same and checks only that it is an http(s) URL: the allow-list is the server's. A patch rule (issue 373) is `inspect rule add METHOD PATTERN patch LATENCY_MS - BODY64` (BODY64: base64 of a JSON merge patch, RFC 7386), or in
a set `{"pattern", "patch": "JSON text"}` with an optional method and latencyMs and nothing else. The request goes to the real server; when the
answer is 2xx with a JSON content type (`application/json`, `+json`), whole or gzip-encoded, it is parsed, merged with the patch (an object merges into
an object, `null` removes a member, anything else replaces, arrays whole), written back compact with a `Content-Length` of its own, no
`Content-Encoding` (and no `Content-MD5`/`Digest`) and `X-Apksim-Rule`, and the app gets that; numbers are written as they came (64-bit ids
survive). Anything else is relayed as it came: a non-2xx or non-JSON answer, a brotli or other encoding, a body that does not parse, a body
over 8 MB (held in memory to be patched); the exchange's row says why in `patchSkipped`, and has `"patched":true` when it was patched. The detail and a
recording hold what the app got. A chunked answer is relayed with a `Content-Length` once read whole (a skipped one too, unless over the limit,
which goes on chunk for chunk). It works where the server's answer is real: not replaying (the row says the answer came from the recording), and the
rule needs no allow-list since it reaches only the app's own server. Nothing in them names a file (`Session.check` is unchanged: `inspect` and `rec` are their own
messages). Without `--inspect-network` the tab's `inspect` is refused (`r refused: inspect`), as it is in a session that did not ask when the server runs
with `--inspect-network optional` (issue 371: `/ws?...&inspect=1` asks; the beam agent's `inspect-network optional` and a start's `"inspect": true` do the
same there, and `/beam/hello` says `"inspect": "off|optional|on"` for an agent that has it). The `i` message's `"inspectAllowed"` says whether a session could have it. Where the inspector is on for every session (`--inspect-network`, the agent's `inspect-network on`) a session
may decline it (issue 389: `/ws?...&inspect=0`, a start's `"inspect": false`; `/beam/hello` says `"inspectOptOut": true`) and runs with no network engine.

Rules sent as soon as the session opens (issue 445) are held with the tab's other early commands and, once the instance's control port is up, applied in order on the control connection's own thread, not through Host's loop: they are in place before the Application and the first activity exist, so the app's first requests meet every rule (`scripts/inspector-early-rules-check.py APK`, by hand). `Session` sends them ahead of its `stats`, as the connection reads one line at a time and `stats` waits for the loop. The other `network` and device commands still wait for the loop. These rule commands are not logged as `control` lines and not kept by the flight recorder, so a replay of a recording does not re-apply them.

## Sample workflow

`.github/actions/preview` wraps the script. A pull request job that builds the debug APKs of the head and the base and posts the
comment:

```yaml
name: preview
on:
  pull_request:
jobs:
  preview:
    runs-on: ubuntu-latest
    permissions:
      contents: read          # listing explicit permissions drops the default: checkout of a private repository needs this
      pull-requests: write
    steps:
      - uses: actions/checkout@v4
        with: { fetch-depth: 0 }
      - name: Build the pull request and its base
        run: |
          ./gradlew :app:assembleDebug && cp app/build/outputs/apk/debug/app-debug.apk "$RUNNER_TEMP/pr.apk"
          git checkout --quiet "${{ github.event.pull_request.base.sha }}"
          ./gradlew :app:assembleDebug && cp app/build/outputs/apk/debug/app-debug.apk "$RUNNER_TEMP/base.apk"
      - uses: aglose/apksim/.github/actions/preview@main
        with:
          serve-url: https://sim.example.com            # as the runner reaches it
          public-url: https://sim.example.com/preview   # as reviewers do (optional)
          token: ${{ secrets.APKSIM_SERVE_TOKEN }}
          apk: ${{ runner.temp }}/pr.apk
          base-apk: ${{ runner.temp }}/base.apk
```

A pull request from a fork has no secrets: the job posts nothing there. The action passes everything through environment
variables, never on a command line.

## Checked

`scripts/compare-check.py URL [--token FILE] [--apk APK] [--app2 ID]`, against the test app (`ci-smoke.sh` runs its
protocol layer against the Linux image). It checks, without a browser: `/?compare=A,B` is served and a malformed one refused;
two sessions of one app, driven as the page drives them, show the same bytes in their frames, and still after a mirrored tap on the
FAB; one session dark gives different frames and both dark the same again; the two count as two sessions; closing the sockets
ends them; a ttl outside its range is refused and one inside reports `expiresInS` (with `--wait-expiry` the id is `404` after its
60 s); `pr-preview.py --dry-run` prints the comment, and against the server and a stand-in GitHub it adds both builds, posts one
comment, updates that comment on a second run and leaves no token in it. With `--chrome` (a Mac with Chrome, over the DevTools
protocol) it checks the page's own count, for JPEG and for H.264:

| Chrome, test app, 822x1782 | JPEG | H.264 |
|---|---|---|
| the same app twice, settled | 0 of 1,464,804 pixels | 0 |
| after a tap on the FAB (on either screen) | 0, both sessions got a new frame | 0 |
| A dark, B light | 1,464,030 (box 0,0 to 821,1781) | 1,463,998 |
| both dark | 0 | 0 |
| A on a tablet, B on a phone | reported as different sizes | the same |
| two apps, one with `--status-bar off` | 312,186 (box 24,47 to 797,1575) | |
| one session's socket closed | the comparison ends, no session left on the server | the same |

The count costs ~17-26 ms on a phone-sized screen, at most every 100 ms and only when a frame arrived.

`scripts/inspector-check.py URL` checks the report and the inspector against a server started with `--allow-rec --inspect-network
--issue-url ...` (and, against one without, that the tab's commands are refused; with `--forward-port N` and a server on the same
machine started with `--inspect-forward-allow http://127.0.0.1:N/mock`, it is also the upstream of a forward rule). On the test app's network screen (its server does
not exist): Fetch gives a `DNS` row with status 0; a rule that answers `POST /v1/greeting` with 200, a JSON body and 600 ms makes the
same tap an exchange the rule answered (27 bytes, 600 ms, all of it the rule's latency, nothing sent), whose detail has the request's
`Authorization` as `REDACTED` and the bodies; a rule count of hits; status 999, a space in a pattern, bad base64, a path and a
command naming a file are refused; a `rule set` leaves exactly its rules in order, its rule's headers reach the answer,
`Content-Length` and `Transfer-Encoding` are refused, and the limit the `i` message gives holds for `set` and `add`; `rec save` gives a recording the server serves (3.8 KB for a minute of the test app's input and
log). With `--chrome` the page: the cards show, a rule typed into the panel is listed and answers the app's request, and the report
button downloads one zip whose every CRC tests sound, with the note, a PNG, the recording (itself a zip), the settings and the
network list, and opens the filled-in issue address with the note as its title.

Not done: more than two apps, a slider or onion-skin view, perceptual (not pixel) differences, resynchronising screens that
drifted apart, the viewer credential above, rules that match a request's headers or body, rewriting a real server's answer other than a JSON merge patch (below; a status rule replaces it whole), WebSocket and non-HTTP traffic in the inspector (as in recordings), and a compare mode for sessions on a visitor's Mac (`apksim beam`): both run on the
server.
