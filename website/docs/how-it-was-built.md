# How it was built

apksim came together one goal at a time, each measured before the next was started:

1. **Launch in seconds, then several times faster.** The runtime puts Robolectric's `android-all` framework and its
    native runtime on a plain JVM, opens the window before anything else, and loads the framework from the JVM's own
    class loader so that the JVM's AOT cache can hold it. The next goal was ten times faster: handing each launch to
    a pre-booted standby took the window from ~0.1 s to 12 ms and the small app's first frame from 1.49 s to ~0.22 s.
2. **Run real APKs.** dex2jar miscompiled code and took 30 s, so the DEX translator was written from scratch. An ELF
    loader and a bionic shim followed for native libraries, until a large app's R8 release build and its 179 MB debug
    build both ran.
3. **120 fps.** HWUI got a thread of its own, frames were paced to the display's vsync, and the per-pixel work
    (AGSL shaders, layer effects, bitmap scaling, video decoding) moved to Metal and VideoToolbox.
4. **A testbed for device tests.** A large app's 4,107 emulator tests were run here, on the Mac and in a headless
    Linux image, and fixed until none failed, so that CI needs no emulator.
5. **Everything around it.** The adb device, the browser tab, network record and replay, screenshot tests, the
    flight recorder and its replay, the crawler, the MCP server, Bluetooth LE and scenes are mostly combinations of
    those building blocks: the control channel, the accessibility tree, `settle`, data slots and the recorder's rings.

Today that is about 65,000 lines of Java (the runtime), 8,500 of C and Objective-C (the window, the ELF loader, the
bionic shims, Metal and the codecs), 20,000 of Python (tools and checks) and a Kotlin test app whose screens and
tests reproduce the behaviours that once went wrong.

Four habits did most of the work:

- **Use Android's own code wherever it exists.** The framework and its Skia, HWUI and SQLite are Android's own.
    apksim writes only what a device has around an app, and checks that against AOSP's sources.
- **Measure, then keep or drop.** `scripts/bench.py` and CI's `startup-gate` hold launch time, A/B runs are
    interleaved because timings swing with the machine's load, and ideas that measured no gain are written down so
    that they are not tried twice (partial redraw, linear textures, rendering into the window's IOSurface).
- **Trace before changing behaviour.** When a test passes on the emulator and fails here, the mechanism is found
    first, with breakpoints and return values over JDWP (`scripts/jdi/`). Two guesses broke hundreds of tests.
- **Every bug becomes a fixture.** A miscompiled shape goes into `testapks/translator-fixture`, a loader case into
    `testapks/loader-fixture`, a framework difference into a test that also passes on an API 36 emulator
    ([docs/conformance.md](https://github.com/aglose/apksim/blob/main/docs/conformance.md)); `scripts/both.py` compares screens with the emulator pixel for pixel.

## The hard parts

- **Translating DEX to JVM bytecode.** Dalvik is a register machine whose registers carry no types: a zero can be an
    int, a float or null. The JVM's locals are typed, so the translator infers a type for every value (union-find over
    values) and gives each register a local. Its two worst bugs were both in what an exception handler sees: counting a
    return as an instruction that can throw, and merging a handler's live registers at every block of its range, typed
    ints and floats as references and dropped them. That showed up far away, as Compose animations that snapped to
    their end. Methods over the JVM's 64 KB limit have to be split, and R8 and D8's debug mode reuse registers
    differently, so the fixture is built with both. It now translates about 5,400 classes in about 0.6 s.
- **Loading Android's native libraries into a macOS process.** Same CPU, different everything else. The loader maps
    and relocates ELF files itself. bionic and libSystem disagree on struct layouts, constants and `errno`, and the two
    arm64 ABIs pass variadic arguments differently, so JNI's variadic calls go through a table chosen by the caller's
    address and each variadic libc function needs a thunk (`syslog` crashed in `strlen` without one). Apple's
    `pthread_cond_timedwait` reads a monotonic deadline as wall-clock time and returned at once, so two WebRTC threads
    spun a core each. macOS maps 16 KB pages and never makes one writable and executable, which libraries linked for
    4 KB pages assume they can have ([Limitations](limitations.md)). On arm64 Linux, glibc owns the thread pointer, so the
    loader rewrites the instructions that read it.
- **Launch time.** The JVM's AOT cache skips classes from Robolectric's instrumenting class loader and from signed
    jars, so the framework is loaded another way and one jar is unsigned at setup. Robolectric's dispatch through
    `invokedynamic` was a quarter of a cold launch until call sites were bound directly. A standby has to become the
    instance without the terminal noticing: it receives the launcher's stdin, stdout and stderr over a Unix socket,
    moves to its working directory and returns its exit code. A CI job on a Mac fails a pull request that makes
    launches slower ([docs/startup-gate.md](https://github.com/aglose/apksim/blob/main/docs/startup-gate.md)).
- **120 fps with a CPU rasteriser.** Robolectric's clock has 1 ms resolution, and a 120 Hz frame is 8.33 ms: a
    rounding slip between the two once spun a core for the rest of every frame. A 256 MB young generation paused
    12-50 ms in mid-scroll; at 64 MB a collection takes about 1 ms. The ripple effect's per-pixel shader cost ~15 ms a
    frame on the CPU. AGSL shaders are translated to Metal's shading language and rendered a frame ahead, and scaling
    bitmaps on the GPU took a tablet's raster time from ~36 ms to ~2.3 ms. What is left is HWUI rasterising on one
    core: 15-35% of frames still take 9-12 ms.
- **Being the system server.** Robolectric is a unit-test library: it records that an activity was started, and it
    has no activity stack, no window placement, no touch routing between windows, no network callbacks or sticky
    broadcasts, and an uncaught exception left the app running. Each was rebuilt to do what AOSP's code does (which
    windows accessibility reports, which window a touch goes to, when a network callback fires, how a crash ends the
    process). A typical bug: `WifiManager.getMaxSignalLevel()` answered 0, an app that buckets the signal level divided
    by zero inside a coroutine, nothing reported it, and the screen waited for ever.
- **Device tests without a device.** A device test runs on its own thread beside the app's main thread. Here the
    test body is on the main thread, so `Thread.sleep`, `CountDownLatch.await` and `runBlocking` must run the main
    queue while they wait, Compose's test clock must queue behind what is already posted, and an endless animation
    must not keep the looper from ever going idle. A second clock mode follows real time for UI that has to last.
    With all of that, a large app's 4,107 device tests pass, on a Mac in about a quarter of its emulators' time.
- **Video and audio.** A decoder returns pictures in presentation order with their own timestamps, and holds input
    buffers while it reorders. Pairing each input with one output made ExoPlayer drop 58 of 90 frames as late, and
    handing out one input buffer at a time showed a 1080p60 clip at ~9 pictures a second. An AudioTrack's playback head
    may move only as sound is played, or the player's clock races ahead of the pictures.
- **Time.** The uptime clock is simulated, and only the main loop may move it. A worker thread polling with
    `SystemClock.sleep` once pushed it minutes ahead, and timers fired early: most likely why an app's 10-minute
    inactivity timer went off after about 30 seconds.
- **Network record and replay in minified apps.** R8 renames OkHttp, so there is no class to hook. The translator
    instead redirects the JDK calls every HTTP stack ends in, which R8 never renames, and a stand-in TLS socket hands
    the app the server's real certificate chain, so hostname checks and pins still pass
    ([docs/network.md](network.md)).
- **The host has opinions.** macOS's `setlocale` swaps the process locale under running threads, which crashed a JIT
    compiler thread while a standby booted. App Nap throttles the timers of a hidden window. The window server brings a
    newly launched app to the front whether asked or not, so windows that must not take focus start as accessory apps.

What is still open is under [Limitations](limitations.md) and in [AGENTS.md](https://github.com/aglose/apksim/blob/main/AGENTS.md), "Open work".
