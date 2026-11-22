# Automated exploration: the crawler and the screen map

`scripts/crawl.py` walks an APK's screens by itself over the control channel, breadth first, within a budget, and
writes what it found: a graph of screens and the actions between them, the shortest replayable path to each screen, a
capture of each screen in every combination of a device matrix, and any crash on the way with the path that caused
it. With `--base` it walks a second build the same way and says which screens are new, gone or changed. It needs no
test code in the app and no list of screens, which is what `scripts/snapshot.py` wants.

Status: item 1 of the roadmap, checked on the test app (`scripts/crawl-check.py`); "Measured" has the numbers.

## Shape

```bash
scripts/crawl.py APK --out DIR [--base APK2] [--start URL]... [--max-screens N] [--max-seconds S] [--max-depth N]
                 [--skip-text REGEX]... [--tap-unlabeled] [--fill TEXT] [--matrix AXIS=V,V]... [--matrix-file SPEC.json]
                 [-j N] [--setting NS:KEY=VALUE]... [--sdcard-file PATH=text:TEXT]... [--keep-data]
                 [--base-option "APKSIM OPTIONS"] [--seed RUN (--only ID,ID... | --only-file PATH)] [--events]
                 [--record-deps [--deps-packages PKG,PKG]]
                 [-- APKSIM_OPTION...]
```

- `--out DIR`: `report.html` (one file: the map as a graph you can click, a card per screen with its matrix, findings,
  and with `--base` the comparison with difference images), `crawl.json` (everything the report shows, for other tools),
  `screens/<screen id>/<combination>.png`, `base-screens/` and `diff/` with `--base`, and each instance's log in `logs/`.
  The first combination of each screen and the difference images are inlined in the HTML; the rest of the matrix is
  inlined while the page stays under 12 MB, else linked (then keep the folder with the report).
- Budget: `--max-screens N` (stop adding screens), `--max-seconds S` (wall time, checked between actions: what is left
  is listed under `incomplete`), `--max-depth N` (screens more than N actions from a start are found but not explored;
  `--max-depth 0` only observes the starts), `--max-actions N` (controls tried on one screen, default 40), `--stable-ms N` (see "Measured", default 200),
  `--max-scrolls N` (scroll-downs looking for more controls, default 4), `--max-repeat N` (a screen reached by N
  identical steps in a row is not followed, default 4: a counter, a stack of dialogs).
- Starting points: the launcher activity, or each `--start URL` (a deep link, as `restart URL`: `apksim://stack`, an
  `https` link the app handles). Every start is a root; screens reached from several are merged.
- Apps behind a sign-in: start where the app is signed in. `--setting global|secure|system:KEY=V`, `--sdcard-file
  PATH=text:TEXT` and any apksim option after `--` (`--feature`, `--locale`, `--network replay FILE`...) go to every
  launch; a deep link past the sign-in screen is a `--start`; `--keep-data` uses the data slot as it is (no `--fresh`, one
  instance, since only one slot is prepared) for an app whose session lives in its data. The crawler never types a
  password: text fields are left alone unless `--fill TEXT` is given, which types it into the fields of each screen
  before its controls are tried.
- Safety: a control whose label (its text, description or the text inside it) matches the skip list is never tapped and
  is shown as skipped in the graph. The default list: delete, remove, erase, clear, reset, sign out, log out, purchase,
  buy, pay, checkout, order, subscribe, unsubscribe, send, submit, post, publish, share, uninstall, close account,
  confirm, transfer, withdraw, donate (whole words, any case). `--skip-text REGEX` (repeatable) replaces the list.
  Controls with no label are skipped too, since nothing says what they do (`--tap-unlabeled` taps them): the report
  counts them. Instances run in apksim's sandbox with a fresh data slot, so what a walk does stays in the slot; add
  `--network-profile offline` after `--` for an app that would send to a real server.
- Matrix: `--matrix device=phone,tablet --matrix appearance=light,dark` (axes of `snapshot.py`: device, appearance,
  locale, fontscale; missing axes take phone, light, en-US, 1.0), or `--matrix-file` naming a snapshot spec whose
  `matrix` and `exclude` are used. `--no-captures` walks only.
- `--seed RUN --only ID,ID...` (or `--only-file PATH`, one id per line, `#` for a comment): start from an earlier walk's
  result instead of from nothing, and walk again only the screens named (see "Seeded walks").
- `--events`: besides the human lines, which stay as they are (`[new] depth N: ...`), one line of JSON on stdout per
  finished screen, per level, and one at the end (see "Seeded walks").
- `--record-deps`: per screen, the app classes whose code ran, their source files and the resources resolved, and the
  same for the app's start (see "Per-screen dependencies").
- `-j N` instances at once (default 3; others share this machine). Exit status: 0 when the walk finished, 1 when it
  found a crash (or with `--base` a difference), 2 for bad arguments or an instance that never started.

## What one walk does

1. `-j` headless instances start (`apksim --headless --snapshot`, the code of `snapshot.py`'s `Worker`: a fixed clock
   and UTC, no animations, carets not blinking).
2. Level 0 is each start (`restart URL`, settle, read the screen). Then, level by level: every screen of the level goes
   to an instance, which replays its path (default configuration, `restart [URL]`, the path's steps), checks it
   arrived where it should, reads the controls from `nodes tree`, and tries each in turn: replay, tap, settle, read
   where it led. A control leads to a screen (new or known), to the end of the app (the last activity finished: an
   `exit` edge), or to a crash. The merge of a level is in screen order then control order, so the graph does not
   depend on which instance was quicker.
3. Controls below the fold: it drags up on the biggest scrollable node (`drag`: no fling), reads again, up to `--max-scrolls` times while
   new controls show; those are tried after the same swipes. The screen scrolled is the same screen (its id after
   each scroll is an alias).
4. A path is a start and a list of control commands (`tap-text Trips`, `tap 158 1110`, `drag ...`), what `drive.py`
   takes: `crawl.json` has it as `path`, the report prints it on the card. It is the shortest the walk found (breadth
   first), not the shortest possible.
5. Captures: for each screen and each combination of the matrix the instance replays the path under the default
   configuration, applies the combination (`device`, `appearance`, `locale`, `fontscale`: configuration changes, as a
   user making them while on the screen), settles and takes `shot`. Screens are spread over the instances; with fewer
   screens than instances the combinations are split too. A path that does not replay (the screen it reaches is not
   the one expected) is a `replay` finding, and a capture that fails a `capture` one.
6. `--base`: the other build is walked and captured the same way (`--base-option "--setting global:K=V"` gives its launches
   options of their own, and with no `--base` the same APK is the base: "what does this setting change"). Screens are
   paired by id, then by replaying each new-only screen's path on the base build. `same`, `changed` (another structure, or
   the same structure and a capture that differs from the base's by more than `--diff-delta` in more than `--diff-pixels` of
   its pixels in any combination; `crawl.json`'s `diffs` and `diff/` have the maps), `new` (the path does not reach a
   screen there) and `gone` (a screen of the base no path of the new build reaches).

## Seeded walks

For a history of screen maps (one per commit), a walk need not start from nothing: a tool that knows which screens a
change can have touched names them, and the crawler walks those again and takes everything else from an earlier walk.
The crawler does not decide what is affected; it is told.

`--seed RUN` names an earlier walk's `--out` folder (its `crawl.json` and `screens/`), and `--only ID,ID,...` or
`--only-file PATH` the screens (ids from that `crawl.json`) to walk again. Then:

1. Every screen of the seed is in the new graph. Those not named are carried over unchanged: their entry (path, depth,
   actions), their transitions and their capture files (hard-linked into OUT's `screens/`, copied where linking fails),
   with no instance time spent on them. The seed's findings are kept, except those whose path passes through a screen
   walked again (they are found again or not). The seed's `incomplete` lines are kept with `seed: ` in front.
2. The named screens are done first, before anything else, so a walk cut short by `--max-seconds` has covered them;
   one that was not reached is named in `incomplete` (`--only screen(s) not explored: ...`). Each is reached by its
   stored `path`, read, and explored in full as a normal walk explores a screen: every control tried, its transitions
   replaced by what they do now, its captures retaken in every combination.
3. A control of such a screen that leads to a screen the seed does not know opens the normal breadth-first walk from
   there, within the usual budgets (`--max-screens` counts the carried screens too; `--max-depth` the depth from the
   starts). One that leads to a known screen is a transition to it; that screen is not walked again.
4. When a named screen's path no longer replays (a step fails, after one retry) or arrives at another structure, what it
   reaches is the screen at that position: it takes the old screen's place in the order, its path, parent and depth,
   records `replaces: OLD_ID`, and is explored. The old id is gone: transitions into it and children's `parent` point to
   the new one. A path that fails is also a `replay` finding; one that ends the app or crashes keeps the old entry with
   `flaky` and no transitions. If the structure it reaches is already a screen of the seed, that screen is walked again
   (and `replaces` the old id) and the old entry is dropped.
5. `--start` is not used (every screen's path has its start). The matrix must be the seed's (`combos` in its
   `crawl.json`, so the carried captures fit; a different one is refused). `--seed` without `--only` would walk nothing
   and is refused (leave out `--seed` to walk everything again); `--seed` with `--base` or `--base-option`, and with
   `--sweep`, are refused. The seed may be OUT itself: its captures are moved aside first.

`crawl.json` is as before, with two keys per screen: `origin`, `"walked"` (explored or found in this run; every screen
of a walk without `--seed`) or `"carried"` (taken from the seed), and `replaces: OLD_ID` on a screen that took a named
screen's place. Readers that do not know them keep working.

`--events` (with or without `--seed`) prints, beside the human lines:

```
{"event": "level", "build": "new", "depth": 1, "screens": 4, "known": 9}
{"event": "screen", "build": "new", "id": "MainActivity-1a2b3c4d", "origin": "walked", "depth": 1, "title": "Trips"}
{"event": "done", "exit": 0, "screens": 15, "edges": 99, "incomplete": [], "seconds": {...}}
```

A `screen` line comes when a screen's controls have all been tried (carried screens at the start, as they need
nothing), with `replaces` when it has one; a screen found but not explored (a budget) gets one at the end of the walk
with `"explored": false`. A `level` line comes with each `[BUILD] depth N:` line (`depth` the level's least: a seeded
walk's first level is the named screens at their own depths). Captures follow the walk, so a screen's line comes before
its captures are taken. `build` is `new`, or `base` for the other build of a `--base` walk. Every event is one line
starting with `{`; the human lines never do.

## Per-screen dependencies

`--record-deps` says, for each screen it walks, which of the app's source files and resources the screen depends on, so
that a later commit's changed files can select only the screens worth walking again (`--seed RUN --only ...`). The
crawler records; a tool that knows the changed files selects.

Instances are launched with the class probes on (`--jvm -Dapksim.deps=true`, the probes of `instrument.py
--record-deps`, docs/affected-tests.md: two static reads at the start of every method of the probed packages) and
apksim's resource hook (every resource the framework resolves through `CppAssetManager2.GetResource` or `GetBag`:
values, layout and drawable files, strings, styles and their parents, references followed). An instance answers the
control command `deps`: `deps reset` starts a span, `deps read` the probed classes whose code ran since, `deps
resources` the resources resolved since (the app's own and its libraries', `type/name`; `deps resources all` adds the
framework's), `deps start` and `deps start resources` the app-start span. `restart` opens a span by itself, after the
old activities are torn down and before the new start activity is made, so one screen's `onStop`/`onDestroy` never lands
in the next one's set. The app-start span (launch, Application, content providers, receivers, up to the first
activity's creation) is kept apart once per process.

For every screen it explores, the crawler reads `deps read` and `deps resources` after the first replay of its path has
settled and the screen has been read (two reads `--stable-ms` apart that agree): the span is the `restart` that begins
the replay, every step of the path, and the screen settling. Each explored screen of `crawl.json` gains:

```json
"deps": {"classes": ["dev.apksim.composeapp.MainActivity", "dev.apksim.composeapp.MainActivityKt$Trips$1", ...],
         "sources": ["dev/apksim/composeapp/MainActivity.kt", "androidx/compose/foundation/layout/Column.kt", ...],
         "resources": ["string/default_error_message", "style/Theme.ComposeApp"]}
```

and the document a top-level `"start_deps"` of the same shape (read once per walk, from the first instance) and
`"deps_packages"` (the probed packages, `dev/apksim/`). All lists are sorted.

- `classes`: as the probes name them (`Class.getName()`). Only classes of the probed packages: by default the app
  package's first two parts (`com.example` for `com.example.app`, which takes in its modules); `--deps-packages
  com.example,org.example.lib` chooses. Library code outside them is not probed.
- `sources`: the files those classes were compiled from, as package path plus file name (`com/example/app/Main.kt`),
  through `scripts/affected.py`'s map over the APK's DEX: each class's source file attribute in its package, Kotlin's
  SMAP for code inlined from other files (so a Compose screen also lists `androidx/compose/.../Column.kt` and the app's
  own inline helpers), and D8's synthetic lambdas take their outer class's file. No repository is read.
- `resources`: `type/name`, the app's and its libraries' (merged into the app's package by the build), never the
  framework's. A resource of a shared library package other than the app's is `pkg:type/name`. `id/...` entries are
  names, not files.

Mapping a changed file to screens is the caller's: a changed source file selects the screens whose `sources` have it; a
changed resource file (`res/layout/x.xml`, `res/drawable-night/y.xml`) the screens whose `resources` have `layout/x`,
`drawable/y`; a changed value file (`res/values*/strings.xml`) each name it defines. A change to any file in
`start_deps` selects every screen (they all run after the start). Never subtract the start set from a screen: a screen
that shares classes with the start (the first screen shares nearly all) still depends on them. What cannot be mapped
selects everything: build and packaging files, `assets/` (not recorded), generated code, native code, constants inlined
by the compiler, dependency versions. docs/crawl-deps-spike.md has the measurements of the prototype and the full list
of what a changed file cannot be mapped through.

What to know:

- Only explored screens have `deps`. A screen found and not explored (`--max-depth`, `--max-seconds`, `--max-screens`)
  has none, and should be treated as selected by any change. A carried screen of a `--seed` walk keeps the `deps` its
  seed had; a screen walked again gets new ones (or none, if this walk has no `--record-deps`: the crawler says so).
  `start_deps` is recorded again by every walk with the flag.
- Code that outlives its screen (a callback that runs after the `restart`, work posted for later) is attributed to the
  next screen walked on that instance; that only makes the next screen walk again more often, never less.
- R8 builds: renamed and merged classes have no reliable source file. The crawler looks at the probed packages' classes
  in the DEX and, when more than a tenth name no source file (R8 without `-keepattributes SourceFile`) or more than
  three tenths have one- or two-letter names (renaming with no mapping file), writes `"deps_unreliable": "why"` at the
  top level. `sources` are still written from what the DEX names, but a reader must not select screens by them then
  (walk everything again, or crawl a debug build for maps); `classes` and `resources` stand. No mapping file is read yet.
- Cost: probes need a cold launch (crawl instances are cold anyway) and probed classes have their own class cache, so
  the first walk with `--record-deps` after a build translates every class once more. See "Measured" for walk times.

## What a screen is

A screen's id is `Activity-HASH`: the activity's class, and the first 8 hex digits of a SHA-1 over a structural string
made from `nodes tree` (the accessibility tree of the windows the user can touch: Views and Compose's semantics
alike). The string has, for each visible node, its class, view id name, the flags that say what it is for (clickable,
long-clickable, editable, checkable, scrollable) and not its state (checked, selected, enabled, focus), where it
sits coarsely (a third of the width, top, middle or bottom of the height; not inside a scroller, whose content
moves) and its children; consecutive children with the same structure (the rows of a list) are written once, so a
list of 3 rows and one of 30 are one screen. Text is not in it, so a list with different rows, a counter and a clock
are one screen; neither are sizes or colours.

Where it takes two screens for one:

- A node that appears or goes: a message under a button, an error line, an expanded row. The same screen in two
  states is two screens (the test app's Settings tab after "Bind" shows one more line: another screen).
- A list whose rows differ in structure (an ad between rows, a row with a button and one without) changes the pattern
  that is collapsed: different screens when the mix changes.
- A scroll position that shows other nodes than the aliased ones (a list scrolled to a place with other row kinds).
- Dialogs: a dialog is its window's tree over nothing (the windows accessibility reports), so each dialog is its own
  screen; a dialog opened on a dialog (the test app's non-modal dialog) is another while the window count differs.
- A screen that animates for ever (a shader, a spinner) is read as it is at the time: its structure is stable, its
  capture is not (`settled: false` in `crawl.json`).

Where it takes two for one:

- Screens that differ only in text or data: the second item's detail page, "Stack 1" and "Stack 2", an article and
  another article. This is on purpose, and means the walk sees one example of each kind of screen, not every record.
- Screens of the same shape with different meaning (two settings pages made of the same switch rows).
- A visual difference with the same structure (a colour, a text): the same screen; `--base` still reports it as
  changed by its capture.

`--max-repeat` cuts the chains that structure cannot: a counter's screen or a stack of dialogs reached by the same
control again and again.

## Measured

Test app, Apple Silicon Mac (12 cores) shared with eight other sessions' builds and runs: the load average was 22-26
throughout (up to 60 earlier), so read these as upper bounds; `-j 3`. `scripts/crawl-check.py` (2026-11-10):

| run | result | time |
|---|---|---|
| walk from home, `apksim://stack`, `apksim://dialogs`, then 2 captures (light, dark) of each screen | 15 screens, 99 transitions, 1 `exit` (SecondActivity's "Finish") | 116 s |
| the same walk again, no captures | the same 15 screens and 99 transitions | 76 s |
| `--base` (same APK, base launched with a Settings value only the slot screen shows), depth 1 | 5 screens, exactly the slot screen `changed` (46,494 pixels differ), the animating Shader tab not compared | 33 s |
| a prepared start (`--setting`), depth 0 | the seed is on the screen it reached | 6 s |

Seeded walks (`crawl-check.py` steps 8 and 9, 2026-11-21, load average ~9): the full walk with captures found 19
screens and 167 transitions in 247 s; seeded with that result and `--only` one dialog screen, the same screens,
transitions and paths in 10 s (that screen walked and captured again, 18 carried). A seed of home and the slot screen
walked with a long Settings value (the slot screen's text wraps: another structure), walked again without it and
`--only` the slot screen: that screen `replaces` the old id and the rest is carried, 10 s against the seed's 23 s.

In the Linux image (`crawl-check.py --docker IMAGE --quick`, depth 2, the same load; what `scripts/ci-smoke.sh` runs): all
checks pass; 14 screens and 58 transitions with captures in 145 s, the repeat walk 75 s, the comparison 84 s, the prepared
start 27 s.

The walk's cost is the replay: reaching a screen is a `restart`, a settle (0.1-0.7 s) and a read of the tree (0.12 s),
done again for every control, ~1.3 s per transition on one instance and about three times that with other work on the
machine. A screen that never settles (the Shader tab) costs ~4 s a settle. `settle` does not wait for a coroutine's `delay()` or a
posted Runnable (it looks for work due that can draw, and a delayed load is neither), so a screen is read until two reads
`--stable-ms` (200) apart agree: the first read of a screen that fills in 100 ms after it opens saw the empty one. Before `--max-repeat` and the scroll aliases,
a walk of the same app followed a stack of non-modal dialogs and the scrolled Settings tab for 17 screens and 117
transitions in 289 s. Captures: 15 screens x 4 combinations (phone and tablet, light and dark) took 190 s in the heavy
load (a `device` switch recreates the activity and lays out a 2560x1600 display), 2 combinations 40 s.

What the first runs found out, which the check now covers: a screen scrolled for a control below the fold is a new
structure unless it is aliased to the screen; the Shader tab's capture differs on every run, so `--base` must not
compare screens that never settled; a replay checked after the scroll steps rather than before them failed every
scrolled control.

`--record-deps` (2026-11-21, load 8-13 from other work): home, `apksim://stack` and `apksim://appcompat` to depth 2,
10 screens, `-j 3`, no captures: 38 s with the flag against 37 s without (class caches warm for both). Three walks of
check step 1's starts on main and three with this change, interleaved, gave the same 19 screens and 167 transitions
every time (205-208 s each): the step 2 failure seen once on an "Add" transition did not come back.

## Limits

- Everything is replayed from a fresh start, so a screen that needs state the path cannot make (a record created on
  the way, a purchase) is reached only through the start (`--start`, `--setting`, `--sdcard-file`, `--keep-data`).
- Only taps, swipes and the fills of `--fill` are tried: no long press, drag, text entry into a search field, back
  button or keyboard navigation, and a control the accessibility tree does not list as clickable is not found.
- A control that opens another app, a picker or a chooser leaves the screen unchanged (a picker is cancelled).
- Crash detection is the instance ending with `FATAL EXCEPTION` in its log (exit 10); an exception the app handles
  is not one. The instance is started again and the walk goes on.
- Matrix captures are taken by changing the configuration on the screen reached under the default one, not by
  reaching the screen under the combination: a screen that loses state on a configuration change shows it there.


## Resilience sweep (roadmap item 2)

`crawl.py --sweep LIST` (`process-death, recreate, rotate, offline, permission-deny, fontscale[=2.0], rtl[=ar-EG], dark`, or `all`)
runs each perturbation on every screen reached and judges it. State perturbations compare the screen before and after (structure,
visible texts, then pixels within `--sweep-pixels`) as reached and after each of the first `--sweep-actions` controls that stay on
it (a counter). Other rules: `crash`, `endless-spinner` (a ProgressBar still up `--spinner-seconds` after opening with no network,
and not online), `text-cut-off` (the new `t` flag of `nodes tree` and a line in `a11y`: `TextTruncation`, Views by their layout,
Compose by the semantics GetTextLayoutResult; the same rule also comes from the dark case's accessibility audit), `offscreen`, `rtl-not-mirrored` (side-by-side texts keep their order), `a11y-*` (dark).
Findings go to the HTML report and `OUT/junit.xml` (a failing case per finding with the repro commands); `--allow FILE` accepts
findings (`RULE SCREEN [TEXT]`, shell patterns; skipped cases, stale entries listed). Exit 1 on any unaccepted finding.
`apksim://faults` (and `FaultsViewsActivity`) are the test app's deliberate faults; `crawl-check.py` step 5 checks each is found,
the controls beside them that do it right are not, and the allow-list `testapks/composeapp/crawl-allow-faults.txt` turns the run green.

Measured (test app, low load): the faults screens, 32 cases, 30 s; `crawl-check.py` in full 7 min 25 s (sweep step 66 s). Whole test app
(15 screens, `all`): 219 cases in 209 s after a 93 s walk.

Known noise, not yet handled: states reached by tapping a dialog open (the dialogs screens, the scrolled Settings variants) report
"state-lost" because a recreate closes the dialog or scrolls back; a screen whose control's label depends on earlier state ("Italian
vacation") fails to set up (`replay` finding). Run the sweep on screens without those, or allow-list them.

Sweep limits added in review: a sweep the deadline cut short reports a `sweep-incomplete` finding (and its JUnit lists the cases
not run as skipped), so it cannot look like a pass. `permission-deny` sends `permission deny` after each state is reached, which
answers the requests that state's controls raised; requests a replay raises earlier are not answered (launch with
`-- --permissions deny` for that).
