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
                 [-j N] [--setting NS:KEY=VALUE]... [--sdcard-file PATH=text:TEXT]... [--standby] [--keep-data] [--keep-app-state] [--data-ignore PATTERN]...
                 [--base-option "APKSIM OPTIONS"] [--seed RUN (--only ID,ID... | --only-file PATH) [--diff-seed [--diff-controls changed|none|all]]]
                 [--events] [--screen-titles] [--record-deps [--deps-packages PKG,PKG]]
                 [--freeze-network] [--deterministic] [--no-dedup-variants]
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
  identical steps in a row is not followed, default 4: a counter, a stack of dialogs), `--screen-titles` (the title is part of a screen's id: see "Where it takes two for one").
- Starting points: the launcher activity, or each `--start URL` (a deep link, as `restart URL`: `apksim://stack`, an
  `https` link the app handles). Every start is a root; screens reached from several are merged.
- Apps behind a sign-in: start where the app is signed in (see "Signed-in walks" below for the three ways).
  `--setting global|secure|system:KEY=V`, `--sdcard-file PATH=text:TEXT` and any apksim option after `--`
  (`--feature`, `--locale`, `--network replay FILE`...) go to every launch; a deep link past the sign-in screen is a
  `--start`. A start screen with a password field prints `warning: the start screen (...) has a password field: the
  walk stays signed out` with what to do, and crawl.json's `signin.warnings` keeps it. Without `--autofill` the crawler never types a
  password: text fields are left alone unless `--fill TEXT` is given, which types it into the fields of each screen
  before its controls are tried.
- `--keep-data` starts from a prepared data slot (the one a launch with the same `--data-slots NAME` after `--` takes,
  `NAME-0`, else `default`) for an app whose session lives in its data: each instance (`-j N` as without it) runs on a copy of its own (an APFS clone, or a reflink), made
  again from the prepared slot at each of its launches, so replays start from the prepared data as they would from an
  emptied slot, and the prepared slot's data is never written (only its `.lock` file, to see it is not in use). The
  copies keep its serial and ANDROID_ID (a slot's name gives them otherwise) and are removed at the end, a stop
  included; copies a crawl killed outright left (`kTOKENI-0` and their `.device`, unused for 6 hours) are removed by the
  next `--keep-data` walk of that app. `--keep-data` also passes `--biometrics auto` unless `--biometrics` is set after
  `--`, so apps that unlock saved sessions or biometric-gated sections via `BiometricPrompt` stay unlocked across
  workers and relaunches. A prepared slot in use by a running instance stops the walk. An app that stored
  absolute paths into its data folder in its own files reads the prepared slot through them, from the copy.
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
  result instead of from nothing, and walk again only the screens named (see "Seeded walks"). Add `--diff-seed` to
  compare the walked/new screens directly against the seed's graph and captures (semantic text/control diffs plus pixel
  diff maps) without re-walking the base build, with `--diff-controls changed|none|all` (default `changed`: tap only
  new or renamed controls).
- `--freeze-network`: every launch answered from one recording of the app's server answers, the clock at the time it
  began; `--deterministic`: only routes that replay to the same screen are walked (see "Server data and routes that do
  not replay").
- `--events`: besides the human lines, which stay as they are (`[new] depth N: ...`), one line of JSON on stdout per
  finished screen, per level, and one at the end (see "Seeded walks").
- `--record-deps`: per screen, the app classes whose code ran, their source files and the resources resolved, and the
  same for the app's start (see "Per-screen dependencies").
- `-j N` instances at once (default 3; others share this machine). Exit status: 0 when the walk finished, 1 when it
  found a crash (or with `--base` a difference), 2 for bad arguments or an instance that never started.

### Signed-in walks

Three ways to walk what a signed-in user sees, from least to most set-up:

- `--autofill`: each launch signs in with the sign-in saved for the app, as a plain `apksim` launch does
  (`STATE/autofill/PACKAGE.properties`: `work/autofill` in a checkout, `~/.apksim/autofill` for an installed release).
  The file maps what each field says (its placeholder or hint, or the label just above it) to the value to type, and
  `submit` to the text of the button to press:

  ```properties
  Username=tester
  Password=...
  submit=Sign in
  ```

  Write it yourself (`chmod 600`; from a password manager's CLI, say). apksim logs only the field names, and a password
  field's text is left out of `nodes` and crawl.json and drawn masked by the app. Everything else the app shows after
  signing in is in the walk's files as it is (crawl.json, captures, report): a username, a server's address, an
  account's data. Treat OUT as private.

  Without `--autofill` the crawler passes `--no-autofill`, so a walk never signs in with a saved sign-in by accident.
  With it, each start and each replay's `restart` sees the sign-in through before anything is read or captured:
  - A password field on screen: the walk waits (up to 20 s) until the form is filled and its button pressed, then until
    a screen with no password field is up. So the root is the signed-in screen, and what the sign-in writes is part of
    the data the app had at its start (no relaunch for it).
  - No password field soon after the start (3 s after a launch, half a second after a settled `restart`): the app is
    taken for signed in and the sign-in is disarmed
    (`autofill off`), so a form reached later in the walk (a re-authentication screen) is never filled. After a
    `restart` it is armed again (`autofill on`).
  - A form that was filled and did not go away (a wrong password, a server error, a form with no `submit` named):
    **the walk stops**, exit 2, with what it had found so far in crawl.json (`stopped`, `signin.warnings`) and nothing
    read or captured of that screen, so the password is not sent again at every replay. With `-j N` one instance signs
    in before the others start.
  - A form left unfilled (its labels do not match the saved names) is explored as it is, signed out, and not waited for
    again at that start, so a mismatch costs 20 s once.

  Only a password field tells the walk that the sign-in failed. A second step with no password field (a one-time code)
  looks signed in, and each launch would send the password again: do not use `--autofill` for such an account. Nor can
  an app that keeps a password field on its signed-in start screen be walked this way.

  A network recording keeps request bodies as they were sent, the password among them, so `--autofill` is refused with
  `--freeze-network` (and `--seed` of such a walk), `--network record`, `--network-unmatched fetch:` and `--rec` with
  `net`.

  crawl.json's `signin.states` counts how the sign-ins ended (`done: filled Username, Password; pressed Sign in`,
  `gave up: ...`, `not filled: ...`). Every instance (`-j N`) and relaunch signs in again against the real server, so a
  walk of 200 screens may sign in dozens of times: fine for a test account, not for one that locks after repeated
  sign-ins. The test app's `apksim://login` (`LoginActivity`: `tester` / `apksim-test`) is the check
  (`crawl-check.py`, 14).
- `--keep-data`: for an app whose session lives in its data. Sign in once in a launch of your own that takes the slot
  the walk will copy (`apksim --data-slots NAME ... APK`, then `crawl.py --keep-data APK ... -- --data-slots NAME`), quit
  it, and every instance runs on a copy of that slot (above). The copies keep the slot's serial and ANDROID_ID, which a
  server may have tied the session to.
- Fingerprint sign-in: an app that unlocks a saved session with `BiometricPrompt` (a "sign in with fingerprint" after
  the first password sign-in) stays unlocked with `--biometrics auto` (every prompt answered with a match), which
  `--keep-data` passes unless `--biometrics` is set after `--`. Prepare the slot as above, turn fingerprint sign-in on
  in the app's own settings during that launch (`--biometrics enrolled` for it, `biometrics match` when it asks), and
  the walk's copies unlock by themselves. `--autofill` and `--keep-data` together are allowed: the copy is the start,
  and a form that still shows is filled.

## What one walk does

1. `-j` headless instances start (`apksim --headless --snapshot`, the code of `snapshot.py`'s `Worker`: a fixed clock
   and UTC, no animations, carets not blinking). The zone is UTC whatever the Mac's own is (a `--timezone` after `--`
   wins), so a walk compared with a plain launch, or with a walk on another machine, may show times and dates that
   differ by the zone alone; crawl.json's `timezone` records it.
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

### Screens whose replay is not exact

An app with live data (a forecast, alerts, a feed) shows another structure, or other labels, at each launch, so a path
recorded once may not lead back to its screen. The walk is forgiving before it gives up, and does not give up on the
screen's subtree:

- A replay step that fails (a `tap-text` whose label is not there yet, a reply that did nothing) is tried again after a
  real wait of 1.5 s and `settle 6000 300`; so is a control's own `tap-text` before it falls back to the control's
  coordinates. A screen reached with another structure is read again after the same wait.
- A screen is replayed up to three times before it is explored, the third waiting longer for the start screen.
- When all three fail, the screen is a `flaky` finding (and `flaky` in `crawl.json`, "FLAKY" on its card), and its
  controls, as the walk saw them when it found the screen, are still tried, each on what the path reaches now: any
  structure of the same activity will do, as long as it is not another screen the walk knows and shares a control with
  the screen as first seen (in a single-activity app every screen is "the same activity"). A step whose label changed
  (`tap-text Ski 3°`, now 4°) is tried as the tap at its coordinates when it was recorded, but only on a control there
  now that the skip list allows: a layout that moved never puts a Delete or Sign out under the old point. What the
  controls lead to is followed as from any screen, so screens below a flaky one are found, and may be flaky in turn.
  Edges tried on another structure carry a note saying so. A control of a flaky screen that leads to a screen of the
  same activity with the same controls and title (numbers left out: a refresh) is an edge back to it, not a new screen.
  A control that worked when its screen replayed and does not now is tried on what the path reaches now too.
- Each control of a screen is tried from a new launch that replays the screen's path. When that replay misses the screen
  three times in a row (`ARRIVE_MISSES`: it lands on another screen the walk knows, as when a live list's order
  changes), the screen's other controls are not given a launch each to find that out again: they are `FAILED` edges
  noted "not tried", and the screen is a `flaky` finding saying so. A replay that reaches the screen starts the count
  again.
- A flaky screen is captured the same way, where its path leads now, so its picture may show other data.
- A screen that never settles (an endless animation) pays the full `settle 6000` on each retry: ~7.5 s per failed step.
- `scripts/crawl-replay-check.py` (CI's quick checks, no apksim) walks a stand-in app with each of these.
6. `--base`: the other build is walked and captured the same way (`--base-option "--setting global:K=V"` gives its launches
   options of their own, and with no `--base` the same APK is the base: "what does this setting change"). Screens are
   paired by id, then by replaying each new-only screen's path on the base build. `same`, `changed` (another structure, or
   the same structure and a capture that differs from the base's by more than `--diff-delta` in more than `--diff-pixels` of
   its pixels in any combination; `crawl.json`'s `diffs` and `diff/` have the maps), `new` (the path does not reach a
   screen there) and `gone` (a screen of the base no path of the new build reaches).

### Unstable screens

A screen read at the wrong moment is no baseline: a seed that caught a list in its error state ("Could not load"), or
half filled, has a later walk of the same build report that screen changed, or gone, which is noise in a pull request's
evidence. So every screen a walk explores is checked once on arrival, and `unstable` in `crawl.json` (a reason, as one
line; "UNSTABLE" on its card) when either holds:

- **Its content changed between two reads.** Once the screen is read (after `settle` and the reads that agree on its
  structure), it is read again `--stable-ms` (200 ms) later: one `ui-tree` round trip, after any wait that time already
  covered (the inline capture, the deps read). Visible texts and the controls' states (checked, enabled, bounds) must be
  what they were, clock strings (`HH:MM`) left out as everywhere else. A counter, a ticker, a request's answer arriving
  after `settle` all show here: "its content changed when read again 200 ms later: 'tick 0012' gone; 'tick 0014'
  appeared". Another structure on the second read says so too.
- **Requests of the app failed while it was reached.** apksim counts the app's failed requests, with or without a
  `--network` option (`network failures`, `NetFailures`): connects refused, unreachable or timed out, host names that
  did not resolve, requests a replay (`--freeze-network`) had no answer for, and, while the tap reads the app's
  connections, exchanges that ended with no answer (not a stream the app cancelled itself) or a 5xx. The count is read just before the replay's `restart` (or
  taken as 0 for a launch made for it, or for an instance still on the screen its launch opened, which is read with no
`restart`) and again after the second read: "2 requests of the app failed while it was
  reached (last: api.example.com:443 connect failed: ...)". Not counted: a lookup the JDK answers from its cache of
  failed ones (10 s), a 4xx, the JDK's own HttpURLConnection while no `--network` option is on, and native code's
  sockets. A failure that has nothing to do with the screen (an analytics call) makes it unstable as well: the walk
  cannot tell, and says at its end how many of its screens are unstable, with a hint when more than half are (an app
  that cannot reach its servers from here).

A launch's own failures before it (a standby's) are not counted: the count starts at each launch (and each test). One
request can count more than once when it fails over (a second address, a retried pooled connection), and one that
fails over to an answer still counts.

It is decided where the screen is explored, so a screen found and never explored (a budget) has none, and a `flaky` one
is not checked (it is already known not to replay). A screen walked again in a `--seed` walk gets this walk's answer;
a carried one keeps the seed's. `--no-stability-check` turns both checks off (no extra read, no `network failures`).

With `--diff-seed` (and `--base`), a pair that would be `changed`, `new` or `gone` where its screen is unstable in the
seed (or the base build) or in this walk is `unstable` instead: its `was` is the status it would have had,
`unstable_in` the sides (`base`, `walk`), its `why` says on which side and why, then what it would have said (`ui_diff`
and `diffs` stay as computed, and the report shows them). Those pairs are also listed in `crawl.json`'s `unstable`
(`id`, `base`, `was`, `in`, `why`), shown apart in `report.html`, printed at the end, and do not fail the exit status.
A pair that was stable in the seed and is unstable only in this walk may be the change's own doing (a broken request,
a screen that now flickers): the end of the run names those apart, and `--fail-on-unstable` counts them as differences
that fail the exit status. A pair that compares `same` stays `same`, unstable or not. A walk of an unchanged build
against its own seed therefore reports no changed, new or gone screen for such screens. `crawl-select.py` lists the
baseline's unstable screens in its `--json` (`unstable`: id to reason) and notes the selected ones. A real change on a
screen that is unstable in either walk is listed as `unstable`, not `changed`: look at that list too, and walk the
seed again when it is long. The pair's older `unstable` key (combinations that never settle, whose captures are not
compared) is unrelated and unchanged.

The test app's `apksim://unstable?mode=tick` (a counter every 100 ms) and `?mode=fail` (a connect to a loopback port
nothing listens on, as it opens) are the two cases: `scripts/crawl-check.py` step 15 walks them and the same build
against that seed; `scripts/crawl-diff-seed-check.py` checks the comparison on a synthetic graph.

## Repeatable walks

`restart` begins a replay with no activity, no saved state and no ViewModel, but the app's own data stays: its files,
preferences, databases, /sdcard and Settings values. A walk that tapped "Dynamic colors" or a theme option captured
every later screen of that instance in another colour, and paths that ran through a toggle replayed onto other screens,
so two walks of one build gave different maps (36 and 38 screens of one app's settings, 17 screens' captures apart).
So each replay starts from the data the app had at its launch:

- After the first replay of a launch (`restart`, settle), the instance notes what its data slot holds: every file of
  the slot and of its device folder with its size and modification time, and each SharedPreferences file by its
  values (`Instance.data_state`). Left out, as no screen reads them: the app's caches, AndroidX's profile installer
  (it writes once some seconds after every start, so each new launch would call for another), WorkManager's own
  database, SQLite's `-shm` index (readers write it too) and the slot's lock. Also what the instance itself keeps
  across a `restart`: a permission request nobody answered (the walk never answers one) and runtime grants
  (`permission`), so a replay after a tap on "Camera" is not a replay with a request still waiting.
- Before each replay (explore, capture, sweep), it compares: when the app has written anything since, the instance is
  quit and launched again on an emptied slot (`--fresh`, as at the walk's start) before the replay goes on.
  Preferences count key by key: a value that changed or went is a write, and so is a key that appears, unless it
  appeared with that value while a path was being replayed (navigation, before any control is tried). Opening a
  settings screen writes its defaults (a fresh slot has none), which every replay through it repeats; a toggle tried on
  a screen is not on a path, so its first write counts.
- A sweep case that sets the instance up before its replay (`network offline`) relaunches first, so its replay runs
  on the instance it set up.
- `crawl.json` counts these launches (`relaunches`) and what caused them (`relaunch_files`: files, `file#key` for
  preferences). Each costs a cold launch (one or two seconds); an app that writes on its own at every turn (a log
  file, a timer's record) costs one per replay. On a podcast app's settings (38 screens, ~300 transitions) comparing
  files alone relaunched 207 times, all for preference defaults, and explored in 850 s against 455-590 s before.
- A SQLite database is compared by its rows, not its files (Python's `sqlite3`, read-only, which reads the `-wal` too): a hash
  per table, leaving out Room's `room_master_table` and `room_table_modification_log`, `android_metadata` and
  `sqlite_sequence`; the `-wal` and `-journal` files themselves are not compared (a write-ahead log changes with a
  checkpoint or an invalidation tracker's row, which no screen shows). A database that cannot be opened is compared by
  size and date, with its `-wal` and `-journal`. `relaunch_files` then names the table that changed (`databases/app.db#events`), as it names a preference's key.
- `--data-ignore PATTERN` (repeatable) leaves out what an app writes at every action and no screen reads: a file of the slot
  (`fnmatch`, `databases/analytics.db`), a table (`databases/app.db#events`) or a preference key
  (`shared_prefs/x.xml#last_seen*`). Put there what `relaunch_files` shows a walk relaunching for.
- `--keep-app-state` replays on the instance as it is (faster; the old behaviour). With `--keep-data` a relaunch is on a new
  copy of the prepared slot.
- Within a single screen's exploration, when a control leaves the slot's data unchanged from arriving at that screen,
  the crawler reuses the screen in place (for self-loops, or after a verified `back` / dismiss button returns to the
  exact same screen and UI signature) or uses a self-verifying warm `restart` (falling back to a full relaunch if the
  replayed screen's signature differs). Plain walks (without `--base` or `--sweep`) run with GPU rendering, `--max-fps 30`,
  `--audio off`, `--anim-scale 0` at launch, `-Dapksim.lazyRaster=true` (skip CPU raster until `shot`),
  `-Dapksim.fastTransitions=true`, combined `ui-tree` reads, in-process `wait-tree` waypoint waits, `fast-drag`,
  tree-quiet early exit in `settle` for continuously animating screens, a shared standby pool (`APKSIM_STANDBY_POOL`,
  default `2`, with `400 ms` spawn stagger), capped crawl JVM memory (`APKSIM_CRAWL_MEM=1`: `-Xms128m -Xmx512m`),
  action-level work stealing across workers, async worker retirement on per-generation `--keep-data` slots,
  WAL-incremental SQLite hashing, navigation-constant and background startup-sync learning (existing SQLite tables
  rewritten autonomously during shallow navigation across three or more fresh launches stop triggering relaunches, while
  user-action mutations continue to relaunch), inline capture of the default combination (`phone-light-en-US-1`) during
  exploration, and screen-variant / parameterized-sibling deduplication (opt-out via `--no-dedup-variants`; multi-window
  dialogs stay separate and `--diff-seed` never hides changes on carried destinations).

What it does not cover: state an app keeps in memory only (a static that a tap changed and nothing writes) outlives a
`restart`, as before. External storage is at a device's path, `/storage/emulated/0`, in every instance
(`DevicePaths` in AGENTS.md), so a screen that prints it is the same in each. The app's internal folders
(`getFilesDir`, `getCacheDir`, databases) are still host folders of the instance's slot, so a screen that prints one
of those still differs between instances.

### Server data and routes that do not replay (`--freeze-network`, `--deterministic`)

A screen that shows what a server sends (weather, a feed, prices) is another screen, or another picture, at each launch:
its rows come in another order, a label is longer, a card is there or not. Replays then miss their screen, and pull
request comparisons show changes no commit made. Measured on a weather app walked live: about a third of all replays
reached the end of their path and found another screen there, and the service began refusing the walk's ~2,500 launches.

- `--freeze-network`: every launch is answered from one recording, `OUT/network.har`. A launch replays a copy of the
  recording as it is when the launch starts and fetches from the server only what the recording lacks
  (`--network replay COPY --network-unmatched fetch:FILE`, docs/network.md), staging per-launch copies under apksim's
  `STATE/tmp` so `--freeze-network` stays warm with `--standby`. What it fetched is merged into the
  recording when the launch ends, the first answer to a request kept (method, URL and body), so from then on every
  launch sees that answer. The app's clock is the time the recording began (minute), not the fixed 2026-01-01 09:41, so
  "in 20 minutes" in a server's answer stays in 20 minutes. `crawl.json` records `clock` and `network` (the recording,
  its entries, how many this walk added). A seeded walk starts from its seed's `network.har` (copied: the seed's is not
  written) and runs at its seed's `clock`, so a pull request's walk shows the base's data. Requests the engine cannot
  answer from a recording (a connection that is not HTTP, WebSocket, a client that offers only HTTP/2) are not frozen.
  `network.har` holds what the signed-in account was sent: it stays in OUT.
  A server that signs the app in with a session cookie: recordings redact `Set-Cookie`, so the cookie of each fetched
  sign-in is kept in `OUT/network.har.secrets` (mode 600; merged and copied to each launch with the recording, and from
  a seed), and a launch whose sign-in is answered from the recording gets the server's cookie with it, so what it then
  fetches is signed in. That file is a live session: never share or commit it (`network.har` alone still replays, signed
  out). A 401 or 403 a launch fetched after a sign-in replayed without its cookie (a `network.har` copied without its
  `.secrets`), or a 401 the server gave the recorded cookie (the session ended), is not frozen: the app sees it, the
  recording does not keep it, the walk's last lines say so, and `crawl.json`'s `network` has `not_kept` (how many) and
  `not_kept_requests` (the first 20, with the reason). Screens walked then may show a signed-out or error state: walk
  again from an empty recording. docs/network.md "Fetching what a recording lacks" has the rules.
- `--deterministic`: only routes that replay to the same screen are walked. A screen whose path, replayed three times,
  leads elsewhere is a `flaky` finding saying it was not walked, and nothing under it is walked; a control whose label
  is not there at a replay is a `FAILED` edge, not a tap at the label's old place; a control is never tried on what a
  path leads to instead of its screen. Waiting longer for a screen or a label still loading is kept: that is time, not
  another route.

Together, a route is walked when it replays with the server's answers held still, and dropped when even then it does not.

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
   `crawl.json`, so the carried captures fit; a different one is refused). `--seed` with neither `--only` nor `--only-file` would walk
   nothing and is refused (leave out `--seed` to walk everything again); `--seed` with `--base` or `--base-option`, and with
   `--sweep`, are refused. The seed may be OUT itself: its captures are moved aside first.

### Diffing against the seed (`--diff-seed`)

With `--seed RUN --only ... --diff-seed`, the crawler compares the walked, replaced, and newly discovered screens
directly against the seed's `crawl.json` and `screens/` without re-walking the base APK:

- Carried screens are immediately `same` with zero instance time; walked screens are paired by id (or `replaces: OLD_ID`
  when structure changed), diffed semantically on `title`, visible `texts` (clock ticks excluded) and `controls`
  (`ui_diff` in `crawl.json` and shown in `report.html`), and pixel-diffed against the seed's captures using the same
  renderer recorded in the seed (`"snapshot"` in `crawl.json`).
- Where a difference is (issue 630). Each screen in `crawl.json` records `text_boxes` (the bounds `[l, t, r, b]` of
  each entry of `texts`, in the capture's pixels) and `size` (`[w, h]`). A `ui_diff` `texts` or `controls` entry gets a
  `bounds` object beside its lists, of the same shape: `added[i]` is the box of `added[i]` in the new build, `removed[i]`
  its box in the base build, and `replaced[i]` is `[base box, new box]` (`null` where a box is not known, as for a seed
  written before this); `ui_diff.size` is the display's size (the new build's; boxes assume both builds ran on the same display, and a control's box is where the walk found it, so it is only reliable above the fold). `diffs[id][combo]` gets `size` and `regions`: boxes of
  the connected areas of the difference map's red pixels (differences over `--diff-delta`; areas a few cells (8 px each, a gap of up to 2) apart are one; only for a comparison that counts as different),
  largest first, at most 8, with `regions_more` counting the rest. Read from the map by `diff_regions`, which needs no
  imaging library; `scripts/crawl-check.py` checks it.
- `--diff-controls` chooses how much control exploration runs on each `--only` screen whose structure did not change:
  `changed` (the default with `--diff-seed`) carries edges for untouched controls and taps only new or renamed controls;
  `none` skips scrolling and button taps for a pure screen UI + pixel diff; `all` re-explores every control.
- To keep PR diffs fast (a few seconds for a single affected screen), `observe_at` keeps each worker parked on its
  reached screen so `discover_screen` reuses it without a second replay or relaunch, the worker pool caps at
  `min(-j, len(--only))`, teardown runs in parallel, and GPU-rendered `--diff-seed` runs opt into apksim's warm standby
  JVM when available (`APKSIM_STANDBY=0` forces a cold launch). `--standby` gives any GPU-rendered walk the same hand-over (a plain or `--seed` walk, whose relaunches are the most numerous). It does nothing with `--base`, `--base-option` or `--sweep`, which render with `--snapshot`. A standby a walk starts stays up for an hour (`apksim --stop-standbys`), and the walk uses the folder's own standby. apksim keeps one standby per folder, so with `-j N` the instances share it and a relaunch that finds it taken launches cold.

A carried screen that the new graph no longer leads to from a start is not `same`. Its `compare` entry is `gone`
(`id` null, `base` its id, with `why`) when every screen that led to it in the seed was walked again or is itself
gone, and `unknown` when one that led to it was neither (a screen the seed never explored, or it had no recorded way
in), as it may still exist. `doc["edge_changes"]` (`edges` stays the graph's own list) lists the transitions out of screens walked again against the seed's:
`added` (`from`, `action`, `to`), `removed` (`from`, `action`, `base_to`) and `retargeted` (`from`, `action`, `to`,
`base_to`), with ids of screens replaced in the walk mapped to their new ids; skipped controls are ignored, and `removed` is
unreliable when the walk was cut short. A screen walked again that crashed, was cut short or is `flaky` is unknown
ground: what only it led to is `unknown`, not `gone`. `unknown` does not fail the exit status; `gone` does. With `--diff-controls none` (one screenful read, nothing
tapped) no carried screen is ever `gone`, only `unknown`, and `removed` is left empty with a `note`; the same holds for
what a screen led to when a budget kept one of its transitions from being followed. Transitions of carried screens are the
seed's and are not listed. `scripts/crawl-diff-seed-check.py` (CI) checks this on a synthetic graph.

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

With `--diff-seed` each walked screen also gets a `compare` line as soon as its last capture is taken and compared
with the seed's (carried screens get none: their `screen` event says `origin: carried`):

```
{"event": "compare", "build": "new", "id": "Settings-1a2b", "base": "Settings-1a2b", "status": "changed", "why": "text 'Save' -> 'Done'", "ui_diff": {...}, "diffs": {"default": {"differs": true, "pixels": 5120, "map": "diff/Settings-1a2b/default.png"}}}
```

`ui_diff`, `unstable` and `was` (a pair left out as unstable, below "Unstable screens") are present when the pair has them. A walked screen the seed lacks gets its line after the
capture stage, with `"base": null` and `"status": "new"`; a screen of the seed nothing reaches now (`gone`) is only in
`crawl.json`. The lines come while the captures run, so a viewer sees
a pair when its screen is finished rather than when the whole run ends; the walk itself still ends first (the
captures follow it), and a screen whose captures were all taken during the walk is compared after the capture stage.
`crawl.json`'s `compare` and `diffs` are unchanged.

### Stopping and continuing a walk

A walk cut short says what it left. `crawl.json` has, in every walk:

- `unexplored`: the ids of the screens found and never explored, and of those explored only in part (the time ran out
  while their controls were tried; those are also in `partly_explored`), in discovery order. Carried screens count as
  the seed left them (the seed's own `unexplored`), and the `--only` ones until they are walked again.
- `cut_by`: what cut the walk short, any of `max-seconds`, `max-screens`, `max-depth` and `stopped`; `[]` for a walk
  that finished.

SIGTERM or SIGINT (a Stop button) stops a walk: the instances are killed, and `crawl.json` and `report.html` are
written with what is known at that moment (the screens found, the transitions of every level merged so far, the
captures taken), `"stopped": true` and an `incomplete` line `stopped (SIGTERM) after N s: M screen(s) not explored`;
the exit status is 143. The screens of the level being explored when the stop came are unexplored (a level is merged
when all its screens are done), and captures follow the whole walk, so a walk stopped while exploring has none. The
instances are killed, not quit, and nothing is captured: the test app's walk (11 screens) exits under 10 ms after
SIGTERM, so a caller's 4 s before it kills the process tree is ample. No instance is started once the stop has begun
(the pool's threads may still be replaying), the walk is read before the instances are killed, so their ends are not
findings, and no `--events` `done` event is written. A stop during a `--base` walk writes the new build's walk.

`--seed STOPPED_OUT --only-file IDS` with `unexplored` written one per line goes on from there: those screens are
explored and every new screen they lead to is walked, breadth-first, within the budgets given again (`--max-screens`
counts the carried screens; raise it for a walk it cut). The carried screens the seed has no captures for (all of a
walk stopped before its captures) are captured in this run, when the seed was stopped; a finished seed's carried
screens keep the captures it has. The seed's `stopped (...)` line is not carried into `incomplete`: the continuation
says itself whether it was cut short. An `--only-file` with no ids is allowed: that run only
takes the missing captures (a walk stopped during its captures). Repeated, a walk stopped any number of times ends
with the graph one uninterrupted walk finds (`scripts/crawl-check.py` step 13).

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

Mapping a changed file to screens is implemented in `scripts/crawl-select.py` (unit-tested in
`scripts/crawl-select-check.py`):

```bash
scripts/crawl-select.py BASE_OUT --diff origin/main...HEAD --repo REPO --out-ids affected.ids
scripts/crawl.py NEW.apk --out PR_OUT --seed BASE_OUT --only-file affected.ids --diff-seed
```

A changed Kotlin/Java source file selects the screens whose `sources` contain it; a changed Android resource file
(`res/layout/x.xml`, `res/drawable-night/y.xml`) selects the screens whose `resources` contain `layout/x` or
`drawable/y`; a changed `res/values*/*.xml` file is narrowed via `git show` to only the modified resource names; a
changed Compose Multiplatform resource under `composeResources/values*/*.xml`, `drawable*/`, or `font*/` is narrowed
the same way and resolved through Kotlin `Res.<type>.<name>` references in `--repo`. A change to anything in
`start_deps` (or `deps_unreliable`, manifests, build files, `assets/`, `composeResources/files/`, or unprobed packages)
reports `mode="full"` so the caller falls back to a full walk. Non-Android files (docs, tests, iOS/desktop source sets)
are ignored by default (`--no-default-ignore` disables that). Never subtract `start_deps` from a screen: a screen that
shares classes with startup still depends on them. `docs/crawl-deps-spike.md` has the measurements of the prototype and
the full list of what a changed file cannot be mapped through.

`--json` prints the result for another tool to rely on: `mode` (`none`, `partial`, `full`), `selected`, `screens` (per
selected screen, why: `{"file", "via": "source"|"resource"|"constant"|"unexplored", "name"?}`; `name` is the resource
or constant), `ignored` (changed files the ignore globs dropped), `unplaced` (changed sources in a probed package that
no recorded screen ran and that no constant scan placed: not "no screen changes", only "nothing here says which"),
`full` (for `mode: full`, `{"file", "rule"}`: the file and the rule that forced it, such as `start_deps`,
`unprobed-package`, `unmappable-resource`, `deps-unreliable`) and `diff_controls` (`all` when a source selected a
screen, since a changed click handler is not seen by `--diff-controls changed`, which compares labels; else `changed`).
A file that only declares constants (`const val`, `static final`; no function or class) is inlined into its users, so
no screen records it: it selects the screens whose `sources` include a file under `--repo` that mentions one of its
constants (a name scan, not a resolver). A screen found and never explored is selected by any change that is not
ignored (also an unplaced one), not only when another file selected something. Still not selected: runtime `.txt` and
`.md` content (ignored by default) and a constant used only through reflection.

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
- Cost: `--record-deps` launches can be warm (`--standby`: the standby reads `-Dapksim.deps` as it takes the launch, `Probes.reload`, before any app class is translated), and probed classes have their own class cache, so
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

A text that is not a control and has nothing under it (a label) is placed where its parent is, not by its own centre:
its box is as wide as its text, so a longer one ("Massachusetts, United States" where another place has "Florida,
United States") would move into the next third and make a detail screen per item. Walks write this as `"screen_ids": 2`
in `crawl.json`; walks before it made ids from every node's own centre (`1`, a `crawl.json` with no `screen_ids`). A
seeded walk makes ids the way its seed did, so the screens it walks again are compared with the seed's; a whole walk
made after an update has other ids than one made before it for screens with such labels.

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
- Screens built from one layout and told apart only by their text (a detail screen per item, a generated app's
  pagers: two "pager" screens with other titles) are one screen, whatever their titles, so an edge to the second is
  recorded as an edge to the first and the second is never walked (issue 607: it looked like a replay landing in
  the wrong section; the replay was right). `--screen-titles` puts the screen's title (the first text that is not a
  number) into its id: ids then differ from a walk without it (do not seed one with the other), and a title that
  changes with state (a counter in the top bar) makes a new screen each time, bounded by `--max-repeat` and
  `--max-screens`. A seed must be walked with the same setting (`--seed` refuses a mix); under `--base` and `--sweep` a changed title shows as another screen, not as changed text.
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
  A ListView's rows (a popup menu, a toolbar's overflow, a ListPopupWindow sheet) count as clickable when enabled,
  as a device's accessibility reports them (`Nodes.listItem`, apksim 0.60.0). Their screens hash differently from a crawl
  taken with an earlier apksim, so compare `--base` crawls made with the same version.
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
