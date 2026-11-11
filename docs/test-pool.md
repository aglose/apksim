# Pre-warmed test processes

`instrument.py --isolate` runs every test in a process of its own, as the AndroidX Test Orchestrator with
`clearPackageData` does. Each process starts a JVM, indexes the APKs, sets up Robolectric's sandbox and the native
runtime, and only then runs its test; on a large app's CI that start-up was about a third of each test's time (issue
205). With the pool, those processes are started ahead: each boots everything no test decides, then waits for its test.
The test still gets a process nothing else ran in, and the process still exits after it: only the boot moved off the
test's critical path.

```bash
scripts/instrument.py app.apk app-androidTest.apk --isolate -j 3                  # the pool is on: 6 processes ahead
scripts/instrument.py app.apk app-androidTest.apk --isolate -j 3 --prewarm 3      # 3 ahead (less memory)
scripts/instrument.py app.apk app-androidTest.apk --isolate -j 3 --prewarm off    # every process cold, as before
```

## How it works

- **instrument.py** (`Pool`): with `--isolate` (and `--prewarm` not `off`) it starts twice `-j` processes (or N) before it
  lists the suite, so they boot while the listing runs. Each is the run's own command line, `-e` arguments, `--jvm`
  options, `--bundles`, `--record` and all, minus the `-e class` that names the test, plus `--pooled`. When a test's turn
  comes, the oldest waiting process is handed the test: its `-e` pairs (`KEY NUL VALUE NUL`) on stdin, which is then
  closed. A replacement starts at once, so the next process boots while this test runs, as long as tests are left for
  it; the run starts exactly one process per test. Processes left over (a suite smaller than the pool) get an empty
  stdin and exit 0.
- **apksim** (`--pooled`, `TestPool`): a pooled test process runs `Main` as any test process does up to
  `Instrument.run`'s properties, then defines the test APK's classes its last process loaded (`loadAhead`, as for the
  app's: no static initializers), and runs the runner probe's test (`RunnerApplication`) with no runner to create. That
  sets up the sandbox, the native runtime and a first, plain Application, so no app code runs before the hand-over. It
  prints `[apksim] pool: ready, N ms after the JVM started`, reads stdin to its end, sets each pair as
  `apksim.instrument.KEY` (what the launcher's `-e` sets) and goes on as a cold process, the probe of a suite's own
  runner included: that runs after the hand-over, as a runner's `newApplication` may read the test's arguments (12 ms for the test
  app's runner, on the sandbox the warm-up set up). An
  empty stdin ends the process quietly.
- Everything after the hand-over is the code a cold process runs, so the process is the same kind of process to
  instrument.py: its output is read as before, `--test-timeout` counts from the hand-over (a pooled process stuck in
  its boot is killed as a hung one and its test fails "timed out"), one that died before it was handed its test fails
  that test as a cold process that died at start-up does (its stderr in the failure), `--reap-early` and
  `--exit-marks` apply unchanged, and Ctrl-C ends waiting processes with the rest (they are in instrument.py's list of
  live processes from their start).
- `--jvm` options and every other launch option are honoured: the pooled process is started with them, unlike a warm
  launch handed to a standby, which booted before it knew them. A bundle's `repro.sh` leaves `--pooled` out (it names
  its test with `-e class` instead).
- The JUnit XML gives each test a pooled process ran `apksim.pooled=true`; `apksim.startup_seconds` then counts from
  the hand-over.
- **Through `docker run`** (`--apksim`): instrument.py adds `-i` to a pooled process's `docker run`, so stdin reaches
  the container. **In the Linux image** (`--entrypoint instrument.py`) the pool works as on the Mac: it needs no
  standby, socket or `sandbox-exec`, only stdin. So Linux needs nothing more than the image already has.

## What it costs and saves

A pooled process does the same work as a cold one, so the pool saves wall time, not CPU: a test waits for none of the
boot when its process was ready, and for what is left of it when not. It pays when cores would otherwise sit idle while
processes boot, and when tests are long enough for the next process to boot meanwhile. A machine already CPU-bound at
`-j N` (issue 205's CI Macs) gains little: the boots then compete with the tests. Memory: up to `-j` plus the pool's
size JVMs at once, 3 x `-j` by default (~400-500 MB each: `-j 4` is ~12 JVMs, ~5.5 GB).

Why twice `-j`: a process taken for a test is replaced at once, so with a pool of `-j` the next process for a slot has
only one test's time to boot. The test app's tests are short (median 0.1-0.6 s) and a boot takes 1-4 s on a busy Mac,
so its next process was often still booting. With twice as many each has about two tests' time, and start-up fell to
zero for most tests (below).

## Measured

Mac (M4 Pro, 12 cores), 2026-11-10, the test app's suite narrowed to 10 classes (32 tests, 26 run, 6 skip), `--isolate
--exit-marks`, three rounds with the arms interleaved in each (order alternated). Eight other sessions were building and
testing on the same Mac, and the load average swung between ~15 and ~60 over the runs, so single runs are noise:
compare within a round, and the medians. Start-up is per test, from spawn (cold) or hand-over (pooled) to the test's
start; CPU is all processes' user + system time.

| | wall, 3 rounds | wall median | start-up per test, median | CPU median |
|---|---|---|---|---|
| `-j 1`, cold (`--prewarm off`) | 167, 66, 55 s | 66 s | 1.35 s | 65 CPU-s |
| `-j 1`, pool of 1 | 171, 34, 35 s | 35 s | 0.65 s | 72 CPU-s |
| `-j 1`, pool of 2 (default) | 126, 64, 29 s | 64 s | 0.00 s | 78 CPU-s |
| `-j 3`, cold | 127, 28, 26 s | 28 s | 1.75 s | 68 CPU-s |
| `-j 3`, pool of 3 | 92, 21, 41 s | 41 s | 1.50 s | 83 CPU-s |
| `-j 3`, pool of 6 (default) | 67, 20, 15 s | 20 s | 0.00 s | 76 CPU-s |

The pool of twice `-j` beat cold in every `-j 3` round (-47%, -29%, -42%) and in two of three `-j 1` rounds (-25%, -47%;
in the third it ran first, under the round's heaviest load, and tied). The test's own time rose under the heaviest load
(boots of the next processes compete with it), and CPU by ~10-15%: the pooled processes' extra work (the test APK's
`loadAhead`, a probe in a process the run may close unused) and contention. Exit was the same everywhere: 0.0-0.1 s a
test (`--exit-marks`: class cache save and scratch removal), 5-7 s in all for the 26 tests; the pool does not touch it,
and `--reap-early` still takes it off a slot.

Linux image (amd64 under Rosetta in Colima on the same Mac, `--entrypoint instrument.py`, `-j 2`, a pool of 2: before the
default became twice `-j`), 10 tests: 65.9 s pooled against 85.6 s cold; tests handed a ready process started in
0.1-0.6 s against ~10 s cold, where a boot takes 10 s and a test 1-20 s.

Not tried: booting pooled processes at a lower scheduling priority (macOS background QoS would keep them on the
efficiency cores while the tests run on the performance ones, but a process cannot raise itself back up on Linux), and
a pool shared by several instrument.py runs.

## Checks

- `scripts/instrument-check.py` (CI, no JVM): a stand-in apksim plays pooled processes; `pool-dies-waiting` (one dies
  before it is handed its test: that test fails with its stderr, the rest pass, no process is left), `pool-hangs-waiting`
  (one never reads its test: `--test-timeout` ends it), `pool-dies-in-test`, `pool-docker` (`docker run` gets `-i`),
  `pool-deep` (`--prewarm 5`), `pool-off` (no pooled process), `interrupt-pool` (Ctrl-C with processes waiting), and
  every other `--isolate` scenario now through the pool (`init-error-only-isolate`: six processes closed unused).
- `scripts/pool-check.py APP TEST [--apksim CMD --local DIR]` (CI, in the Linux image): the same tests with the pool on
  and off give the same results, every test ran pooled with it on and none with it off, a pooled process honoured `-e
  timezone` and `--timezone`, and a failing test's bundle came from its pooled process with a `repro.sh` that names the
  test and not `--pooled`.
