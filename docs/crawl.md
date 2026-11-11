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
                 [--base-option "APKSIM OPTIONS"] [-- APKSIM_OPTION...]
```

- `--out DIR`: `report.html` (one file: the map as a graph you can click, a card per screen with its matrix, findings,
  and with `--base` the comparison with difference images), `crawl.json` (everything the report shows, for other tools),
  `screens/<screen id>/<combination>.png`, `base-screens/` and `diff/` with `--base`, and each instance's log in `logs/`.
  The first combination of each screen and the difference images are inlined in the HTML; the rest of the matrix is
  inlined while the page stays under 12 MB, else linked (then keep the folder with the report).
- Budget: `--max-screens N` (stop adding screens), `--max-seconds S` (wall time, checked between actions: what is left
  is listed under `incomplete`), `--max-depth N` (screens more than N actions from a start are found but not explored;
  `--max-depth 0` only observes the starts), `--max-actions N` (controls tried on one screen, default 40),
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
3. Controls below the fold: it swipes up on the biggest scrollable node, reads again, up to `--max-scrolls` times while
   new controls show; those are tried after the same swipes. The screen scrolled is the same screen (its id after
   each scroll is an alias).
4. A path is a start and a list of control commands (`tap-text Trips`, `tap 158 1110`, `swipe ...`), what `drive.py`
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

The walk's cost is the replay: reaching a screen is a `restart`, a settle (0.1-0.7 s) and a read of the tree (0.12 s),
done again for every control, ~1.3 s per transition on one instance and about three times that with other work on the
machine. A screen that never settles (the Shader tab) costs ~4 s a settle. Before `--max-repeat` and the scroll aliases,
a walk of the same app followed a stack of non-modal dialogs and the scrolled Settings tab for 17 screens and 117
transitions in 289 s. Captures: 15 screens x 4 combinations (phone and tablet, light and dark) took 190 s in the heavy
load (a `device` switch recreates the activity and lays out a 2560x1600 display), 2 combinations 40 s.

What the first runs found out, which the check now covers: a screen scrolled for a control below the fold is a new
structure unless it is aliased to the screen; the Shader tab's capture differs on every run, so `--base` must not
compare screens that never settled; a replay checked after the scroll steps rather than before them failed every
scrolled control.

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

