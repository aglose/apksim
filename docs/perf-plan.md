# Performance plan: memory, launch time, run time (2026-11-09)

What to change next to make an instance smaller, faster to start and cheaper to run, ordered by measured payoff per
effort. Everything under "Baseline" was measured for this plan; expected gains are estimates until each step's A/B
says otherwise (AGENTS.md: interleave runs, the machine is noisy). Nothing here is built yet.

## Baseline

Test app (debug build), M4 Pro (12 cores), `--headless`, JDK 24.0.1, main at `e382d13`, class cache warm.

**Cold launch** (`APKSIM_STANDBY=0`, `-Dapksim.timing=verbose`): first frame at 1.69-1.85 s over the session, runs
made back to back within ~30 ms of each other. One of them:

| Stage | Ends at | Takes | What is in it |
|---|---|---|---|
| JVM start (AOT cache) | 96 ms | 96 | |
| APK cache entry, manifest, DEX index | 213 | 117 | manifest 53 |
| Runner and injector | 283 | 70 | |
| Sandbox and test environment made | 565 | 280 | `BouncyCastleProvider.<init>` ~180 |
| Native runtime loaded | 870 | 305 | dlopen and JNI registration ~160, system font map ~115 |
| Application state | 1,235 | 365 | package, resources (a 17 MB table inflated), Application |
| Install state (providers, permissions) | 1,310 | 75 | |
| Host, renderer, controls | 1,412 | 100 | |
| Activity resumed | 1,625 | 215 | app code, interpreted |
| First frame | 1,830 | 205 | first Compose traversal, interpreted |

`-Dapksim.profile=true` (wall-clock samples of the main threads, 270 over the launch, so shares are rough): **24% under
`MethodHandleNatives.linkCallSite`**, i.e. linking Robolectric's `invokedynamic` call sites (of those 66 samples: 24
in `cleanStackTraces`, 34 spinning LambdaForm classes); 10% in `BouncyCastleProvider.<init>`; 7% in
`Typeface`'s system font map. GC: 8 young collections, 104 ms of pauses in all, the longest 20 ms.

**Warm launch** (from a standby, with the sampler on): hand-off 4 ms, framework ready 81, activity resumed 147, first
frame 323. 17% of the samples are still `linkCallSite` (call sites the warm-up never reached), 12% class loading
(`AppClasses.translate`, `loadClass`); the rest is the app's own start, interpreted.

**Test process** (`--instrument`, one class of 4 trivial tests): 1.55 s before the first test starts, 1.64 s wall and
2.25 CPU-seconds in all (3.9-4.9 CPU-seconds with C2, so stopping at C1 is right). A trivial test costs 5-6 ms and
its setup 12-16 ms, so the per-test floor is not a lever; the per-process start is. The suite's 157 tests: 24 s.

**Memory, one idle instance: 353-381 MB footprint** (`footprint PID`; 450-476 MB resident).

| Part | MB | Notes |
|---|---|---|
| Java heap, old generation | 84-91 | all live: a full GC frees nothing |
| Java heap, young generation | 64 | `-Xmn64m`, written once and dirty for good |
| AOT cache pages the process writes | 69 | class metadata it links and resolves |
| Metaspace and class space | 37 | 3,634 hidden classes (LambdaForms) hold 11.4 MB of it |
| JIT code cache | 28 | |
| malloc | 75-95 | 51 live: JVM 31, Bitmaps 7.8, a surface buffer 5.9, zlib streams 4 |

What the 94 MB of live heap is (class histogram and a heap dump):

| Holder | MB |
|---|---|
| The framework's `resources.arsc`, inflated into one `byte[]` (`Asset$_CompressedAsset.mBuf`) | 17.5 |
| Zip central directories of 37 open jars (`ZipFile$Source.cen`), mostly the framework jar's 76,761 entries | 9.9 |
| Method handles, LambdaForms, MemberNames, MethodTypes, 5,679 `MethodCallSite`s | ~10 |
| Strings (204,574) | 8.1 |
| `java.lang.reflect.Method` (50,780, kept by shadow lookups) | 4.5 |

**A standby: 364 MB**, for up to an hour after the last launch. One app open is therefore ~740 MB.

**Idle CPU: 2.4-4.8% of a core.** Host's loop turns every 2 ms (`IDLE_POLL_NANOS`) whether or not anything is due.

**Frames** (`fps`, per frame): a fling 1.9 ms looper, 0.4 record, 1.95 raster; the Shader tab 0.9 looper, 0.8 record,
1.8 raster at 120 fps. Nothing new was found here beyond AGENTS.md's "Open work".

## Done (2026-11-09)

- **Step 0, in part:** `stats` reports `footprintMb` (`Footprint`: proc_pid_rusage's physical footprint, VmRSS on
  Linux; matches `footprint PID`) and `cpuMs`; `bench.py --mem` prints the instance's footprint 5 s after its first
  frame and, warm, the standby's. Measured: cold 369 MB, warm instance 404 MB, standby 372 MB.
- **A1:** `Main` initializes `AndroidTestEnvironment` (its BouncyCastleProvider) on a thread of its own at the start of
  a cold launch (`-Dapksim.initAhead=false` turns it off). Six interleaved pairs: first frame 2,080 → 1,985 ms mean,
  the test environment set up ~100 ms sooner; less than the 180 ms the provider costs, as the two threads compete
  for class loading. A one-class test run: 1.63 → 1.50 s wall, the same CPU.
- **C3:** the screenshot bitmap is made for each shot and recycled after it. The suite (157 tests) passes.
- **D1:** when nothing is wanted (no frame, input in the last 100 ms, queued script, drag, `settle` or command), Host
  sleeps in Robolectric's `ShadowPausedLooper.poll` until the next main-queue message is due, at most 40 ms (under the
  50 ms stall limit, so the clock keeps the time slept). A post to the main queue from any thread ends it, as do
  control commands (`Control.submit`) and window input (`apksim-input-wait`, woken by `simwindow.m`'s queue);
  `-Dapksim.idleSleep=false` keeps the 2 ms poll. Measured side by side with nothing attached, 30 s windows: headless
  0.21-0.41 s of CPU against 0.45-0.60 s, windowed 0.81 against 1.24 s. A command reaches an idle loop in 1.3 ms;
  window resizes reach a frame no later; `timer-check.py` drift -131 ms in 60 s (the old loop: -223 ms);
  `overlay-input-check.py`, `settle`, `serve-check.py` (tap to frame 30 ms, Shader tab 120 fps) and the suite pass.
  Not near zero, as hoped: what is left is the JVM's own threads and Robolectric waking every background looper (the
  ConnectivityThread) at each clock step, ~25 a second now. The earlier 2.4-4.8% baseline included the JIT's
  warm-up; settled, the 2 ms poll was ~1.5-2%.

- **B1:** `org.robolectric.internal.bytecode.InvokeDynamicSupport` and `ShadowInvalidator` in apksim.jar, ahead of
  Robolectric's: each framework call site is bound straight to its shadow or original method, with no SwitchPoint guard,
  fallback or per-site `catchException`; an invalidation (a test with other shadows) resets that class's sites to a
  fallback that binds again at their next call, which is when Robolectric's guard would have. Stack traces are cleaned
  by Robolectric's rules where apksim reports them (`StackTraces`: crashes, test failures before any listener hears
  them, bundles), and a crash's logged trace is identical to before. `-Dapksim.directCallSites=false` is Robolectric's
  binding, `-Dapksim.cleanStackTraces=true` keeps its per-site cleaning. Measured (`bench.py 4 --mem`, two interleaved
  rounds; cold six interleaved runs with timing marks):

  | | Robolectric's | Direct |
  |---|---|---|
  | Cold first frame | 1,906-1,950 ms | 1,610-1,673 ms |
  | Warm first frame | 326-339 ms | 264-265 ms |
  | Footprint, cold instance 5 s after first frame | 387-388 MB | 345-354 MB |
  | Footprint, warm instance | 428-436 MB | 384 MB |
  | Standby | 372-391 MB | 340-356 MB |
  | Test app suite (157 tests) | 24.0 s, 16.8 s user CPU | 21.2 s, 11.0 s user CPU |
  | Main thread per fling frame | 1.85 / 1.21 ms | 1.39 / 0.79 ms |

  Per-site cleaning alone was ~145 ms of the cold launch (direct with cleaning: 1,696 ms). What changed for the app: an
  exception it catches and prints itself (`e.printStackTrace()`, `Log.e(tag, msg, e)`) now shows framework methods as a
  trampoline frame plus a `$$robo$$` one, as live thread dumps always did. Suite, `bundle-check.py`, crash traces from
  a configuration change and a posted Runnable (identical under both bindings) checked; the Linux image only in CI.

## Found not to work as planned

- **C1 (map `resources.arsc`):** Robolectric's binary resources code holds the table as a `byte[]` from end to end
  (`FileMap.getDataPtr`, `Asset.getBuffer`, `ResTable`, four `ShadowArsc*` shadows), so it cannot be mapped without
  replacing all of them. Its NATIVE resources mode (`-Drobolectric.resourcesMode=NATIVE`) would read a stored table
  through the real AssetManager2, but this runtime's native library does not register `ApkAssets`' natives: the test
  app ends at once with `UnsatisfiedLinkError: ApkAssets.nativeLoad`. Doing C1 means a native runtime built with the
  resources natives, then a stored copy of the framework's resources, and the fidelity check that switching every
  app's resource implementation needs. Moved to the bets (E4).

## Step 0: make the numbers one command

Each step below needs the same four figures before and after. Today they took `footprint`, `jcmd` and a one-off heap
dump parser.
- `stats` gains the process's physical footprint (`proc_pid_rusage`, what `footprint` prints) and the count of linked
  call sites; `scripts/bench.py` prints footprint at first frame + 5 s beside its times, for an instance and its standby.
- `-Dapksim.profile=true` reports in test runs too (`Sampler.report` only runs under `--exit-after-first-frame` now).
- The startup gate (docs/startup-gate.md) gets a cold budget beside its warm one, and a footprint budget.

## A. Cold boot: 1.83 s towards ~1.1 s

Cold is every test process, every launch in the Linux image, and the first launch after a build. All of this is
main-thread work with eleven cores idle.

**A1. BouncyCastle off the critical path (~180 ms, 10%).** `AndroidTestEnvironment`'s static initializer runs `new
BouncyCastleProvider()`, which loads and configures every algorithm, interpreted. A thread started at the top of
`Main` builds the provider while the APK is indexed and the runner is made (it has ~280 ms before the class
initializes); the initializer then takes that instance. Either a small ASM patch of that initializer in
`prepare-runtime.sh`, or our own copy of the class ahead on the class path as `FailOnTimeout` is. Check: the
"sandbox" stage, `KeyStores`' BKS path, `NetworkReplayTest`.

**A2. The system font map from a file (~100 ms).** `Typeface.loadPreinstalledSystemFontMap()` opens all 236 font files
(450 FreeType faces, coverage bitsets) on every cold start. Android's zygote does it once and hands apps a serialized
map; the framework jar and the native runtime have both halves (`Typeface.serializeFontMap`,
`deserializeFontMap(ByteBuffer, Map)`, `nativeReadTypefaces`). Write the map once into `work/runtime` (keyed by the
fonts folder), map it and deserialize in `FastNativeLoader`. Check: a text-heavy snapshot spec is pixel-identical
(`scripts/snapshot.py`), emoji and a CJK locale included. Fallback if the native half is missing in this runtime: a
fonts.xml with the families of the common scripts first and the rest loaded on demand.

**A3. Native runtime load beside the sandbox (~150 ms).** `System.load` of libandroid_runtime and its JNI
registration (~160 ms) wait until `setUpApplicationState`, after 280 ms of sandbox work that does not need them, and
both could overlap APK indexing. Start them on a thread at `Main`. Risk: the natives cache per-thread state at first
use (AGENTS.md: JniConstants on a worker aborted); trace what `JNI_OnLoad` binds to its thread before moving it, and
keep the deferred static initializers on the main thread.

**A4. The framework's resources mapped, not inflated (~40 ms, 17.5 MB of heap: see C1).**

**A5. Explain the first test's install state.** In a test run the first real test's install-state step takes
280-310 ms against 75 ms in a normal launch of the same app. Not traced yet; sample it (step 0) before guessing.

## B. Robolectric's dispatch, flattened (the largest single item)

Every framework method is a trampoline: `invokedynamic`, bound at first call by `InvokeDynamicSupport` to a chain of
method handles (a SwitchPoint guard so shadows can change between test configurations, a `guardWithCatch` that only
strips `$$robo$$` frames from stack traces, then the shadow or the original). apksim has one fixed set of shadows per
process and never re-binds. Cost today: 24% of a cold launch and 17% of a warm one in linking, 3,634 generated
classes, ~10 MB of heap, and until C2 inlines a path, three extra interpreter frames on every framework call. Test
runs stop at C1, which inlines such chains poorly, so they pay for it throughout.

**B1. Our own `InvokeDynamicSupport` ahead on the class path (days).** Same bootstrap signatures; binds each site once
to a `ConstantCallSite`: straight to the original when `findShadowMethodHandle` finds no shadow, else to the shadow
with the class test Robolectric needs, no SwitchPoint, no catch wrapper. Stack traces are cleaned where they are
printed instead (`Crashes`, `Bundles`, Instrument's failure output). Estimate: 150-250 ms off a cold launch, 20-40 ms
off a warm one, 15-25 MB. Check: the whole test app suite, `ci-smoke.sh`, the startup gate, and the large app's
suites (docs/ci-testbed.md) for CPU-seconds, which is where C1 should show it.

**B2. Rewrite the framework jar at setup (1-2 weeks, after B1 proves the decision function).** For each trampoline
whose method no shadow implements, replace the `invokedynamic` with a direct call of the `$$robo$$` body, decided by
running Robolectric's own lookup offline against apksim's shadow list, so it cannot disagree with it. Classes the
optional shadows touch (`ShadowRealSleep`, `--realtime-clock`, `--snapshot`) keep their trampolines. That removes the
bootstrap call itself for most sites, in every process, and makes those methods plain code for the AOT cache, the
interpreter and C1. The jar's instrumentation version goes into the runtime hash.

## C. Memory: ~375 MB towards ~300 MB an instance

**C1. Map the framework's `resources.arsc` (17.5 MB).** Extract it once in `prepare-runtime.sh` and give Robolectric's
asset code a mapped buffer in place of the inflated array (the heap dump shows the tables read through a `ByteBuffer`
wrapped around it; whether every reader takes a mapped one is the first thing to check). The app's
own table (stored, 0.5 MB here, several MB in a large app) the same way, from the APK mapping `AppClasses` already has.
Alternative worth one try: Robolectric's native resources mode, which `FlatSandboxBuilder.build` is already handed.

**C2. B's share: 15-25 MB** (generated classes, method handles, `Method` objects).

**C3. The screenshot bitmap on demand (5.9 MB phone, 16.4 MB tablet).** `Host.run` allocates a display-sized Bitmap
that only `shot`, `--shot` and `screencap` read. Allocate it there. `resizeDisplay` allocates another on every resize.

**C4. Standbys give way.** A standby is a cache of 364 MB. It should quit on the system's memory-pressure warning (a
dispatch source in `libstandby`), and its idle time should follow use (an hour after an interactive launch, minutes
after `--exit-after-first-frame` or a fleet). An instance under pressure drops `GpuBitmaps`' two 96 MB caches and
collects. First, though: while measuring, two standbys of another checkout were found waiting on one socket, both 36
hours old and each using ~1% of a core. The one-hour limit and one-per-socket rule did not hold for them; not
investigated (their build may be older than main).

**C5. The framework jar's index (~2 of the 9.9 MB).** A quarter of its 76,761 entries are resources; with C1 they can
live in a file of their own, off the class path. Which class entries the AOT cache makes unnecessary cannot be known,
so those stay. Low priority.

**C6. Young generation for fleets.** `-Xmn32m` frees 32 MB an instance and cost ~30 ms of a cold launch here (17
collections against 8, 123 against 104 ms of pauses). Not as the default before a fling A/B (`-Xlog:gc`), but
`fleet.py` and `serve` instances could take it now through `--jvm`.

## D. Run time

**D1. An idle loop that sleeps (idle CPU to ~0).** Between frames Host should park until the next thing that can
happen: the earliest main-queue message, or a wake from input (`simwindow.m`'s queue), `Control.QUEUE`, a post from
another thread (it needs a hook where the main queue is woken; find it in Robolectric's paused looper first, since
without one a worker's post would wait out the park), or a vsync request. The per-turn ticks (`Autofill`, `SlotDevice`, `TimeTick`, `SystemBars`) become deadlines in that wait. Keep
the 2 ms poll behind a switch for A/B. Check: `timer-check.py` (clock drift), `overlay-input-check.py`, input latency
in `serve-check.py`, and 32 idle instances in `fleet.py`.

**D2. Collect when idle, not mid-scroll.** HotSpot does not count native memory, so Bitmaps and display lists pile up
until a ~150 ms full collection lands wherever it lands (docs/plan.md, step 2). When no frame or input has happened
for 2 s and the old generation or malloc has grown past a threshold since the last one, run it then.

**D3. Class bytes without copies (small).** A cached class goes pack → `byte[]` → stream → the JDK's `byte[]` →
`defineClass`. An input stream that is `ByteBuffered` lets the loader define straight from the mapped pack.

**D4. Frames.** Unchanged from AGENTS.md: effect layers on a second thread or one renderer when an app's busy screens
need it. B shortens first frames (interpreted traversals), not steady ones.

## E. Bets, each behind a spike

**E1. App classes the JVM can archive.** Translated classes carry no stack map frames, so the AOT cache holds the
framework only and JDK 25's record mode rejects them. The translator already knows every register's type at each
block start; emitting frames from that (reference merges are the hard part) would let a per-APK cache hold the app's
classes, and on JDK 25 and later their profiles, so the app's start-up code stops being interpreted: the part of a
warm launch that is left. It also needs the classes in a jar on the class path (the `apksim:` source is not
archivable), hence a cold start or a standby per APK. Spike: frames for one DEX file, verified with
`-XX:+BytecodeVerificationRemote`, then one APK's cache on JDK 25 and the test app's launch and suite times.

**E2. A standby prepared for the last APK** (AGENTS.md, "Not tried"): its classes defined, and with E1 its cache.
Worth it only if E1's spike shows the gain, since it doubles standby memory unless C4 is in.

**E4. Robolectric's NATIVE resources mode** (C1's way through): build the native runtime with `ApkAssets` and
`AssetManager` natives, keep the framework's `resources.arsc` stored so AssetManager2 maps it, and compare every
screen the snapshot specs cover in both modes. 17.5 MB an instance and the table's parse at each cold start.

**E3. A standby for test runs and for Linux.** A C1 standby that `instrument.py` hands shards and reruns to would take
most of the 1.55 s off each test process; Linux needs the hand-off without `sandbox-exec`. After A and B, when the
remaining cold cost is known.

## Measured for this plan and dropped

- `-XX:ArchiveRelocationMode=0`: the 69 MB of written AOT cache pages and the launch time did not move.
- A smaller initial heap with tight free ratios (`-Xms96m`, `MaxHeapFreeRatio=25`): same launch time, same
  footprint. The old generation is all live, so there is nothing to give back; shrink the live set instead (C1, C2).
- A full GC after start-up: frees nothing (91 MB before and after).
- Per-test overhead in test runs: 5-6 ms for a trivial test. The cost is per process.

## Order

| Step | Size | Expected | Needs | Status |
|---|---|---|---|---|
| 0 Numbers in `stats`, `bench.py`, the gate | small | - | - | part done: `stats`, `bench.py --mem`; the gate and call-site count left |
| A1 BouncyCastle | small | -180 ms cold | - | done: -95 ms cold, -110 ms a test process |
| C3 Screenshot bitmap on demand | small | -6 to -16 MB | - | done |
| C1/A4 Resources mapped | small-medium | -17.5 MB, -40 ms cold | - | blocked: needs the native resources mode (E4) |
| D1 Idle loop | medium | idle CPU ~0 | - | done: idle CPU -40 to -55%, not ~0 |
| B1 Constant call sites | medium | -150 to -250 ms cold, -15 to -25 MB | - | done: -290 ms cold, -65 ms warm, -35 to -50 MB, -34% suite CPU |
| A2 Font map | medium | -100 ms cold | B1 first (both touch start-up; measure apart) | |
| C4 Standbys give way, D2 idle GC | small each | 364 MB back under pressure; no mid-scroll stall | - | |
| A3 Native load on a thread | medium, risky | -150 ms cold | A1, A2 | |
| B2 Jar rewrite | large | the rest of linking | B1 | |
| E1 spike, then E2/E3 | large | app start-up no longer interpreted | B2 | |

A1, C3 and D1 are done; C1 is blocked (E4). B1, A2 and A3 all change the cold path and should
land one at a time so the gate attributes each. Together, before the bets: cold ~1.83 → ~1.1-1.2 s, a test process
~1.55 → ~0.9 s before its first test, warm ~0.32 → ~0.27 s, an idle instance ~375 → ~300 MB, idle CPU near zero.
