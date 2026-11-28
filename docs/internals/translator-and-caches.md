# App classes, the DEX translator, the APK and class caches, debugging app code

Agent reference notes, moved out of AGENTS.md so they are read only when needed. Quoted section names
("Video", "Warm launches") are sections of these files: AGENTS.md's index says which file has each.

## Code notes
- `AppClasses`: adds an `apksim:` URL to the JVM app class path. Class bytes are translated from DEX on demand.
  DEX files are mapped from the APK (`DexFile` reads a `ByteBuffer`), not copied onto the heap; compressed ones
  (APKs for older Android versions) are inflated once into the APK's cache entry's `dex/` (`ApkCache`) and mapped from there
  (inflating the test app's 36 MB took ~60 ms a launch). Classes are found by binary search of the DEX's sorted
  type_ids (`DexFile.findClassType`), not a map of every class name (~80 ms for 21k classes). `loadAhead` defines
  the classes the app loaded last time (no static initializers, so no app code) on 2 threads while the main thread
  starts the framework: ~100 ms off a launch.
- `ApkCache`: the per-APK folder in `STATE/cache` (manifest, extracted `.so` files, inflated DEX), named
  `<file name>-<16 hex>`: CRC32 and Adler32 of the APK's ZIP central directory and its length, so a copy, `touch` or
  re-download of the same build reuses it (keyed by mtime, every rebuild and CI run left another, ~370 MB for a large
  app, for good: issue 125). Taken on a thread of its own at the top of `Main` (~2 ms: SHA-256's provider lookup was
  ~60 ms of a cold JVM, CRC32C and String.format ~3 ms interpreted), under a shared lock on `cache/apk-cache.lock`;
  the process then holds a shared lock on `<entry>/in-use.lock` until it dies and touches `<entry>/last-used`.
  A sweep runs at most once an hour (`cache/apk-cache.swept`), 3 s after startup on a background thread, or from
  `Standby.halt` (before the client gets its exit code, up to 10 s: told first, the next launch could start while
  this process still held the data slot) when the launch ends sooner: Runtime.halt would kill the thread, and short
  test listings never swept. It lists and sizes entries without a lock (a launch's take
  waits for an exclusive holder), picks those not this process's and unused for 10 minutes (unused 14 days first,
  then least recently used until the rest fit in `-Dapksim.apkCacheMB`, default 2048), then under the global lock
  held exclusively checks each again (used since, or locked: kept) and moves it into `cache/.apk-cache-trash/`,
  emptied after the lock (no entry can be named that: an entry's name ends in -16 hex). Old
  mtime-named entries (no lock file, so one may be running unseen) go only once 14 days unused by their manifest's
  mtime, never for the cap. File locks, not PIDs, as containers sharing a cache
  volume repeat PIDs. `scripts/apk-cache-check.sh` checks it (CI: `ci-smoke.sh`).
- `ClassCache`: translated classes kept between launches in `work/cache/classes/<runtime hash>/<dex crc>.pack`.
  An entry is used only while the "is X an interface?" answers it was translated with still hold, and the
  hierarchy answers a retargeted super call used (the translator's only inputs from other classes). `<package>.classes` lists what the app loaded, so worker threads
  pre-translate the classes of changed DEX files once the app asks for its first class. The runtime hash covers
  every class in `apksim.jar`, so any rebuild starts with a cold cache. `--jvm -Dapksim.classCache=false` turns
  it off.
  A DEX file with a new CRC is not all new (issue 558): a rebuilt debug app changes nearly every file's checksum while
  ~99.7% of its classes are what they were, a string added anywhere moving every later index. Each saved class is also
  in `<package>.index` (content hash -> the pack holding it, entries whose pack is gone dropped at each save), by
  `dex/ClassHash`: a hash of the class's definition (header, system and runtime annotations, fields, methods, static
  values, source file name, code walked instruction by instruction) with every index operand resolved to the name it
  stands for. A class missing from its DEX file's pack is looked up there, and a hit is used and copied into the
  new pack, so the next launch takes the ordinary path (`saved N translated classes (M found by content ...)`).
  Anything the walk cannot read (invoke-custom, method handles, an unknown opcode) gives no hash: that class is keyed
  by its DEX file alone. `has(..., null)` asks the class's own pack only. Hashes of classes this process translated are worked out at the save (before anything leaves `fresh`; an exit's `flush` makes the saver let go at once) (idle saver thread; an exit gives them 1.5 s, the rest go unindexed; a test run (`--instrument`) hashes on one thread and gives the saver and the exit 150 ms each, issue 602), not on the translating thread, and the load-ahead workers do the content lookup in parallel, not the planner serially (issue 571). Known miss: const-string/jumbo against const-string (a string index crossing 65,535 between
  builds) changes the instruction's size and so the offsets after it, so that class hashes differently. The hash is worked out outside the cache's lock, and
  only once the index has an entry. The index is not age-swept (each save drops entries whose pack is gone); two
  processes saving at once keep the last writer's (missed reuse, never a wrong class); a beamed instance's content lookup also reads, read only,
  the caches of up to three earlier builds of the same package from the same site and signing key
  (`-Dapksim.beamSiblingCaches`, `Serve.beamSiblings`, issue 578; `scripts/check-class-cache-siblings.sh`, CI). Issue 583:
  a build whose own cache holds its `identity` and a pack gets no siblings (its signers, a read of the whole APK, are
  not needed); a new build's first launch takes the newest sibling's load hints (so lookups run on the ahead workers);
  the siblings' indexes are read on a thread of their own as the cache opens; and a save keeps index entries whose pack
  is in the seed (a beamed launch's index named only the packs it rewrote, and the agent moves it over the build's).
  Measured through a real agent on two debug builds of a large app (socket to first frame): a new build 4.3-4.5 s
  before, 3.5-3.6 s after (5.6 s with no earlier build), the same build again 3.4-3.6 s before, 2.4 s after. Off with
  debug info or coverage, and `-Dapksim.classContent=false`. `scripts/class-hash-check.java OLD.apk NEW.apk` hashes and
  translates every class of two builds: equal hashes must translate to equal bytes (exit 1 otherwise) and it counts
  the classes that translate alike but hash apart. It saves once translating pauses for 3 s, and again on the way out (`quit`, closing the window, Cmd+Q,
  `--exit-after-first-frame`), so `scripts/train.sh` leaves the test app's cache warm.
- `dex/`: the **DEX→JVM translator** (written from scratch; dex2jar miscompiled code and took 30 s). One Dalvik
  register is one JVM local; union-find type inference over values. A handler sees the registers before an
  instruction that can throw (returns cannot), and only there: merging more (a handler's live registers at every
  block of its range, or at a return) typed ints and floats as references and dropped them
  (`-Dapksim.debugTypes=owner/Class.method` reports such a merge). Methods over 64 KB are split automatically
  (`emitSplit`). It also rewrites app calls: `System.loadLibrary` → `Natives`, `new RuntimeShader` →
  `TrackedRuntimeShader`, `Canvas.draw*(…Paint)` of shapes and paths → `GpuCanvas`, `RenderEffect.create*` → `GpuEffects`,
  `new RenderNode` → `GpuRenderNode`, wall-clock reads (`System.currentTimeMillis`, java.time `X.now()`,
  `Clock.system*`, `new Date()`, `Calendar.getInstance`) → `SimClock`, and Compose's `CursorAnimationState(animate)`
  through `Snapshot.cursorBlink`. `Peephole` then shrinks each method (not with `--debug`): a parameter copy
  is read from the parameter, and an adjacent `xstore n; xload n` with n dead after (liveness, handlers included)
  goes, so a getter is `aload_0 getfield ireturn` rather than 7 bytecodes. ~15% fewer bytecodes, ~4% off a warm
  launch; `-Dapksim.peephole=false` for A/B (its own class cache). The translator runs on every class a launch
  after a rebuild asks for, so its data is flat (2026-11-03, from a JFR profile of `scripts/translate-all.java`):
  Peephole and liveness work per basic block over `long` words, labels exist only at block starts (every
  instruction only with debug info), DEX references sit in arrays by index shared across threads' views. That
  halved translation time and allocation; a translator change must leave `translate-all.java`'s output identical
  unless it means to change the bytecode.

## Debugging app code
`./apksim --debug 5005 --control 7400 app.apk`, then IntelliJ/Android Studio "Remote JVM Debug" (Attach, localhost,
port 5005; the app does not wait for it). Breakpoints in app sources (`MainActivity.kt`, Java or Kotlin) stop, step
and show locals. `--debug` adds the JDWP agent and `-Dapksim.debugInfo=true`, which makes the translator read each
method's DEX `debug_info_item` (`MethodTranslator.readDebugInfo`) into a `LineNumberTable` and `LocalVariableTable`,
plus Kotlin's SMAP (`SourceDebugExtension`, for inlined code). A local's entry covers only instructions where its
register's JVM local holds the declared kind (`LocalRanges`), so a debugger never reads an int slot as a reference.
`this` is slot 0; parameters are the copies in Dalvik's registers, so JDI lists them as locals, not arguments.
Split methods (`emitSplit`) get line numbers but no locals (their registers live in arrays). Without `--debug`,
classes are byte-identical to before: debug info cost ~30 ms of a warm launch (bigger classes to load; R8 builds
such as the owner's app carry a line entry per instruction) and ~5% of a cold one. Debug classes have their own class
cache (`work/cache/classes/<hash>-debug`), so the first `--debug` launch after a build translates everything.
Checked with a JDI client (what IntelliJ uses): `count++` at MainActivity.kt:63 stops with `$count$delegate`.
R8-minified apps have pc-encoded lines (retrace them with the app's mapping file) and no locals.

## Rules
- **Debugging a test run or app code without sources:** start with `--jvm
  "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=PORT"` and attach `scripts/jdi/Trace.java` (which
  methods run, with arguments) or `Ret.java` (what they return). Suspect the translator when a value is impossible:
  `scripts/translate-class.java` and javap show `aconst_null`/`if_acmpne` where Dalvik had ints.
