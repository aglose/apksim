# apksim: agent guide

Runs unmodified Android APKs on an Apple Silicon Mac as **one ordinary process, no VM**.
Read README.md for results and limits; this file covers how to work on the code.

**This file is loaded into every session, so it holds only what every session needs**: the goals, the map, everyday
commands and the rules. How each subsystem works, why, what was measured and what is not done is in
`docs/internals/*.md` ("Reference notes" below), which nothing loads by itself. Before changing or debugging an
area, read its file; to find a class, option, command or issue number, `grep -rn NAME docs/internals docs`.

## Owner's goals (in order asked)
1. **Launch as fast as possible:** done. A launch is handed to a pre-booted standby JVM: first frame ~0.22 s (test
   app), ~0.36 s (the owner's R8 app); what is left is mostly the app's own startup code. `scripts/bench.py` measures
   it, and CI's `startup-gate` fails a pull request that makes it slower (docs/startup-gate.md).
2. **Run real APKs, including the owner's own app:** done (its R8 release and debug builds both run).
3. **120 fps**: the owner said "don't stop until it's that fast". Average is ~117-120 fps; 15-35% of frames are
   still 9-12 ms rather than 8.3 ms (docs/internals/open-work.md).
4. **The owner's app's device tests as a CI testbed:** all ~4,100 pass, in a fraction of the emulators' time
   (docs/ci-testbed.md; docs/plan.md phase 0 for what is left).

## Layout
- `apksim`: the launcher (bash). Hands a launch to a standby when it can, else builds the class path, sandbox
  (`sandbox-exec`) and JVM flags and runs `dev.apksim.Main`. Builtins only up to the hand-off: each fork costs ms.
- `runtime/src/main/java/dev/apksim/`: the runtime. `Main` (entry), `Host` (the Android main thread: activity stack,
  input, frame loop, `runCommand`), `Control` and `DeviceControls` (the `--control` commands), `SimRunner` (points
  Robolectric at the APK), `AppClasses`/`ClassCache`/`ApkCache` and `dex/` (the DEX→JVM translator), `HwRenderer`
  and `gpu/` (HWUI, AGSL on Metal), `video/`, `camera/`, `net/`, `gl/`, `ble/`, `health/`, `rec/` and
  `FlightRecorder`, `skin/`, `Instrument` and `TestActivities` (`--instrument` test runs), `AdbServer`/`AdbShell`,
  `Standby` (warm launches), `ElfLoader` (the APK's `.so` files), `serve/` (`apksim serve` and `beam`; plain JVM, no
  Android), and a `Shadow*` class per framework gap.
- `runtime/src/main/java/org/robolectric/...`: apksim's copies of a few Robolectric classes, ahead of Robolectric's
  on the class path. Re-copy and re-apply the changes at every Robolectric upgrade (robolectric-overrides.md).
- `runtime/stubs/`: compile-only org.webrtc signatures. Never give a stub field an initializer (javac inlines it).
- `native/`: `simwindow.m` (Cocoa window), `elfloader.c`, `bionic.c`, `jnibridge.c` (Android ELF loader and shims),
  `gpushader.m`, `standby.c`, `apksim-client.c`, `heaptrim.c`, `devicepaths.c` and others; `native/linux/` for the
  image.
- `scripts/`: build (`setup.sh`, `build.sh`, `build-native.sh`, `train.sh`), drivers (`drive.py`, `ctl.py`,
  `relaunch.sh`), runners (`instrument.py`, `snapshot.py`, `crawl.py`, `fleet.py`, `bench.py`), and a `*-check.*`
  script per feature; `ci-smoke.sh` is what CI runs in the Linux image (scripts-docs-ci.md lists them all).
- `docs/`: user-facing specs (one per feature) and `docs/internals/` (these notes). `website/` plus
  `scripts/docs-site.py`: the documentation site, https://apksim.web.app.
- `testapks/composeapp`: the test app (and its `src/androidTest` suite), with an `apksim://...` screen per feature;
  `translator-fixture`, `loader-fixture`, `bitmap-fixture`, `media-fixture`: regression fixtures (test-app.md).
- `.github/workflows/`: `ci.yml` (`linux-smoke`, `startup-gate`), `release.yml`, `docs.yml`, `corpus.yml`.
- `work/` (not source): `lib/` jars, `runtime/` dylibs, fonts and ICU, `apksim.jar`, `app.aot`, `cache/` (per-APK
  manifest, extracted `.so` files, app data).

## Reference notes (`docs/internals/`)
Quoted names are the section titles other docs and code comments cite as `AGENTS.md "..."`. An older doc or
comment that sends you to AGENTS.md for something not in this file means these notes: grep them.
- `launch.md`: the launcher's JVM flags and why, `Main`, startup threads, `FrameworkFlattener`, `HeapTrim`, the AOT
  cache, "Warm launches" (standbys), rules for launch and memory benchmarks.
- `host.md`: `Host`'s frame loop, vsync and clock, input dispatch, window placement, the activity stack
  (`Host.arrange`), `recreate`, `Crashes`, fatal signals, `Exits`, `settle`/`--snapshot`, animation shadows.
- `control-commands.md`: every `--control` command and the class behind it: input, `shot`, `nodes`, `settle`,
  device controls, activity results, background work, bound services, broadcasts, `push`, `ble`, `health`,
  `touchmode`, `thermal`, `sensor`, `compat`, `keyboard`, `bars`, the keystore, `record`, who may connect.
- `mac-window.md`: "Simulator features": the window, menus, window modes, sign-in links, launch options
  (`--activity`, `--window`, device identity, `--feature`, `--build`, `--setting`), `InstallState`, time zones, and
  what each of the test app's `apksim://` screens checks.
- `instrumentation-tests.md`: "Instrumentation tests": `--instrument`, `scripts/instrument.py` (shards, isolation,
  timeouts, affected tests), failure bundles, test movies, UiAutomator, the device behaviours a test run needs
  (clocks, `--realtime-clock`, Compose, Room, Mockito...), the UiAutomator engine, the device-difference probe.
- `translator-and-caches.md`: `AppClasses`, `ApkCache`, `ClassCache`, the `dex/` translator, "Debugging app code"
  (`--debug`, JDI traces).
- `robolectric-overrides.md`: direct call sites (`InvokeDynamicSupport`), the gap tripwire (`Gaps`), apksim's copies
  of `ActivityController`, `CppAssetManager2`, `CppApkAssets`, `MappedResources`.
- `rendering.md`: `HwRenderer`, AGSL→Metal (`gpu/`), `GpuRenderNode`, `GpuBitmaps`, "GLES" (offscreen, EGL).
- `media.md`: "Video" (WebRTC, MediaCodec, audio, MediaPlayer, libavcodec on Linux, jnigraphics, bionic shims for
  ELF code; `Autofill` sign-in is at its end), "Camera", "Per-instance costs" (`--max-fps`, `--video`, `--audio`,
  `--anim-scale`).
- `network.md`: "Network": the tap and the engine, record, replay, conditions, where the translator hooks,
  connectivity, Wi-Fi and system broadcasts, the beam sandbox's in-memory network, `loopback.c`.
- `storage-and-device.md`: `Storage` and data slots, `SlotDevice` (/sdcard, Settings), `DevicePaths`, `SysFiles`.
- `adb.md`: "adb device": `--adb`, the shell's commands, sync, install, live instrumentations, forwards.
- `serve-and-beam.md`: "apksim serve", `apksim beam`, `FrameStream`, shared and kept sessions, tab shell and files,
  the tab relay, "MCP server".
- `recording.md`: `FlightRecorder`, `apksim replay`, journeys, the trace viewer, `--coverage`.
- `fold-and-skins.md`: `Fold` (devices, postures, WindowManager extensions, the SceneKit animation), `skin/`,
  `Skinning`.
- `native.md`: the ELF loader and bionic shim, `alooper.c`, timerfd, 4 KB-aligned libraries and
  `--move-shared-data`, the locale rule.
- `scripts-docs-ci.md`: every script and what it checks, the `docs/` list, the documentation site's build and
  versions, the CI jobs and legs.
- `test-app.md`: the test app's screens and constraints, "Test APKs", "Comparing with the emulator" (the AVDs,
  `both.py`, the target-28 build).
- `releases-and-linux.md`: "Releases" (`package-release.sh`, `release.yml`, the Homebrew cask), "Linux / Docker".
- `open-work.md`: "Open work": what is left, and perf ideas measured and not kept.

**Keep this file small.** A new class, option, command, measurement or fix goes into the matching
`docs/internals/` file (and the user-facing `docs/` or `website/` page), in the style there: what it does, why,
what was checked, what is not done. Add to AGENTS.md only a rule a session working on *something else* would
break without knowing, in a line or two.

## Everyday commands (from ~/apksim)
```bash
scripts/build.sh && scripts/train.sh          # after any Java change: the AOT cache is invalid once the jar changes
scripts/build-native.sh                        # after any native/ change (no retrain needed)
./apksim --headless --control 7400 testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk
python3 scripts/drive.py 7400 'wait 3000' 'tap 720 1690' 'wait 3000' 'frames' 'fps' 'quit'
python3 scripts/bench.py 5 <apk> [<apk>...]   # launch times, cold and warm (--cold / --warm for one)
./apksim --stop-standbys                       # this folder's warm-launch standbys
./apksim --instrument TEST.apk [-e class a.B#m] APP.apk    # androidTest run; scripts/instrument.py for suites
(cd testapks/composeapp && ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest)
```
Launch with `--headless` for checks; a window only when the window is what is tested.
Coordinates are device pixels (phone: 822x1782, density 2). Test-app bottom nav (four equal items): Home 0-205,
Trips 205-411, Settings 411-616, Shader 616-822 (scripts tap 130, 300 or 350, 515 or 550, 720); y=1690. FAB is at
733,1533.
The owner's own app, its paths, coordinates and sign-in are in `~/.config/apksim/NOTES.md` (private, not in the
repo): read it before working with that app.

Control commands (`drive.py PORT CMD...`, `ctl.py [@PID|@PORT] CMD`, `adb shell apksim CMD`; every answer is one
line; the main ones, with arguments and behaviour in control-commands.md): input `tap x y`, `swipe`, `drag`, `wheel`, `pinch`, `type`,
`key`, `back`, `paste`, `touch`, `tap-text`; reading `shot FILE.png`, `wshot`, `nodes [tree|all]`, `viewtree`,
`activity`, `windows`, `insets`, `config`, `display`, `stats`, `frames`, `fps`, `shaders`, `video`, `gaps`, `deps`,
`compat`, `logcat on|off`; waiting `settle`, `ping`; app lifecycle `restart [URL]`, `open URL`, `recreate`,
`process-death`, `home`/`resume`, `lock`/`unlock`, `memory`, `quit`; device `appearance`, `locale`, `fontscale`,
`timezone`, `clock`, `device`, `size`, `rotate`, `fold`, `skin`, `bars`, `keyboard`, `touchmode`, `animscale`,
`maxfps`, `window`, `zoom`; simulated hardware and services `network`, `location`, `sensor`, `camera`, `bluetooth`,
`ble`, `health`, `thermal`, `biometrics`, `permission`, `notifications`, `push`, `clipboard`, `pick`, `sysfs`,
`jobs`, `services`, `alarms`; tools `record`, `rec`, `diff`, `a11y`, `debug layout`, `autofill`, `selftest`.

## Git and pull requests
The repo is public (https://github.com/aglose/apksim, Apache 2.0). `main` takes changes only through pull requests: a
GitHub ruleset requires a PR whose `linux-smoke` and `startup-gate` checks pass (`.github/workflows/ci.yml`: builds the
runtime and the Linux image, then runs the test app headless in parallel legs; launch times on a Mac), and blocks force-pushes and deleting `main`. No approving review is
needed. Work on a branch, push it, `gh pr create`, wait for the check, then squash it: `gh pr merge --squash`, one
commit per PR on `main` (`--rebase` when a PR's commits are worth keeping apart). Keep branches current by rebasing,
never by merging `main` in: `git rebase origin/main` and `git push --force-with-lease` on your own branch. A branch cut
from another PR's branch is rebased once that PR lands: `git rebase --onto origin/main OLD_PARENT_TIP` (the parent's
squash is a new commit, so a plain rebase would replay the parent's commits). Merged branches are deleted on GitHub.
`scripts/hooks/pre-push` (set by `scripts/setup.sh`: `git config core.hooksPath scripts/hooks`) refuses direct pushes
to `main` before they leave the machine. CI does not cover the Mac-only parts (window, Metal, VideoToolbox, AOT
timings): say in the PR what was checked locally (`bench.py`, `drive.py` runs).
- **Several sessions share this folder.** Commit only your own files, and never switch branches, reset, stash, rebase
  or rewrite history here while another session may have uncommitted edits: a `git filter-repo` run here once threw
  away a peer's work in progress. Do git-side work in a worktree (`git worktree add DIR -b topic origin/main`), or
  ask the session working in the folder to commit first.
- **Nothing personal goes in the repo, ever:** not in files, code comments, commit messages, PR titles or bodies,
  issues or release notes. That means no names of the owner's own apps, employer or servers, package names, hostnames,
  IP addresses, email addresses other than the GitHub noreply one, 1Password item names or sign-in values. Say "the
  owner's app" or describe it ("a large R8 app"). Private notes go in `~/.config/apksim/NOTES.md`.
  `scripts/hooks/check-private` enforces it: `pre-commit` checks the staged changes, `pre-push` the branch name and
  every commit the remote lacks (diff, message, author/committer email), against the terms in `~/.config/apksim/denylist` (private, one regex per
  line). CI runs the same check with the `PRIVATE_DENYLIST` repository secret. Commits use the GitHub noreply
  address (this checkout's local `git config user.email`), and so must squashed commits, whose author GitHub takes
  from the account, not the branch: merge with
  `gh pr merge --squash --author-email 7216340+aglose@users.noreply.github.com` (or `--rebase`, which keeps the
  branch's own authors).
- **No AI attribution:** no `Co-Authored-By` trailer for Claude (or any other assistant) in commit messages, and no
  "Generated with" line in PR bodies; GitHub lists co-authors as contributors. `.claude/settings.json` turns Claude
  Code's own trailers off.
- **Every pull request gets a thorough code review on Sonnet** (Copilot reviews are off): right after `gh pr create`,
  run the `pr-reviewer` agent (`.claude/agents/pr-reviewer.md`, model `sonnet`) on the PR, fix its confirmed findings
  on the branch and push, and report what it found and what was fixed or left. It is read-only and posts nothing to
  GitHub. A `PostToolUse` hook in `.claude/settings.json` (`.claude/hooks/pr-created.py`) reminds a Claude Code
  session after each `gh pr create`; a PR opened any other way (the app's Create PR button, the web) needs the same
  review before it is merged. Other agents (Codex and the like) without the agent file: review the diff with the same
  checklist, on a Sonnet-class model where you can choose.

## Hard-won rules
- Restart your instance with `scripts/relaunch.sh PORT APK [LOG]`: launching while the old instance still holds the
  port leaves the old build running and the new one dead.
- **Never `pkill -f dev.apksim.Main`.** The owner may have their own instance open (for example, logged in to
  the owner's app), and standbys match it too. Quit your instances with `drive.py PORT quit`, standbys with
  `./apksim --stop-standbys`.
- **Credentials:** never type or paste the owner's real passwords. 1Password autofill only works in Chrome, not this
  window. The owner signs in themselves: Cmd+V in the window pastes the clipboard.
- **After any Java change, run `scripts/train.sh`** or timings silently get worse (no AOT). The cache is tied to the
  folder path (`work/app.aot.root`) and, in an installed release, to its release; the launcher launches without a
  cache built elsewhere and says so (launch.md "Rules").
- `scripts/build.sh` fails on compile errors. Check its output before trusting a run. It compiles for Java 22
  (`--release 22`: `gl/` uses the foreign function API).
- **A worktree needs `work/` of its own:** link `work/lib` and `work/media` from the main checkout, but copy
  `work/runtime` (`cp -Rc`, a clone) and run `scripts/build-native.sh` when its `native/` differs: a linked runtime
  built from another branch fails `train.sh` with UnsatisfiedLinkError, and building into it would change every
  other checkout's. Check `git log origin/main` before branching: a worktree cut before a history rewrite shares no
  history with main (rebuild the change on main rather than pushing it).
- **Benchmarks:** measurements are noisy when the machine is busy (WindowServer and the Claude app use CPU). Use
  interleaved A/B runs, not one-offs. After a build, the first launch of each APK other than the test app is cold
  (no class cache): run each once before `bench.py`. Read an idle memory footprint after the 3 s idle trim, with
  the machine's memory pressure beside it (launch.md "Rules").
- `scripts/build.sh` writes the jar beside the old one and renames it, so running instances keep their classes.
- zsh is the shell: `==` and unquoted globs (`--include=*.kt`) break one-liners. Quote them, or use bash/python.
- **Never read, print or edit `work/autofill/<package>.properties`** (the owner's sign-in for `Autofill`); if it is
  missing, ask the owner.
- **When a test passes on the emulator and fails here, trace before changing behaviour:** start with `--jvm
  "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=PORT"` and attach `scripts/jdi/Trace.java` (which
  methods run, with arguments) or `Ret.java` (what they return). Suspect the translator when a value is impossible
  (`scripts/translate-class.java` and javap).
- **The clock:** Robolectric's looper clock has 1 ms resolution and drops fractions: advance by whole ms. Nothing
  but Host's loop may move the clock in a running instance. The Choreographer is paused with a 1 ms frame delay, and
  vsync grid lines come from `lineAtOrBefore`/`lineAfter` (an inexact inverse once spun a core). host.md "Rules".
- Some apps step their shader clocks at 30 Hz on purpose (the owner's does): measure 120 fps on interactions or the
  test app's Shader tab. Keep the young generation small (`-Xmn64m`): 256 MB gave 12-50 ms GC pauses mid-scroll.
- **Code that runs for every app** (startup, shadows) must reference only JDK and framework types, never a class
  that references a library an APK may lack (org.webrtc, OkHttp): such APKs then fail to start with
  NoClassDefFoundError. `train.sh` trains on the test app, where that shows as a failed stage.
- **Free native-backed objects eagerly** (Bitmap pixels, renderers, Metal textures): HotSpot does not count native
  memory towards collecting, as ART does. A RenderNode whose last parent goes away loses its display list
  (rendering.md "Rules").
- **Native code:** macOS never makes a page writable and executable at once, and an extracted `.so` is never
  rewritten in place (rename a new file over it). Nothing may change the process locale once the JVM runs threads:
  check new native libraries for `setlocale` imports (`nm -u`). A variadic libc function an APK imports needs a
  `THUNK` in `bionic.c` (`scripts/so-imports.py APK`). native.md, media.md.
- Every thread apksim starts is named `apksim-...` (`Crashes` takes any other thread for the app's). Resolve paths
  from commands with `Storage.absolute`, not `Path.toAbsolutePath` (a standby's JVM fixed its directory at start).
  A system property read while a standby booted is ignored by the launch it becomes: a warm launch honours only the
  `-D` switches listed in the launcher, read after the hand-off (launch.md "Warm launches").
- **An observer must never change what it observes** (bundles, movies, the flight recorder, coverage, the network
  tap): `scripts/observer-gate.py` in CI compares a suite with and without them.
- **Translator changes** must leave `scripts/translate-all.java`'s output identical unless they mean to change the
  bytecode; a newly miscompiled shape goes into `testapks/translator-fixture` first, failing before the fix
  (`scripts/check-translator.sh`), and through `scripts/corpus.py`. Loader and bionic cases: `loader-fixture`.
- **Bookkeeping a change needs** (the first two are CI quick checks): a new launcher option gets a row in
  `scripts/launch-options.tsv` (`check-launch-options.py`: decide serve, beam and mcp for it); a changed shadow,
  `scripts/stub-census.sh --update`; a new `docs/*.md` or `website/**/*.md` page, a place in the site's nav or
  `DOCS_HIDDEN` (`scripts/docs-site.py` says which; `docs/internals/` is not on the site); an edit to
  `trace-viewer.html`'s script, `trace-viewer-check.py --update-csp`; a new subsystem stand-in, its row in
  `subsystem-audit.py`'s table.
- A new image check goes into the `ci-smoke.sh` leg whose time it fits (`SMOKE_PARTS`; scripts-docs-ci.md).
- **Linux image:** Colima shares only the home folder with its VM, so mount folders under it (`work/...`), not the
  scratchpad in /private/tmp. Tag images you build with a name of your own (`apksim:ci` and `apksim:latest` are
  shared). Use JDK 24 there (JDK 25's AOT record mode rejects translated classes). releases-and-linux.md.
