# Conformance: libraries' documented behaviour here and on an emulator

Every device behaviour a test run lacked so far was found by a user's suite failing (AGENTS.md, "Instrumentation
tests"). This sweep looks for the next ones first: tests of AndroidX libraries' documented behaviour run under
`./apksim --instrument` and on an API 36 emulator, each difference traced (`scripts/jdi/Trace.java`, `Ret.java`)
before anything changes, and each fix a small pull request with its repro in the test app's suite.

## Running it

```bash
python3 scripts/conformance.py TEST.apk [--app APP.apk] [--emulator SERIAL --util test-services.apk] [-e KEY VALUE]...
```

A library's androidTest APK instruments itself, so it is passed alone (as both APKs); `--app` is for a test APK that
targets an app. Here it runs through `scripts/instrument.py` (`work/conformance/NAME/here.xml`). With `--emulator` the
same APKs are installed on that device (`adb install -t`, `-g` with `--grant-permissions` as apksim gets `--permissions grant`; only packages it does not have, uninstalled afterwards,
so an emulator another session runs keeps its apps), run with `am instrument -w -r`, and a run a crash ended is
finished one class at a time. `--util` installs an androidTestUtil APK for the run, queryable and with
MANAGE_EXTERNAL_STORAGE as AGP does it: the test app's runner writes through Test Storage, which on a device needs the
Test Services APK (`androidx.test.services:test-services`, from Google's Maven repository). `--here-xml` and
`--emulator-xml` reuse an earlier side's XML. The table (`table.md`) sorts every test into passes on both, fails here
only, fails on both, passes here only, skipped, ran on one side only, and groups the failures here only by cause.

The batch-1 run:

```bash
python3 scripts/conformance.py testapks/composeapp/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
  --app testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk --name batch1 \
  --emulator emulator-5580 --util test-services-1.6.0.apk -e package dev.apksim.composeapp.conformance
```

The emulator: `ApksimCmp36` from AGENTS.md "Comparing with the emulator" (`system-images;android-36;google_apis;arm64-v8a`,
`pixel_2`), started with `-no-window -no-audio -no-boot-anim`, then `wm size 822x1782`, `wm density 320`,
`svc power stayon true` and `settings put global hide_error_dialogs 1`. With other work on the Mac its System UI
can stop responding; the script refuses to run while that dialog has the focus (every Espresso test fails with
RootViewWithoutFocusException otherwise). `scripts/conformance-check.py` (CI, `ci-smoke.sh`) checks the script's
reading of `am instrument` output and its table without a device.

## Where the tests come from

The plan was AndroidX's own device tests, built from the AndroidX repository's playground projects. Running that
Gradle build was refused by this session's permission check (it builds and runs upstream code), so batch 1 uses the
fallback: focused tests of each library's documented behaviour in the test app's
`src/androidTest/.../conformance/`, written device-style (the test body off the main thread on a device, UI work
through `onActivity`/`onFragment`) and checked on the emulator before any change here. The test app gained
fragment-ktx, fragment-testing, lifecycle-process, navigation-fragment and navigation-compose for them.

## Batch 1: activity, fragment, lifecycle, navigation

31 tests here and with the pause fix (#266), all passing on both sides; the two ProcessLifecycleOwner tests that
need the AppCallbacks fix travel with it in #267.

| Class | Tests | Passes on both | Fails here only, before | Fixed by |
|---|---|---|---|---|
| ActivityConformanceTest (back dispatch, result registration, ViewModel and saved state across recreate, FullyDrawnReporter, lifecycle order, insets, save order) | 9 | 9 | 0 | |
| LifecycleConformanceTest (repeatOnLifecycle, withStarted, currentStateFlow, lifecycleScope) | 4 | 4 | 0 | |
| FragmentConformanceTest (view lifecycle, recreate, back stack, results, DialogFragment, child fragments, back callbacks) | 9 | 9 | 0 | |
| NavigationConformanceTest (NavHostFragment: navigate and back, popUpTo, deep links, recreate, results, navigateUp) | 6 | 6 | 0 | |
| NavigationComposeConformanceTest (rememberSaveable across back, predictive back) | 2 | 2 | 0 | |
| RotationConformanceTest (no second pause on a rotation while stopped; in #266) | 1 | 1 | 1 | the pause fix |

### Causes found

1. **A rotation paused a stopped activity again** (fixed in #266, `ShadowSimInstrumentation.callActivityOnPause`).
   Robolectric's configuration change calls `Instrumentation.callActivityOnPause` whatever the activity's state
   (traced: `ActivityController`'s `lambda$configurationChange`, line 497, on a stopped MainActivity, from
   `StackWindowsTest#aStoppedActivityRotatedAndResumedShowsItsWindow`); a device pauses only a resumed activity
   (`performPauseActivityIfNeeded`). Every ActivityLifecycleCallbacks heard a second onActivityPaused, and AndroidX
   ProcessLifecycleOwner's resumed count stayed at -1 for the rest of the process (bisected over the suite's classes
   to that one test). Now only a resumed activity is paused; `-Dapksim.pauseOnlyResumed=false` restores the old call.
   Repro: `RotationConformanceTest`.
2. **A process singleton's ActivityLifecycleCallbacks stay on the first test's Application** (fixed in #267).
   AndroidX ProcessLifecycleOwner registers them once a process, from AndroidX Startup's initializer, whose own
   singleton runs it once. Each test here gets a fresh Application, so from the second test on ProcessLifecycleOwner
   hears of no activity (traced: `activityStarted$lifecycle_process` and its siblings hit in the first test only) and
   stays in whatever state that test left: its documented 700 ms delay to CREATED never comes. The emulator passes
   both ProcessLifecycleOwner tests; here they fail after any other test. #267 shares one process-wide callback list
   and controller across a run's Applications.

Also found while running the suite: `UncaughtExceptionTest` left its coroutine exception in kotlinx-coroutines-test's
collector, which failed whichever Compose rule test ran next ("uncaught exceptions before the test started"); it now
takes it itself.

### Differences left

- **Espresso and a ComponentDialog's root**: `Espresso.pressBack()` with a `ComponentDialog` shown passes here and fails
  on the emulator every time (RootViewWithoutFocusException): apksim gives the dialog's window the focus and a settled
  layout sooner than a device does. Not in the suite; not traced yet.
- **A stopped activity rotated comes back resumed for a moment**: Robolectric's configuration change resumes the new
  instance, and apksim then stops it again (`ShadowSimUiAutomation`), so callbacks hear a resume and pause a device
  would not send (a device defers the relaunch until the activity is visible again).
- **A latch awaited on the main thread**: a test body runs on the main thread here, so `CountDownLatch.await` there
  holds the main queue, where on a device the test thread waits while the main thread works. `Thread.sleep` and
  `LockSupport.parkNanos` in app and test code already run the main queue meanwhile; a latch's await (the JDK's own
  park, which the translator does not see) does not. With `--realtime-clock` a FullyDrawnReporter listener awaited this
  way never ran (the frame that reports comes after the idle); the test polls instead. Next to fix.
- **Back on a task's root activity** ends stopped here at once; on the emulator the stop comes after the task's
  transition, and in one run of three the activity was destroyed. Timing-dependent on the device, so not in the suite.

### Run against AndroidX's own tests

Not yet: blocked on the permission to build AndroidX's playground projects (`work/androidx`, never committed). Once
allowed, each library's androidTest APK goes through the same script and its table is added here.
