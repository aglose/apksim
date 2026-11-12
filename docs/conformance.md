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
fragment-ktx, fragment-testing, lifecycle-process, navigation-fragment and navigation-compose for them, and for batch 2
recyclerview and espresso-contrib.

## Batch 1: activity, fragment, lifecycle, navigation

56 tests. With the four fixes below, everything that runs on both sides passes on both; some of
AppCallbacksConformanceTest's tests run only here (they need a second Application, which a device's process never has)
or only with an `-e` switch.

| Class | Tests | Passes on both | Fails here only, before | Fixed by |
|---|---|---|---|---|
| ActivityConformanceTest (back dispatch, result registration, ViewModel and saved state across recreate, FullyDrawnReporter, lifecycle order, insets, save order) | 9 | 9 | 0 | |
| LifecycleConformanceTest (ProcessLifecycleOwner, repeatOnLifecycle, withStarted, currentStateFlow, lifecycleScope) | 6 | 6 | 1 | AppCallbacks; the pause fix in the full suite |
| FragmentConformanceTest (view lifecycle, recreate, back stack, results, DialogFragment, child fragments, back callbacks) | 9 | 9 | 0 | |
| NavigationConformanceTest (NavHostFragment: navigate and back, popUpTo, deep links, recreate, results, navigateUp) | 6 | 6 | 0 | |
| NavigationComposeConformanceTest (rememberSaveable across back, predictive back) | 2 | 2 | 0 | |
| RotationConformanceTest (no second pause on a rotation while stopped) | 1 | 1 | 1 | the pause fix |
| ValueAnimatorConformanceTest (INFINITE read back, an endless animator still repeating after its fifth cycle) | 2 | 2, here with `--realtime-clock` | 2 | the repeat count |
| LatchConformanceTest (a CountDownLatch awaited for main-thread work: posts, delayed posts, no timeout, a timeout, a worker, an interrupt, FullyDrawnReporter, a worker and a timeout during an endless frame loop, a worker while a Runnable posts itself again) | 10 | 10 | 5, the frame loop's 2 in the default clock mode, the reposting Runnable's with `--realtime-clock` | the latch wait |
| AppCallbacksConformanceTest (a singleton's activity and component callbacks heard in later tests and unregistered through the Application it kept, a per-Application one replaced, changes made while the next Application starts, a failed setup between tests) | 14 | the ones a device can run | 2 | AppCallbacks |

### Causes found

1. **A rotation paused a stopped activity again** (`ShadowSimInstrumentation.callActivityOnPause`).
   Robolectric's configuration change calls `Instrumentation.callActivityOnPause` whatever the activity's state
   (traced: `ActivityController`'s `lambda$configurationChange`, line 497, on a stopped MainActivity, from
   `StackWindowsTest#aStoppedActivityRotatedAndResumedShowsItsWindow`); a device pauses only a resumed activity
   (`performPauseActivityIfNeeded`). Every ActivityLifecycleCallbacks heard a second onActivityPaused, and AndroidX
   ProcessLifecycleOwner's resumed count stayed at -1 for the rest of the process (bisected over the suite's classes
   to that one test). Now only a resumed activity is paused; `-Dapksim.pauseOnlyResumed=false` restores the old call.
   Repro: `RotationConformanceTest`.
2. **A process singleton's ActivityLifecycleCallbacks stay on the first test's Application** (`AppCallbacks`).
   AndroidX ProcessLifecycleOwner registers them once a process, from AndroidX Startup's initializer, whose own
   singleton runs it once. Each test here gets a fresh Application, so from the second test on ProcessLifecycleOwner
   hears of no activity (traced: `activityStarted$lifecycle_process` and its siblings hit in the first test only) and
   stays in whatever state that test left: its documented 700 ms delay to CREATED never comes. The emulator passes
   both ProcessLifecycleOwner tests; here they failed after any other test. Now the process has one callback list and
   one ComponentCallbacksController, as a device has one Application: every Application uses them from the start of its
   startup (before its providers and onCreate), so whatever a singleton registers or unregisters, through any
   Application, from any thread, is live. At the end of each startup, what an earlier startup registered from a call
   site this one registered from again (an onCreate's own listener) goes, as do framework callbacks of earlier
   Applications; registrations are told apart by owner, startup, occurrence and call path (every APK frame, from a
   shadow of Application's register methods, which records each change under one lock with the framework's own), not
   by class; what an Application's constructor or attachBaseContext registered counts as its startup's.
   `-Dapksim.keepAppCallbacks=false` turns it off. Repros: `LifecycleConformanceTest`'s ProcessLifecycleOwner tests
   after any other test, and `AppCallbacksConformanceTest` (`-e appCallbacksFailedSetup true` adds a failed setup
   between two tests, whose middle test fails on purpose).
3. **A latch awaited on the main thread held the main queue** (`TestActivities.await`). A test body runs on the main
   thread here, so `CountDownLatch.await` there held the queue the latch was waiting on, where on a device the test
   thread waits while the main thread works (traced: the test's `await(5, SECONDS)` on the main looper's thread, the
   posted `countDown` run only at teardown). `Thread.sleep` and `LockSupport.parkNanos` already ran the main queue
   meanwhile; now the translator sends app and test code's `await()` and `await(long, TimeUnit)` to `TestActivities`,
   which, in a test run on the main thread outside a dispatch, runs the queue while waiting on the latch in 2 ms steps
   and moves the clock by the real time waited (so a delayed post comes due about when it would on a device), and is the
   latch's own wait everywhere else. Five of `LatchConformanceTest`'s seven failed before, in both clock modes;
   `ActivityConformanceTest`'s FullyDrawnReporter test awaits its listener again instead of polling. These waits, and
   `Thread.sleep`'s and `parkNanos`'s, ran the queue with `ShadowLooper.idle()`, which an endless frame loop (a
   Choreographer callback that asks for the next frame, as a Compose Popup does) never lets return in the default
   clock mode: the unpaused Choreographer moves the clock a frame and runs it at once. A latch wait or a sleep under one
   never ended (`LatchConformanceTest`'s frame loop tests and `MainThreadSleepTest.aSleepEndsDuringAnEndlessFrameLoop`
   hung until `--test-timeout`). They now run due tasks one at a time and check the latch, an interrupt and the deadline
   between them, as UiAutomator's frame wait does, in both clock modes: with `--realtime-clock` a Runnable that posts
   itself again kept `idle()` draining just the same (`aWorkerEndsTheWaitWhileARunnableRepostsItself`).

4. **A ValueAnimator set to repeat for ever repeated once** (`ShadowRepeatingAnimator`). Robolectric's
   `ShadowValueAnimator.setRepeatCount` turns `INFINITE` into 1 (its bytecode: `if (count == -1) count = 1` before the
   real setter), so `getRepeatCount()` said 1 and the animator ended after its second cycle. Found writing the
   frame-loop latch tests (a 100 ms one ended at 273 ms). Its shadows are on in running instances too: the test app's
   own rotating square on `apksim://bitmaps` stopped at 360 degrees after two seconds. Robolectric caps it because its
   unpaused Choreographer runs every frame asked for at once, so an endless animator would never let the main looper go
   idle. Host's loop and `--realtime-clock` run frames at vsyncs, so there the real count is set; default test runs
   keep the cap, which Robolectric-style suites rely on. `-Dapksim.repeatForever=false` keeps the cap everywhere.
   Repro: `ValueAnimatorConformanceTest` (on apksim with `--realtime-clock -e realtimeClock true`: both failed before,
   2 of 2 on the emulator), and `ci-smoke.sh` checks the square still turns after 3.5 s.

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
- **Back on a task's root activity** ends stopped here at once; on the emulator the stop comes after the task's
  transition, and in one run of three the activity was destroyed. Timing-dependent on the device, so not in the suite.
- **runOnMainSync waits for the main thread to go idle**: Robolectric's `RoboMonitoringInstrumentation.runOnMainSync`
  runs the runnable and then `waitForIdleSync`, which an endless frame loop never lets return in the default clock
  mode (traced: the test's `runOnMainSync` in `ShadowPausedLooper.idle`, the loop's frames under it). A device's only
  waits for the runnable to run. Found writing the frame-loop tests, which post to the main thread instead.

## Batch 2: Compose ui and foundation, material3, recyclerview

46 tests, in the same package, with recyclerview 1.4.0 and espresso-contrib (RecyclerViewActions) added to the test app.
All 46 pass on both sides, here in both clock modes, with no change to apksim (run with #272's runtime, since these
suites are where a main-thread latch would show; none of the tests uses one).

| Class | Tests | Passes on both |
|---|---|---|
| ComposeUiConformanceTest (positions and clipped bounds at the display's density, composition locals, focus and key events, long press against tap, derivedStateOf, AndroidView interop, system bar insets, BackHandler, the lifecycle owner, an animation on the paused clock, saved state restored, IME actions, window info) | 13 | 13 |
| ComposeFoundationConformanceTest (scrollToIndex, a fling that settles, keyed items across a reorder, pager swipe and animateScrollToPage, BasicTextField input, press interactions, performScrollTo) | 8 | 8 |
| Material3ConformanceTest (snackbar action and timeout, DropdownMenu pick and back, Slider drag and SetProgress, Switch and Checkbox, AlertDialog, ModalBottomSheet, TabRow, tooltip, pull to refresh, SwipeToDismissBox, ModalNavigationDrawer's scrim, a menu inside a Dialog) | 14 | 14 |
| RecyclerViewConformanceTest (what is bound, RecyclerViewActions, fling, smoothScrollToPosition, ListAdapter's commit, the removal animation, ItemTouchHelper, saved position across recreate, GridLayoutManager spans, the accessibility scroll, a nested horizontal list) | 11 | 11 |

### Difference found, left as designed

- **An animation started before waitForIdleSync finishes inside it** in the default clock mode. RecyclerView 1.4 runs
  the accessibility scroll-forward action as `smoothScrollBy`, and a test that checked the position right after
  `waitForIdleSync` passed here and failed on the emulator, where the idle wait returns while the scroll runs
  (traced: all 33 of the scroll's `ViewFlinger` frames ran under the test's one `waitForIdleSync`, through
  `ShadowPausedLooper.idle` and Robolectric's unpaused Choreographer). This is the default clock skipping idle time
  (AGENTS.md, "Instrumentation tests"), which Robolectric-style suites rely on; with `--realtime-clock` the same test
  fails as on the emulator (2 of 2 runs), so suites written for a device have that switch. The test now waits for the
  scroll to settle, as a device test must.

## Batch 3: work, room, datastore, espresso, uiautomator

50 tests, with WorkManager 2.12 and DataStore 1.3 in the app (as an app using them has them: WorkManager's startup
initializer and job service are then the app's) and Room 2.8 (its database the tests' own, compiled by KSP) and
espresso-intents in the tests. With the fixes below, all 50 pass on both sides, here in both clock modes.

| Class | Tests | Passes on both | Fails here only, before | Fixed by |
|---|---|---|---|---|
| WorkManagerConformanceTest (a one-time request's output, a chain's input, a failure failing its dependents, retry after backoff, unique work KEEP and REPLACE, cancelling a CoroutineWorker, an unmet constraint, an initial delay) | 9 | 9 | 0 | |
| RoomConformanceTest (a blocking DAO call from the test body, on the main thread, in runOnUiThread and in a lifecycle callback, suspend queries, a Flow re-emitting, an invalidation observer, a transaction rolled back, a file database reopened, a migration, a destructive fallback, a missing migration) | 12 | 12 | 4 | the test body's thread |
| DataStoreConformanceTest (an edit read back, data's emissions, a new store on the same file, two active stores, a failed edit, a corrupt file with and without a handler, one preferencesDataStore delegate store per process) | 8 | 8 | 0 | |
| EspressoConformanceTest (typeText, an idling resource, onIdle and a delayed post, onData, a Spinner's popup, a dialog's root and pressBack, scrollTo, missing and ambiguous matches, espresso-intents stubs and a result, replaceText) | 10 | 10 | 0 | |
| UiAutomatorConformanceTest (a click by text, setText, scrollUntil in a ScrollView, a RecyclerView and one of tall items, Until.gone, pressBack on a dialog, the display size, setOrientationLeft, the hierarchy dump, executeShellCommand) | 11 | 11 | 1 | scroll events |

### Causes found

1. **A blocking Room query from the test body threw** (`TestActivities.roomMainThread`). Room refuses a blocking DAO
   call on the main thread ("Cannot access database on the main thread since it may potentially lock the UI"), and a
   test body runs on the main thread here, where on a device it runs on the instrumentation thread (the stack: the
   test method, the generated DAO, `RoomDatabase.assertNotMainThread`, `isMainThread$room_runtime`, which compares
   `Looper.getMainLooper().getThread()` with the current thread). Four of `RoomConformanceTest`'s tests failed here
   and passed on the emulator. The translator now sends that `getThread()` in `RoomDatabase` (and older Room's
   `ArchTaskExecutor.isMainThread()` there) to `TestActivities`, which answers "not the main thread" only for the test's
   own code (the test method, `@Before`, `@After`, under JUnit's `FrameworkMethod`) outside any message's dispatch,
   `runOnMainSync`, `ActivityScenario.onActivity` (which runs its block at once on the main thread here; `onFragment`
   too), `Activity.runOnUiThread` (the same) or lifecycle call (`Instrumentation.callApplicationOnCreate`, `callActivityOnCreate` and the rest, a provider's
   `attachInfo`). The app's startup, and an activity or fragment the body launches, still count as the main thread
   and Room refuses there, as on a device (`aBlockingQueryOnTheMainThreadThrows`,
   `aBlockingQueryInRunOnUiThreadThrows`, `aBlockingQueryInALifecycleCallbackThrows`, which failed with the first
   version of the fix). Main-thread work an
   Espresso action runs inline from the body is let through where a device would throw, and a minified app's Room
   keeps its own check.
   `-Dapksim.roomTestBody=false` turns it off.
2. **UiAutomator's scrollUntil stopped part way** (`ShadowSimUiAutomation.executeAndWaitForEvent`). UiAutomator learns
   whether a scroll reached the end from the TYPE_VIEW_SCROLLED event the gesture brings (`Until.scrollFinished` reads
   scrollY against maxScrollY, or the from and to index against the item count), and counts a swipe with no event as a
   null scroll, giving up after five (`MAX_NULL_SCROLL_RETRY`). apksim has no event stream, so an 80-row ScrollView
   stopped at rows 40 to 53. Now the wait reports a scroll event for each view whose scroll state changed, filled by
   the view's own `onInitializeAccessibilityEvent` (through its accessibility delegate when it has one, as
   RecyclerView does: from and to positions and the item count), before the window change it already reported. Compose's scrolling
   has no such state on its view, so it still gets no scroll event.
   A view counts as scrolled when those fields or its first child's position changed: a list moves its children, and
   a swipe within one tall item changes no index (a RecyclerView of items six screens tall stopped at item 0 here;
   UiAutomator itself stops such a list's scrollUntil after item 1, on the emulator too).
3. **With `--realtime-clock`, UiAutomator's idle wait returned before a new window's first layout**
   (`ShadowSimUiAutomation.settle`). It ran what was due and stopped as soon as nothing was, but a window just shown
   lays out at the next vsync, a few ms later, so a query right after a launch found an empty window (three of
   `UiAutomatorConformanceTest`'s tests, in the full suite with the switch). It now runs what comes due for its quiet
   time at most and stops once nothing is scheduled within 50 ms, as a device's wait for quiet would.

Found and fixed in the tests themselves, while checking them on the emulator first:
- DataStore 1.3 in the test APK alone failed on the device with `NoClassDefFoundError: androidx.tracing.PropagationToken`:
  the app had an older androidx.tracing, so the test APK left those classes out. Here the same test passed (not
  traced: the class is in neither APK). DataStore went into the app.
- An Espresso tap on a field just below the top of the screen landed on the activity's action bar on both sides
  (`launchFragmentInContainer`'s activity has one, and the content is laid out under it); on the emulator the field
  had the focus already from an earlier test's key events, so typing still reached it. The screens now have no action
  bar and are padded by the system bars (a device's test runs have them, apksim's none).

## Run against AndroidX's own tests

Not yet: blocked on the permission to build AndroidX's playground projects (`work/androidx`, never committed). Once
allowed, each library's androidTest APK goes through the same script and its table is added here.
