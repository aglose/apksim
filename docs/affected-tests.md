# Affected tests and time-balanced shards

A pull request that changes one screen should not wait for every device test of the app. `instrument.py` can record
which of the app's and the tests' classes each test runs code of, then, for a change, run only the tests that reached a
class compiled from a changed file. It can also split a suite over shards by the time each test took in an earlier
run, rather than by its name's hash. This page is the spec: what is recorded, how a change selects tests, when it falls
back to the whole suite, and what was measured.

## How

```bash
# 1. On main (nightly, or after each merge): run the suite once with recording on. Keep both files.
scripts/instrument.py app.apk app-androidTest.apk --record-deps deps.json.gz --xml timings.xml

# 2. On a pull request: only the tests the branch's changes reach, in shards balanced by measured time.
scripts/instrument.py app.apk app-androidTest.apk --affected-by deps.json.gz --diff origin/main...HEAD \
  --ignore '*.md' --ignore '*/src/test/*' --shards 4 --timings timings.xml --xml out.xml
```

- `--record-deps FILE` runs the whole suite as usual (every option works with it: `--shards`, `--isolate`, crash
  recovery) and also writes FILE, a gzip'd JSON file: per test, the classes it reached; per source file, the classes
  compiled from it.
- `--affected-by FILE` with `--changed PATH...` (or `--changed @LIST`, a file with one path a line) or `--diff REV`
  (`git diff --name-only REV` in `--repo DIR`, default the current folder: `origin/main...HEAD` is what the branch
  changed since it left main; a single revision compares it with the working tree) runs the selected tests and
  prints why: each changed file and how many classes it maps to, then "N of M tests affected". A change that selects
  nothing runs nothing and exits 0. A change the class map cannot see runs the whole suite and says which file.
- `--ignore GLOB` (repeatable, `fnmatch` on the path as given or as git prints it) leaves out changed paths that
  cannot affect the device tests: documentation, JVM unit tests (`src/test`), other platforms' code. Nothing is
  ignored by default.
- `--shards N --timings XML` splits the tests (all of them, or the selected ones) over N shards by measured time, and
  hands each shard an `-e class` list. Without `--timings`, `--shards N` alone keeps AndroidJUnitRunner's hash
  sharding; with a selection and no timings, the selected tests are split by count. `--shard I` runs only shard I
  (0-based), for a CI matrix of N jobs on separate machines: each job must be given the same suite, timings and change,
  so every job computes the same split.
- `scripts/affected.py show FILE [--changed PATH... | --diff REV]` prints what a deps file holds and which recorded
  tests a change selects, without running anything.

## Recording

### What "a test reaches a class" means

A test reaches a class when code of that class runs while the test runs: a method, a constructor or the static
initializer. That is the unit everything else works in. A changed file's classes are known from the APK (below), and
any test that ran none of their code cannot be affected by the change, with the exceptions listed under "Limits".

### The options measured

The obvious recording, "the classes loaded while test N ran", is wrong in one process: a class loads once per process,
so test N sees only what no earlier test loaded. Three ways around it were weighed:

1. **A probe the translator adds to each method (chosen).** apksim translates every class of the APKs from DEX
   itself, so it can add code to them at no cost to anyone who does not ask. With `-Dapksim.deps=true`
   (`instrument.py --record-deps` sets it) every class of the probed packages gets a private static int, and each of
   its methods starts with

   ```
   getstatic   ThisClass.$apksimDeps
   getstatic   dev/apksim/dex/Probes.epoch
   if_icmpeq   skip
   ldc         ThisClass
   invokestatic Probes.hit(Class) -> int    // records the class, returns the epoch
   putstatic   ThisClass.$apksimDeps
   skip:
   ```

   `Probes.mark()` moves the epoch on at every test boundary, so each class reports again the first time its code
   runs in the next test, however long ago it was loaded: two static reads and a compare per method call otherwise.
   Interfaces cannot have a static field that is not final, so their default and static methods call
   `Probes.hitInterface`, which keeps the epoch in a ClassValue. The epoch is volatile, so a worker thread's probes
   see a new test begin (a plain field could stay in a register or be hoisted out of a loop by the JIT); on arm64 that
   read is a load-acquire. The probe goes in after `Peephole`, into the
   buffered method, so nothing else in the translator changes; probed classes have their own class cache
   (`work/cache/classes/<hash>-deps<packages>`). With recording off the translator's output is byte-identical
   (`scripts/translate-all.java`, below).
2. **Classes loaded, one process per test (`--isolate`).** Correct for loading, but a process per test costs a JVM
   start, a cold JIT and an app start each time: see the measurements. It also over-counts: loading a class (for
   verification, a signature, a constant) is not running it.
3. **Class loading per test in one process, plus a reset.** No JVM can unload and reload the app's classes between
   tests without a class loader per test, which Robolectric's one sandbox and the app's statics rule out.

The JVM's own coverage tools (JaCoCo's agent, JFR method sampling) were not used: JaCoCo instruments every class again
at load (here the classes are already rewritten once, by apksim) and records per process, not per test; sampling
misses short methods.

### Which classes get probes

Classes whose name starts with one of `-Dapksim.deps.packages` (comma-separated; through instrument.py, `--record-deps
FILE -- --jvm -Dapksim.deps.packages=com.example,org.example.shared`), by default the app package's first two parts (`com.example`
for `com.example.app`), which takes in an app's modules and its test APK. Library code (androidx, kotlin, okhttp) gets
none: it is most of the code a test runs, and a change to it is a dependency change, which runs the whole suite
anyway. A changed source file whose classes are outside the probed packages is unknown to the deps file, so it too
runs the whole suite.

### Test boundaries

`Instrument` adds a JUnit listener (`DepsListener`) when recording. A test's classes are those reached between its
`testStarted` and `testFinished`: Robolectric's per-test setup (the Application and its `onCreate`), the test body and
its rules, and the reset after it. Code that runs between tests (`@BeforeClass`, `@AfterClass`) counts for every test
of the class before and after that gap; what runs before the first test (building every class's runner, such as a
Parameterized class's `@Parameters`, and the instrumentation's `newApplication`) counts for every test of the run. Work a test leaves running
on another thread and that runs after the test ends counts for the next test; nothing is lost from the union, but it
may be put on the wrong test. A test whose process dies while it runs has no record; selection then always runs it.

The listener writes its lines to stdout, between am instrument's status blocks (`INSTRUMENTATION_APKSIM_DEPS: ...`),
and `instrument.py` takes them out of the output before parsing it. That works the same with `--apksim "docker run
..."` and inside a sandboxed launch, where the JVM cannot write to the caller's folders.

### The source map

When the run ends, `instrument.py` reads every `classes*.dex` of the APK and the test APK (`scripts/affected.py`, a
small DEX reader) and keys each probed class by the source files it holds code of:

- its DEX `source_file_idx` with its package: `com/example/ui/Home.kt` for `com.example.ui.HomeKt$Screen$1`;
- every file Kotlin's SMAP (`dalvik.annotation.SourceDebugExtension`) names: the files whose inline functions were
  inlined into it, each keyed by the package of the class that declares it. A change to an inline function then
  selects the tests that ran code it was inlined into;
- for D8's synthetic classes (lambdas, outlines; `D8$$SyntheticClass`), their outer class's.

The deps file stores the map for every probed class of the APKs, reached or not, so a changed file of a class no test
reached is told apart from a file the recording knows nothing about.

## Selection

1. The changed files come from `--changed` or `git diff --name-only --no-renames REV` (a rename is its old path
   deleted and its new path added).
2. A path that matches an `--ignore` glob is left out.
3. A `.kt`/`.java` path is keyed as the recording keyed classes: the `package` its first statement declares (read
   from the file) plus its name. A file that is gone (deleted, or a rename's old path) or whose package is not in the
   map is matched by its path instead: a key that is a suffix of it (`app/src/main/java/com/example/Foo.kt` ends with
   `com/example/Foo.kt`). Keys come from the deps file and from the APKs being tested now, so a new class in a new
   file is known too. The key's classes are the change.
4. A `.kt`/`.java` path with no key is code these APKs do not hold, so it selects nothing, when two things hold: its
   package (declared, or for a file that is gone, the folders after `kotlin/` or `java/` in its path) is one the
   recording probed, and the recorded build names the source file of at least 90% of the probed classes (the deps
   file's `named`: 99.9% on a large app's debug builds). That is another module's file (a suite per module), an iOS or
   desktop source set of a Kotlin Multiplatform module, or a file of declarations only. Otherwise (a package that was
   not probed, a minified build) the whole suite runs.
5. Any other path (resources, manifests, Gradle files, version catalogs, ProGuard rules, assets, native code) is not
   something the class map can see, so it runs the whole suite, with one exception: it belongs to the Gradle module
   around it (the nearest folder with a `build.gradle(.kts)` that is not the root project), and when none of that
   module's `.kt`/`.java` files is in these APKs, while some are in the probed packages (rule 4's test), the module is
   not part of this suite's APKs and the file selects nothing. A file of the root project (the version catalog,
   `settings.gradle.kts`, the root build file), or of a module with no Kotlin or Java code (resources only), always
   runs the whole suite.
6. The suite is listed (`-e log true`, one short process) and a test runs if it reached one of the changed classes,
   or if the deps file has no record of it (a test added since the recording, renamed, or whose recording process
   died). So a new test file needs no special case: its tests are new.

The tests left run as one process (or as the shards) with `-e class` naming them, whole classes where every test of
a class is selected, in as many processes one after another as the argument's 100 KB budget needs (`narrow()`).

### Debug and minified builds

Selection works on builds whose DEX keeps source file names and class names: debug builds, and the androidTest APKs
of a module, which are not minified. That is what instrumented tests run against almost always. On an R8-minified
build, classes are renamed and their `source_file_idx` is gone or replaced (`SourceFile`, `r8-map-id-...`, unless the
app keeps `-keepattributes SourceFile`), and R8 inlines and merges code across classes, so a test that ran a merged
class did not, by name, run the one its code came from. There a changed file maps to no key, the deps file's `named`
share is far below 90%, and the whole suite runs: safe, never wrong, but no saving. A mapping file could restore names, but not R8's inlining across classes, so it is not
read: record and select on a debug build.

## Balanced shards

AndroidJUnitRunner's hash sharding gives shards equal numbers of tests, not equal times: one slow class can make one
shard twice as long as the rest. With `--timings` (JUnit XML from an earlier `instrument.py --xml`, as many files as
needed; a test's last time wins) the tests are sorted longest first and each goes to the shard with the least time so
far (greedy LPT, within 4/3 of the best split and in practice much closer). Tests with no time count as the median of
those with one, and none counts as less than 1 ms (the XML rounds to ms: tests timed at 0.000 s would otherwise all go
to the first shard). A shard can be empty (more shards than tests, or a selection smaller than a CI matrix); an empty
shard's run passes, while a run without shards that finds no tests still fails. Each shard then runs its tests in the suite's order, named with `-e class` through `narrow()`, so its
crash recovery works as for one process. The predicted time of each shard is printed before the run.

With `--isolate` the same split picks a machine's tests (`--shards N --shard I`; `--shard` is required, since each test is
a process and `-j` already sets how many run at once): the tests of shard I run each in its own process, and with `--timings`
the start order is `--order`: `interleave` (default) alternates the longest and the shortest test, so long tests start early
but not stacked together (a suite's heaviest tests started side by side took 33 s against 21-29 s alone and hit a 30 s
`Timeout`), `longest-first` is the earlier behaviour, `suite` keeps the suite's order. Without `--timings` the split is by
count and tests start in the suite's order.

The split needs the suite's list, so a balanced run starts with a listing process (a few seconds); hash sharding needs
none.

Measured on the large app's whole-app journeys (76 tests, 77 s in one process, one of them waiting 30 s of real time
for the app's poll), timings from that one-process run:

| Shards | Hash (`-e numShards`) | By measured time (`--timings`) |
|---|---|---|
| 2 | 61.6 s (shards 62 and 33 s) | 44.4 s (44 and 37 s, predicted 38 and 38) |
| 3 | 52.4 s (52, 23 and 12 s) | 36.0 s (36, 34 and 25 s, predicted 30, 23 and 23) |

With 3 shards the 30 s test has a shard to itself, which is as short as that suite gets. Shards run a little longer than
predicted: each process starts the app's framework (a few seconds), and shards share the machine's cores.

## Limits

Selection is as good as "ran code of a class". It misses a test when:

- the test depends on a value **inlined at compile time**: a Java `static final` constant or a Kotlin `const val`
  from another file is copied into the reading class, which records nothing of the file that declared it. Changing the
  constant changes the reader's bytecode, but the file that changed is the declaring one. (Kotlin inline functions are
  covered by the SMAP, above.)
- the test reads **a static field of a class whose code it never runs**, set up by an earlier test in the same process
  (Java code reading another class's static field directly; Kotlin goes through a getter, which runs). Robolectric
  gives each test a fresh Application but the app's statics carry over, as on a device.
- the code is **found by reflection or generated** from the changed file: annotation processors and KSP (Hilt, Room,
  Moshi) write classes into other files, which are not in the diff. The generated class is usually reached together
  with the class it was generated for, so a change to the source reaches the tests through it; a generated class
  that the test reaches alone is missed.
- the change is **outside the probed packages** but in a `.kt`/`.java` file of the APK: it is unknown, so the whole
  suite runs (safe).
- **the recording is stale**: the deps file reflects the code it was recorded on. Tests whose paths through the code
  changed since then may reach new classes. Re-record often (on every merge to main, or nightly); tests that changed
  are selected through their own files.

- **a module's resources reach the APK without any of its code** (rule 5 would call the module absent). A module
  whose Kotlin or Java code is all outside the probed packages, or that has none, is never called absent.

The unit is a source file, not a line: a file holding a class that every test touches (an Application, a receiver the
manifest declares, which Robolectric creates for every test) selects every test whenever any line of it changes.

## Checked

`scripts/affected-check.py` (CI: the `linux` job's `data` leg, with `--image apksim:ci`) on the test app
(`testapks/composeapp`, 42 tests). It records the suite, then breaks one file at a time with a change that compiles,
rebuilds both APKs with Gradle, runs the whole suite on the broken build and then only what `--affected-by` selects
for that file:

| Broken file | Tests the break fails | Selected | All failures among them |
|---|---|---|---|
| `NullBindingService.kt` (a service's onBind) | 1 | 7 of 42 (NullBindingTest) | yes, and only they fail |
| `MainActivity.kt` (the FAB counts by 2) | 3 | 10 of 42 | yes |
| `SyncCache.java` (a helper in the test APK) | 1 | 1 of 42 (TranslatorTest) | yes |

It also checks without running anything that the app module's resources, manifest and build file and the root
project's settings and version catalog select the whole suite, that a source or build file of the `uiserver` module
(another APK) selects nothing, that ignored paths select nothing and that tests the deps file has no record of are
always selected; and that `--shards 3 --timings` runs every test exactly once. `scripts/instrument-check.py` (CI too,
no JVM) covers the same paths against a stand-in apksim: a balanced split, 3,000 long-named tests split into
`-e class` lists under the argument limit, and a selection from a hand-made deps file.

The translator change leaves `scripts/translate-all.java`'s output byte-identical with recording off (the test app's
20,893 classes, the large app's 71,681), translates every class with probes on (no errors), and
`scripts/check-translator.sh` passes.

## Measured on a large app

The app of [ci-testbed.md](ci-testbed.md) (Kotlin Multiplatform, about a dozen modules, debug test APKs of
~170-185 MB), on the Mac (M4 Pro), 2026-11-05. Probed packages: the default, the app package's first two parts.

**Recording costs nothing measurable.** Each suite twice, plain and recording, interleaved:

| Suite | Tests | Plain | Recording | Deps file | Classes reached, median test |
|---|---|---|---|---|---|
| shared module | 1,318 | 43.1, 38.1 s | 42.8, 38.5 s | 141 KB | 22 |
| a feature module | 1,003 | 10.2, 7.5 s | 9.1, 9.6 s | 117 KB | 22 |
| another feature module | 461 | 6.2, 4.1 s | 5.5, 4.2 s | 108 KB | 22 |
| app module, UI tests | 156 | 14.3, 11.3 s | 14.3, 10.4 s | 179 KB | 230 |
| whole-app journeys | 76 | | 77 s | 191 KB | 1,991 |

Measured with a plain epoch field; with the volatile one (above), the shared module again: 44.8 and 38.9 s plain,
42.7 and 41.9 s recording, within the runs' own spread.

Of the classes the deps files hold (8,000-14,400 per APK), 99.9% are placed by the source map once D8's synthetic
classes take their outer class's file (95-97% without).

**"Classes first reached in test N" would miss most of it.** Replaying the shared module's run in its order, the
classes a test reached that no earlier test had (what per-test class loading in one process would see) are 10.5% of
the (test, class) pairs the probes record; for the 529 source files some test reaches, they select 14% of the
(file, test) pairs the probes do, and miss tests for 424 of the 529 files. On the test app: 20% of pairs, 18% of
(file, test) pairs. One process per test instead (`--isolate`) is correct for loading but costs a JVM, a cold JIT and an
app start per test: [parallel.md](parallel.md) measured 16 s against 8 s for 10 tests on the Mac, ~560 CPU-seconds
against ~30 in the Linux image; for the 1,318 tests above that is minutes against 40 s.

**What a change selects.** The app's last 60 merged pull requests (first-parent commits on main), replayed against
deps files recorded on the newest one, with the ignore list a team would set for this repository (documentation,
the iOS and desktop apps and source sets, the server, scripts, CI files, JVM-only tests, Compose stability baselines,
the lint baseline):

| Suite | Whole suite | Some tests | Nothing | Mean share of the suite's time run |
|---|---|---|---|---|
| shared module (1,318 tests, 36 s) | 32 | 6 (median 3% of tests) | 22 | 55% |
| a feature module (1,003) | 32 | 1 | 27 | 54% |
| another feature module (461) | 32 | 3 | 25 | 54% |
| app module, UI tests (156) | 33 | 5 | 22 | 56% |
| whole-app journeys (76, 75 s) | 33 | 10 (median all 76) | 17 | 69% |

Weighted by each suite's time, a pull request ran ~63% of the device-test time it runs today. The whole-suite runs
are changes the class map cannot see: the version catalog (19 of the 60), the app's or a module's Gradle file, and
the shared module's resources (Compose resource strings, translations, drawables, Room schemas), which every suite's
APK holds. Pull requests that change only code select little: the unit tests a few files' classes reach, or nothing
when the change is another module's, an iOS or desktop source set, or code no device test runs. The journeys, which
start the whole app, reach nearly every screen's code, so a code change selects all of them. The replay uses today's
class map for older changes, so it is an estimate.
