# Instrumentation tests (`--instrument`), UiAutomator, bundles and movies

Agent reference notes, moved out of AGENTS.md so they are read only when needed. Quoted section names
("Video", "Warm launches") are sections of these files: AGENTS.md's index says which file has each.

## Instrumentation tests (androidTest APKs)
`./apksim --instrument app-debug-androidTest.apk [-e KEY VALUE]... app-debug.apk` runs a test APK against the app as
`am instrument -w -r` would and prints its raw output (INSTRUMENTATION_STATUS blocks, exit 1 on any failure). As on a
device, the test APK's classes join the app's in one process: `AppClasses.installTest` serves them from `apksim:/test/`
after the app's (their class cache hints are `<package>.test.classes`). `Instrument` reads `<instrumentation>` from the
test manifest, finds the test classes (library packages are not searched), and runs each under `SimRunner`
(`@RunWith(AndroidJUnit4)` is pointed there by `android.junit.runner`). Tests run the Robolectric way, not the device
way: the test body on the main thread, Espresso/ActivityScenario through Robolectric's in-process `LocalUiController`
and `LocalActivityInvoker`, a simulated clock that jumps over idle time, and no window or Host loop. The
instrumentation class itself (AndroidJUnitRunner or a subclass) is not run, except its `newApplication`: when it is
not plain AndroidJUnitRunner, `RunnerApplication` first runs a probe test (with a plain Application, so the app's
onCreate does not run) that creates the runner and calls its newApplication with the manifest's class; the
Instrumentation shadow records the class it hands on to Instrumentation.newApplication and stops there, and every test
then gets that Application (`SimTestEnvironment` sets `Config.application`; Robolectric's global config is fixed
before the probe). If the runner's newApplication fails with the probe's context, the manifest's is kept, with a
line in the log; `--jvm -Dapksim.runnerApplication=false` skips the probe. The test app's `HarnessRunner` swaps in
`HarnessTestApplication` (`RunnerApplicationTest`). The runner's onCreate runs too, once a process at the first
test's setup (`TestRunnerSetup`): up to its `super.onCreate(arguments)`, which the translator sends to
`TestRunnerSetup.superOnCreate` so it keeps the arguments the runner passes on instead of starting a run. The
listeners those arguments name (`listener`, which a report library's runner adds to) and any `-e listener` hear the
run through `TestRunnerSetup.LISTENERS`, as AndroidJUnitRunner registers them; they join at the first test, which
they hear started (tests ignored before it are replayed to them), and the arguments the runner passed on are the run's
from then on. Only a runner's own onCreate runs (AndroidJUnitRunner's would start a run), after `Instrumentation.init`
with the test APK's and the target's contexts. A listener that throws stops hearing the run and the run fails (exit
status), as JUnit drops a failing listener. A suite whose every test is ignored reaches no test setup, so its runner
and listeners are not set up. Robolectric resets its providers before JUnit reports a test's end, so the Test Storage provider
(`SimTestStorage`: test_outputfiles, test_runfiles, internal_use and testargs, files under `--test-outputs DIR`,
default `cache/test-outputs` in the state folder) is registered again before each listener call. The test app's
`HarnessRunner` adds `RecordingListener` (`RunnerListenersTest`).
`-e class a.B#m,a.C`, `notClass`, `package`, `notPackage`, `log true` (list only) work (`Class#method` also selects every parameter of a Parameterized `method[...]`, as androidx's MethodFilter strips the suffix; `Class#method[A]` stays exact; in `class`/`notClass`, `\,` is
a comma inside a name and `\\` a backslash, for Parameterized's `test[0: x, y]`: an apksim extension, which
`instrument.py` writes when it names tests; any other backslash is literal), and every `-e` reaches
`InstrumentationRegistry.getArguments()`; `--device tablet` is 2560x1600 at density 2, the emulator's Nexus 10.
Each test gets a fresh Application and data folder (the app's persistent data is not used), but app statics carry over
within a process. `scripts/instrument.py APP TEST [--isolate] [-j N] [--shards N [--timings XML] [--shard I]]
[--xml FILE] [--record-deps FILE | --affected-by FILE --changed PATH...|--diff REV] [--test-timeout SEC]
[--order interleave|longest-first|suite] [--exit-marks] [--reap-early SEC] [--run-ignored] [-- APKSIM_OPTS]` runs a
suite and writes JUnit XML (with each test's time, from when its output arrived); `--isolate` gives every test its own
process, as the AndroidX Test Orchestrator with `clearPackageData` does, except that a test `@Ignore`'d on its method or
class is reported ignored without one (its listing passes `-e apksimListIgnored true`, which makes `Instrument`'s `-e log
true` listing report such tests ignored rather than passed; an assumption's skip still needs its process;
`--run-ignored` gives them one, as the Orchestrator does; the app's runner listeners hear of them neither way, as a
process whose every test is ignored sets none up; issue 205); `--shards N` splits the suite over N processes
at once with AndroidJUnitRunner's `-e numShards N -e shardIndex I`, which `Instrument` takes as a device's runner does
(a test's shard is `abs(description.hashCode() % N)`, AndroidJUnitRunner's `abs(hash) % N` except for a hash of
`Integer.MIN_VALUE`, which matched no shard there; an empty shard is a run of no tests); with `--timings XML` (an
earlier run's JUnit XML) the shards are balanced by measured time instead, longest first, each named with `-e class`,
and `--shard I` runs only one of them (a CI matrix); with `--isolate` the tests' start order is `--order` (default
`interleave`: the longest and shortest remaining alternate, so a machine's heaviest tests do not all start together and
push one another past a `Timeout`; `longest-first` was the old order; docs/affected-tests.md). `--exit-marks`
(`-Dapksim.exitMarks=true`) prints under each isolated test where its process's exit went (`exit marks`: profile,
class cache, scratch removal, the non-daemon threads left; `halt marks` from `Standby.halt`: net flush, next standby,
cache sweep), and makes apksim print `APKSIM_EXIT: STATUS` right after `INSTRUMENTATION_CODE`; `--isolate --reap-early
SEC` takes that line as the process's end, frees its `-j` slot and finishes the teardown in the background, killing the
process group after SEC s; `--isolate --group-size N [--group-seconds T] [--isolate-classes REGEX] [--retry-isolated N]`
runs up to N tests of a class per process (a group also ends at T s of `--timings` time), keeps classes matching REGEX on a
process per test, runs a test that failed in a shared process again alone (last attempt wins, `apksim.retried` in the XML),
always gives a group's never-started tests a process each, and every entry gets its process's CPU seconds
(`apksim.cpu_seconds`, from `os.wait4`; docs/parallel.md "Groups"; `instrument-check.py`'s `group*` scenarios) (reap-early is off by default; everything a test leaves, bundles, recordings, outputs, listeners, is
written before it; only the class cache save, scratch removal and cache sweep come after). `--isolate` keeps `--prewarm`
(2 x `-j`, or N) processes booted ahead (`TestPool`, launcher `--pooled`: boots the sandbox through a probe with no runner, then reads its test's
`-e` pairs on stdin), one test each as before; `--prewarm off` starts each cold (docs/test-pool.md).
`scripts/flake-hunt.py` reruns chosen tests under shuffled order (`-e apksimOrder`), isolation, CPU load and
`--realtime-clock`, and reduces order-dependent failures to their culprits by ddmin (docs/flake-hunt.md). `--record-deps FILE` records which of the app's and tests'
classes each test runs code of (`-Dapksim.deps=true`: the translator adds a probe to every method of the app's
packages, `dex/Probes`, and Instrument's `DepsListener` reports each test's classes on stdout), and `--affected-by FILE
--changed PATH...|--diff REV` runs only the tests that reached a class compiled from a changed `.kt`/`.java` file (by
DEX source file, package and Kotlin's SMAP), and the whole suite for any other change (docs/affected-tests.md). Test
runs stop the JIT at C1 (the launcher adds `-XX:TieredStopAtLevel=1` for `--instrument`, before `--jvm` options): ~40% less CPU at the same
wall time on a large app's suites, and no cliff when more processes run than there are cores (docs/parallel.md). Without it, a process that ends before every test has a result (a native crash or abort, or
the Timeout watchdog below) does not end the run: the test that was running is reported failed with the exit status
and the stderr tail (with none running, a failed "(run)" entry is, so the run still fails), the suite is listed (`-e log true`, the run's own `-e` arguments, only once a process has died),
and the tests with no result run in a new process, as `-e class` (whole classes where none of their tests ran, else
Class#method, chunked under Linux's 128 KB argument limit) with the other `-e` arguments kept. No test runs twice; a
new process that adds no result (a crash at startup) ends the run, the rest reported failed as not run.
`--test-timeout SEC` (default 900, 0 for none) handles a hang the same way: a process that goes SEC s without a test
starting or ending (stuck in teardown, or out of the watchdog's sight) gets a SIGQUIT (the JVM's thread dump: the
stacks of the threads in JUnit code go in the failure) and then SIGKILL to its process group (each process gets a
session of its own, so instrument.py forwards Ctrl-C/SIGTERM itself: a process starts and is noted for that under one
lock, and none starts once a stop has begun), and with `--apksim "docker|podman run ..."` a `docker rm -f` (by a
`--cidfile` instrument.py adds: SIGKILL to the client leaves the container running; the ID is read before the client is
killed and the client killed before `rm -f`, and a client that dies of any signal has its container removed too; each
process gets a cidfile of its own, as docker refuses one that exists, so a `--cidfile` in `--apksim` is taken out and
gets the ID of the last container to end); the test
fails "timed out after SEC s" with the dump's JUnit threads first (each whole from its header down, cut at the
bottom), then the stderr tail, which starts at a stack's header rather than inside it; a stack at the end of stderr
need not be where the process is stuck (issue 115's was CloseGuard reporting, at a GC, a descriptor a listener's
executeShellCommand returned and never closed), and with `--bundles` gets process.txt. Test runs launch cold, so
the process is the JVM itself (the launcher execs it), never a standby's client.
`ProcessDeathTest` checks it with `-e crashOnPurpose true` (otherwise its two crashing tests are skipped): one halts
the process, one ignores interrupts past its timeout, and the tests after each still pass; with `-e hangOnPurpose
true` one more hangs with nothing to end it but `--test-timeout`. `instrument-check.py` covers both against a stand-in
(hangs one process, sharded, isolated, through a stand-in docker, and Ctrl-C, also while a process starts).
**Failure bundles** (`--bundles DIR`, also `instrument.py --bundles DIR`; `Bundles`, docs/plan.md phase 5): each
failing test leaves `DIR/<class>/<method>-<8 hex of SHA-256("class#method")>/` with `failure.txt` (stack, settings,
which capture point wrote it, how long each part took), `screen.png` (every window, drawn as UiAutomation's screenshot
is), `windows.txt` (every window, those accessibility leaves out marked `covered`), `nodes.txt` (as the `nodes`
command: what UiAutomator could reach), `logcat.txt` (that test's lines only: `AdbLog` is installed
for the run), `threads.txt`, `looper.txt` (the main queue and androidx.test's activity stages) and `repro.sh` (the
launch narrowed to `-e class` that test, from `-Dapksim.argv`). Where it captures matters: listeners hear of a
failure after `@After` and rules have closed the activity, so `SimRunner`'s helper runner wraps `methodInvoker`
(the body), the handler Espresso has at the start of each body is wrapped through its holder (`Espresso.BASE
.failureHolder()`: the wrapper captures, then hands the failure to that handler, so a test's own still decides; a
scenario closed by `use {}` is gone by the time the exception leaves the body; a test that catches an Espresso failure
and passes loses its bundle), the listener takes the rest (@Before/@After/rule failures, classes with their own
`@RunWith`; with `--bundles-on all` it rewrites a body's "passed" bundle when teardown then fails), and
`FailOnTimeout`'s watchdog writes the failure, threads and log before it ends the process. A capture must not change
what the test does next, and reading the tree does: Compose's node provider adds a child view (AndroidViewsHandler) to
the AndroidComposeView and builds its accessibility caches, and a test polling past a handled Espresso failure then
failed (#73; `FailureBundleHandledTest`). So the Espresso handler's capture leaves `nodes.txt` for later (`nodesPending`:
a placeholder until then) and reads it once that screen cannot matter: when the failure leaves the body, as an activity
finishes (`ShadowSimActivity.finish`, before it: that activity's captured windows only, e.g. a `use {}` scenario
closing, as another shown below stays in use), or at the listener; each read is appended with a line saying when and
whose windows, until every captured window is read (`pendingRoots`), and failure.txt's parts get its time. Body and listener captures read it at once. A capture never runs the
main queue or moves the clock (uptime is read through `ApksimClock`, not SystemClock, which `--realtime-clock` would
advance); a part past its deadline (screen 2 s, others 5 s) has its thread interrupted and is skipped in later bundles;
a test's first 2,500 log lines are kept as they arrive (`AdbLog.keepHead`), past the ring's 20,000. A bundle's folder is
emptied before it is written. `--bundles-on all`, `--bundles-max N` (default 50 full bundles a process),
`--bundles-redact REGEX` (instrument.py applies it to process.txt and index.html's previews too, with Python's re:
`\Q...\E` is translated, other syntax must be what both read, and one Python cannot read stops the run before it
starts). A bundle follows the test's outcome: a different exception ending the test after a handled one rewrites it
(Espresso's handler throws its own exception for what it got, which counts as the same failure); with
`--bundles-on all` a test that passed, including one whose class has its own runner or whose body threw what
`@Test(expected)` expected, ends with a bundle that says passed.
**Test movies** (`--record failed|all --record-dir DIR [--record-size WxH] [--record-fps N]`, also `instrument.py`, which
links them from the JUnit XML like bundles; `TestRecorder`, `scripts/record-check.py`, issue 132): Motion-JPEG
`DIR/<class>/<method>-<hash>.avi` (`MjpegRecorder`, so headless and in the Linux image too), kept for a failed test (all
with `all`) and deleted otherwise. A test run has no display loop, so `ShadowSimViewRootImpl` offers a frame after each
window traversal and `TestRecorder` draws the screen (`Bundles.screenBitmap`) at most N (default 15) times a second of
real time (each frame due an interval after the last capture ended and at least 4x its cost later, so a slow draw never starves the main looper: issue 360; `scripts/record-check.py` checks it with `-Dapksim.record.captureDelayMs`), fitted into WxH (default 1280x720, never up); the writer repeats the last frame, so a movie runs in real time
(the simulated clock's idle jumps do not show), and a test that never shows a window has none. A recording must never
fail a test (issue 144: a capture taken inside `ViewRootImpl.performDraw` through HWUI's `HardwareRenderingScreenshot`
never returned from `nSyncAndDrawFrame`), so a frame is taken from a main-thread message posted after the window's draw
(never inside one: `ShadowSimViewRootImpl.drawing`), drawn on a CPU canvas (no render thread; elevation shadows are not
drawn, unlike a bundle's screen.png), and a frame slower than 2 s ends that test's movie with a line on stderr.
`--record-format mp4` (issue 454, `Mp4Recorder`) writes `<method>-<hash>.mp4` instead: the frames and real-time pacing of the
AVI, encoded by `H264Encoder` (VideoToolbox, OpenH264 in the image), as avc1 with `moov` before `mdat`, in `.part` while it
runs and put together at the end, so a dead process leaves no .mp4. A frame the encoder drops extends the previous one's
duration (stts has runs). With no encoder (Linux without libopenh264) apksim says so once on stderr and writes the AVI;
`instrument.py` links whichever exists. Each kept movie has a sidecar `<method>-<hash>.json` (`start_epoch_ms` of its first
frame, `duration_ms`, `fps`, `width`, `height`, `format`), and the JUnit XML a property `apksim.recording.startEpochMs`
(`scripts/record-check.py` checks both formats). Not done: `adb shell screenrecord` in test runs; a process that dies mid-test leaves an
unfinished (index-less) AVI.
`instrument.py` removes an earlier run's bundles from DIR first, adds `process.txt` for a test whose process died, links bundles from the XML (a property, and
`[[ATTACHMENT|...]]` in `system-out`) and writes `DIR/index.html`; with `--apksim` it reads them from
`--bundles-local`. The sandbox allows writes to DIR. As root (the image) the folders are made writable by everyone,
for the host's `instrument.py`. `scripts/bundle-check.py` (CI) checks it with the test app's `FailureBundle*Test`
classes, which fail only with `-e failOnPurpose true` (`watchdog` for the one that ends the process). `scripts/observer-gate.py` (CI) runs a suite plain and with every observer on (bundles of all tests, movies, `--rec net,db`, coverage) and fails when a test's outcome or the set of tests differs: an observer must never change what it observes. Two things this needed in `SimRunner`: one shared injector (so one sandbox: a second
sandbox brought a second main thread to a main Looper already bound to the first), and `SimTestEnvironment`, whose
reset between tests asks the JVM's loader which classes are loaded (Robolectric casts its own loader to
`ClassTracker`, which `FlatLoader` bypasses). Every test runs on API 36 whatever the APK targets (`SimRunner.OneSdk`:
Robolectric otherwise picks the manifest's targetSdk, and an app targeting 37 had no tests left to run). Test classes
are found from their class bytes (ASM), not reflection: that runs before the sandbox exists, and parsing an annotation
whose default is a framework enum initialized that class without it, breaking it for the run. A library's test APK
(KMP `androidDeviceTest`, which instruments itself) is passed as both: `--instrument X-androidTest.apk X-androidTest.apk`
(no launcher activity needed in a test run). The test app's `src/androidTest` (Compose rule, Espresso, UiAutomator, an
animation clock check, a translator check and offscreen GLES) is the check:
`./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`; CI runs it in the Linux image. The translator's own
regression check is `scripts/check-translator.sh` (CI too): `testapks/translator-fixture` is plain Java built with R8
(`fixture.dex`) and with D8 in debug mode (`fixture-d8-debug.dex`), whose register reuse produced the two liveness
miscompilations (an int returned in a synchronized block's lock register; a return shared by paths inside and outside
a lock's catch-all range); it translates them and runs them on a plain JVM. `multidex/` (from `src-multi`) is one app over two DEX
files, a class in `classes.dex` making a super call to a default method that only its superclass in `classes2.dex`
reaches (issue 522): the translator asks the whole app for superclasses and interfaces (`dex/Hierarchy`, AppClasses'
across every DEX file and the test APK's; the class cache keeps each answer used as a one-byte hash under a
`NUL class NUL method` key, so a pack still depends on other DEX files only through recorded answers). Its `build.sh` rebuilds the committed
`.dex` files. When the translator miscompiles a new shape, add it there and check it fails before the fix.
The native loader's is `testapks/loader-fixture` (`loaderfixture.c`, built with the NDK by its `build.sh` into the
test app's `jniLibs` for arm64-v8a and x86_64; the `.so` files are committed so the test app needs no NDK), run by
`NativeLoaderTest` in the suite, so on the Mac and in both Linux images: the stack guard through the thread pointer,
TLS on several threads with registers live across the access (TLSDESC on arm64), barriers (reused, destroyed at once), mutexes and condition
variables at 4 mod 8 (boxed on both Linux architectures), thread attributes with `pthread_getattr_np` and an app-supplied stack, and setjmp/longjmp into bionic's jmp_buf. It is
built for API 29, so it also has Android's own RELR tags (`DT_ANDROID_RELR`, which the NDK writes for API 28-29
libraries); the loader once skipped them and called an unrelocated initialiser. Beside it, `pagefixture.c` builds two
libraries linked for 4 KB pages: `libpagefixture4k.so` (code and RELRO on one 16 KB page: must load and run on the
Mac) and `libpagefixturenorelro.so` (`-z norelro`: code and writable data on one page: refused on the Mac by default,
moved with `--move-shared-data`), checked by `NativeLoaderTest` too (the move tests run on the Mac only with the switch,
whose property the test reads; the refusal test only without it) (relocated pointers, .data, .bss, an exported variable through the GOT and
dlsym, a load (literal) and an adr in assembly, and through dl_iterate_phdr: .data's PT_LOAD, PT_DYNAMIC, DT_PLTGOT's
live GOT and the symbol's st_value); `pagefixturefar.c` (moved data out of an adr's reach: an UnsatisfiedLinkError
naming the entry on every try) and `pagefixturecxx.cpp` (a C++ exception caught with its personality slot moved).
`build.sh` checks each still has code and data on one 16 KB page (issue 137). When the loader or a bionic
shim gets a new case wrong, add it there; a device passes every check.
UiAutomator works (`ShadowSimUiAutomation`): getWindows lists the windows `AccessibleWindows` reports (under a
Dialog, only the dialog, as on a device: issue 97), getRootInActiveWindow the topmost focusable one. Each window gets an
accessibility window id (`AttachInfo.mAccessibilityWindowId`, which the system assigns on a device): a node's
equals/hashCode include it, and without it every window's root was equal to every other's, so UiDevice (which keeps
the roots in a set) searched only the active window. Node bounds are on the screen for a window not at 0,0 (a
centred dialog): AccessibilityInteractionController takes the window's position off each node and applies the
window-to-screen matrix the accessibility service gives (`ShadowSimAccessibilityManager.getWindowTransformationSpec`;
Robolectric's is the identity, so a dialog's nodes sat at the top left and clicks missed). Each window's root node is
linked to its views with
`setQueryFromAppProcessEnabled` (API 34), so UiAutomator walks the real view and Compose tree through the framework's
AccessibilityInteractionController (By.text/desc/res with testTagsAsResourceId, clicks and gestures by injection,
setOrientation* as a real configuration change, waitForIdle advancing the simulated clock frame by frame until the
UI is quiet, for its quiet time at most: an open Compose Popup polls its position every frame, and with Robolectric's
unpaused Choreographer that frame loop never let the looper idle). UiAutomator waits for a Choreographer frame after each step of `UiDevice.swipe`/`drag`, `UiObject.swipe*`, `UiScrollable` and multi-pointer gestures (`InteractionController.waitForNextFrame`: a post to the main looper, then `latch.await(5 s)`); on the main thread that post never ran and each step waited out 5 s (a 20-step swipe took 100 s), so the translator sends that await to `TestActivities.awaitFrame`, which runs the main queue one task at a time until the frame has been drawn, checking the latch and timeout between tasks (an open Popup's frame loop never let `idle()` return) (`UiAutomatorTest.swipeScrollsTheListWithoutWaitingForFrames`, `PopupTouchTest.aSwipeWithAPopupOpenWaitsOnlyForFrames`). No accessibility event stream: executeAndWaitForEvent reports, after the action settles, a
TYPE_VIEW_SCROLLED for each view whose scroll state changed (filled by the view's own onInitializeAccessibilityEvent:
scrollY/maxScrollY, or from/to/item count; changed means those or its first child's position, as a list moves its
children and a swipe within a tall item changes no index; sealed, its source the view, on the window's in-process
connection, so a filter's `getSource()` is that view's node, as a service receives it), then a window change; other
filters time out. UiAutomator's scroll, fling
and scrollUntil read the end from that scroll event (`Until.scrollFinished`); without it scrollUntil took each swipe
for a null scroll and gave up after five (`UiAutomatorConformanceTest`). Compose scrolling has no such view state, so
it gets none. With `--realtime-clock` the idle wait (`settle`) runs what comes due for its quiet time and stops once
nothing is scheduled within 50 ms, so a window just shown has its first layout before a query. `TestActivities` does the system server's part that Host's stack does otherwise: BACK goes to
the window's back callback as ViewRootImpl would (Robolectric injects past it), back on a launcher task's root moves it
to the background, finish() pauses/stops/destroys and resumes the one below, and a start for a running
singleTask/singleTop activity is delivered to its onNewIntent. More of a device's behaviour that a test run needs
(each found by a suite that passed on the emulator and failed here):
- Compose's test clock posts to the UI thread behind what is queued there on a device; here the test is on the main
  thread, so the translator calls `TestActivities.beforeUiThreadAction` before Compose's `runOnUiThread`, which runs
  the main queue first (GlobalSnapshotManager's apply). Without it, with autoAdvance off, a state set in runOnIdle
  never reached the composition. The looper stays PAUSED: Compose's Robolectric idling needs it
  (INSTRUMENTATION_TEST mode made it call Espresso.onIdle on the main thread; a device fingerprint made it wait for
  draw passes that never come).
- The Compose rule's idling resource is noted where the translator sees `RobolectricIdlingStrategy`'s constructor, so
  Espresso's waits also wait for Compose (`TestActivities.composeIdling`). ui-test 1.12 gave that constructor a third
  argument; until both shapes were matched, `ComposeEspressoIdleTest` failed on Compose 1.12.
- `Thread.sleep` in app and test code is translated to `TestActivities.sleep`: in a test run, on the main thread and
  outside a message dispatch, it runs the main queue's due messages while sleeping (the clock does not move), as a
  device's main thread would run beside the test thread. A journey polling its fake server for a request that a
  Dispatchers.Main coroutine had not sent yet otherwise waited out its timeout (`MainThreadSleepTest`). Work Compose's
  test library hands to `runOnUiThread` (its clock's frames, runOnIdle) counts as a dispatch, as it is a main-thread
  message on a device: a composable's `runBlocking` in a frame the clock stepped ran a window frame from inside the
  measure, which Compose refused ("performMeasureAndLayout called during measure layout", `ComposeBlockingReadTest`).
- JUnit's `Timeout` rule (and `@Test(timeout)` in plain JUnit runners) runs the test on a thread of its own, and
  Robolectric refuses to drive the main looper from there ("Main looper can only be controlled from its thread in
  PAUSED mode"). apksim.jar comes first on the class path with its own `FailOnTimeout` (JUnit 4.13's API): the test
  stays on the main thread and a watchdog interrupts it when the time is up (`TimeoutRuleTest`). A test still running
  10 s later, ignoring interrupts, ends the process with its stack (a device abandons its thread and goes on; here it
  holds the main thread): `instrument.py` reports that test failed and runs the rest in a new process.
  The timeout failure carries a suppressed report (`TimeoutDiagnostics`, issue 604, read on a daemon thread waited for
  at most 1.5 s so a lock cannot stall the watchdog; the halt message has it too): the main looper thread's stack when it
  is not the timed-out one, each window root's has-window-focus and is-layout-requested, and the names of Espresso's
  registered idling resources, not asked if idle (their code must not run off the test thread).
- `UiAutomation.executeShellCommand` (UiDevice's too) runs apksim's adb shell (`AdbShell`) in the process, to the end,
  and returns what it printed: `getprop`, `settings put global animator_duration_scale 0` (as `animscale`: Views and
  Compose both follow it), `touch`/`ls`/`rm` in /sdcard, `logcat -c`/`-d` (the log is kept from the first `logcat` a
  test asks for), `am`, `pm`. On the main thread a command runs at once, not through the Host loop, and in a test run
  the control commands it reaches are animscale, fontscale, network, bars, timezone, bluetooth, thermal, compat, camera and gaps
  (`ShellCommandTest`, `ThermalTest`), and `pm grant|revoke PKG PERMISSION` (Robolectric's granted set, reset at the next
  test's setup; `CameraTest`).
- The framework's wall clock is the real one (`SimRunner.WallClock`): Robolectric pointed System.currentTimeMillis
  in framework code at its uptime clock, so the framework thought it was 1970 while the app's own code and the JDK
  knew the date (DateUtils.isToday(now) was false). The uptime clock stays simulated, and the wall clock does not jump
  when it skips idle time. `--timezone ZONE` sets the zone at each test's setup (`TimeZones`, as in running
  instances), and a test's `executeShellCommand("apksim timezone ZONE")` changes it until the next setup
  (`WallClockTest` with `-e timezone ZONE`).
- `--realtime-clock` (opt-in, test runs only; `RealTimeClock`): the simulated clock follows real time instead of
  skipping idle time, for suites written for a device whose timed UI must last (a banner that closes itself after
  5 s). By default Robolectric's Choreographer is not paused in a test run, so each frame asked for moves the clock
  15 ms and runs at once, and one Espresso check ran every frame of a running animation, closing the banner first.
  With the switch the Choreographer is paused as Host runs it, each frame asked for queues an asynchronous vsync
  message (`ShadowRealTimeDisplayEventReceiver`), a main-thread clock read moves the clock up to real time
  (`ShadowRealTimeSystemClock`), a looper held by a traversal's sync barrier waits up to 100 ms for that frame
  (Espresso posts its check behind the barrier, drains, then blocks on it: without the wait it never ran) until
  barriers have kept coming back for 250 ms with gaps under 100 ms (an endless animation), after which a barrier with
  nothing due behind it counts as idle, as on a device; neither a message at the head between frames nor work due
  behind one barrier starts that streak again (issue 131: a 16 ms ticker made an Espresso swipe's nested idle wait
  frame after frame until the test's timeout; `RealTimeSwipeTest`), and
  anything moving the clock past real time (idleFor, SystemClock.sleep) waits for it. Off, nothing is registered.
  The test app's suite takes 13 s with it against 5.6 s (`RealTimeClockTest`, run with `-e realtimeClock true`).
  Short waits (`waitOutBarrier`, the wait for real time after `idleFor`, `pumpFor`'s steps) sleep only up to a margin
  before their deadline and spin the rest (`RealTimeClock.waitUntil`) where the OS overshoots sleeps: macOS VM CI
  runners woke a 16 ms sleep ~115 ms late, so a tap's idle took seconds (issue 201). `--precise-waits on|off|auto`
  (default auto: five 5 ms sleeps probed on first use; margin 4x the median overshoot, 200 ms at most, none under
  3 ms; one stderr line says so). Costs a core while a main thread waits. `-Dapksim.barrierStreakMs=N` sets the
  250 ms streak (issue 215): a DrawerLayout settling through ViewDragHelper's Scroller (no animator) looks like an
  endless animation for that long, so an Espresso click right after `openDrawer` acted mid-slide and missed
  (`DrawerSettleTest` on `apksim://drawer`, `-e drawerRepro true -e realtimeClock true`: fails at 250, passes at 500
  and 1000 with `RealTimeSwipeTest` still passing; the default is unchanged, and spotting a running Scroller is not done).
- `robolectric.windowManager.useRequestedSize`: a window gets the size it asks for, so a Compose Dialog
  (WRAP_CONTENT) is not measured at its width and then at the display's on every traversal (it never got idle).
  Running instances set it too (`SimRunner`'s static block, issue 506): a dialog-themed activity's WRAP_CONTENT window
  had the display's frame, so it was remeasured at the display's width, got a status bar inset of its own and ended up
  shorter than on a device (`apksim://floating-tall`: 640x1686 at 91,48 as on the API 36 emulator, was 822x1638 at
  0,72; `apksim://floating` 640 wide, was 822). `--jvm -Drobolectric.windowManager.useRequestedSize=false` (cold)
  keeps the old frames. Not matched yet: a floating window that hides the bars itself stays inside them (the emulator
  gives it the whole display).
- Processes started together share one per-APK cache folder (`work/cache/<apk name>-<content key>`, `ApkCache`):
  `ElfLoader` extracts each `.so` to a per-process temporary file and renames it into place (and skips it by the
  entry's CRC, kept in `<name>.so.crc`), and `AppInfo` writes the manifest the same way. Copying in place let one of
  four `--isolate` processes load a library another was rewriting (`NoClassDefFoundError` for a bundled SQLite
  driver's native library, on the first test after a rebuild).
- The framework's 8 dp touch slop (`Host.useDeviceGestureThresholds`), JniConstants warmed on the main thread (Coil's
  ImageDecoder on a worker aborted the process), and receivers registered before a test's reset can be unregistered
  (WebRTC's NetworkMonitor, a static, aborted at teardown otherwise).
- Before Application.onCreate, as installing and ActivityThread do (`InstallState`, in running instances too: see
  "Simulator features"): `Settings.Secure.ANDROID_ID` is set
  (Robolectric leaves it null), every requested permission of protection level normal is granted (runtime ones too
  with `--permissions grant`; signature, privileged and internal ones stay denied unless the app defines them, with
  the framework's levels read from its own manifest, which Robolectric's package manager lacks), and the app's content
  providers are created, highest initOrder first (AndroidX Startup's initializers run; not for the runner probe's
  plain Application; a provider that fails fails the test's setup, as it crashes a device's app start, unless
  `-Dapksim.providerFailures=log`). A provider that came up earlier in the process and refuses a later install (LeakCanary's
  installer checks a static; a device's process creates providers once) is only logged, once per class
  (a RuntimeException only; an explicit `-Dapksim.providerFailures=fail` fails it; issue 353; the test app's
  `OnceOnlyProvider`, `OnceOnlyProviderTest`). `--network-profile offline` still holds through it (`NetConnectivity.networksReplaced`). The host's network is connected and VALIDATED (`HostNetworks`), as an app checks.
- Room's "Cannot access database on the main thread" check counts the test body as the instrumentation thread it is
  on a device: the translator sends `RoomDatabase`'s `Looper.getThread()` (Room 2.6+) or `ArchTaskExecutor.isMainThread()`
  (older) to `TestActivities.roomMainThread`/`roomIsMainThread`, which answer "not main" only for the test's own code
  (under JUnit's `FrameworkMethod`) outside any dispatch, `runOnMainSync`, `ActivityScenario.onActivity`,
  `Activity.runOnUiThread` or lifecycle call (`Instrumentation.call*`, a provider's `attachInfo`), so the app's
  startup, `onActivity`/`onFragment`/`runOnUiThread` blocks and the callbacks of an activity the body launches still
  throw, as on a device. A device test calling a blocking DAO method from its body failed here (`RoomConformanceTest`). Work an Espresso action runs inline from the
  body passes where a device would throw. Only an unminified `RoomDatabase` is matched (a test calling an R8 app's Room
  keeps Room's own check); `-Dapksim.roomTestBody=false` turns it off.
- StrictMode's incorrect-context check (`detectIncorrectContextUse`) passes for a context whose wrapper chain holds an Activity or a DecorContext (`TestActivities.ShadowSimStrictMode`, running instances too): a device's activity base is an activity-type ContextImpl, Robolectric's is the application's, so every View logged a violation (issue 341). Only the two StrictMode checks: `Context.isUiContext()` on an activity still answers false, and shadowing `ContextWrapper` for it throws ClassCastException (a superclass shadow must be the base of ShadowActivity's chain). `UiContextTest`.
- `getInstrumentation().getContext()` is the test APK's own context (its package and assets: JSON fixtures), through
  the translator (`TestActivities.instrumentationContext`).
- JUnit's `@RunWith(Parameterized)` runs inside the test environment, each parameter set as a SimRunner child
  (`SimParameterized`), so a constructor can read the instrumentation's arguments. `@Parameters` is called once;
  `@BeforeParam`/`@AfterParam` run around each set. Not yet: `@Parameters` itself runs before the test environment
  exists (a factory reading the instrumentation's arguments or the app's resources fails), class fixtures run once
  per set rather than once, and the test APK's context is published after the Application's onCreate (an Application
  or provider calling getInstrumentation().getContext() at startup gets the app's).
- bindService to a service no installed app has returns false and never calls back, in running instances too (see
  "Bound services"). `LockSupport.parkNanos` on the main thread in a test run
  (`runBlocking`'s event loop) runs the main queue meanwhile, the clock following real time, where `Thread.sleep` does;
  so does `CountDownLatch.await` (`TestActivities.await`: a test waiting for a post or a listener on the main thread
  timed out, `LatchConformanceTest`). All three run the queue's due tasks one at a time (`runDue`), checking the
  latch, an interrupt and the deadline between them, not `ShadowLooper.idle()`: an endless frame loop (a Compose
  Popup, an animation that never ends) never lets that return with Robolectric's unpaused Choreographer, nor a
  Runnable that posts itself again in either clock mode, and a wait or sleep under one never ended.
- Views draw after each traversal (`ShadowSimViewRootImpl`: recorded into a display list that is dropped, so
  View.computeScroll runs and Scroller-driven moves finish), a focusable window on top (a dialog) takes the window
  focus (`ShadowSimWindowManagerImpl`: Espresso looks in the focused window), and Espresso's
  loopMainThreadUntilIdle also waits for the Compose rule's idling resource, as it is registered on a device.
- Compose lazy layouts prefetch: after a scroll the item past the viewport is composed and measured in idle time, as
  on a device (a suite counted a second "loading more" row only one of which was on screen). Compose picks a
  scheduler that does nothing when `Build.FINGERPRINT` is "robolectric" (`PrefetchScheduler_androidKt`); the
  translator sends that one read to `TestActivities.prefetchFingerprint`, so test runs get Compose's own
  `AndroidPrefetchScheduler` (view posts; `drawingTime` is 0 here, so every run counts as an idle frame, and a request
  past the first in a run waits for the next Choreographer frame). FINGERPRINT itself stays (`LazyPrefetchTest`).
  Running instances keep Compose's choice, so they do not prefetch (not yet measured for frame pacing).
- `--start-activities` (opt-in, `-Dapksim.startActivities`): a start of one of the app's activities that no running
  one takes launches it, as ActivityThread does (the one below is paused, then stopped), and NEW_TASK | CLEAR_TASK
  finishes the task's others; by default Robolectric only records the start, which Robolectric-style suites check.
  The stopped activity's window is hidden until it comes back (also for back on a launcher root, which sends the task
  to the background), so UiAutomator, UiAutomation's windows and screenshots, and failure bundles see only the top
  one, and gets the window focus back when it returns (`StackWindowsTest`). UiDevice rotation puts the windows back
  in the stack's order afterwards (a recreated activity's window is added last) and the focus on the top one.
  Robolectric's own stops (ActivityScenario.moveToState) leave windows as they were, and any resume shows a hidden
  window again with the focus (`ShadowSimInstrumentation.callActivityOnResume`), so `moveToState(RESUMED)` after one
  of apksim's stops brings the screen back.
  Not yet: a start for a result (startActivityForResult, ActivityResultContracts) is still only recorded, so a flow
  that waits for the started activity's result needs the test to supply it, as with Robolectric.
- Mockito (`MockMakers`, `--mock-maker MODE`, `-Dapksim.mockMaker`): a test APK's
  `mockito-extensions/org.mockito.plugins.MockMaker` names a maker for ART (mockito-android's, dexmaker's), whose first
  mock threw "Plugins.registry is null" here; `AppClasses` serves that resource through MockMakers. `auto` (default)
  swaps an ART maker for `mock-maker-subclass` (translated classes are JVM classes), `keep` leaves it, `subclass` or
  `inline` force Mockito's, anything else is used as given. `inline` needs Byte Buddy's agent (`--jvm
  -Djdk.attach.allowAttachSelf=true --jvm -XX:+EnableDynamicAgentLoading`) and mocks JDK types only: translated
  classes have no stack map frames, which a retransformed class needs (VerifyError). Final classes cannot be mocked
  (`MockitoTest`).
- In app code everywhere (the translator): `KeyStore.getDefaultType()` is "BKS", Android's, with Bouncy Castle added
  after the JDK's providers (`KeyStores`), and AES/GCM set up again with the same key and IV is allowed, as Android's
  provider allows it (`Ciphers`: unsafe, but what the app gets on a device); `android.net.ssl.SSLSockets` supports
  the JDK's TLS sockets, so OkHttp's Android platform sets ALPN and offers HTTP/2 (`ShadowSimSSLSockets`).
**The UiAutomator engine** (`--uia engine`, `-Dapksim.uia=engine`; a prototype, off by default: `--uia classic` is
everything above; docs/uiautomator-engine.md has the design, the measurements and what is not done). `Uia` decides
what UiAutomator otherwise waits for: an idle wait runs the main queue until nothing is due soon, or frames keep
coming while a hash of the accessibility tree (`UiaTree`) has stood still for 250 ms of the app's time, or a tree
still moving has had 1000 ms; with `--realtime-clock` its own waits fast-forward (`RealTimeClock.fastForward`: the
clock moves to the next frame or message without the wall-clock wait, real time's base moved back by what was
skipped), which covers idle waits, the frame wait after a swipe's step and the sleeps UiAutomator paces gestures with
(the translator sends `SystemClock.sleep` in `androidx/test/uiautomator/` classes to `Uia.sleep` with its kind,
gesture or poll; without the engine that is the sleep itself); from a test's first use of UiAutomation
AccessibilityManager is enabled with no service listed, as on a device, and the events views send reach
UiAutomation's listener and executeAndWaitForEvent's filter, the synthesized ones remaining as the fallback; and with
the default clock a poll's sleep gives the app's other threads real time while they are at work (`UiaWorkers`), where
a wait's whole timeout used to pass in a few real milliseconds. The test app's UiAutomator tests with
`--realtime-clock`: 42.1 s classic (19 tests), 8.8 s with the engine (23); the suite's 342 tests pass with it on either
clock. `UiaEngineTest` (CI runs it and the other
UiAutomator tests with `--uia engine -e uiaEngine true`, on both clocks) checks it, and passes on the API 36 emulator.
`-Dapksim.uia.trace=true` prints each idle wait, sleep and event.

Not supported yet: `adb shell am instrument` of a test APK (only of a self-targeting instrumentation, see "adb device"), shell commands that need another process or the Host loop (input, screencap, `am instrument`), and
test-services shell calls. A test that reaches a
server compiled into the app reaches it from here when the Mac is on that network, where an emulator does not;
`--no-network` keeps only localhost (127.0.0.1 and ::1).
The device-difference probe catches simple framework getters that Robolectric answers with a default where a device gives a real value: `DeviceProbeTest#probe` (test app androidTest) reads 295 normalised getters (`probe.KEY=VALUE`), `scripts/gen-device-probe.py -s SERIAL` makes `testapks/composeapp/device-probe-api36.tsv` from an API 36 emulator (install the pair, run, uninstall), and `scripts/device-probe-check.py APP TEST [--apksim CMD]` compares apksim's run with it: exit 1 for a difference not in `device-probe-known.tsv` (KEY, apksim's value, reason `(a)` can crash or mislead / `(b)` cosmetic or by design) and for a known one that no longer differs, so fixing one means deleting its line. CI's `tests` leg runs it in the image (16 s). docs/conformance.md "Device-difference probe" lists today's 117 differences, 73 of them (a); add a probe there when a getter bites an app.

## Code notes
- `Instrument`, `TestActivities`, `ShadowSimUiAutomation`: `--instrument` test runs, the system server's part of
  them, and UiAutomator (see "Instrumentation tests"). `Bundles`: failure bundles in test runs (`--bundles DIR`).
  `AccessibleWindows`: which windows accessibility reports (UiAutomation, `uiautomator dump`, `nodes`, bundles'
  nodes.txt), as the window manager computes them: top down, a window is left out once touch-modal windows above it
  (any Dialog, a dialog-themed activity, a focusable popup: their touchable region is the whole task) or their frames
  leave none of it uncovered, unless it has the focus. AOSP's `AccessibilityController.computeWindowsForAccessibility`
  (Android SDK sources, `android-33/com/android/server/wm`), down to its bounding-box test (`Region.quickReject`:
  a window inside a hole of what is left still counts, as there). Touch input does not use it (`Host.isTouchThrough`).
  `AccessibleWindows.stack()` is the one window order: WindowManagerGlobal's, in which windows were added, except that
  a sub-window (PopupWindow, attached dialog; `Host.stackSubWindows`, issue 613) sits just above its parent window
  (below it for the media types, by sublayer) and under windows added on top of the parent later, as Host
  draws them and sends touches down; every window list (UiAutomation, dump, `nodes`, bundles) uses it. Sorting by
  window type put a popup opened before a dialog above the dialog. Test app: `apksim://dialogs?open=popupfull|fullpopup`.
