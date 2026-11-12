# Flake hunter

`scripts/flake-hunt.py` runs chosen tests many times under perturbations that bring out the two common kinds of flaky
test, and says which kind each one is:

- **order-dependent**: it fails only after certain other tests ran in the same process (app and test statics carry
  over between a process's tests, on a device and here). The hunter names the tests that make it fail, reduced to the
  fewest, and prints the command that reproduces it.
- **timing-dependent** (or **nondeterministic**): it fails some of the time whatever ran before it, under a busy CPU
  or with the simulated clock following real time.

```bash
# the tests a change reaches, 5 runs per perturbation
scripts/flake-hunt.py app.apk app-androidTest.apk --affected-by deps.json.gz --diff origin/main...HEAD
# named tests, 10 runs each, only some perturbations, in the Linux image
scripts/flake-hunt.py app.apk test.apk -n 10 --perturb shuffle,isolate -e class com.example.CartTest,com.example.LoginTest \
  --apksim "docker run --rm -v $PWD:/work ghcr.io/aglose/apksim:VERSION"
```

## Perturbations

A listing (`-e log true`, nothing runs) finds the tests first; a class-level failure (`@BeforeClass`, `Class#null`)
counts against each of its tests. Every run goes through `instrument.py`, so a process that crashes or hangs is handled there (`--test-timeout`, a new
process for the tests left). `-n N` runs of each (default 5):

| | how | brings out |
|---|---|---|
| `suite` | one process, the suite's order | the baseline |
| `shuffle` | one process; classes, and methods within each class, shuffled (`-e apksimOrder`). The first run is the suite's order reversed, so with `suite` every pair of tests has run both ways round | order dependence |
| `isolate` | a process per test (`--isolate -j J`, through the pre-warmed pool: docs/test-pool.md) | a test that fails only after others; a test that fails alone too is not order-dependent |
| `oversubscribe` | one process, suite order, with 2 busy processes per CPU beside it (`--hogs N`) | waits on real time that assume an idle machine |
| `realtime` | one process, suite order, `--realtime-clock` | tests that rely on the simulated clock skipping idle time |

`-e apksimOrder A#m,B#n,...` is apksim's (Instrument): it sorts a process's tests into that order as far as JUnit runs
them, a class's tests together and classes in the order of their first listed test; unnamed tests go last. The hunter
only makes orders of that shape, so a run's order is exactly the list it passed.

## Verdicts

For each test that failed in a `shuffle` run and passes alone (every `isolate` run), the hunter runs the tests that
came before it in that run again, with it, in the same order, in one process. If it fails again, and passes when run alone (a skip or no result alone is not enough),
delta debugging (Zeller's ddmin) reduces those tests to a 1-minimal set that still makes it fail: removing any one of
them makes it pass. Each step is one process. The verdict is then `order-dependent`, with that set as the culprits and
a command (instrument.py with `-e class` and `-e apksimOrder`) that reproduces it. A test whose class failed at class
level (a `@BeforeClass` that found another test's state: the run reports only `Class#null`) is placed where that entry
ran, and reduced and reproduced by its own name. Otherwise a test that failed only
under `oversubscribe` or `realtime` is `timing-dependent`, one that failed elsewhere too `nondeterministic`, one that
never passed `failing`, the rest `stable`.

Output in `--out DIR` (default `flake-hunt`): `flake-hunt.xml` (JUnit: an entry per test and perturbation, failed when
a run of it failed, "passed K of N runs (verdict)" as the message, the culprits and command for an order-dependent
one; `flakehunt.*` properties), `index.html` (the table, the culprits and commands, each test's first failure),
`verdicts.json`, and `runs/` (each run's JUnit XML and log, ddmin's steps included). It exits 1 when any test is not
stable.

## The check

The test app has four flaky-on-purpose classes, skipped unless `-e flakyOnPurpose true`: `FlakyStateTest`'s
`leavesAStaticSet` sets a static that `FlakyOrderTest#expectsAFreshStatic` expects clear (in the suite's order it runs
first and passes), as does `FlakyClassOrderTest`'s `@BeforeClass` (after the polluter its class fails as a whole), and
`FlakyTimingTest#aDelayedTaskRunsWithoutWaiting` posts a task 2 s ahead, waits 2 s on the
uptime clock and expects it done within 1 s of real time: true on the simulated clock, which jumps over idle time, and
false with `--realtime-clock` (or on a device), as a suite written against one clock fails on the other. `scripts/flake-hunt-check.py APP TEST [--apksim CMD --local DIR]`
hunts in those plus bystanders (the other tests of those classes, `AndroidIdTest`) and checks that both order-dependent
tests are blamed on `leavesAStaticSet` and nothing else, that each printed command reproduces its failure, that the timing test fails
under `realtime` and is called timing-dependent, and that every bystander is stable.

## Measured

2026-11-10, `flake-hunt-check.py` (8 tests: the 6 of the two order classes and the timing class, and `AndroidIdTest`'s 2;
`-n 3`, every perturbation), with other sessions loading the Mac:

| | runs | wall |
|---|---|---|
| Mac (M4 Pro, 12 cores) | 15 + 5 for ddmin | 74 s |
| Linux image (amd64 under Rosetta, Colima on the same Mac; `--apksim "docker run ..."`) | 15 + 5 for ddmin | 188 s |

Both: `FlakyOrderTest#expectsAFreshStatic` passed 3/3 in `suite`, `isolate`, `oversubscribe` and `realtime` and 1/3 in
`shuffle` (it failed in the reversed run and in one shuffled one), was reproduced with the 5 tests before it, and ddmin
took it to `FlakyStateTest#leavesAStaticSet` alone in 5 single-process runs in all; the printed command fails it.
`FlakyTimingTest` failed 3/3 under `realtime` only: timing-dependent. The other 6 were stable.

A first timing fixture (500 hand-offs between two threads within 250 ms) did not do: it passed every oversubscribed run
(macOS, like Linux, favours threads that block often, so busy processes barely delay their wake-ups) and failed two
shuffled runs when other sessions loaded the machine, so the hunter called it nondeterministic. `oversubscribe` brings
out budgets on CPU-bound work rather than on wake-ups; no test of the test app shows one.
