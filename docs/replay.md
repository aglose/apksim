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
```

Options of its own, anywhere before the bundle: `--apk APK`, `--live-network` (the network goes out as usual instead
of answering from `network.har`), `--out DIR` (the report; default `work/replays/<bundle>-<time>/`, or
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
gets the answers it got. A request the recording has no answer for gets the engine's 599 and is listed in the report's
notes; it does not fail the replay by itself (an analytics call with a timestamp in its body never matches). After the
journal the network stays in replay mode; `--live-network` leaves it live from the start. A bundle recorded without
the `net` channel replays with the live network.

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

What makes a replay drift: anything on screen that is real time (a measured duration, a clock with seconds), thread
timing (a worker's result before or after a frame), state that `onSaveInstanceState` does not keep (a checkpoint keeps
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
given, and a crash bundle (exit 10, the report naming the command). `--image` runs every launch in the Linux image
(CI: `scripts/ci-smoke.sh`).

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
build was found, 1 when not (the oldest already bad, the newest good, nothing replayable).

Checked with `scripts/bisect-rec-check.py APP.apk [OUT] [--image IMAGE] [--changed APK]`: the Trips rename recorded,
then six builds: the app twice, an unsigned APK (skipped: "signed by another key than the recorded build"), the app,
and the app with `--feature dev.apksim.testapp.rename_v2` twice. The first midpoint lands on the unsigned build, so the
search goes around it; it must name build 5 first bad and build 4 last good, having replayed at most 5 of the 6, and
the page must hold the four frames. With `--changed APK`, a build of the test app with one word of source changed
("Roman vacation" to "Roman getaway", the same debug key), it bisects [app, app, changed] to the changed build.

| (2026-11-10, the test app) | Mac | Linux image (amd64, Rosetta) |
|---|---|---|
| Six builds, launch-option change, one skipped | first bad 5, last good 4; 5 replays, 46 s | first bad 5, last good 4; 5 replays, 92 s |
| [app, app, source-changed build] | first bad 3; 3 replays, 23 s | first bad 3; 3 replays, 107 s |

Each replay of this 4.5 s recording took 6.5-14 s cold on the Mac and 15-45 s in the image, a container each.

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
