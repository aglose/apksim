# A large app's device tests on apksim (CI testbed, 2026-11-03 to 11-04)

The owner's app (Kotlin Multiplatform: Compose, R8, SQLite, WebRTC, about a dozen modules) runs its instrumented
tests on two API 34 emulators in GitHub Actions: every module's `androidDeviceTest` (KMP `commonTest` on a device,
Compose UI tests, whole-app journeys against a fake backend) and the app module's end-to-end tests (UiAutomator,
intents, system back, rotation). This page records running the same test APKs on apksim's `--instrument`, what that
needed, and how it compares. App-specific detail (names, commands) is in the owner's private notes.

## Result

**In the app's own CI (2026-11-04, apksim 0.5.0):** all 4,111 tests pass, none flaky. The API 34 emulators in the
same run also passed all 4,111, so the two agree on every test. The job runs each suite in its own container of the
released image (`ghcr.io/aglose/apksim:0.5.0`) on a GitHub ubuntu-24.04 runner, with the app's fixes below applied:

| Suite | Tests | ubuntu-24.04, one container per suite |
|---|---|---|
| shared module | 1,314 | 59 s |
| six feature modules | 2,549 | 7-19 s each, 77 s together |
| 3D model module (offscreen GLES 3 on Mesa) | 6 | 7 s |
| app module, UI tests | 156 | 29 s |
| whole-app journeys, one process | 76 | 96 s |
| end-to-end (UiAutomator), one process per test, `-j 4` | 10 | 82 s |
| total | 4,111 | 350 s of tests; the job took 15.6 min with the Gradle build and the image pull |

On apksim 0.4.0, before the app fixed its sign-in race (the last item below), the journeys took 225-286 s with 5-8
flaky tests a run, and the suites 438-459 s together.

**First pass (2026-11-03), on the Mac and in a local Linux VM:** 4,107 tests. Unchanged, the app passed 4,103-4,105
of them here; with two small fixes to its tests (below) all 4,107 passed, on the Mac and in the Linux image.

| Suite | Tests | Mac (M4 Pro) | Linux image (4 vCPU VM, x86 under Rosetta) | Emulator in CI, tests only |
|---|---|---|---|---|
| shared module (common tests on a device, Compose UI tests) | 1,310 | 40 s | 82-89 s | 301 s |
| six feature modules | 2,549 | 32 s | 96-104 s | 50 s |
| 3D model module (offscreen GLES 3) | 6 | 2 s | 9-11 s (Mesa) | 2 s |
| app module, UI tests | 156 | 14 s | 40-43 s | 137 s |
| end-to-end (UiAutomator), one process per test, `-j 4` | 10 | 28 s | 87-105 s | 110 s |
| whole-app journeys, one process | 76 | 158-167 s | 158 s | 533 s |
| whole-app journeys, one process per test, `-j 4` | 76 | 127-151 s | 684 s | |
| total (journeys in one process) | 4,107 | 4 min 44 s | 8 min 17 s | about 19 min, over two jobs |

Measured 2026-11-03 evening with `scripts/instrument.py` (wall time, each suite started fresh); the Linux runs used
`--apksim "docker run ..."` on this Mac's Colima VM (4 CPUs, 8 GB), so every instruction went through Rosetta: a
GitHub ubuntu-24.04 runner (4 vCPU, 16 GB, native x86) was expected to be faster; it was not (755 s for the isolated
journeys at `-j 4`, against 684 s here). One process per test costs a JVM start and a cold JIT each, and without a
cache volume a fresh translation of the app's classes: alone, one journey took 15.6 s in a fresh container and
10.4-10.6 s with `/opt/apksim/work/cache` on a volume (2026-11-04). Four at a time on 4 vCPUs, each JVM's own JIT
threads compete, which makes it ~36 s a test. Mount the cache volume, and prefer one process for the journeys
(the single-process timeouts below are the app's, now explained). A test that ends that one process (a native crash,
or one that ignores interrupts 10 s past its JUnit timeout) no longer takes the rest of the run with it: `instrument.py`
reports it failed and runs the tests with no result yet in a new process.
The emulator times leave out its boot (~1.5 min per job) and installing each ~170 MB test APK. A cold class cache made
no difference (166 s cold, 164 s warm for the shared module): the tests' own work dominates.

The failures without the app's fixes are the app's, not apksim's:
- One feed UI test clicks the card's merged semantics node, i.e. the whole card, and `performClick` taps its centre,
  which is the clip count's own button, not the title. It fails the same way on an API 36 emulator; CI's API 34
  emulator passes it (why was not looked into). Clicking the unmerged title node fixes it (passes here and on the emulator).
- Journeys whose fake server must be the only one: a server address compiled into the app answers from a Mac on the
  owner's network, so the app takes that route and shows the real server's data (a journey expecting "no cameras"
  found some). Letting the journeys turn that address off fixes it. `--no-network` is no substitute (it breaks the
  journeys that expect the remote route).
- In one long single-process journeys run, one to six different journeys a run time out at 20 s signing in, with
  "Failed to connect to /127.0.0.1:PORT". Explained 2026-11-04 (the app's, a race): PORT is an earlier journey's fake
  server, already closed. The sign-in screen re-seeds its server field from the stored most recent connection each
  time that record loads (`remember(mostRecentConnection) { mutableStateOf(it?.serverUrl) }`), and the app's database
  is a process-wide static, so in one process the previous journey's record is still there. When it loads after the
  journey has typed its own server's URL, the field reverts and the app connects to the old address; the journey's
  own server never sees a request (an `lsof` trace showed it listening throughout). It cannot happen with one process
  per test, where there is no earlier record. A device run in one process has the same race; it shows here more often
  because the first query is slower. The app fixed it on 2026-11-04: the field takes the stored connection only
  until something is typed or filled in. Since then, a whole run has had no flaky journeys.

Found on the way, in apksim: Mesa's EGL has no window configs and WebRTC's `EglBase` asks for one (every journey with a
live camera failed in the image until `eglChooseConfig` asked for pbuffer configs), and processes started together
raced on extracting the APK's native libraries (fixed: rename into place).

## Running the suites in parallel (2026-11-04)

The app's CI runs the suites one after another, the end-to-end tests one process per test: 670 s and 1,318
CPU-seconds in the Linux image on 4 CPUs (under Rosetta), 167 s on the Mac. With the end-to-end tests in one process,
three suites at once, the journeys in two shards and the JIT stopped at C1 (now the default for test runs), the same
4,134 tests took 133-137 s and ~435 CPU-seconds there; on the Mac, four at once, ~58 s. One journey waits 30 s of real time for the app's poll, which
bounds the run. Measurements, limits and how-to: [parallel.md](parallel.md). It also found two macOS bugs (WebRTC
threads spinning on monotonic condition variables, and a crash at exit), both fixed.

## CI time and cost

From the 27 green runs before 2026-11-03: the two emulator jobs take a median 16.5 and 18.8 min, so a pull request
waits 18.8 min (p90 23.6) and uses 34.9 runner-minutes. Over the week before, they used 6,514 runner-minutes (508
jobs, cancelled ones included), about 28,200 a month.

One apksim job, measured in the app's CI on 2026-11-04: 15.6 min and 15.6 runner-minutes a pull request, including
the Gradle build of the test APKs and pulling the image, against 18.8 min and 34.9 for the emulator jobs: about 55%
fewer runner-minutes. Its tests take 350 s; the rest is the build. (The projection from the Mac's Linux VM was ~14
min.) At list price for a 4-core Linux runner ($0.012/min) that is ~$150 a month against ~$338; for a public
repository standard runners cost nothing either way, and the gain is the wait and the free runner slots. An emulator
stays for anything that measures a real device's startup, and for the app's Android CLI journeys until they move to
apksim's adb device (0.5.0 runs the CLI's on-device server: `adb install`, `am instrument`).

## Running a suite

```bash
# a library's device-test APK (KMP androidDeviceTest) instruments itself: pass it as both
./apksim --instrument shared-androidTest.apk shared-androidTest.apk
# one package of a suite, as AndroidJUnitRunner's -e package
./apksim --instrument app-androidTest.apk -e package com.example.journeys app-androidTest.apk
# an app's androidTest, one process per test as the Orchestrator does, with JUnit XML
scripts/instrument.py app-debug.apk app-debug-androidTest.apk --isolate -j 4 --xml e2e.xml
# the same in the Linux image (APKs in ./out, named relative to it)
scripts/instrument.py --apksim "docker run --rm -v $PWD/out:/work apksim:ci" app-debug.apk app-debug-androidTest.apk --xml out/e2e.xml
```

In another repository's CI, the released image runs a suite with its own `instrument.py`, with no apksim checkout
(`docker run --rm -v $PWD/out:/work --entrypoint instrument.py ghcr.io/aglose/apksim:VERSION app.apk test.apk ...`;
a job to copy: [linux-docker.md](linux-docker.md#prebuilt-image)).

A cold build of a project this size can need more than a 4 GB Gradle heap when nothing is cached (it thrashed for an
hour): `-Dorg.gradle.jvmargs=-Xmx10g` finished in a minute.

## What it took

- **UiAutomator** (`ShadowSimUiAutomation`): the window tree from the process's own windows, linked to the views
  with `setQueryFromAppProcessEnabled` so UiAutomator walks the real view and Compose tree; clicks by injection;
  `setOrientation*` as a configuration change; `waitForIdle` on the simulated clock.
- **Running the suites at all:** every test on API 36 (an app targeting 37 left Robolectric with no tests to run);
  test classes found from their bytes, not reflection (it broke a framework class before the sandbox existed,
  failing a whole suite); library test APKs without a launcher activity.
- **Two translator miscompilations** (every app, not only tests): ints and floats typed as references and dropped,
  because exception-handler liveness merged too much (a return counted as throwing; a handler's live registers added
  at every block of its range). Compose's animations in v2 tests snapped to their end (duration scale 0), and
  play-services' `GoogleApiHandler` thread died on every launch.
- **A device's system server and threads, in a test run** (`TestActivities`): BACK to the window's back callback;
  back on a launcher task's root moves it to the background; `finish()` runs pause/stop/destroy; a start for a running
  singleTask activity reaches `onNewIntent`; Compose's UI-thread work after what the main queue holds; a window gets
  its requested size (Compose dialogs never settled); the framework's 8 dp touch slop; JniConstants initialised on the
  main thread; receivers registered before a test's reset can be unregistered.
- **GLES** (`dev.apksim.gl`): GLES20/30 and EGL14 on ANGLE (Metal) for offscreen rendering; in the Linux image on
  Mesa's llvmpipe (surfaceless EGL), checked by the test app's `GlesTest` (it passes on the emulator too).

Found with `scripts/jdi/Trace.java` and `Ret.java` (breakpoints and return values over JDWP) and
`scripts/translate-class.java` (one class's translation, for javap). Remaining work: [plan.md](plan.md), phase 0.
