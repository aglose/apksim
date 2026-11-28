# Replay: a flight recording played again inside apksim

`apksim replay BUNDLE.apkrec` starts the recorded app from the bundle's checkpoint and plays what the user did from
there, with the network answered from the recording. It says where the screen first differed from the recorded one,
or that it never did, and leaves the app running for a person to carry on with `--control`. The recording side is
[flight-recorder.md](flight-recorder.md); replay on a device over adb is `scripts/replay-adb.py` (also there).

```
apksim replay session.apkrec                                  # window, the recorded build, network from the HAR
apksim replay --headless session.apkrec; echo $?              # 0 matched, 3 diverged, 10 crashed, 2 cannot replay
apksim replay --control auto session.apkrec                   # stays up afterwards: `replay` reports, then it is yours
apksim replay --apk app-new.apk session.apkrec                # another build of the app (same signer)
apksim replay --live-network --compare end session.apkrec
apksim replay --loose session.apkrec                          # query values made at run time still get their answers
```

Options of its own, anywhere before the bundle: `--apk APK`, `--live-network` (the network goes out as usual instead
of answering from `network.har`), `--loose` ("A session with a sign-in" below), `--out DIR` (the report; default `work/replays/<bundle>-<time>/`, or
`~/.apksim/replays` for an installed release), `--compare all|end|none` and `--threshold N`. Every other option is an
ordinary launch option and is passed on after the replay's own, so it wins: `--headless`, `--control auto`,
`--network-trust CERT` (a server the app reached with a certificate apksim was told to trust while recording),
`--feature`, `--device`, `--jvm`...

## What it does

**Before the app starts** (`ReplayPlan`, on a plain JVM, as `apksim skin` runs; then the launcher runs the launch it
prints):

1. Reads `manifest.json`. A bundle without a checkpoint is refused (exit 2): there is nothing to start from.
2. Finds the APK: `--apk`, else the path the manifest recorded (`apkPath`, new), else a file of the recorded name
   beside the bundle or in the working folder. Without `--apk` it must be the recorded build (`apkSha256`). With
   `--apk`, another build is taken only from the recorded build's signer (`apkSigners`, new in the manifest, compared
   by certificate whatever the signature scheme; for an older bundle the recorded build's own signer, so that build
   must be found), the rule Android applies to an update that keeps the app's data and `apksim beam` applies to data
   from another build (`serve/ApkSigners`). Refused otherwise (exit 2), as is a bundle that is not a readable zip or
   whose values would put a line break into the launch.
3. Unpacks `checkpoint/data.zip` (SlotPack's format) into a data slot of its own, `rp<12 hex>-0`, and its `.device`
   folder: SharedPreferences, databases, files, `/sdcard` and the Settings values, as the checkpoint cloned them. The
   rest of the bundle goes to `work/replay/rp<12 hex>/`. Both are swept by a later replay once 10 minutes old and no
   instance holds the slot.
4. Launches with the checkpoint's device: `--device` (the preset it names, or its size), `--appearance` and `--locale`
   from Robolectric's qualifiers, `--font-scale`, `--timezone`, `--anim-scale`, the launch's `--feature` flags (new in
   the checkpoint's device); `--clock` at the app's wall clock then (the simulated one, new in the checkpoint, so a
   recording made with `--clock` keeps its date; the checkpoint's real time for older bundles), so the Application
   and its providers already see that date; `--network replay network.har --network-replay-timing recorded
   --network-replay-mark off`; `--data-slots rp<hex>`; `--no-autofill`.

**In the app's process** (`Replay`):

5. The checkpoint's rotation is applied, the wall clock is set to the checkpoint's again (`SimClock`; not when the
   replay was given its own `--clock`), every activity of the stack is looked up in the build (one it lacks is exit 2,
   not a crash), and the stack is rebuilt from `checkpoint/state.bin` in place of the start activity: bottom first, each activity built with
   its unparcelled intent, then `create(state).start().restoreInstanceState(state).postCreate(state)`, the top one
   resumed, as `process-death` rebuilds a stack.
6. Once that stack's first frame is drawn, `input.jsonl` is played at its recorded offsets on the app's clock
   (`t - checkpoint.uptime`): each event goes to the Host loop as live input does (`Host.dispatch`: the same touch,
   key, character, Back, wheel and resize paths as the window, `--control` and adb), each at its own time rather than
   the loop's 7 ms spacing for queued input, and device commands (`rotate`, `open`, `appearance`, `network`...) run as
   commands. Characters left out of the recording (password fields, pasted text) are typed as `x`; `clipboard set` gets
   as many `x`; `paste` is not run, its characters being in the journal.
7. Before each gesture (a first finger down, a key, Back, a command, the first of a run of characters or wheel
   steps) and at the end, the screen is compared with the recording ("Divergence" below). The end is once the
   recording's own end (`uptimeAtSave`) has been reached and the screen has drawn nothing for 300 ms (3 s at most).
8. Without `--control` the process ends: exit 0 when every point matched, 3 when one diverged. With `--control` the
   app stays up for whoever drives it, and `replay` answers where it stands. A crash ends it with 10 as any crash
   does, and the report says after which event or command.

The network: the bundle's HAR is replayed by the existing engine (docs/network.md), at the recorded timing, and
without the `X-Apksim-Replay: recorded` header replay mode otherwise adds (`--network-replay-mark off`, new), so the app
gets the answers it got. A request the recording has no answer for gets the engine's 599 and is listed in the report
(`requests`, below) and its notes; it does not fail the replay by itself (an analytics call with a timestamp in its body
never matches). A connect that failed while recording fails the same way again ([network.md](network.md) "Failed
connects"). After the journal the network stays in replay mode; `--live-network` leaves it live from the start. A bundle
recorded without the `net` channel replays with the live network.

## What happened to each request

`report.json` has `requests` when the network was answered from the bundle, and stderr's last line counts them:

```
[apksim] replay: matched at all 14 points; 96 events, 2 commands; 41 of 43 recorded requests asked again, 0 unmatched; report .../report.json
```

```json
"requests": {
  "recorded": 43, "asked": 41, "unmatched": 1, "total": 58,
  "entries": [
    {"entry": 0, "method": "POST", "url": "https://api.example.test/v1/signin", "status": 200,
     "redactedInput": true, "asked": 1, "first": 1, "matched": ["exact"]},
    {"entry": 1, "method": "GET", "url": "https://api.example.test/v1/profile?device=3f0c&lang=en", "status": 200, "asked": 0},
    {"entry": 2, "method": "CONNECT", "url": "metrics.example.test:8443",
     "error": "connect failed: java.net.ConnectException: Connection refused", "asked": 3, "first": 4, "matched": ["connect-failed"]}
  ],
  "unmatchedRequests": [
    {"seq": 2, "method": "GET", "url": "https://api.example.test/v1/profile?device=91aa&lang=en",
     "closest": {"entry": 1, "method": "GET", "url": "https://api.example.test/v1/profile?device=3f0c&lang=en"},
     "differs": ["query differs: device=3f0c&lang=en recorded (only the value of device)"], "ignoreQuery": ["device"]}
  ]
}
```

- `entries`: every entry of `network.har`, in its order (`entry` is its index there), with how often the replay asked
  for it (`asked`; the last of several entries with one key repeats, as in any replay), the order number of the first
  request it answered (`first`) and how its requests matched: `exact`, `redacted-input` or `loose-query: NAMES` (both
  below), `connect-failed` for a connect that failed again. `replayable: false` marks what a replay never answers with (a
  request that failed, a stream still open at the save, a WebSocket upgrade).
- `unmatchedRequests`: the requests that got the 599, in order (`seq` numbers every request the replay made, answered
  or not, so the two lists interleave), each with the closest entry and what differs, as the stderr line has it;
  `ignoreQuery` when only those query parameters' values differ, `bodyOnly` when only the body does. The first 500.
- `recorded` counts the entries a replay can ask for again (answers and failed connects), `asked` those it did,
  `unmatched` and `total` the requests.

A request that failed for another reason than its recording (a socket doing TLS from its start) is in the notes only.

## A session with a sign-in

A recording of an app that signs in at every cold start replays cleanly when it was made by this version or later;
three things used to need options worked out by hand.

**The password.** The journal leaves characters typed into a password field out ([flight-recorder.md](flight-recorder.md)
"Privacy") and a replay types `x` for each, so the sign-in request's body is not the recorded one. The recorder now
takes the same text out of the requests that carried it: while it leaves characters out it keeps them in memory, and at
a save every request begun after they were typed whose body or URL holds them (each character as itself,
percent-encoded or a JSON escape; 4 characters or more, not inside a longer word; the `input` channel must be on) is
written with as many `x` in their place and marked `_apksimRedactedInput`. The
recorded body is then the one the replay sends, and matches exactly; the bundle no longer holds the password in its
HAR either. A marked entry also answers a request with its method and URL whose body differs (`matched:
["redacted-input"]`): the body is the part the replay cannot type, for instance when Delete was used in a way the
recorder's count missed. Why not match every sign-in on method and URL alone: `--network-match method,url` does that
for every request of the replay, so two POSTs to one URL with different bodies get each other's answers; the mark
loosens only the method and URL of requests that carried left-out input. For those it loosens every request: once no
entry matches exactly, any request with that method and URL gets the marked entry's answer, whatever its body (a second
sign-in with another password is answered as the recorded one was). This does not need `--loose`.

What it does not cover: a body that carries the text in another form (hashed, encrypted, base64 inside JSON), a bundle
from an older apksim, and left-out text shorter than 4 characters. Such a request is unmatched with "body differs",
and the replay says so once:

```
[apksim] replay note: 1 unmatched request differs from a recorded one only in the body, and the recording left 13 typed
or pasted characters out (typed here as x): if the body carries them (a sign-in), --network-match method,url matches it
without its body
```

**Values made at run time.** An id the app makes after the checkpoint (a device or installation id, a nonce) is another
one in the replay's process, so requests with it in the query match nothing. The unmatched line and the report name the
parameter when the closest entry has the same parameter names and only values differ:

```
[apksim] network replay: no recorded response for GET https://api.example.test/v1/profile?device=91aa&lang=en
         closest: GET https://api.example.test/v1/profile?device=3f0c&lang=en (query differs: device=3f0c&lang=en recorded (only the value of device))
         --network-ignore-query device matches it (a value made at run time; `apksim replay --loose` for all such)
```

`--network-ignore-query device` leaves that parameter out of every match. `apksim replay --loose` (the launch option
`--network-loose`) does it without naming them: a request no entry matches is answered by the entry with the same
method, path, parameter names, body (and headers, when matched) whose query values differ in the fewest parameters, an
entry not yet served first. The report marks each such answer (`matched: ["loose-query: device"]`), so a loose replay
still says which answers were not the request's own. It is off by default: a search for "a" answered with the results
for "b" is a wrong answer, and only the reader knows which parameters are noise. Values made at run time in a body or a
path are not covered (`--network-match method,url` for the first).

**Servers that were not there.** See [network.md](network.md) "Failed connects": the connect fails in the replay as it
failed in the recording, instead of the app retrying against 599s.

## Passwords in a recording

A recording never holds a password typed into it, so by default a replay types `x` for each character. To replay a
sign-in with the real one, have apksim keep passwords apart from the recordings:

```
apksim rec secrets on            # once; off | list | forget ID|all
apksim replay session.apkrec     # types the kept password, on this machine
apksim replay --secrets test.properties session.apkrec   # another account's: s1=VALUE lines (the manifest names them)
apksim replay --secrets off session.apkrec               # x, as before
```

- **Off until you turn it on.** On, instances started from then on keep each password typed into a password field, when
  a recording with it is saved, in `~/.apksim/secrets/ID.sealed`. The recording has only the ID, the names (`s1`, `s2`)
  and lengths, so it can be sent to someone else; they give their own with `--secrets FILE`.
- **Who can open them.** The recorder runs in the app's sandbox with a public key only (`~/.apksim-secrets/public.key`,
  which an app may read and not change), so no app can open what it or another sealed. The private key is in your login
  Keychain, which the sandbox cannot reach; `apksim replay` reads it outside the sandbox. Anything that runs as you
  outside the sandbox can read it too. On Linux, in CI or over SSH, `APKSIM_SECRETS_KEY_FILE=FILE` (mode 600) is used
  instead.
- **What they are bound to.** The recording's random ID, the app's package and its signing key, taken from the APK about
  to run: a sealed file copied under another recording's ID does not open for another app.
- **Where they go.** From the replay's plan to the app's process through a pipe: not its arguments, its environment or a
  file. They are typed only while a password field has the focus; anywhere else `x` is. Such a launch is always cold.
- **Matching** is exact again: what the app sends is blanked as the recording is before it is compared (4 characters or
  more, in the URL and body, with as many `x` as the recorded password was long, so another account's matches too).
  Reports, logs and `journey.json` hold the placeholder.
- **A live replay** (`--live-network`, or a recording with no `net` channel) sends them to the real server, so it types
  them only with `--secrets store` or `--secrets FILE`. With options that keep or show what the app sends (`--rec`,
  `--network`, `--network-unmatched`, `--network-inspect`, `--jvm`, `--debug`) they are never typed.
- **Not kept:** by `apksim serve` and `apksim beam`, replays and crawls (`--no-autofill`); text pasted outside a
  password field; keys whose character the key map does not give. `scripts/replay-adb.py` and `journey.py` type
  placeholders.

## Divergence

The recorder keeps frames of the presented screen at most 5 a second, scaled to 960 pixels on the long side and
JPEG-encoded at quality 70, with the time each was presented (`screen-times.txt`). A replay compares the same way: the
latest frame is scaled with the same filtering and encoded at the same quality, then letterboxed into the movie's
size when the screen's shape has changed since (a rotation), as the recorder fits such frames, so an identical screen
gives an identical JPEG and a difference of 0. The journal's events are not delayed by the Host's idle sleep, which is
off until the replay is done. A pixel counts when one channel differs by more than 64. Because
animations and the recorder's 200 ms sampling do not line up between the two runs, each point is compared with
the newest frame the recording kept by then, the others it kept in the 1.5 s before (none older), and the first one
kept up to 250 ms after it, and the closest counts.
The point diverges when more pixels than the threshold differ: by default 0.005% of the frame, at least 16 (21 for
the phone's 442x960 frames); `--threshold N` gives a count, a value under 1 a fraction.

The report (`report.json` in `--out`): the result, the bundle, APK, checkpoint and network used, and each point with
its label (`before down 300,260`, `before open apksim://network`, `end`), its offset from the checkpoint and its pixel
count. `points/NN-recorded.jpg` and `points/NN-replayed.jpg` are the two frames of each point, and a diverged point has
`points/NN-diff.png` (red over the threshold, amber under it, grey where equal). Stderr says it in one line:

```
[apksim] replay: diverged at point 6 (end, +9106 ms): 382 pixels differ (threshold 21); frames in .../points
[apksim] replay: diverged at point 6 of 6 (end, +9106 ms); 7 events, 1 commands; report .../report.json
```

What makes a replay drift: anything on screen that is real time (a measured duration, a clock with seconds), a focused
text field's caret caught in the other phase of its blink (44 pixels of a 442x960 frame for a Material text field, over
the default threshold; `scripts/replay-check.py` replays its typed sign-in with `--threshold 300`), flings
(how far a list flies after a flick varies from run to run even live: one `swipe 400 1400 400 400` on the test app's
home list ended at Item 67, 65 and 58 in three launches, and a replay that sent the same events within 2 ms of the
recording's ended elsewhere again), thread timing (a worker's result before or after a frame), state that `onSaveInstanceState` does not keep (a checkpoint keeps
what process death keeps), and a server whose answers the recording does not have. The test app's network screen shows
how long each fetch took, so `scripts/replay-check.py` records it with `--feature dev.apksim.testapp.no_timings`, which
leaves that out (the recording keeps `--feature` flags, so the replay gets it too).

## Measured (2026-11-10, the test app, Mac, with eight other sessions building and running)

`scripts/replay-check.py`'s recording: the Trips tab, a checkpoint, `open apksim://network` and an OkHttp fetch from
the fixture server, Back, the Trips rename. 8 input lines (taps, an `open`, Back), 13-17 frames, a 1-entry HAR; the
journal runs 9.1 s from the checkpoint to the save. The Mac and the Linux image (amd64 under Rosetta) gave the same
pixel counts.

| Run | Result | Points (pixels over 64) |
|---|---|---|
| Same build, fixture server stopped, `--control` | matched; the app on Trips with "Roman vacation" | 0, 0, 0, 0, 0, 0 |
| Same build, no `--control` | exit 0 | 0, 0, 0, 0, 0, 0 |
| Changed build (`--feature dev.apksim.testapp.rename_v2`: renames to "Roman holiday") | exit 3, first divergence `end` | 0, 0, 0, 0, 0, 382 |
| The crash bundle of `open apksim://crash?kind=post` | exit 10, "crashed after open apksim://crash?kind=post at +710 ms" | 0 |

For scale: one digit of the home screen's "FAB clicks: 1" changing to 2 is 46 pixels; the rename above 382. Before
candidates were chosen by the frames' own times (the movie's 200 ms slots), a frame kept 190 ms late showed a stale
count and differed by 54 pixels.

Launch to result, cold (`APKSIM_STANDBY=0`) on a Mac busy with eight other sessions' builds: 18-20 s, of which the
first frame took 9.5 s and the journal plays at its recorded pace (9.1 s); the crash bundle's 6.7 s. In the Linux
image (amd64 under Rosetta, a container each): 45-51 s. Warm (the planned launch is one a standby takes, so
`apksim replay` goes to one when it is ready): 12 s, the first frame at 2.2 s.

## Check

`scripts/replay-check.py APP.apk [OUT] [--image IMAGE] [--changed APK]` records the session above against
`scripts/network-fixture.py`, stops the fixture, and replays: with `--control` (matched at every point, the app on
Trips with the renamed trip afterwards and still answering), without (exit 0), as a changed build (exit 3, the first
divergence at the end and none before it, the difference map written), with `--changed APK` against another build if
given, and a crash bundle (exit 10, the report naming the command). Then a session with a typed sign-in on
`apksim://network?signin=1` (a user and a password typed into fields and sent in a POST's body, a GET with an id made at
run time in its query, a connect to a mapped host nothing listens on): the bundle holds the password nowhere, its
sign-in entry has the placeholder and the mark, the failed connect is an entry; replayed as it is, the sign-in and the
connect come out as recorded and the GET is unmatched with `--network-ignore-query device` named (exit 3); with
`--loose` and with that option every point matches and every recorded request is asked again; and a copy whose sign-in
entry is unmarked with another body is unmatched with `--network-match method,url` named. Each replay's `requests` and
summary line are checked. `--image` runs every launch in the Linux image (CI: `scripts/ci-smoke.sh`).

`scripts/check-replay-match.sh` (`ReplayMatchCheck`, CI's quick checks) checks the matching itself on a plain JVM: the
forms left-out text is found in, the marked entry, loose query values, failed connects and the report's counts.

## Bisect: which build first diverges

`scripts/bisect-rec.py BUNDLE BUILD...` replays one recording against builds in order, oldest first, and finds the
first that diverges or crashes:

```
scripts/bisect-rec.py session.apkrec builds/                       # a folder: its .apk files in natural name order
scripts/bisect-rec.py session.apkrec app-101.apk app-102.apk app-103.apk -- --network-trust cert.pem
scripts/bisect-rec.py session.apkrec --builds builds.txt --image apksim:ci   # one `APK [apksim options]` a line
```

Each build is `apksim replay --headless --apk BUILD` (with the options after `--`): exit 0 is good, 3 (diverged) and 10
(crashed) bad, and 2 (it cannot be replayed against that build) or anything else skipped, as `git bisect skip` does:
another signer than the recorded build's (the same rule as `--apk`, and as beam's data from another build), another
package, an activity the recording's stack needs and the build lacks. The oldest build must replay good and the newest
bad; between them a binary search replays about log2(N) builds, and goes around a skipped one by trying its nearest
neighbours. When only skipped builds separate a good one from a bad one, it says so and names them, exits 1, and `bisect.json`
lists them as `candidates` with no `firstBad`.

`--timeout SEC` (default 600) ends a replay that runs longer, as for a build that hangs, and skips that build.
`--builds FILE` takes a launch option with a build (`app.apk --feature X`), for builds that differ by a flag rather
than by code. `--image IMAGE` replays in the Linux image. The output (`--out`, default `work/bisect-<bundle>`):
`runs/NN-<build>/` with each replay's report, frames and log; `bisect.json`; and `index.html`, every build with its
result and where it diverged, and at the first bad build's first divergence the recorded frame, the last good and first
bad builds' frames and the difference map side by side, embedded so the page can be sent alone. Exit 0 when a first bad
build was found, 1 when not (the oldest already bad, the newest good, nothing replayable). The divergence's last good
frame is that build's capture of the same event (label and recorded offset, or its own `end`), as a capture missing in either replay
shifts the point numbers; with none, the page shows no last good frame.

Checked with `scripts/bisect-rec-check.py APP.apk [OUT] [--image IMAGE] [--changed APK]`: the Trips rename recorded,
then six builds: the app twice, an unsigned APK (skipped: "signed by another key than the recorded build"), the app,
and the app with `--feature dev.apksim.testapp.rename_v2` twice. The first midpoint lands on the unsigned build, so the
search goes around it; it must name build 5 first bad and build 4 last good, having replayed at most 5 of the 6, and
the page must hold the four frames. With `--changed APK`, a build of the test app with one word of source changed
("Roman vacation" to "Roman getaway", the same debug key), it bisects [app, app, changed] to the changed build. Last,
[app, app, app]: no build diverges, and `lastGood` is the newest build replayed (3), not the oldest.

| (2026-11-10, the test app) | Mac | Linux image (amd64, Rosetta) |
|---|---|---|
| Six builds, launch-option change, one skipped | first bad 5, last good 4; 5 replays, 46 s | first bad 5, last good 4; 5 replays, 92 s |
| [app, app, source-changed build] | first bad 3; 3 replays, 23 s | first bad 3; 3 replays, 107 s |

Each replay of this 4.5 s recording took 6.5-14 s cold on the Mac and 15-45 s in the image, a container each.

## Recording to journey: a test that targets nodes

`apksim replay --journey BUNDLE` replays as usual and also writes `journey.json` beside its report: the recording as
steps that find their element the way a test would, by text, content description or resource id, with the texts each
step should bring on screen. `scripts/journey.py journey.json` runs it over `--control` and writes JUnit XML.

```
apksim replay --journey --headless --out out session.apkrec
scripts/journey.py out/journey.json --xml results.xml                     # from the recording's checkpoint
scripts/journey.py out/journey.json --apk app-new.apk -- --device tablet  # another build, another device
scripts/journey.py out/journey.json --port 7400                           # an instance already running
```

From the test app's recording (`scripts/journey-check.py`):

```
{"n":1,"do":"tap","target":{"text":"Trips"},"at":[305,1686],"expect":["Italian vacation","Tokyo weekend","Alps ski trip","Trip note"]}
{"n":2,"do":"tap","target":{"text":"Italian vacation"},"at":[172,256],"expect":["Roman vacation"]}
{"n":3,"do":"tap","target":{"text":"Home"},"at":[96,1686],"expect":["FAB clicks: 0","Item 1","Item 2"]}
{"n":4,"do":"tap","target":{"id":"fab"},"at":[733,1533],"expect":["FAB clicks: 1"]}
{"n":5,"do":"tap","target":{"id":"fab"},"at":[733,1533],"expect":["FAB clicks: 2"]}
{"n":6,"do":"tap","target":{"id":"fab"},"at":[733,1533],"times":[[0,4],[104,8]],"expect":["FAB clicks: 4"]}
```

**Why during a replay.** Reading the accessibility tree changes a Compose screen: the node provider adds a view to the
`AndroidComposeView` and builds its caches (the failure bundles' issue 73). The recorder must not do that to the app
the user is using, so taps are resolved when the recording is replayed, just before each tap is dispatched, on the
screen the replay shows at that moment. The replay still compares its frames; in the check it matched at every point
with the tree read before each tap.

**Targets.** The node under the tap: the deepest that `nodes` lists with a label there, by its text, else its content
description, else its resource id (a View's id, or a Compose `testTag` with `testTagsAsResourceId`; only on clickable or
editable nodes, as `nodes` lists them, which now prints `id="..."`; a View's id is given as a service that asks for
ids gets it). When several nodes share the label, `index` says
which (in `nodes` order). A tap with no labelled node under it keeps its coordinates (`at`). A finger that moved past
the touch slop is a `swipe` with its end points and duration (coordinates: a scroll is a distance, not an element), and
`fling` when it was moving faster than the framework's minimum fling velocity (50 dp/s) in its last 50-100 ms before
lifting; it keeps its path with timings, which journey.py plays back as `touch down|move|up` at the recorded times
(older journeys: a `swipe` of the recorded duration for a fling, a `drag`, which does not fling, otherwise). A fling step gets no expectations: where a
fling ends varies from run to run (above), so what it scrolls into view is not something a test can expect;
held 500 ms or more, a `longpress` on its node, with how long it was held (`ms`). Taps on the same node (or, with none, the
same point) that follow one another as a double tap does (each within 300 ms of the last one's lift and 100 dp of the
first: ViewConfiguration's double tap timeout and slop) are one step, closed against the screen before its first tap, with each tap's start and length (`times`, ms), played as recorded: two steps would have
a settle between them and be two single taps. Typed characters become one `type` step, keys `key` steps, Back
`back`, wheel steps `wheel`, commands (`open URL`, `rotate`...) `command` steps. Two-finger gestures and window resizes
are left out and listed in the journey's `notes`. A replay that crashes (exit 10) still writes the journey, with the
steps up to the crash and a note saying where it crashed; a finger still down then (a long press whose onLongClick
crashed, a double tap's second tap) is lifted at the crash, so the press that crashed is kept (`scripts/check-journey.sh`
checks these on a stand-in screen).

**Expectations.** At each step's start the texts on screen are read (only what is on screen: `nodes` now marks a node
that is in the tree but not visible, a list item scrolled away, `offscreen`, and neither the generator nor journey.py's
matching counts it); a step expects the texts that appeared on
screen for the first time in the journey (up to 6), or, when none did (Back to an earlier screen), the first 3 that
appeared since it started. Times, dates and durations are left out (`12:30`, `327 ms`), as are sizes (`411×891`) and
texts over 80 characters, wherever they are in a text, on any of its lines ("Fetched\n327 ms";
`scripts/check-journey.sh`, CI). A screen that comes back after Back brings its whole furniture back, a window size class among it
("compact 411×891"), which would tie a journey to one device; first appearances do not.

**The runner** (`scripts/journey.py`, also in the Linux image beside `instrument.py`): without `--port` it starts the
app from the recording's checkpoint (`apksim replay --input none --compare none --control auto`: the checkpoint's data,
device and stack, no input; `--input none` is new) or, with `--start launch --apk APK`, a fresh launch (`--no-autofill`, as a replay has) in data slots of its own (`--data-slots journey`, so it never
empties a normal launch's `default` slot). Options after
`--` go to that launch. Each step: a tap or long press at the centre of its node, waited for up to `--timeout` (10 s);
a long press and a double tap as `touch down|up` at their recorded times, each hold timed from when the Host dispatched
the finger down (`settle` says "input queued" until then), not from its answer; the other steps as the control commands of their names (typed text as `type64 BASE64`, new, which
keeps every space; a path-less swipe that flung, or took under 250 ms, as `swipe`, which flings, else as `drag`); then `settle`, which waits as
long as the step's own input takes (a swipe's `ms`, 7 ms a typed character) plus `--timeout` while the input is still
being dispatched, then each expected text waited for. `nodes` escapes `\`, `"` and line breaks in its values (`\\`, `\"`, `\n`), which journey.py decodes, so a
label with quotes or a semicolon matches whole. A rotary step keeps the `rotary` command's sign. JUnit XML
(`--xml`): one test case per step, the failing step's message (`expected 'Roman vacation' on screen after 10 s; it
shows ['Roman holiday', ...]`), the rest skipped. Exit 0 passed, 1 failed, 2 could not start, a replay still starting
after `--start-timeout` (120 s) included: it runs no step then (`scripts/journey-start-check.py`, a stand-in apksim; CI;
it also checks a long press's and a double tap's timing and a long `type` step's wait). `--by-coordinates` taps
the recorded points instead, to compare.

A generated UiAutomator test (Kotlin for the app's `androidTest`) is not done: the journey and its runner cover running
it in CI, and the step format maps one to one onto `By.text`/`By.desc`/`By.res` when someone wants it.

Checked with `scripts/journey-check.py APP.apk [OUT] [--image IMAGE]`: the journey above generated from a recording of
the Trips rename, the Home tab, two FAB taps and a double tap; it passes from the checkpoint (6 steps, about 4 s of
steps); it passes
on a tablet (`-- --device tablet`: a navigation rail instead of the bottom bar), where the same journey by the
recorded coordinates fails at its first step; and as a changed build (`rename_v2`) it fails at step 2 expecting "Roman
vacation" with steps 3-6 skipped; and a recording that ends in a crash (apksim://crash?kind=post) replays to the
crash (exit 10) and still writes its journey, the Trips tap and the `open` command, with the crash in `notes`. The replay that writes the journey took 20 s on the Mac and 28 s in the
Linux image; each journey run, the app's start from the checkpoint included, 20-21 s on the Mac and 18-21 s in the
image (both cold, the Mac shared with other sessions' builds), of which the five steps take about 4 s.

## Not done

- Input the recording does not have: sensors, camera frames, location fixes (the `location` command is replayed),
  notifications, alarm and WorkManager timing; text that reached the app outside input dispatch (autofill,
  accessibility `setText`).
- Statics: a checkpoint is the saved state and the data folder, as after process death, not the process's memory.
- Device options the checkpoint does not record: `--setting` seeds (their values are in the slot's Settings anyway),
  `--build` fields, `--permissions`, system bars, `--max-fps`, camera. Pass them to `apksim replay` as launch options.
- After the journal the network stays in replay mode (a request with no recorded answer gets a 599), rather than
  answering from the HAR where it can and going out otherwise.
- Frames are compared as wholes; there are no masks for regions known to differ.
