# Flight recorder, replay, the trace viewer and coverage

Agent reference notes, moved out of AGENTS.md so they are read only when needed. A section another doc or a
code comment cites as `AGENTS.md "Name"` is in one of these files: AGENTS.md's index says which.

## Code notes
- `FlightRecorder`, `rec/` (`Rec`, `Ring`, `Db`): the flight recorder (`--rec CHANNELS`, `rec` command,
  docs/flight-recorder.md). Rings of the last N seconds (input at `Host.dispatch`, device commands at
  `runCommand`, logcat, net through the network tap (`Net.capture`: passive, see "Network"), SQLite through
  translator hooks to `rec.Db`, screen frames from the raster thread), checkpoints (stack intents + saved state, a
  clone of the data folder), and `rec save` to a `.apkrec` zip; a crash saves one by itself (`Crashes.terminate`).
  A checkpoint cycles the visible activities through onStop/onStart as a quick Home and back would: the one way
  recording shows in the app, so one nobody asked for by name is taken only with the app at rest
  (`FlightRecorder.atRest`: no input for 1.5 s, fewer than 5 frames drawn, or video/camera/WebRTC pictures published, in them, no request under way as far as
  the tap knows: `Net.busy`), however long that takes (`rec` says what a due one waits for; only the first has a
  limit, 15 s, since without one nothing can be replayed), or just before the first input after the interval
  (`beforeInput`, from `FlightRecorder.input`); a second after an input, as before, it landed in the middle of
  what the input started (issue 320: a dropped screen load, a player stuck after its first picture). It ends in
  `Host.arrange`, as every stack change does. `rec-check.py` checks when they come.
  `scripts/replay-adb.py BUNDLE -s SERIAL --yes` plays a bundle on a device over adb (the checkpoint's data, /sdcard
  and Settings put back, its display set, `input.jsonl` at its recorded offsets; the network is live; issue 192).
  It replaces the app's data, /sdcard files and Settings values there, so it stops without `--yes` and backs the
  app's data up first (`--backup-dir`, `--no-backup`); `--dry-run` prints the plan and touches nothing. Only run
  against apksim's own adb device so far, not a phone or emulator. Serve and beam give the instance `--rec-crash-id RID` (and serve `--rec-crash-dir`): a crash's bundle is then written as `RID.apkrec` in the session's recordings folder (a beam instance: its out folder's `rec/`, `BeamSandbox.recDir`), `Session.claimCrashBundle` makes it one of the session's recordings once the instance has gone, and the tab's end message carries `"rec":"RID"` (issue 516; `serve-check.py --crash-app ID --crash-rec`, by hand: not in CI; the beam side not run).
- `ReplayPlan`, `Replay`: `apksim replay BUNDLE` (docs/replay.md): the checkpoint's slot, device and stack, `input.jsonl` fed to
  the Host loop at its offsets, the HAR answering, frames compared; exit 0/3 (diverged)/10; `scripts/replay-check.py`. `report.json`'s `requests`
  (`Net.replayReport`, `Recording.report`) says what happened to each request: every HAR entry with how often it was
  asked for and how it matched (`exact`, `redacted-input`, `loose-query: NAMES`, `connect-failed`), the unmatched ones
  in order with the closest entry and what differs, and stderr's last line counts them ("N of M recorded requests
  asked again, K unmatched"). A session with a typed sign-in replays as recorded (docs/replay.md "A session with a
  sign-in"): the recorder keeps left-out characters in memory (`FlightRecorder.LeftOut`) and a save writes the
  requests that carried them with as many `x` (`Recording.scrub`, `_apksimRedactedInput`; any request with that method
  and URL also matches such an entry whatever its body). The left-out runs are never dropped for age (a request that
  carried one can be newer than the window), only past 64; a key with Ctrl or Meta ends a run and adds nothing; an unmatched request that differs only in query values names `--network-ignore-query NAME`, and
  `apksim replay --loose` (`--network-loose`) answers it from the closest such entry, marked in the report.
  `scripts/bisect-rec.py BUNDLE BUILD...`: the first build whose replay diverges, an HTML report (`bisect-rec-check.py`).
  `apksim replay --journey` writes journey.json (taps resolved to nodes: `Journey`), run by `scripts/journey.py` (JUnit XML; `journey-start-check.py`: its start and steps' timing, against a stand-in apksim; `check-journey.sh`: `JourneyCheck`, which texts it may expect and how taps and presses become steps, on a stand-in screen).
- `serve/TraceView`, `resources/dev/apksim/serve/trace-viewer.html`: `apksim rec view [FILE]`, the static trace viewer for
  `.apkrec` and zipped failure bundles (docs/trace-viewer.md; `scripts/trace-viewer-check.py`, `--update-csp` after editing its script).
- `--coverage DIR` (`dex/Coverage`, `CoverageDump`, `scripts/coverage.py`): hits per method and line of `--instrument` runs, JaCoCo XML, LCOV and HTML; docs/coverage.md.
