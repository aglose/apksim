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

51 tests, with WorkManager 2.12 and DataStore 1.3 in the app (as an app using them has them: WorkManager's startup
initializer and job service are then the app's) and Room 2.8 (its database the tests' own, compiled by KSP) and
espresso-intents in the tests. With the fixes below, all 51 pass on both sides, here in both clock modes.

| Class | Tests | Passes on both | Fails here only, before | Fixed by |
|---|---|---|---|---|
| WorkManagerConformanceTest (a one-time request's output, a chain's input, a failure failing its dependents, retry after backoff, unique work KEEP and REPLACE, cancelling a CoroutineWorker, an unmet constraint, an initial delay) | 9 | 9 | 0 | |
| RoomConformanceTest (a blocking DAO call from the test body, on the main thread, in runOnUiThread and in a lifecycle callback, suspend queries, a Flow re-emitting, an invalidation observer, a transaction rolled back, a file database reopened, a migration, a destructive fallback, a missing migration) | 12 | 12 | 4 | the test body's thread |
| DataStoreConformanceTest (an edit read back, data's emissions, a new store on the same file, two active stores, a failed edit, a corrupt file with and without a handler, one preferencesDataStore delegate store per process) | 8 | 8 | 0 | |
| EspressoConformanceTest (typeText, an idling resource, onIdle and a delayed post, onData, a Spinner's popup, a dialog's root and pressBack, scrollTo, missing and ambiguous matches, espresso-intents stubs and a result, replaceText) | 10 | 10 | 0 | |
| UiAutomatorConformanceTest (a click by text, setText, scrollUntil in a ScrollView, a RecyclerView and one of tall items, Until.gone, pressBack on a dialog, the display size, setOrientationLeft, the hierarchy dump, executeShellCommand, a scroll event's source) | 12 | 12 | 2 | scroll events |

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
   The event is delivered as a service receives it: sealed, its source the scrolled view (which the view's own
   `onInitializeAccessibilityEvent` sets, with its window's id), on that window's in-process connection, and
   `getSource()` that view's node. Before, `getSource()` was null: Robolectric's `ShadowAccessibilityRecord` answers it
   only with a node given to it (`setSourceNode`), never through a connection. So a filter of
   `UiAutomation.executeAndWaitForEvent` that read the source matched nothing and the wait timed out
   (`aScrollEventsSourceIsTheScrolledView`, which passes on the emulator; UiAutomator's own scroll waits never read
   the source).
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

## An app that targets 28: API 30, API 36 and apksim (2026-11-13)

A consumer's app has `targetSdk 28` and its CI emulators run API 30 (Android 11); apksim runs the API 36 framework. Two
layers differ: **target-gated behaviour** (what the platform does for an app of an older target) and **ungated changes**
between Android 11 and 16. Both are measured with the test app built for target 28 on three: `ApksimCmp30` (AGENTS.md
"Comparing with the emulator"), the shared `ApksimCmp36` (`emulator-5580`) and apksim.

```bash
cd testapks/composeapp && ./gradlew -Papksim.targetSdk=28 :app:assembleDebug :app:assembleDebugAndroidTest   # build it last, and both tasks together
B=testapks/composeapp/app/build-t28/outputs/apk
python3 scripts/conformance.py $B/androidTest/debug/app-t28-debug-androidTest.apk --app $B/debug/app-t28-debug.apk \
  --name full-api30 --emulator emulator-5582 --util test-services-1.6.0.apk -e expectTarget 28   # then --name full-api36 --emulator emulator-5580
scripts/target-gate-check.sh testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk $B/debug/app-t28-debug.apk
```

The property (`-Papksim.targetSdk=N`) sets `targetSdk` and builds into `app/build-tN/` as `app-tN-debug.apk` and
`app-tN-debug-androidTest.apk`; without it nothing changes (`app/build/outputs/apk/debug/app-debug.apk`). Run the default
build first and the target build last, both tasks in one command: a default build after a target one, or one task alone,
left the other's outputs folder without its androidTest APK in this checkout. The target-28 build also uses
`src/t28/AndroidManifest.xml` as the debug source set's manifest, which AGP merges over the main one: the legacy
`BLUETOOTH` and `BLUETOOTH_ADMIN` with no `maxSdkVersion`, and not the API 31 `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT`, as an app
of that target declares them (a release build with the property gets the target without the overlay). A non-numeric value
stops the build with a message. Lint is not part of `assemble`; nothing else needed changing.

`-e expectTarget 28` is what lets `Target28Test` run, and is independent of the targetSdk under test: without it every test
there is skipped (the default build, or a run that forgets it), and `theApkTargetsWhatTheBuildAskedFor` compares the two. It
is an argument rather than an app `<meta-data>` in the overlay because that one made eight `WorkManagerConformanceTest`
tests fail on apksim only (they passed with and without it on the other build, repeatably; the mechanism is untraced and
is left as a finding).

### The suite (418 tests that ran somewhere; 421 entries with three pseudo-entries: an incomplete `(run)`, Test Storage's `Test mechanism`, `[assumed away]`)

| | API 30 emulator | API 36 emulator |
|---|---|---|
| apksim agrees (both pass) | 266 | 264 |
| fails on apksim only | 0 | 0 |
| passes on apksim only | 10 | 12 |
| skipped on a side (assumptions, switches) | 142 | 141 |

The skipped tests are not compared. Every test that passes on an emulator and ran on apksim passes there, with one kind of
exception below (tests apksim skips for want of a switch). Logs: `work/conformance/full-api30/`, `full-api36/`,
`rerun-api36/`; the target-28 probe's raw output for each side: `work/conformance/probe/`. The tests the three do not agree
on, by layer (✗ = fails, – = skipped or did not run):

| Test(s) | API 30 | API 36 | apksim | Layer |
|---|---|---|---|---|
| `BluetoothTest#c_svc…` | ✗ timed out waiting for STATE_OFF | pass | pass | Android's: `svc bluetooth disable` on API 30 (not traced) |
| `NativeBitmapTest#rgba1010102…`, `UiaEngineTest#looksAtAScreen…` | ✗ no `Bitmap.Config.RGBA_1010102` (API 33), no `RuntimeShader` (API 33) | pass | pass | Android's: the test app uses newer API |
| `UiaEngineTest#aFilter…` (2), the incomplete `(run)` | ✗ the process crashed | pass | – (`--uia engine` only) | Android's 30 (a crash in the process; not traced) |
| `ParameterizedSkipTest#…[assumed away]` | ✗ "not run: the process crashed before it" (follows the crash above) | – | – | Android's 30 |
| `NullBindingTest#aConnectionWithoutAutoCreateHearsTheServiceGoAndCome` | ✗ callbacks differ | pass | pass | Android's, 30 vs 36 (not traced) |
| `ShellCommandTest#logcatHoldsTheProcessLog` | ✗ `logcat -c` leaves the line | pass | pass | Android's (not traced) |
| `CoveredWindowsTest#aNonModalDialogLeavesTheActivityReported` | pass | ✗ "the tap reached the activity", **also alone and with the default target-36 APK** | pass | Android's 36, not target-gated; untraced; apksim agrees with API 30 |
| `ValueAnimatorConformanceTest#anInfiniteAnimatorKeepsRepeating` (+1) | pass | ✗ "only 0 repeats" in the full run, **passes alone** at target 28 and 36 | – (`-e repeatForever`) | unexplained: leaked state or order on API 36 |
| `ComposeUiConformanceTest#lifecycleOwner…`, `FragmentConformanceTest` (2), `RotationConformanceTest`, `UiAutomatorConformanceTest#untilGone…`, `UiAutomatorTest#swipeScrolls…`, `UiaEngineTest#aWait…`, `…#accessibilityIsOn…` | pass | ✗ in the full run, **all pass alone** (`rerun-api36/`) | pass / – | noise of the API 36 run: a target-28 app raises the POST_NOTIFICATIONS prompt on API 33+ and it takes the focus (a watcher pressed Back on it; a run without one failed ~90 UI tests) |
| `ComposeUiConformanceTest#systemBarInsetsMatchTheView` | ✗ 48 against 0 | ✗ 48 against 0 | pass (no bars: 0 = 0) | **target gate**, Android's (below) |
| `DialogFocusTest`, `RunnerListenersTest#testStorage…`, `StackWindowsTest` (2) | ✗ | ✗ (differently: `StackWindowsTest` ends CREATED→STARTED on 30, DESTROYED on 36) | pass | neither emulator: their premises are apksim's (a test run's window stack, runner arguments through Test Storage) |
| `RealTimeSwipeTest#aSwipeOverAPostFromEachFrame…`, `FailureBundleParameterizedTest` (2), `Test mechanism` (Test Storage `FileNotFoundException`) | ✗ | ✗ | – | a switch of apksim's (`--realtime-clock`, `-e failOnPurpose`), and Test Storage's output files |
| `RealTimeClockTest`, `NativeLoaderTest` moved-data tests, `UiaEngineTest` engine-only, `ComposeClockStepTest` | pass | pass | – | apksim skips for want of a switch (`--realtime-clock`, `--move-shared-data`, `--uia engine`) |

The suite was re-measured on `main` with the compatibility table and the sensors (`main-api30/`, `main-api36/`): the same
266 / 0 / 10 and 264 / 0 / 12, with 31 tests more (`CompatChangeTest`, `SensorsTest`), apksim's own and run here only. Both
assume what a target-36 app sees, so they are skipped below it (`CompatChangeTest`: the ids are for 36; `SensorsTest`'s
step counter needs no ACTIVITY_RECOGNITION below target 29, as on Android 11: a target gate apksim follows).

### System bars: the target gate that does follow the target

At target 28 Compose's `WindowInsets.systemBars` is 0 while the root view's insets are 48 on both emulators (edge-to-edge is
enforced from target 35): `ComposeUiConformanceTest#systemBarInsetsMatchTheView` fails there, and passes at target 36
(API 36 emulator, default build). A test run has no bars, so apksim passes both (0 = 0). With bars (`--status-bar on --nav-bar
gesture`) apksim gives the emulators' answer: target 28 fails with `expected:<48> but was:<0>`, target 36 passes, the same
before the compatibility table (#326) and after it, on `main`. So this gate is
already followed through the framework's own code; it is not one of the gates `CompatChanges` reads as enabled.

### Target-gated behaviour: `Target28Test#probe`

`Target28Test#probe` prints one `t28.KEY=VALUE` line per observation as instrumentation status and asserts nothing; the
table is made from the probe run alone on a fresh install of each side (`work/conformance/probe/`), before the compatibility table landed
and again on `main` with it (identical). The asserting tests are few on purpose: `theApkTargetsWhatTheBuildAskedFor`
and `legacyBluetoothPermissionsAllowAScan` (the refusal without the permission is `BleTest`'s `e_a`/`e_c`, which now revoke
`BLUETOOTH_ADMIN`/`BLUETOOTH` below target 31 and assert Ble.java's message; an emulator cannot revoke a normal permission).
Notifications are not asserted in a test run: `Notifications.allowed()` (shown below target 33, else only with
POST_NOTIFICATIONS) lives in a running instance and is checked there by `scripts/target-gate-check.sh` (the test app's Notify
button: the target-36 build is held back, the target-28 one shown); in a test run `areNotificationsEnabled()` is Robolectric's
default true whatever the target, which is a gap for target 33+.

| Observation (target 28) | API 30 | API 36 | apksim | Layer |
|---|---|---|---|---|
| `checkSelfPermission` BLUETOOTH, BLUETOOTH_ADMIN, VIBRATE; CAMERA, ACCESS_FINE_LOCATION, POST_NOTIFICATIONS denied | same | same | same | |
| `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` | denied | **granted** (split permissions of the legacy ones) | denied | target gate: apksim has no split permissions; `Ble.java` enforces the legacy ones itself, so scans and names behave alike |
| Adapter name, `startScan` | allowed | allowed | allowed | |
| `areNotificationsEnabled`; posted notification listed | true; yes | **false; no** (the prompt a channel raises, until accepted) | true; yes | target gate in a running instance (`Notifications.allowed()`, below 33 shown: follows API 30); in a test run Robolectric's default |
| `canScheduleExactAlarms` | n/a (API 31) | true | **false** | target gate (S, 31: `AlarmManager`): Robolectric's default; apps below S are allowed exact alarms |
| `getLastKnownLocation("gps")` without the permission | SecurityException | SecurityException | **ok** | ungated: apksim does not enforce location permissions |
| `getInstalledPackages`, `getPackageInfo("com.android.settings")` | the system's packages | the system's packages | **none** | ungated: apksim has no system apps (visibility filtering starts at target 30, so a target-28 app is meant to see them) |
| `NetworkSecurityPolicy.isCleartextTrafficPermitted`, `Environment.isExternalStorageLegacy`, `PendingIntent` without a mutability flag | true, false, ok | same | same | not asserted for the last: `ShadowPendingIntent` never throws, so a target-36 app that omits the flag is also fine here (a gap) |

Left, with their mechanisms: split permissions (`InstallState` grants only the permissions an APK lists), exact alarms
(`ShadowAlarmManager`'s flag is never set from the target), location enforcement, the system package list, PendingIntent
mutability, `areNotificationsEnabled()` in test runs at target 33+, and the WorkManager observation above.

### CI

The `build` job builds the target-28 pair after the default one and the `tests` leg runs `Target28Test`, `BleTest`,
`BluetoothTest` and `InstallPermissionsTest` on it (`-e expectTarget 28`). Cost, measured: the target-28 build is ~10 s warm
on the Mac (cold in CI an estimate of 30-60 s on the 93 s build step, one more Gradle invocation); the whole 418-test suite
on apksim is 48 s on the Mac and the default suite's CI step 281 s under Rosetta, so this subset is estimated at 30-60 s in
the image. The CI addition is estimated at about 1.5-2 min in all and was not measured in CI before this PR.
