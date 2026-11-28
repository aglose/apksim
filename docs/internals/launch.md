# Launcher, startup, warm launches and memory

Agent reference notes, moved out of AGENTS.md so they are read only when needed. Quoted section names
("Video", "Warm launches") are sections of these files: AGENTS.md's index says which file has each.

## The launcher
- `apksim`: launcher (bash). Hands the launch to a standby when it can (`work/runtime/apksim-client`), otherwise
  builds the classpath, sandbox (`sandbox-exec`) and AOT cache flags, then runs `dev.apksim.Main`. Builtins only up
  to that hand-off: each fork there cost a few ms. Two of its settings are for memory (docs/perf-plan.md, "Memory,
  2026-11-14"): `-XX:ArchiveRelocationMode=0` maps the AOT cache at the address `train.sh` built it for (16 TB on
  macOS, where nothing else is placed: the JDK's 32 GB default sits among malloc's regions and the heap, and was taken
  in most launches), so its 60 MB of read-only metadata stay shared file pages rather than ~45 MB rewritten in every
  process (not for a beamed instance: it gives up the random placement of the JVM's class metadata, which stays for a
  site's APK; `APKSIM_AOT_RELOCATE=1` keeps the JDK's relocation for any other launch, read as a JVM starts. In the
  Linux image the default address is free, 60 of 60 launches, and the gain is ~38 MB of each process moved from private
  memory to file pages its other processes share, not a smaller resident size); and `MallocSpaceEfficient=1` in the
  JVM's environment (macOS;
  `MallocSpaceEfficient=0` keeps the default), without which macOS's malloc keeps freed memory dirty and counted for
  good (the JIT's arenas after a launch, a busy screen's buffers; `malloc_zone_pressure_relief` returns none of it;
  seen on macOS 26.1, and the variable is not in malloc's man page: an OS that ignores it behaves as before). On Linux
  glibc keeps freed memory in its arenas the same way, and the launcher passes `-XX:TrimNativeHeapInterval=5000`: the
  JVM calls `malloc_trim` every 5 s (docs/perf-plan.md, "Memory in the Linux image"; `--jvm
  -XX:TrimNativeHeapInterval=0` turns it off).
  And `-XX:+UseCompressedOops` on every OS: without it a machine with 128 GB or more gets a default heap past ~30 GB,
  the JVM turns compressed pointers off, and the startup cache (built with them) is refused altogether, ~1.1 s
  (Linux) to ~1.9 s (Mac) more on every cold launch; asked for, the JVM caps that default heap at ~30 GB. An
  `--jvm -Xmx` of 32 GB or more still loses the cache (the JVM warns "Max heap size too large for Compressed Oops").
  Cold launches and test runs stop the JIT at C1 (`TieredStopAtLevel=1`), which shrinks the JVM's code cache to
  48 MB; the launcher gives it tiered's 240 MB back (`-XX:ReservedCodeCacheSize=240m`, address space only): at 48 MB
  every ~7 MB of new code started a full collection ("CodeCache GC Threshold"), 35-60 ms on the test app and up to
  226 ms on a large app, dropping video frames (issue 492).

## Warm launches
A launch is handed to a **standby**: a JVM started in the background that has booted everything that does not depend
on the APK, as Android's zygote does, and waits on a Unix socket (`$TMPDIR/apksim-standby-<hash>.sock`, log beside
it). The launcher's client (`native/apksim-client.c`) sends the APK, the launch's options and working directory, and
its own stdin/stdout/stderr (SCM_RIGHTS); the standby (`Standby.java`, `native/standby.c`) puts those on fds 0-2,
moves to that directory, and becomes the instance, so its output reaches the terminal and its exit code becomes the
client's. If the client is killed (Ctrl-C, closed terminal, `kill`), the instance quits. Before waiting, a standby runs
`StandbyWarmup` as a Robolectric test with no APK: the sandbox `Host` reuses, the native runtime, an application,
then three rounds of a framework-only activity (views, text, a RenderNode drawn through `GpuCanvas`, HWUI frames
through `HwRenderer`, touches), so call sites Robolectric links through invokedynamic and framework classes are
done, plus the codec list, networks, XML parser and runtime hash; Robolectric then resets as between tests. No app
code runs before the launch. It then waits for the JIT queue to drain, collects, and gives the heap's unused pages back (`HeapTrim`): ~2.5 s
from start to ready, ~190-225 MB while it waits (~350 MB before 2026-11-14).
- One standby per apksim folder and sandbox flags (`--no-network`, `--no-sandbox`, and `b` for `apksim beam`'s
  instances, which the agent starts itself: docs/beam.md "The beam sandbox"), not per working directory.
  Instances start the next one 1 s after their first frame (`--spawn-standby`, inside their own sandbox) or on exit.
  A launch waits for a booting standby only past its first second (a cold launch is ~1.3 s), else launches cold.
- A beamed launch that goes cold prints `[apksim] cold launch: WHY (standby name "..")` (any launch with `APKSIM_COLD_WHY_LOG=1`;
  the agent echoes it as `[serve] session ID: cold launch: ...`; issue 577), and `/beam/hello` from a trusted page boots a
  standby (docs/beam.md "Standbys").
- A standby quits after an hour unused (`-Dapksim.standbyIdle` seconds) and when `work/apksim.jar` or `work/app.aot`
  change, so after `build.sh` + `train.sh` the next launch is cold and starts a fresh one. `./apksim --stop-standbys`
  stops them; `APKSIM_STANDBY=0` launches cold and starts none. `--jvm` options other than the `-D` switches listed in
  the launcher (read after the hand-off), `--debug`, `--instrument` and training launch cold: a property read while
  the standby booted would be ignored. A standby gives way when memory runs short (`Footprint.memoryShort`: macOS's
  pressure above normal; on Linux under a tenth of the cgroup's limit, or of MemTotal without one, and under 256 MB
  left, the inactive file cache counted free), and the next launch is cold.
- Linux (issue 561): opt-in, `APKSIM_STANDBY=1` (the client hands a refused launch back as `cold`, which still starts
  the next one); off by default, as a container running one app or suite has no next launch. The image builds
  `libstandby.so` and `apksim-client` from the same sources. In the arm64 image (4 CPUs) the test app's first frame is
  ~0.22 s from a standby against 0.94-1.7 s cold; an idle standby is ~175 MB of anonymous memory (~290 MB resident).
  A JVM a standby became is not the launching process's child: the container's PID 1 must reap it, or it stays a
  zombie (`docker run --init`; `apksim serve` re-runs itself under the image's tini when it is PID 1).
- An instance from a standby has the standby's environment and PID, not the launcher's: `scripts/ctl.py` reads the
  APK from `work/tmp/<pid>/launch.info`. Resolve paths from commands with `Storage.absolute`, not
  `Path.toAbsolutePath` (the JDK fixes that at JVM start).
- `-Dapksim.timing=verbose` prints finer startup marks (app info, classes, sandbox, application state, host setup),
  each with the process's CPU time so far (all threads).
- Tried without gain: one merged uncompressed class-path jar (the JDK's 40-jar search per app class was not the
  cost), lower JIT thresholds (`CompileThresholdScaling` flooded the compile queue), more compiler threads.
- Measured 2026-11-03 (JFR, `-Dapksim.timing=verbose`): of a ~310 ms warm launch of the test app, the APK index and
  class cache take ~10 ms (index load mostly off the main thread) and the main thread waits 1-2 ms on locks in all,
  none on AppClasses'; the rest is the app's own startup (Application ~45 ms, activity ~65 ms, resume to first frame
  ~160 ms). So the class cache's index and locking are not worth redesigning for launch time. Translation is: a warm
  launch that translates everything (`-Dapksim.classCache=false`, as after a rebuild) took ~1.55 s, ~1.15 s since.
- Not tried: a standby prepared for the last APK (classes defined, resources parsed: ~40 ms more, but a second
  standby's memory for rebuilt APKs), per-APK AOT caches for app classes, WhiteBox-queued JIT of last launch's hot
  methods.

## Code notes
- `Main`: entry point. Opens the window first, then installs app classes and runs Robolectric's JUnit runner on `Host`.
  A cold launch initializes Robolectric's `AndroidTestEnvironment` + `SimKeyStore`, `SimRunner`/`Host` runner,
  metadata (`CompatTable`, `Gaps`, `InstallState.frameworkProtection`), and framework `CppApkAssets.preload` on
  parallel threads (`-Dapksim.initAhead=false`). For `http(s)://` URL launches (`ApkDownloader`), `Main` immediately
  reads `--header` values from a file of the user's alone that the launcher writes in the launch's `STATE/tmp/PID` (swept with it) and Main deletes (never JVM arguments, which `ps` and RuntimeMXBean show; a warm launch's client, alive in `ps` as long as the app, gets the hidden `--header-file FILE` in place of the pairs, so a cold fallback reads the same file), starts downloading via native `/usr/bin/curl`
  HTTP/2 (or pure-Java 6-stream parallel ranged HTTP with `-Dapksim.noCurl=true`); redirects are followed hop by hop on
  the download itself (never curl's `-L`), and every caller header and `--netrc`'s credentials go to the first origin
  only (scheme, host and port), curl getting them on stdin (`scripts/check-download-auth.sh`); `cache/downloads/`
  LRU-capped at 512 MB via `-Dapksim.downloadCacheMB`), and on cold launches overlaps sandbox + `FastNativeLoader` boot
  directly inside `SimRunner.SimTestEnvironment.setUpApplicationState` (`SimRunner.deferredApkSetup`, zero warmup/reset).
  For `serve` (`Catalog.addUrl`) and `beam` (`Serve.beamDownloadApk`, both `source` and ticketed `/api/beam/apk`, where
  `Http.file` answers `HEAD` and single-range `206`), `ZipRangeFetch` (issue 674, `-Dapksim.rangeReuse=false` disables,
  `scripts/check-range-reuse.sh` in CI) reuses matching ZIP entries from builds already stored in `uploads/` whenever an
  expected SHA-256 is known ahead of time (from the request's `sha256`, else `HEAD`'s `X-Checksum-Sha256` / `Repr-Digest`
  / `Digest`), coalescing missing ranges and verifying the rebuilt file's full SHA-256 before storing (cloning same-offset
  runs CoW with `cp -c` / `--reflink=always` when worthwhile, falling back to a whole download on non-206, unread CD, low
  overlap or mismatch, and preserving cross-origin header stripping and beam's `allow-download`/content-type rules).
  Its jar is unsigned in `work/lib` (`gradle deps` strips the signature files): the JVM's AOT cache skips signed jars,
  and with them its ~670 classes are in the cache (issue 205).
  Conscrypt's native library (OkHttp's start asks for it) loads on a thread of its own as the first test environment
  is set up (`SimRunner.conscryptAhead`; not earlier: it reads android.os.Build, which must initialize inside the
  sandbox). docs/perf-plan.md is the plan for memory, launch and run time, with what was done and measured.
  Host's runner is built ahead on `apksim-init-runner`, and Robolectric reads the app's manifest while it builds it
  (`getChildren`): one that got there before `AppInfo.load` named the APK kept the standby warm-up's empty manifest,
  and the app ran with Robolectric's default Application, theme and data folder and none of its own resources ("No
  package ID 7f found"; 0.58.0, first launches beside the startup cache's training, under load). `SimRunner`'s factory
  notes such an answer (`manifestWithoutApk`) and Main builds the runner again once the APK is known (one stderr line);
  an overlapped URL launch keeps its runner, its manifest coming later on purpose. `scripts/late-manifest-check.sh`
  (CI, `ci-smoke.sh` app part; on the Mac with a new state folder) holds the APK cache's lock to make it happen.
- `FrameworkFlattener` (B2, `scripts/build.sh` -> `work/runtime/android-all-flat.jar`): offline ASM rewriter that
  flattens unshadowed non-native `invokedynamic` trampolines (97%), reflective `<clinit>` trampolines, and unshadowed-
  hierarchy `$$robo$init()` sites (using `InvokeDynamicSupport.NO_SHADOW`), stamp-cached and toggleable with
  `APKSIM_FLAT_FRAMEWORK=0`. `package-release.sh` and `docker/Dockerfile` strip duplicate `*.class` from
  `android-all-instrumented-*.jar`. With B2, cold launches default to `-XX:TieredStopAtLevel=1` (matching C2's 120 fps
  with lower CPU/memory; standbys keep full C2; `--jvm -XX:TieredStopAtLevel=4` overrides).
- `FastNativeLoader`: caches Android's serialized system font map in `STATE/cache/framework/fontmap-*.bin` (stamped
  by `fonts.xml` + native dylib; `-Dapksim.cachedFontMap=false` disables).
- `FastNativeLoader`: loads Robolectric's native runtime from pre-extracted `work/runtime`, not a fresh temp dir per launch.
- `Standby`, `StandbyWarmup`: warm launches (see "Warm launches").
- `HeapTrim`, `native/heaptrim.c` (`libheaptrim`): the Java heap's unused pages given back while an instance or a
  standby is idle (docs/perf-plan.md, "Memory, 2026-11-14"). The serial collector keeps every page it has touched:
  the 64 MB young generation is dirty for good after its first fill, and the old generation keeps what it held
  before a full collection. `HeapTrim.idle()` (Host.Memory; a young collection, made by filling eden with garbage,
  as the JVM has no call for one) and `trim(true)` (a full one: a standby once warmed up, memory pressure) arm the
  native side; while armed, JVMTI's GarbageCollectionFinish callback, which runs inside the collection's pause with
  every Java thread stopped, replaces the free part of eden, both survivor spaces and the old generation with fresh
  pages (`mmap` MAP_FIXED over the range, what HotSpot's own commit does; `madvise` MADV_DONTNEED on Linux), only
  above each space's top and only whole pages. `busy()` (Host's next frame or input, a standby's hand-off) disarms
  it, so a running app's collections cost what they did. The spaces are found through libjvm's own structure table
  (`gHotSpotVMStructs`, by name: Universe::_collectedHeap, SerialHeap's generations, their ContiguousSpaces' bottom,
  end and top), and Java arms only if what was read adds up to `Runtime.totalMemory()` and UseSerialGC is on: any
  other JDK layout or collector leaves it off. Never discard at any other time: outside a collection's pause a
  thread may be allocating there. `-Dapksim.heapTrim=false` (cold launches) or `APKSIM_HEAP_TRIM=0` (a standby
  boots before a launch's options exist) turn it off; `-Dapksim.heapTrim.log=true` prints each trim.
  `scripts/check-heap-trim.sh` (CI, `HeapTrimCheck`) runs it on a plain JVM with a small heap, twice (roomy and
  tight): the spaces must be found on the JDK in use (an unknown layout otherwise turns it off silently), a trim
  must return eden's pages, and checksummed objects must survive trims at every young and full collection while
  three threads allocate. Run it after any change to heaptrim.c and before moving to another JDK.
- `SimRunner`: points Robolectric at the APK. **FlatSandboxBuilder/FlatLoader** load the framework from the app
  class loader rather than Robolectric's instrumenting loader, so the JVM AOT cache can hold it. It also registers
  extra shadows. In running instances (not test runs) `android.os.Trace` is a device nobody traces
  (`untracedDevice`: ShadowTrace's tags 0, every call a no-op), set before every setup since Robolectric's reset,
  the standby's warmup included, turns its checks back on: ShadowTrace threw "Section is already running" for an
  async section begun twice, which ended a large app opening a screen. Test runs keep ShadowTrace as it is.
- `standby.c` (JNI) and `apksim-client.c` (the launcher's client): the warm-launch hand-off.
- `heaptrim.c` (Mac and Linux): `HeapTrim`'s native side, see above.

## Rules
- **After any Java change, run `scripts/train.sh`** or timings silently get worse (no AOT). The cache is tied to the
  folder path (`work/app.aot.root`), and the launcher warns if it was built elsewhere. An installed release's cache is also stamped with its release (`app.aot.release`, written by `train.sh`): a cache whose stamp differs from `work/release`, is missing, or is older than `work/apksim.jar` counts as another folder (launch without it, retrain in the background), so an install that keeps its path across upgrades does not pass the JVM a refused cache at every launch (issue 645).
- **Launch benchmarks after a build:** the first launch of each APK other than the test app is cold (no class
  cache). Run each APK once before `bench.py`, or compare against a baseline with the same cache state. Warm-launch
  timings swing with machine load (another VM, an emulator): compare interleaved A/B runs.
- **Memory figures need the machine's state beside them.** `footprint` counts compressed pages, malloc's share swung
  between 47 and 100 MB from run to run before `MallocSpaceEfficient`, and under memory pressure (`sysctl
  kern.memorystatus_vm_pressure_level` above 1) standbys quit and instances collect at once. Compare interleaved runs
  (`bench.py --mem`), and read an idle footprint after the 3 s idle trim, not at the first frame.
- Keep the young generation small (`-Xmn64m` in the launcher). 256 MB gave 12-50 ms GC pauses mid-scroll; 32-64 MB
  gives about 1 ms. Measured with `--jvm -Xlog:gc:file=...` while flinging and on the Shader tab.
- The path has no spaces now, but Robolectric breaks on spaces in its dependency dir, so the launcher links
  `work/lib` through `$TMPDIR/apksim-<hash>`.
