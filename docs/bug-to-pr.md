# From a recorded bug to a pull request that shows it fixed

You have a recording of a bug (a `.apkrec` from the [flight recorder](flight-recorder.md)), the build it was recorded
on, and a build with your fix. `scripts/pr-evidence.py` turns those into what a reviewer wants to see: the bug replayed
on the old build, the same steps on the new build with the place where the screen first differs, and a walk of the
screens your change can reach, each compared with the old build's. It chains tools that exist on their own
([replay](replay.md), the [crawler](crawl.md) with `--record-deps`, `crawl-select.py`, the seeded `--diff-seed` walk)
and writes the Markdown for the pull request.

In an installed release the script is in the install folder's `scripts/`, beside `crawl.py`; it needs only Python 3.

```bash
scripts/pr-evidence.py run bug.apkrec --base app-base.apk --new app-fix.apk \
    --work evidence/ --repo . --diff origin/main...HEAD
```

That is the whole flow. The four steps below are the same thing one at a time, which is how to run it when the map of
the base build is made once (on `main`, nightly) and used by many pull requests.

## Before you start

Five things decide whether the result means anything. Check them first.

1. **A server on your own machine must be reached by name.** The network tap never reads loopback connections, so an
   app pointed at `http://127.0.0.1:8080` records an empty `network.har` and a replay goes out to the live server.
   Point the app at a host name and map it when recording: `--network-host api.example.test=127.0.0.1:8080` (with
   `--network-trust cert.pem` for its certificate). `verify` warns when a recording holds no requests.
2. **An app behind a sign-in is walked signed in only from a prepared data slot.** The crawler passes `--no-autofill`
   and never types a password. Sign in once in a slot of its own (`apksim --data-slots map app-base.apk`, sign in,
   quit), then give the walks `--keep-data` and `-- --data-slots map`: every instance runs on a copy of that slot. An
   app that locks its saved session behind a biometric prompt needs that sign-in saved too; `--keep-data` answers the
   prompt. A baseline that finds one or two screens is the sign-in screen: `baseline` says so.
3. **`--no-network` is a sandbox rule, not a recording.** After `--`, it leaves the app only `localhost`: nothing a
   walk taps can reach a real server, and a local fake server still answers. It neither records nor replays. To walk
   against recorded answers use `crawl.py --freeze-network`, or `-- --network replay FILE`.
4. **A screen at the depth limit has no dependencies.** `--depth N` (crawl.py's `--max-depth`) explores the screens
   fewer than N actions from a start. A screen exactly N away is found, drawn and listed, but its controls are not
   tried and nothing is recorded about which files it runs, so no changed file can select it. If the screen your
   change touches is one of those, walk one level deeper. `baseline` counts them.
5. **Record on the base build, with the options you will replay with.** `verify` can say something only when the
   recording replays on the base build. Launch options the recording does not keep (`--network-trust`, `--build`,
   `--permissions`, camera) go after `--` on every step. (`--data-slots` and `--fresh` after `--` reach the walks only:
   a replay always runs in a slot of its own, restored from the recording.) Record with the screen channel (`--rec
   all`): a recording without pictures gives no verdict.

Two limits of the comparison itself: a replay counts a pixel only when one colour channel differs by more than 64 of
255, so a change in low-contrast text (dark grey on dark blue) can replay as "matched"; and anything on screen that is
real time (a duration, a clock with seconds) differs on every replay, base build included.

## 1. Map the base build

```bash
scripts/pr-evidence.py baseline app-base.apk --out base-map/ --depth 3
```

`crawl.py --record-deps` with defaults: every screen fewer than three actions from the start explored, and for each
the source files and resources it ran. Options the script does not know go to `crawl.py` (`--start URL`, `--keep-data`,
`--max-seconds`, `-j`, `--matrix`...), and options after `--` to every apksim launch. Name the APK first. When a budget
(`--max-seconds`) cut the walk, it goes on from the unexplored screens, up to `--rounds` times.

```
baseline: 3 screens in base-map, 2 with recorded dependencies
  1 screen found at the depth limit (1 from a start) and not explored: no dependencies. A change reaches them only
  through `diff --cautious`; walk with --depth 2 to record them.
baseline: 14 s; map base-map/report.html
```

Keep the folder: it is the "before" of every later comparison, and `diff` never writes into it. It remembers the
options it was walked with (`pr-evidence.json`), and `diff` uses them again.

## 2. Replay the recording on both builds

```bash
scripts/pr-evidence.py verify bug.apkrec --base app-base.apk --new app-fix.apk --out evidence/verify
```

Both builds replay side by side from the recording's checkpoint, with the network answered from the recording:

```
Replay of `bug.apkrec` (com.example.app):
- base app-base.apk: matched the recording at all 5 points (at most 0 pixels differing, threshold 21); requests: 1 recorded, 1 asked
- new app-fix.apk: first differs at point 5 of 5 (`end`, +4958 ms): 310 pixels (threshold 21) in 1 region, the largest 228,500 to 286,520 of a 442x960 frame; requests: 1 recorded, 1 asked
**PASS**: the base build shows what was recorded and the new build does not, first at `end`. Look at that point's pictures: ...
```

How to read it:

- **base matched** is the precondition: the old build still does what was recorded. If the base differs or crashes
  (on a recording that does not end in a crash), the verdict is FAIL whatever the new build does; the recording is of
  another build or other launch options.
- **new first differs at** names the step before which the screens parted (`before down 300,260`, `before back`,
  `end`), with the pixel count and where on the frame. A fix should part where the bug showed, not earlier. The
  pictures are in `evidence/verify/new/points/NN-recorded.jpg`, `-replayed.jpg` and `-diff.png`. The replay proves the
  screen changed there; that it changed to the right thing is yours to look at.
- **requests: N recorded, M asked** compares what the recording holds with what the replay asked for. Fewer asked on
  the new build is a request the fix no longer makes; "without a recorded answer" is one the recording does not have
  (answered 599), which is normal for analytics with a timestamp and a finding for anything else.
- A recording that ends in a crash (the recorder saves one by itself, and marks it in the bundle) is a bug too: the
  base build must crash on the replay, and the verdict is PASS when the new build plays the same steps to the end
  without crashing.
- Exit 0 for PASS. For a change that should show nothing (a refactor), `--expect same` passes when both builds match.
  Exit 1 for FAIL, 2 when a replay could not run (another signing key, a build without an activity of the recorded
  stack) or a build ended before its replay did, leaving no finished `report.json`.

`--base-option "OPTS"` and `--new-option "OPTS"` give one build's launches their own apksim options, for builds that
differ by a flag rather than by code (write a single option as `--new-option=--flag`).

## 3. Walk what the change can reach

```bash
scripts/pr-evidence.py diff app-fix.apk --baseline base-map/ --repo . --diff origin/main...HEAD --out evidence/diff
```

First the selection, from the baseline's dependencies and the changed files:

```
diff: 1 of 3 screens selected
  app/src/main/kotlin/com/example/DetailActivity.kt (source) -> Detail [DetailActivity-20cae433]
  ignored (1): README.md
  1 screen the baseline found and never explored (no dependencies) left out; `--cautious` walks them too
```

- `FILE (source|resource|constant) -> screens`: why each screen was chosen.
- `ignored`: docs, tests and other platforms' source sets.
- `unplaced`: changed sources no explored screen ran. Not "invisible": the map cannot say where they show. Code behind
  a tap the walk did not make, or past the depth limit, lands here.
- "every screen is walked again": the change touches something every screen may depend on (code that runs at app
  start, a manifest, a build file), and the line says which file and rule.
- Screens the baseline never explored have no dependencies. By default they are left out and counted. `--cautious`
  selects all of them for any change, which is safe and noisy: on a 77-screen map it added 16 screens and four false
  "changed" results for one real one.

Then only the selected screens are walked on the new build and compared with the baseline's: texts and controls
(`ui_diff`) and pixels, with the regions that differ. Everything else is carried from the baseline unwalked.

```
diff: 13 s; 1 changed, 2 same
  changed: DetailActivity-20cae433: text 'Total 0.1' -> 'Total 8.0' (phone-light-en-US-1: 580 pixels in 1 region)
```

`evidence/diff/report.html` is the full report. `--changed PATH...` names files without git.

## 4. Write the pull request comment

```bash
scripts/pr-evidence.py comment --out evidence/comment.md --verify evidence/verify --walk evidence/diff
```

Markdown with the verdict lines, the recorded, replayed and difference pictures of the first divergence, a table per
changed screen (before, after, difference, the changed texts), new and gone screens, and what was unchanged. The
pictures are copied into `evidence/comment-images/` (`--images DIR`) under names that do not change between runs
(`replay-recorded.jpg`, `replay-new.jpg`, `replay-diff.png`, `screen-<id>-<combination>-before|after|diff.png`).
Read it before posting: it quotes the app's screen texts and the addresses (without query strings) of requests the
recording had no answer for.

The script hosts nothing. A pull request shows only images it can fetch, so upload that folder where reviewers can
read it (a CI artifact store with public links, a bucket, a branch) and pass its address:

```bash
scripts/pr-evidence.py comment --out evidence/comment.md --verify evidence/verify --walk evidence/diff \
    --image-base https://ci.example.com/pr/123/evidence
gh pr comment 123 --body-file evidence/comment.md
```

Either half may be missing: a comment from `verify` alone, or from `diff` alone.

## In CI

Make the map when `main` changes and keep it as an artifact; on a pull request, download it and run steps 2 to 4:

```bash
scripts/pr-evidence.py run bug.apkrec --base app-base.apk --new app-pr.apk --work evidence/ \
    --baseline base-map/ --repo . --diff origin/main...HEAD --image-base "$ARTIFACT_URL"
```

`run` uses `--baseline DIR` when it is a map of the same base build (by the APK's hash), and walks one otherwise. Its
exit status is the verdict's; when the diff step fails, the comment is still written, with the verdict alone. A
`--baseline` of another build is left untouched and the base is walked into `WORK/baseline`; one of the same build is
reused whatever depth it was walked to. In the Linux image the script is on the `PATH` (`--entrypoint pr-evidence.py`),
but the image has no git: name the files there with `--changed PATH...` (`git diff --name-only` on the runner) instead
of `--diff`.

A coding agent can follow the same steps from the skill in
[docs/skills/apksim-bug-to-pr/SKILL.md](skills/apksim-bug-to-pr/SKILL.md): copy that folder into your repository's
`.claude/skills/`.

## Check

`scripts/pr-evidence-check.py APP.apk [OUT] [--image IMAGE]` runs all of it on the test app: a bug recorded against
the network fixture through `--network-host`, then `run` with the fixture stopped. The "fix" is a launch option on the
same APK (the Bluetooth screen's "ble yes" becomes "ble no"), the change a commit to `BluetoothActivity.kt` in a
repository made for the check. It expects the verdict PASS with the first divergence at the end and one request
recorded and asked on each build, exactly that screen selected and reported changed, the screen at the depth limit
counted and left out, the README ignored, and the Markdown with its six pictures; then `--expect same`, an unchanged
build that is not a fix (exit 1), and `--image-base`. About a minute on a Mac.
