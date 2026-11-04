# A large app's device tests on apksim (CI testbed, 2026-11-03)

The owner's app (Kotlin Multiplatform: Compose, R8, SQLite, WebRTC, about a dozen modules) runs its instrumented
tests on two API 34 emulators in GitHub Actions: every module's `androidDeviceTest` (KMP `commonTest` on a device,
Compose UI tests, whole-app journeys against a fake backend) and the app module's end-to-end tests (UiAutomator,
intents, system back, rotation). This page records running the same test APKs on apksim's `--instrument`, what that
needed, and how it compares. App-specific detail (names, commands) is in the owner's private notes.

## Result

4,107 tests. Unchanged, the app passes 4,103-4,105 of them here; with two small fixes to its tests (below) all
4,107 pass, on the Mac and in the Linux image.

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
GitHub ubuntu-24.04 runner (4 vCPU, 16 GB, native x86) should be faster, not slower. One process per test costs a
JVM start each, which under Rosetta is what makes the isolated journeys slow there (~36 s a test, 4 at a time).
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
- In one long single-process journeys run on the Mac, one to four different journeys a run time out at 20 s, some
  with the app unable to connect to the journey's fake server on localhost. Never with one process per test, never
  for a class run alone, and once in the Linux image. Not explained yet (no descriptor leak).

Found on the way, in apksim: Mesa's EGL has no window configs and WebRTC's `EglBase` asks for one (every journey with a
live camera failed in the image until `eglChooseConfig` asked for pbuffer configs), and processes started together
raced on extracting the APK's native libraries (fixed: rename into place).

## CI time and cost

From the 27 green runs before 2026-11-03: the two emulator jobs take a median 16.5 and 18.8 min, so a pull request
waits 18.8 min (p90 23.6) and uses 34.9 runner-minutes. Over the week before, they used 6,514 runner-minutes (508
jobs, cancelled ones included), about 28,200 a month.

One apksim job: setup and the same Gradle build of the test APKs (~5.5 min), the image (pulled, or built in ~1 min
plus AOT training), tests 8.3 min measured under Rosetta (4.7 min natively on the Mac): about 14 min and 14
runner-minutes a pull request as an upper bound, against 18.8 min and 34.9. Roughly 60% fewer runner-minutes, a little
more if the x86 runner beats Rosetta. At list price for a 4-core Linux runner ($0.012/min) that is ~$135 a month
against ~$338; for a public repository standard runners cost nothing either way, and the gain is the wait and the free
runner slots. Still to do: the job in the app's CI itself (a pull request to it). An emulator stays for anything that
measures a real device's startup.

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
