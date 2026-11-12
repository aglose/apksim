# The UiAutomator engine (`--uia engine`)

A prototype, off by default. UiAutomator (androidx.test.uiautomator) stays what the test APK brings: its classes are
translated and run unchanged. What changes is the layer under it, which apksim owns: `UiAutomation`'s waits and the
sleeps UiAutomator makes itself. `--uia classic` (the default) is the path described in AGENTS.md "Instrumentation
tests"; `--uia engine` (`-Dapksim.uia=engine`, test runs only) is `dev.apksim.Uia`.

```bash
./apksim --uia engine [--realtime-clock] --instrument app-debug-androidTest.apk app-debug.apk
scripts/instrument.py APP TEST -- --uia engine
```

## Why

On a device UiAutomator is another process. It cannot know when the app is idle, so it takes 500 ms without an
accessibility event for idle, paces the gestures it injects with real sleeps, and polls for what it waits for. All
three are waits on a clock. apksim runs the app in the process that drives it: it owns the main queue, the clock and
the frames, so it can decide instead of wait.

Measured first (the test app's 17 UiAutomator tests, the built-in sampler): with the default clock UiAutomator's own
share was about a tenth of a 2.2 s run. With `--realtime-clock` the same tests took 28-30 s, 67% of it in real sleeps
(56% inside gesture injection) and 17% in idle waits; reading the accessibility tree was under 1%. So the engine
changes how time is spent, not how the tree is read.

## What it does

- **Idle** (`Uia.idle`, behind `UiAutomation.waitForIdle`, which every query calls first). The main queue is run one
  task at a time until one of:
  - *quiet*: nothing is queued that is due soon (within the quiet time; with `--realtime-clock` within 50 ms, as before);
  - *steady*: frames or recurring work keep coming, but a hash of the accessibility tree (`UiaTree.fingerprint`: every
    view's position, state and text, and a node provider's nodes, so Compose) has not changed for
    `-Dapksim.uia.steadyMs` (250) of the app's time. An endless animation that moves nothing ends here; the classic
    wait ran it for the whole quiet time on every query;
  - *cap*: the tree is still changing after `-Dapksim.uia.movingMs` (1000). A view sliding into place or a drawer
    settling is waited for; the classic wait gave up at the quiet time.

  Work that is due now always runs before a wait ends.
- **Time** (`RealTimeClock.fastForward`). With `--realtime-clock`, the engine's own waits do not wait: the clock moves
  to the next frame or message and what comes due runs in order, each frame at its time. That covers idle waits, the
  frame UiAutomator waits for after each swipe step, and the sleeps it paces a gesture with. The translator sends
  every `SystemClock.sleep` in UiAutomator's classes to `Uia.sleep` with what it is for: *gesture* (GestureController,
  InteractionController's click, long tap and swipe, scrollUntil's wait for a list to snap back) or *poll* (WaitMixin,
  UiObject's waits, QueryController's retry). Gesture time is fast-forwarded; a poll's sleep stays real there, since
  it waits on the app. The app sees the same event times and the same frames between them; real time's base moves
  back by what was skipped, so the clock follows real time again afterwards and other threads' clock reads agree.
- **Accessibility** (`Uia.connect`, `Uia.onEvent`). From a test's first use of UiAutomation, `AccessibilityManager`
  is enabled with no service listed, as on a device while only UiAutomation is connected (checked on an API 36
  emulator). Views then send their accessibility events, and each window registers under the id its nodes carry.
  The events go to UiAutomation's listener and to `executeAndWaitForEvent`'s filter as they arrive
  (`Uia.runAndAwait`); a filter's are sealed and carry their source node, as a service receives them, the
  listener's carry no source (reading a node inside the view's own call is not safe). The wait ends when the filter
  takes one, at its timeout in the app's time, or when nothing the engine can see is left that could send one: no
  work scheduled on the main thread within the timeout (the clock moves to a delayed post), frames that no longer
  change the tree, and no other thread at work (those get real time, at most the timeout). A device waits its
  timeout out whatever happens, so an event that follows an answer from the network or a timer on another thread is
  missed here where a device would get it. The classic path's synthesized
  events (a scroll for each view whose scroll state changed, then a window change) remain as the fallback: the system
  sends window changes on a device, not the app, and Compose sends no scroll events while only UiAutomation listens.
- **Workers** (`UiaWorkers`). With the default clock a poll's sleep only moved the simulated clock, so a wait's whole
  timeout could pass in a few real milliseconds, before another thread had finished what the test was waiting for.
  A poll's sleep now gives the app's other threads real time while they are at work, at most the sleep's length.
  At work means RUNNABLE and either running Java code or inside a native call with its CPU time still moving (SQLite
  and image decoding are native work; a socket's read is a native wait). A thread that has been at work through
  2 s of such waits without a pause is taken for background noise from then on, so one that spins for ever does not
  turn every wait into real time. An idle wait does not hold for workers: a device's does not either
  (`-Dapksim.uia.holdIdle=true` makes it).

`-Dapksim.uia.events=false` and `-Dapksim.uia.workers=false` turn those two parts off for A/B runs.
`-Dapksim.uia.trace=true` prints each idle wait, sleep and event; a run with the engine on ends with one line of what
it did:

```
[apksim] uia engine: 241 idle waits (194 quiet, 45 steady, 2 stopped) in 576 ms, 2426 tasks, 341 tree reads in 107 ms; ...
```

## Results (2026-11-12, M4 Pro, the test app)

| | `--uia classic` | `--uia engine` |
|---|---|---|
| UiAutomator tests, default clock | 3.0 s (19 tests) | 3.1 s (22 tests) |
| UiAutomator tests, `--realtime-clock` | 41.0 s (19 tests) | 7.2 s (22 tests) |
| of which: 20 looks at a screen that never stops animating, `--realtime-clock` | 10.2 s | 0.6 s |
| the same 20 looks, default clock | 0.65 s | 0.56 s |
| whole suite, default clock | 47.8 s (338 pass; the 3 engine-only tests fail when forced) | 47.1 s (341 pass) |
| whole suite, `--realtime-clock` | 208 s (336 tests, before `UiaEngineTest`) | 177 s (341 pass) |

Since those runs `UiaEngineTest` has a sixth test (an event that comes 1.5 s after its action) and an event wait
runs the work scheduled within its timeout: the 23 tests take 8.8 s with the engine on the real-time clock, against
42.1 s classic for the 19 it runs.

One process each, times as JUnit reports them. The three tests the classic path does not run are `UiaEngineTest`'s
engine-only ones. In the engine's real-time run 16.2 s of gesture time passed in 0.5 s, and 191 idle waits took 0.9 s
together (223 tree reads in 0.26 s). Most of what is left of a real-time run is waits on the app that are real on
purpose (one test waits 2 s for a removal) and launches, which are not UiAutomator's.

The same 22 UiAutomator tests on the API 36 emulator took 96 s. `scripts/conformance.py` with `-- --uia engine`: 21
pass on both sides; `UiAutomatorTest#swipeScrollsTheListWithoutWaitingForFrames` fails on the emulator
(StaleObjectException) and passes here, on either path.

`UiaEngineTest` (the test app's suite; on apksim its engine-only tests run with `-e uiaEngine true`) holds the
behaviours down. The classic path fails four of them: accessibility is not on, neither a click's own
`TYPE_VIEW_CLICKED` nor an event a delayed post sends 1.5 s later reaches a filter, and a one-second
`wait(Until.hasObject)` for what a thread takes 80 ms to compute times out (the default clock's starvation above).
The emulator passes all six.

## What a test may notice

- With `--realtime-clock`, a UiAutomator gesture or idle wait takes the app's time but not the wall clock's. A thread
  of the app that works meanwhile gets less real time than a device would give it; a test that needs its result
  waits for it (`wait(Until...)`), whose sleeps are real.
- An idle wait can end sooner than the classic one (steady after 250 ms of an unchanged tree, where the classic wait
  ran 500 ms) or later (a tree still moving, up to 1000 ms).
- Accessibility is on once a test touches UiAutomation: `AccessibilityManager.isEnabled()` is true, and views do what
  they do on a device then.

## Not done

- Not run: a large app's suites (only the test app's, with both clocks) and `instrument.py --isolate` or shards with
  the engine. In the Linux image (amd64, under Rosetta) the 22 UiAutomator tests pass with it on both clocks
  (`--realtime-clock`: 28 s against 59 s classic) and the whole suite on the default clock.
- A thread waiting for the network, asleep, or in a delay on another dispatcher does not count as at work: a pool's
  idle threads look the same. The network hooks could say whether a request is in flight.
- With `--realtime-clock` a poll's sleep is still real time. Skipping it is only safe when no thread has a timer or a
  request of its own pending, which the engine cannot tell yet.
- Running instances (an instrumentation started over adb) keep the classic path.
- Before it can be the default: the two items above measured on a large suite, the event stream compared with the
  emulator event by event (which events, in what order), and the steady and moving times checked against apps with
  long transitions.
