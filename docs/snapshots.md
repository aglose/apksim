# Screenshot (golden) tests for real APKs

`scripts/snapshot.py` takes an APK, a list of screens and a matrix of device settings, captures every screen in every
combination, compares each capture with a golden image, and writes an HTML report. It catches design-system and
localization regressions (a colour token, a clipped German label, a layout that breaks at 1.3x text, a tablet rail
that lost its icons) in the real release APK, without a screenshot test per screen in the app's own code.

Status: built and checked on the test app (2026-11-05), on the Mac and in the Linux image; "Measured" has the numbers.

## Shape

```bash
scripts/snapshot.py SPEC.json [--apk APK] [--update] [-j N] [--out DIR] [--screens a,b] [--goldens DIR] [-- APKSIM_OPTION...]
```

- `SPEC.json` names the screens, the matrix and the golden folder (below). `--apk` overrides the spec's `apk`.
- Without `--update`: every capture is compared with its golden; a missing golden, a size change or a difference
  above the screen's tolerance is a failure. Exit status 1 if anything failed, 0 if all matched, 2 for a bad spec or
  an instance that could not start.
- `--update`: captures are written as the new goldens (the report still shows what changed against the old ones);
  exit 1 only if a capture itself failed (a screen that never settled, a step that errored). A golden is rewritten
  whenever its bytes differ, also when the difference is within the tolerance.
- `-j N`: N headless instances at once, 1 or more (default: half the CPU count, at most 4, at most the number of
  combinations). Instances write captures and difference maps to a temporary folder, moved into OUT by the script:
  the control protocol splits on whitespace, and on the Mac the launcher's sandbox lets the app write under the home
  folder only in apksim's own state folder. So OUT and GOLDENS can be anywhere, spaces included.
- `--out DIR` (default `work/snapshots/<time>`, `~/.apksim/snapshots/<time>` for a release; it must not overlap the
  golden folder, since its `actual/`, `golden/` and `diff/` are emptied first): `report.html`, `actual/`, `golden/` (copies, so the folder is a
  self-contained CI artifact), `diff/`, `results.json`, and each instance's log.
- `--screens a,b`: only these screens.
- Options after `--` go to every `apksim` launch.

### Spec

```json
{
  "apk": "testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk",
  "goldens": "testapks/composeapp/snapshots",
  "clock": "2026-01-01T09:41:00Z",
  "timezone": "UTC",
  "matrix": {
    "device": ["phone", "tablet"],
    "appearance": ["light", "dark"],
    "locale": ["en-US", "de-DE"],
    "fontscale": [1.0, 1.3]
  },
  "exclude": [{"device": "tablet", "fontscale": 1.3}],
  "tolerance": {"pixels": 0, "delta": 0},
  "screens": [
    {"name": "home", "open": "apksim://composeapp/home"},
    {"name": "trips", "open": "apksim://composeapp/trips", "tolerance": {"pixels": 0.001, "delta": 16}},
    {"name": "fab-3", "open": "apksim://composeapp/home", "steps": ["tap-text Add", "tap-text Add", "tap-text Add"]},
    {"name": "settings", "open": "apksim://composeapp/settings", "only": {"device": ["phone"]}}
  ]
}
```

- `matrix`: any of `device` (`phone|foldable|tablet|desktop|tv|WxH[@density]`, the `device` command), `appearance`
  (`light|dark`), `locale` (BCP 47), `fontscale`. Missing axes take one value: phone, light, en-US, 1.0. The
  combinations are the cross product, minus each `exclude` entry (an entry matches a combination when every key it
  names matches).
- A screen is `name` plus `open` (a deep link the app's activity is started with) and/or `steps` (control commands
  run in order after it starts: `tap`, `tap-text`, `swipe X1 Y1 X2 Y2 MS`, `type`, `key`, `back`...). A default
  `settle` follows the restart and every step; a step that is itself `settle ...` (a longer limit for a slow screen,
  `settle 30000 1000`) replaces the one before it. `only` limits a screen to some values of an axis.
- `tolerance`: a pixel differs when any channel differs by more than `delta` (0-255); a capture matches when the
  share of differing pixels is at most `pixels` (a fraction; `pixels: 12` with a value of 1 or more is a count).
  The spec's `tolerance` is the default; a screen's own replaces it. The default is exact.
- `clock`, `timezone`: the wall clock and zone the app sees (below).
- Goldens are `GOLDENS/<screen>/<device>-<appearance>-<locale>-<fontscale>.png`, e.g.
  `home/tablet-dark-de-DE-1.3.png` (`-1.png` for 1.0). PNG of the app's pixels only (`shot`: no status bar or gesture
  handle), as the app's own encoder writes them, so an unchanged screen gives the same bytes and is compared without
  decoding.
- The image has it on its `PATH`: `docker run --rm -v "$PWD:/work" --entrypoint snapshot.py ghcr.io/aglose/apksim:VERSION
  spec.json` (paths in the spec relative to it, under the mounted folder).

### What one capture does

1. **A fresh activity.** `restart [URL]` (new control command): every activity is finished and destroyed, ViewModels
   cleared, and the launcher activity started again with no saved state, as a new task from the launcher; with a URL
   it is started with that ACTION_VIEW intent instead (the activity that handles it). So no screen sees state a
   previous screen or combination left (scroll positions, `rememberSaveable`, back stack), and a capture does not
   depend on which instance ran it or in what order. App statics, the Application and the app's data persist; each
   instance starts with `--fresh`, and screens should not write data (or should undo it in their steps).
2. **The combination** is applied once per combination, before its screens, in the running instance: `device`,
   `appearance`, `locale`, `fontscale` (configuration changes; cheaper than a relaunch, and `restart` follows anyway).
3. **The clock** is set to `clock` (`clock ISO`) before each screen's restart, so a screen that shows the time or
   date shows the same one in every run.
4. **Steps**, each followed by **`settle`**, then `shot`. `restart` also drops pending activity results and closes a
   file picker a previous screen left open.

## Determinism

The same APK and spec must give the same pixels on every run, on one kind of machine. Each source of variation and
what removes it:

| Source | Fix |
|---|---|
| Animations mid-flight | `animscale 0` once per instance: ValueAnimator's scale and the global setting Compose reads, so both snap to their end. |
| When to take the shot | `settle [MAX_MS] [QUIET_MS]`, below; no fixed sleeps. |
| Wall clock | `clock ISO\|epoch-ms\|real`: the app's wall clock starts at that instant and runs on from it. `--clock ISO` sets it before the Application and its startup providers run (and at each test's setup in `--instrument` runs). A `Clock` the app keeps reads the current setting at every read. The translator routes app and library calls to `System.currentTimeMillis()`, `java.time` `X.now()`, `Clock.systemUTC/systemDefaultZone()`, `new Date()` and `Calendar.getInstance(...)` through `SimClock`; framework code reads it through the WallClock interceptor. A screen showing seconds still changes; minutes do not unless a screen's steps take a minute. |
| Time zone | `--timezone` at launch (the JDK's, android.icu's and `user.timezone`, which Joda-Time reads). |
| Caret blink | `--snapshot`: the top activity's window has focus, as on a device (apksim's windows otherwise have none, and Compose and Views draw a caret only in a focused window), Compose's `CursorAnimationState` is made with blinking off (the caret stays drawn, as Compose's own tests have it; matched by class name, so debug builds only: R8 renames it) and View `Editor`s render the caret whenever it is visible, not in the blink's "on" half only, with no blink timer (whose invalidations every 500 ms would keep `settle` from answering). |
| GPU paths | `--snapshot` sets `apksim.cpuLayerEffects`, `apksim.gpuBitmaps=false` and `apksim.noGpu` (AGSL paint shaders on CPU Skia): Metal's float math and sampling differ from Skia's in the last bits, and Linux has no Metal. Screens with endless shader animation never settle; leave them out. |
| Window size, density | the `device` command sets both; `shot` takes app pixels at device resolution, independent of the Mac window's zoom or display. |
| Fonts | apksim's own fonts in `work/runtime` on every platform, not the host's. |
| Leftover state | `restart` per screen; `--fresh` per instance. |
| Network | not handled: a screen that shows network data is only as stable as its server. Use `--no-network` and the app's offline state, or a fake server. |

`--snapshot` (launcher) turns on the caret and GPU settings above; `snapshot.py` launches every instance with it and
`--headless --fresh`, cold (no standby: a standby has already read the GPU switches).

### The idle signal: `settle`

`settle [MAX_MS] [QUIET_MS] [HORIZON_MS]` (defaults 10000, 200 and 1000; in `Host`) answers once the UI has been
quiet for QUIET_MS of real time:

- no frame wanted (no vsync requested from the Choreographer), no window dirty or awaiting layout;
- no input queued (a `tap` or `type` queues events that the loop delivers 8 ms apart);
- the main looper has nothing due now, and nothing due within HORIZON_MS of simulated time (a delayed message later
  than that, such as a 5 s auto-dismiss, does not hold it up). Three messages that never draw are skipped:
  ViewRootImpl's MSG_TOUCH_BOOST_TIMEOUT and MSG_CHECK_INVALIDATION_IDLE (frame-rate bookkeeping that reposts itself
  for a few seconds after touches and drawing) and AnimationHandler's pauser (a second after a window goes, every
  `restart`). Without the skips every capture waited 1-3 s for them;
- no frame drawn during the quiet window, and the last frame's raster finished (the frame `shot` reads is the latest).

Background threads are not inspected (an image load on an IO thread is invisible until it posts to the main thread);
the quiet window is what covers them, so a screen that loads slowly from disk can raise QUIET_MS in its steps
(`settle 10000 1000`). It replies `settled in N ms`, or after MAX_MS `error: not settled after N ms: REASON` (what was
still busy: a frame wanted, a dirty window, a message due with its handler, callback and object), which fails that
capture. After a `restart` or a device switch the test app settles in 200-220 ms, i.e. at once plus the quiet window;
the Shader tab (an endless shader animation) never does ("a frame is wanted").

## Where goldens must be made

**In the Linux image, on the kind of machine CI uses.** That is where they are checked, and the image is the same
everywhere a team runs it. Mac goldens are nearly the same (below: 75% of captures identical, the rest one pixel off by
one level on a shadow's edge), so a Mac can check image goldens with `"tolerance": {"delta": 1}` (or a few pixels),
for a quick local run before pushing; record with `--update` only in the image.

## Speed

Combinations run on `-j N` headless instances at once, each taking a run of combinations in matrix order (device
outermost, so it switches devices seldom). An instance applies a combination with configuration changes and each
screen with an activity restart, so its cost per capture is the app's activity start plus settle, not a JVM and app
start (which it pays once, ~1-3 s). `docs/parallel.md` has how many instances a machine takes.

## CI

`ci.yml` runs a small matrix of the test app in the Linux image (`scripts/ci-smoke.sh`): it records goldens
(`--update`) into a scratch folder, runs again against them (must pass: proves the image's output is deterministic),
then runs a spec whose screen differs on purpose (must exit 1: proves a change is caught), and uploads the reports.
The goldens are recorded in the job rather than committed, because the image's output on GitHub's x86 runners may
differ from a Mac's Colima (Rosetta) build of it: see "Measured".

## Measured

On the test app with `testapks/composeapp/snapshots/spec.json`: 5 screens (home, home after three FAB taps, trips,
trips with text typed into a focused field, settings with the date) × phone/tablet × light/dark × en-US/de-DE ×
font scale 1/1.3 = 80 captures, 2026-11-05, M4 Pro Mac (12 cores) and Colima on it (4 CPUs, 8 GB, x86 under Rosetta).

| | Mac, `-j 4` | Linux image, `-j 2` (default for 4 CPUs) |
|---|---|---|
| Record (`--update`) | 18 s | 72 s |
| Reruns against those goldens | 5 of 5: all 80 byte-identical, 16-18 s each | 5 of 5: all 80 byte-identical, 60-80 s each |
| Goldens' size | 7.3 MB (90 KB a capture) | 7.3 MB |

- **Independent of the instances:** the same goldens (recorded with `-j 4`) matched byte for byte with `-j 1` and
  `-j 3`, where every combination ran after others in one instance, devices switched rather than launched.
- **A deliberate change is caught.** The test app rebuilt with 1 dp more vertical padding on the phone list's cards:
  exactly the 16 phone `home` and `home-fab` captures failed (14-16% of pixels, the cards shifting further down the
  list), the 64 others (tablet grid, other screens) still identical; exit 1. The report's difference map shows each
  card's outline stepping down. CI checks the same with a spec change (below).
- **Mac against the image:** 60 of 80 captures byte-identical (the PNG bytes, not only the pixels). The other 20 are
  every screen on tablet in light mode, each with one pixel differing by one level: (2531, 1495), the edge of the FAB's
  shadow, where arm64 and x86 round Skia's shadow math differently (in dark mode the shadow is invisible on the
  background; the phone's FAB happens to round the same). So a Mac can check image goldens with `delta: 1`.
- GitHub's runners are native x86 with AVX2 and AVX-512, which Rosetta lacks, so Skia may take other code paths there
  than in Colima. Not measured yet: CI records its own goldens (above) rather than comparing with Colima's.
- **Failure paths:** an instance that dies fails each capture it had left, with the reason; a step that does not apply
  (`tap-text` of absent text) and a screen that never settles (the Shader tab: "a frame is wanted", after the 10 s
  limit) fail their own capture; the run goes on and exits 1.

Found on the way (all affect every instance, not only screenshot tests):

- Shadows were cast from the launch display's centre forever: a phone switched to a tablet lit the FAB's shadow from
  x = 411 rather than 1280 (5-7k pixels differed from the same screen launched as a tablet; found by the `-j 1` run).
  Fixed for every instance: `HwRenderer` sets HWUI's light again on every resize, as ThreadedRenderer does per window.

- `appearance` and `locale` on a landscape device (tablet, a rotated phone) gave new windows the portrait size
  (1600x2560 on a 2560x1600 tablet): Robolectric's setQualifiers("+night") rebuilds the display from the overlay
  alone. Fixed for every instance: `DeviceControls.overlayQualifiers` sets the display again from the full
  qualifiers, keeping its rotation.
- apksim never gave a window focus, so no app drew a text caret, Compose or View. `--snapshot` focuses the top
  activity's window; running instances still do not (a follow-up: focus changes what apps do, e.g. IME and
  onWindowFocusChanged, so it wants its own check against the emulator).
