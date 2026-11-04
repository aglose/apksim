# A large app's device tests on apksim (CI testbed, 2026-11-03)

The owner's app (Kotlin Multiplatform: Compose, R8, SQLite, WebRTC, about a dozen modules) runs its instrumented
tests on two API 34 emulators in GitHub Actions: every module's `androidDeviceTest` (KMP `commonTest` on a device,
Compose UI tests, whole-app journeys against a fake backend) and the app module's end-to-end tests (UiAutomator,
intents, system back, rotation). This page records running the same test APKs on apksim's `--instrument`, what that
needed, and how it compares. App-specific detail (names, commands) is in the owner's private notes.

## Result

4,106 tests; 4,102 pass.

| Suite | Tests | apksim | Emulator in CI, tests only |
|---|---|---|---|
| shared module (common tests on a device, Compose UI tests) | 1,310 | 41 s, 1 fails (below) | 301 s |
| six feature modules | 2,549 | 32 s | 50 s |
| 3D model module (offscreen GLES 3) | 6 | 2 s | 2 s |
| app module, UI tests | 156 | 12 s | 137 s |
| end-to-end (UiAutomator) | 10 | 9 s in one process, 13 s one process per test (`-j 4`) | 110 s |
| whole-app journeys | 75 | 147 s, 3 fail (below) | 533 s |
| total | 4,106 | about 4 min | about 19 min, over two jobs |

apksim times are from an M4 Pro Mac under load, each suite in one process. The emulator times leave out its boot
(~1.5 min per job) and installing each ~170 MB test APK. A cold class cache made no difference (166 s cold, 164 s
warm for the shared module, before the fixes below): the tests' own work dominates.

The four failures are the app's, not apksim's:
- One feed UI test fails the same way on an API 36 emulator (a tap on a card's title does not reach its click
  handler); CI's API 34 emulator passes it. apksim is API 36.
- Three journeys depend on whether a server address compiled into the app answers. From a Mac on the owner's
  network it does, so the app shows the real server's data and picks its local route; an emulator or a CI runner
  cannot reach it. `--no-network` (localhost only) makes other journeys fail instead. The fix belongs in the tests.

## CI time and cost

From the 27 green runs before 2026-11-03: the two emulator jobs take a median 16.5 and 18.8 min, so a pull request
waits 18.8 min (p90 23.6) and uses 34.9 runner-minutes. Over the week before, they used 6,514 runner-minutes (508
jobs, cancelled ones included), about 28,200 a month.

One apksim job, projected: setup and the same Gradle build of the test APKs (~5.5 min), apksim's runtime (~20 s,
as in apksim's own `linux-smoke`), tests ~4 min: about 10 min and 10 runner-minutes a pull request, about 70% fewer.
At list price for a 4-core Linux runner ($0.012/min) that is ~$97 a month against ~$338; for a public repository
standard runners cost nothing either way, and the gain is the wait and the free runner slots. Not measured yet: the
same suites on a Linux x86 runner (the projection uses the Mac's test times). An emulator stays for anything that
measures a real device's startup.

## Running a suite

```bash
# a library's device-test APK (KMP androidDeviceTest) instruments itself: pass it as both
./apksim --instrument shared-androidTest.apk shared-androidTest.apk
# one package of a suite, as AndroidJUnitRunner's -e package
./apksim --instrument app-androidTest.apk -e package com.example.journeys app-androidTest.apk
# an app's androidTest, one process per test as the Orchestrator does, with JUnit XML
scripts/instrument.py app-debug.apk app-debug-androidTest.apk --isolate -j 4 --xml e2e.xml
```

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
- **GLES** (`dev.apksim.gl`): GLES20/30 and EGL14 on ANGLE (Metal) for offscreen rendering.

Found with `scripts/jdi/Trace.java` and `Ret.java` (breakpoints and return values over JDWP) and
`scripts/translate-class.java` (one class's translation, for javap). Remaining work: [plan.md](plan.md), phase 0.
