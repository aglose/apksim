# Scripts, the docs folder, the documentation site and CI

Agent reference notes, moved out of AGENTS.md so they are read only when needed. A section another doc or a
code comment cites as `AGENTS.md "Name"` is in one of these files: AGENTS.md's index says which.

## Layout notes
- `scripts/`: `setup.sh` (one-time; fetches Temurin 24 with `fetch-jdk.sh` and the released test app with
  `fetch-testapp.sh` when there is no JDK 24 or Android SDK), `jdk.sh` (sourced: finds the JDK 24 every build script
  uses; `build.sh` records it in `work/jdk.path` for the launcher), `build.sh` (Java → `work/apksim.jar`), `build-native.sh`, `train.sh` (AOT cache),
  `bench.py` (launch times, cold and warm; `--mem` adds footprints; in the Linux image `--cold` only, mounted at `/opt/apksim/scripts`), `startup-gate.py` (CI's startup-time gate: this build against an anchor
  release, interleaved; docs/startup-gate.md), `drive.py` (control channel client), `ctl.py` (find and drive running instances, app
  data), `fleet.py` (N headless instances driven through one script at once: results, readiness, memory; see
  docs/parallel.md), `scene.py` (a scene: roles with their own APK, device and options driven step by step,
  waiting on one another, against one shared fixture; `scene-check.sh` in CI; docs/scenes.md), `snapshot.py` (screenshot tests: screens × device/appearance/locale/fontscale matrix against
  goldens, HTML report, `--update`; see docs/snapshots.md), `instrument.py` (androidTest suites, JUnit XML;
  `--apksim CMD` runs the Linux image instead; `--test-timeout SEC` kills a process stuck SEC s on one test;
  `instrument-check.py` checks how it handles a process that dies or hangs,
  against a stand-in apksim), `affected.py` (instrument.py's test-impact recording and selection and its
  time-balanced shards, see docs/affected-tests.md; `affected-check.py` checks them on the test app, breaking a file
  and rebuilding it),
  `subsystem-audit.py APK...` (which device subsystems an APK refers to, against a status table at the top of the script:
  simulated / partial / in progress / missing and the option that drives each; references come from decoding every
  method's instructions and are counted for the app's own classes, its libraries and the manifest separately; also
  manifest attributes by resource id, `lib/*/*.so` imports, `--json`; update the table when a stand-in lands;
  `subsystem-audit-check.py` in CI's quick checks, with `testapks/subsystem-audit-fixture`'s binary manifest),
  `check-launch-options.py` (CI quick check: every option the launcher parses has a row in `launch-options.tsv`, yes /
  server-only (the server adds it itself; a page cannot name it) / `no: REASON` for serve, beam (a surface of its own:
  its few `serve.beam` branches are named in the script) and mcp, matched by line against `Catalog.LAUNCH_OPTIONS`, what Session/Serve add and
  mcp.py's list; `no: undecided` only for the cells in `launch-options-undecided.txt`: decide a new option, do not add it
  there), `check-translator.sh` (translates and runs `testapks/translator-fixture`, see "Instrumentation tests"),
  `adb-check.py` (the adb device as a tool's on-device server uses it, see "adb device"), `video-check.py` (recorded
  video over `--control`: selftest on the H.264 fixture clip, `apksim://video`, no HEVC decoder listed; `--hevc` for an
  image built with it: the HEVC clip and the Settings clip; CI runs it in both Linux
  images from `ci-smoke.sh`), `swipe-check.py` (a timed swipe flings, a held press does not scroll; `ci-smoke.sh`), `timer-check.py` (app timers
  against real time and onUserInteraction per input path, on the test app's `apksim://timer`), `idle-zoo-check.py` (`settle` against each source of "the app still has work" on `apksim://idle-zoo`, alone and in combinations, known failures in `idle-zoo-known.txt`; also against `--max-fps 10` with `--tag maxfps10`; by hand, not in CI), `bundle-check.py` (failure
  bundles, see "Instrumentation tests"), `observer-gate.py` (a suite run plain and with every observer on, bundles
  of all tests, movies, the flight recorder's net and db channels and coverage, compared test by test: same tests, same
  outcomes, observed wall time within a factor (3); `--compare` runs the comparison on two XMLs; CI's `tests` leg runs it on a
  subset: the UI-heavy classes and the FailureBundle* ones, run with `-e failOnPurpose true`; the whole test app's suite
  is 488 tests, 64 s plain on the Mac, and passes the gate),
  `beam-check.py` (`apksim beam` and
  `serve --allow-beam` started and driven as a page would, docs/beam.md), `beam-parity.py` (the app in its window
  against the same app beamed into Chrome, same input: new frames per display refresh, fps, CPU, latency),
  `beam-page-check.py` (the page's start on this Mac in Chrome: Stop while the agent has not answered, a full agent's
  fallback to the server), `beam-size-check.py` (Fill mode and the simulator window as two views of one display, in
  Chrome), `beam-move-check.py` (a running session moved between the server and this Mac with no blank frame, and
  with its data, in Chrome), `beam-launchd-check.py` (the launchd agent's jobs and its socket-activated start and idle exit, with a
  stand-in for launchd; `--real` installs a test LaunchAgent),
  `skin-check.py` (`apksim skin` against a frame drawn with a known hole), `share-check.py` and `share-swarm.java` (a
  shared serve session's view-only link and many viewers at once, see "apksim serve"), `rec-check.py` (the flight recorder against the test app and the network fixture: every channel, a checkpoint read
  back, the saved bundle, a crash bundle), `replay-adb.py` (a flight recording replayed on a device over adb, shipped in
  releases; `replay-adb-check.py` checks its plan on a synthetic bundle, no device), `beam-device-check.py` (`apksim beam
  to-device` against a stand-in `adb` and a temporary state folder), `tabfiles-check.py` (a tab's file pushes and their
  refusals, and a recording saved and downloaded, against a running `apksim serve --allow-rec`), `kept-check.py` (kept
  sessions on a running `apksim serve --keep-max N`),
  `crawl.py` (explores an APK's screens breadth-first over the control channel: graph, replayable paths, a capture per
  screen per matrix combination, crashes, `--base` comparison of two builds, `--seed`/`--only` walks, `--record-deps` per-screen classes, sources and resources; each replay from the data the app had at its launch: an instance whose app wrote since is launched again on an emptied slot, `relaunches` in crawl.json, `--keep-app-state` for the old way; `--keep-data` with `-j N`, each instance on a clone of the prepared slot; `--autofill` signs each launch in with the app's saved sign-in and a start screen with a password field otherwise warns that the walk stays signed out (`signin` in crawl.json, which also records `timezone`: UTC); SIGTERM/SIGINT write what is known, `stopped`, exit 143; every crawl.json names `unexplored`, `partly_explored` and `cut_by`, and `--seed RUN --only-file IDS` goes on from there; docs/crawl.md; `crawl-select.py` picks the screens a PR's changed files can affect from a seed's per-screen deps (`crawl-select-check.py`; `crawl-diff-seed-check.py`: gone screens and `edge_changes` on a synthetic graph) and `--diff-seed SEED` walks only those, reusing the seed's other screens and writing a semantic `ui_diff` (docs/crawl.md; `--json` says why per screen (`screens`), `ignored`, `unplaced` (changed sources no screen ran), `full` (file and rule) and `diff_controls`; a constants-only file selects the screens of the files that mention its constants, a screen never explored is selected by any non-ignored change, so a baseline with one never answers `none`; a changed handler is only a `diff_controls: all` suggestion, and runtime `.txt`/`.md` content selects nothing); `crawl-check.py` checks it; a screen whose path no longer replays (live data) is `flaky` and its controls are still tried where the path leads now, docs/crawl.md "Screens whose replay is not exact", `crawl-replay-check.py` on a stand-in app; `--freeze-network` (one shared `OUT/network.har` via `--network-unmatched fetch:FILE`, clock pinned to start, `crawl-freeze-check.py`) and `--deterministic` (stop walking past routes that do not replay); screen ids ignore label length changes (`crawl-ids-check.py`); plain walks (no `--base`/`--sweep`) run on the GPU with `--max-fps 30 --audio off --anim-scale 0`, `-Dapksim.lazyRaster=true`, `-Dapksim.fastTransitions=true`, combined `ui-tree`, in-process `wait-tree`, `fast-drag`, `Settle.check` tree-quiet fast-exit, shared standby pool (`APKSIM_STANDBY_POOL=2`, 400 ms stagger), capped crawl JVM memory (`APKSIM_CRAWL_MEM=1`, `-Xms128m -Xmx512m`), async worker retirement on per-generation slots, WAL-incremental SQLite hashing, navigation-constant learning, screen-variant / sibling-template deduplication (`--no-dedup-variants`, `variant_aliases`, multi-window overlay guard, `--diff-seed` carried-destination protection), action-level work stealing, in-place / warm-`restart` reuse, and inline capture of the default combination: a ~169-screen app with `-j 6` and light captures, 8,762 s → 966 s, issue 623),
  `pr-evidence.py` (docs/bug-to-pr.md: a recorded bug to pull request evidence, shipped in releases and the image beside
  `crawl.py`: `baseline` (crawl.py `--record-deps` to `--depth`, crawl.py's `--max-depth`, going on from screens a budget
  left unexplored, counting the screens with no deps and why), `verify` (the recording replayed on a base and a new
  build side by side with `-Dapksim.networkTrace=true`: a pasteable verdict with the first divergence's label, pixels
  and region, read from the point's diff.png by crawl.py's `diff_regions`, and requests recorded against asked; exit 0
  for base matched and new diverged, or both matched with `--expect same`; a recording that ends in a crash (its manifest's `crash`) passes when the base
  crashes and the new build does not, and a base crash on any other recording is FAIL; no compared point, or a replay
  without a finished `report.json`, is no verdict, exit 2; unanswered requests are counted from the replay log's
  `network replay:` lines, and app text in the Markdown goes in code spans without query strings; the walks' `--data-slots` and `--fresh` after `--` are not given to
  a replay, which has its own slot), `diff` (`crawl-select.py --explored-only`
  by default, `--cautious` for its own default, then the `--seed --only-file --diff-seed` walk with the baseline's saved
  options), `comment` (Markdown, pictures copied under stable names, `--image-base URL`) and `run` for all four;
  `--base-option`/`--new-option` give one build its own launch options; `pr-evidence-check.py APP [OUT] [--image IMAGE]`
  checks it on the test app, the "fix" a `--feature android.hardware.bluetooth_le=false` launch whose line the Bluetooth
  screen shows, in `ci-smoke.sh`'s crawl part inside the image; a replay applies the flags a recording was made with
  over its own, so the recording is made without that flag; `docs/skills/apksim-bug-to-pr/SKILL.md` is a Claude Code
  skill a user's repository copies),
  `serve-check.py` (a running `apksim serve` driven as a browser tab, see
  "apksim serve"), `slot-check.sh` (a data slot's /sdcard and Settings across launches, `SlotDevice`), `relaunch.sh`,
  `both.py`/`vshift.py` (emulator comparison),
  `prof.py` (summarise macOS `sample` output), `so-imports.py` (native import audit), `prepare-runtime.sh`,
  `gen-gles-shadows.py` (writes `gl/ShadowSimGLES20/30`), `jdi/Trace.java` and `jdi/Ret.java` (breakpoints and return
  values over JDWP), `translate-class.java` (one class's translation, for javap), `translate-all.java` (every class of
  an APK: time per pass and a hash per class, to profile the translator and check a change leaves output identical),
  `corpus.py` + `corpus.json` + `link-check.java` (the app corpus, offline half, docs/corpus.md: ten open-source APKs
  by URL and SHA-256, never run; each class translated, the translated output's every invoke resolved as HotSpot would
  at its first run (IncompatibleClassChangeError, NoSuchMethodError, IllegalAccessError, AbstractMethodError: the shape
  of issues 275, 299, 522), and the arm64 libraries' imports no library, shim table or host provides; `--json`,
  `--fail-on-new BASELINE`, `--selftest` runs the link check on `translator-fixture` with the translator's hierarchy
  knowledge removed, which must fail; a translator change that adds a shape the fixtures lack should add it there
  *and* be run through `corpus.py`; `.github/workflows/corpus.yml`, nightly, not required),
  `api-refs.py` (which framework classes, methods and fields apksim.jar uses that another level's android-all jar lacks,
  and which shadow methods stop binding there; docs/api-levels.md),
  `stub-census.sh` (`stub-census.java`; CI's quick checks: `docs/stub-census.tsv`, every native method of the
  framework jar, 5,934 on API 36: `shadowed-by CLASS` 800 (an @Implements shadow of that class, apksim's before
  Robolectric's, has an @Implementation of that name and parameter types, SDK 36 in range, ShadowLegacy* ignored, a
  shadow superclass counted, `Object` parameters only for looseSignatures), `native-runtime` 894 (a ShadowNative* shadow
  method, or libandroid_runtime registers the name AND the class's exact shadow, apksim's if any, has
  `callNativeMethodsByDefault`), `registered-unbound` 40 (registered, no such shadow: Robolectric's instrumented `X`
  returns the default and `ShadowWrangler` calls `$$robo$$X$nativeBinding` only for such a shadow; proved in a test run:
  `android.graphics.Camera` kept an identity matrix and `YuvImage.compressToJpeg` returned false while Bitmap worked) or
  `unshadowed` 4,200 (a call returns the default). The registered names are `docs/stub-census-registered.txt`, from one
  launch's `-Xlog:jni+resolve=debug` by `--update-registered APK` on a Mac (by name: every overload counts). Last column:
  public/protected member of a public class or internal. No argument checks the table against `work/lib` and
  `work/apksim.jar`, `--update` rewrites it, `--summary` prints natives per package; a changed shadow in `runtime/`
  changes rows, so `--update` after one. A native is a `$$robo$$X$nativeBinding` method with the native flag. Not told:
  a native of a `callNativeMethodsByDefault` class that nothing registers, which throws UnsatisfiedLinkError),
  `make-media.sh` (test clips), `docker-build.sh` and `ci-smoke.sh` (Linux image, CI), `network-check.sh` and
  `network-fixture.py` (record, replay and conditions against the test app, see "Network"), `package-release.sh` (see
  "Releases"),
  `gen-device-skins.java` (every built-in skin, see `Fold`), `fold-frames.m` (the fold animation rendered
  from a skin's frames, no app: see `Fold`),
  `gen-window-frames.py` and `window-frames-check.py` (each window's frame of the test app's window screens on an API 36
  emulator, `testapks/composeapp/window-frames-api36.tsv`, against apksim's `windows`; scenes in `window-frames-scenes.txt`,
  known differences in `testapks/composeapp/window-frames-known.tsv`; docs/conformance.md "Window frames"; the check is
  not in CI yet),
  `hooks/` (`pre-push`: no direct pushes to main; `pre-commit`/`pre-push` run `check-private`, see "Git and pull
  requests").
- `docs/`: `architecture.svg` (the README's system design diagram, a hand-edited SVG with light and dark colours:
  update its boxes when what they describe changes), `ios-simulator-parity.md` (feature gaps and status), `flight-recorder.md` (`--rec`, checkpoints, `.apkrec` bundles, the replay plan), `serve.md` (APKs in a browser tab), `beam.md` (a tab's app run on the visitor's own Mac), `skins.md` (device skins: format and `apksim skin`), `linux-docker.md`, `crawl.md` (the crawler and screen map: what a screen is, budgets, safety, `--base`), `bug-to-pr.md` (a recording of a bug to a pull request that shows it fixed and what else it touched: `scripts/pr-evidence.py`, with a "before you start" list; `skills/apksim-bug-to-pr/` is the skill for users' repositories), `ci-testbed.md` (a large app's
  device tests here: results, CI cost, commands), `parallel.md` (many instances and test processes at once: shards,
  suites side by side, fleets, what limits them), `affected-tests.md` (running only the tests a change reaches, and
  shards balanced by measured time), `network.md` (record, replay and network conditions: the spec, where it hooks
  and why, what each client gets), `snapshots.md` (screenshot tests: spec, determinism, Mac against the Linux
  image), `plan.md` (remaining work, in order), `startup-gate.md` (CI's
  launch-time budget: what it measures, why against a fixed anchor, moving the anchor), `conformance.md` (libraries'
  documented behaviour here against an emulator, `scripts/conformance.py`: the table per library, causes fixed and left),
  `api-levels.md` (a spike that ran the API 30 framework: how far it got, what broke, what `--api 30` would cost),
  `uiautomator-engine.md` (`--uia engine`: UiAutomator's idle waits, gesture time and accessibility events decided
  in-process; a prototype), `corpus.md` (the offline app corpus: what it checks, how to add an app, the findings and
  which are real), `stub-census.tsv` and `stub-census-registered.txt` (generated: every framework native and what
  covers it; `scripts/stub-census.sh`).
- `website/`, `scripts/docs-site.py`, `.github/workflows/docs.yml`: the documentation site
  (https://apksim.web.app, Zensical with the Material theme, on Firebase Hosting in the Google Cloud project
  `apksim`: `website/firebase.json`). `README.md` is a concise summary pointing to the site; the site's pages come
  from `website/pages/*.md` (shared visual pages overlaid onto every published version), `website/docs/*.md`
  (version-specific site pages snapshotted per release) and `docs/*.md` (deep-dive docs, excluding `DOCS_HIDDEN` and
  `DOCS_REPLACED`). `docs-site.py` assembles them into `work/site/docs`, rewriting `docs/*.md` links (other
  repository paths to github.com) and list indentation
  (Python-Markdown wants a blank line before a list and each nesting level 4 spaces in; the sources nest by 2 or by 4), then builds with
  `website/mkdocs.yml` into `work/site/out`. A new `website/pages/*.md`, `website/docs/*.md` or `docs/*.md` file must
  be given a place in the nav (or `DOCS_HIDDEN`): the build says which. `--strict` (CI) fails on a link between pages with no target;
  `--serve` previews; `--deploy` builds and publishes (the firebase CLI, signed in as the owner, or CI's
  `FIREBASE_SERVICE_ACCOUNT` secret). The published site is versioned as mike lays a MkDocs site out, without a
  branch of built pages: `--all-versions` builds `work/site/tree` with a copy per published release whose tag has the script (a tag whose release is
  still a draft is not one, so `/latest/` moves at publishing)
  (`/X.Y.Z/`, the tag's README, docs and website exported and built by the tag's own `docs-site.py --version X.Y.Z
  --out DIR`: keep that command line working, later versions of the script call earlier ones with it), `/dev/` from
  the checkout, `/latest/` (a copy of the newest release's, or of dev while no tag has the script), `versions.json`
  for the theme's version picker and a root redirect to `/latest/`. Each deploy builds every copy again, so the
  tags are the only record; a copy that fails to build is left out with a `::warning::`, except the newest, which stops the deploy, as
  an unreadable release list does. CI builds with no credentials and publishes with `--deploy-only`. `changelog.md` is
  generated from GitHub's release notes (`fetch`: the API, with CI's token; no notes offline, and the page says
  so), in a release's copy only up to that release; `@VERSION@` in a `website/pages` file is the copy's release, or
  the newest. Needs Zensical on the PATH or in `work/site-venv` (`python3 -m venv work/site-venv && work/site-venv/bin/pip install -r
  website/requirements.txt`; nothing else of apksim needs it).
  In a table, a code span's `\|` becomes `|` with a break allowed after it. `website/assets/site.css` holds the
  site's colours (light and a blue-grey dark), type and table styling; `app-light.png`/`app-dark.png` there are the
  test app's `shot` output (Home and Trips, both with `--appearance dark`: the app is shown dark in the docs, as
  Greenroom opens its bundled copy), shown on the landing page; the simulator window page's `win-*.png` are dark too:
  retake them when the test app's look changes.
  The workflow builds on pull requests that touch a source, and deploys every version on a push to `main` that
  touches one and whenever a release is published (which is what moves `/latest/`). Its `build` job is not a required
  check (ci.yml skips its jobs for these paths), so look at it before merging a docs change.
- `.github/workflows/ci.yml`: `build` (the runtime, the test app and the Linux image, once, plus the quick checks),
  then five `linux` legs side by side on that image (`app`, `data`, `replay`, `crawl`, `tests`: `scripts/ci-smoke.sh`
  split by `SMOKE_PARTS`, the androidTest runs, affected-check, the observer gate on a subset of UI-heavy classes), and `linux-smoke`, the check every pull request must
  pass (see "Git and pull requests"), which passes when they all did; ~15 min where one runner took ~38. Add a new
  image check to the leg whose time it fits (each is ~5-8 min; the crawler's alone is ~7).
  `linux-smoke-arm64` (the arm64 image on an arm64 runner, with the test APKs `build` makes; on pull requests only
  when natives, the image, the Gradle build or the smoke script change, always on `main`; not required), and
  `startup-gate` on an Apple Silicon runner (`macos-15`, or a self-hosted Mac while the `STARTUP_GATE_RUNNER` repository variable is set; the Mac's job-started hook, `scripts/runner-job-started.sh`, refuses every other job and forks' pull requests: docs/startup-gate.md). A `changes` job runs the personal-information check and skips the build, test and Mac jobs for a pull request that
  touches only Markdown, `.txt`, `docs/`, `.claude/`, LICENSE or NOTICE, and `startup-gate` for one that touches
  nothing a packaged Mac launch is built from (only the test app, `docker/`, `native/linux/`, CI's check scripts or
  Python tools; skipped jobs count as passed for the required checks, which a workflow path filter would leave pending).
  `.github/workflows/corpus.yml`: nightly and by hand, `linux-build` (no test app), `corpus.py --selftest`, then
  `corpus.py --json` over `scripts/corpus.json`; the report is the artifact. Not required, and green whatever it finds.
