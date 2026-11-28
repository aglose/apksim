# Robolectric: apksim's copies of its classes, call sites, the gap tripwire, resources

Agent reference notes, moved out of AGENTS.md so they are read only when needed. A section another doc or a
code comment cites as `AGENTS.md "Name"` is in one of these files: AGENTS.md's index says which.

## Code notes
- `org.robolectric.internal.bytecode.InvokeDynamicSupport` and `ShadowInvalidator` (apksim's copies, ahead of
  Robolectric's on the class path): every framework call site is bound straight to its shadow or original, without
  Robolectric's SwitchPoint guard, fallback and per-site stack-trace cleaning (a quarter of a cold launch in method
  handle chains, ~35-50 MB). An invalidation resets that class's sites to bind again at their next call. Traces are
  cleaned where apksim reports them (`StackTraces`: crashes, test failures, bundles); one the app prints itself shows
  `$$robo$$` frames. `-Dapksim.directCallSites=false` is Robolectric's binding (docs/perf-plan.md, B1).
  `scripts/check-call-sites.sh` (CI) checks re-binding and a binding racing an invalidation (`CallSiteCheck`).
  The gap tripwire (`Gaps`): a site bound with no shadow whose method is native (`bindCallSite`: Robolectric's
  handler found none and `isNative`) runs the instrumenter's default body (0/false/null), the class of bug behind
  issue 514 (Visualizer). Only those sites get a counting handle in front (`foldArguments`, one `AtomicLong` per
  "Class.method(desc)"); every other site is bound as before. `gaps` (control, `adb shell apksim gaps`, `executeShellCommand("apksim gaps")`)
  lists them, most called first, one per line (joined `; `); `Exits.log` (every exit, crashes too) adds `[apksim] gaps: N unshadowed
  framework natives called: A xN, ... (apksim gaps)` when N > 0; `AppInfo.load` resets the counts per launch.
  The baseline (`runtime/src/main/resources/dev/apksim/gaps-baseline.txt`, sorted `Class.method(desc)`): 47 natives
  that are harmless as defaults (Trace, Binder, Parcel fds, Perfetto, VMRuntime, SurfaceControl, MotionEvent, input
  channels, finalizers), taken from the test app's session over the bottom tabs and ~30 `apksim://` screens
  (`--camera fake`, warm and cold: the same set). They are bound as if not gaps, with no counter (`Trace.nativeIsTagEnabled`
  was ~5,000 counted calls), parsed by `Gaps.preload` (standby warm-up, Main's ahead thread). `gaps` lists the others and
  ends "(N baseline natives not counted)"; the exit line shows only those. Each entry was looked at: a getter whose default
  could mislead stays out. `OsConstants.initConstants` is in: Robolectric's `ShadowOsConstants` sets the constants itself
  (read in a running instance: ENOENT=2, EACCES=13, O_RDONLY=0, S_IFMT=61440). `CameraMetadataNative.nativeGetBufferSize`
  (0; its only caller is the object's native-allocation accounting, where 0 is harmless) is left out for now, so `gaps`
  shows it once the camera screen is used; a plain test-app session shows none. The tripwire's first find, `Os.chmod`
  (a no-op that the test app's launch calls), has a shadow now (`LocalSockets.ShadowSimLinux`: the mode's owner rwx
  bits to the host file, its group/other bits left to the host: they belong to the host's users and tools reading the slot,
  and ContextImpl's 0771 applied whole made a root container's slot unlistable for CI's runner user; ENOENT for a
  missing file; `CompatChangeTest.chmodAppliesTheMode`).
  Counts reset per launch (`AppInfo.load`) and per test (`SimRunner.resetState`; `CompatChangeTest.gapsCommandAnswers`).
  `ci-smoke.sh` (app part) prints `gaps` and warns about anything beyond the baseline and that one (`GAPS_CHECK=strict`
  fails; checked on the Mac only). `-Dapksim.gaps=false` (cold launches: a standby has read it) binds them all plainly;
  `CallSiteCheck` covers counting, the baseline and both bindings.
- `Gaps`, `gaps-baseline.txt`: the gap tripwire, framework natives no shadow covers that were called (described under `InvokeDynamicSupport` below; `gaps` command, exit line).
- `org.robolectric.android.controller.ActivityController` (apksim's copy of Robolectric 4.17's, ahead of it on the
  class path): a configuration change that recreates an activity no longer calls `System.gc()` in a running
  instance. Robolectric collects there after destroying the old activity (its activities share one base context, and
  it wants AppCompat's weak per-context caches flushed so tests do not depend on when a collection last ran): a full
  stop-the-world collection inside every rotation, appearance, locale or font-scale change, for each activity of the
  stack that is recreated, shown or not; ~60 ms on the test app's heap, 180-250 ms with 500 MB live (JFR's
  jdk.SystemGC gave the stack), where a device collects nothing. Test runs keep it; `-Dapksim.recreateGc=true|false`
  overrides either way (launches cold). The test app's `apksim://appcompat` (`AppCompatCheckActivity`: a
  night-qualified colour and vector read through AppCompatResources with the activity's and the application's
  context, "appcompat: activity color=#.. vector=#.., app color=#.. vector=#.., instances N") shows the same values
  with and without the collection across appearance switches and rotations; `ci-smoke.sh` checks it. The trade is
  deliberate: a destroyed activity's native-backed objects (Bitmap pixels, renderers) now wait for the next
  collection that reaches them, or Host.Memory's idle one, instead of going at the recreation.
- `org.robolectric.res.android.CppAssetManager2` (apksim's copy of Robolectric 4.17's, ahead of it on the class
  path) and `ResourceHits`: two hook lines (`GetResource`, and `GetBag(int, List)` before its cache) record every
  resource id the framework resolves, for the `deps resources` command and `crawl.py --record-deps`. Off unless
  `-Dapksim.deps=true` in a running instance (read at start and again by `Probes.reload` as a standby takes the launch, so `--record-deps --standby` is warm; test runs never). Re-copy the file and put the two
  lines back at every Robolectric upgrade.
- `MappedResources`, `org.robolectric.res.android.CppApkAssets` and `ResourceString` (apksim's copies of
  Robolectric 4.17's, ahead of them on the class path): a `resources.arsc` is a mapped file, not a `byte[]` on the
  heap. Robolectric inflates the framework's table (16.7 MB, the largest object of an idle instance's heap) and
  reads the app's own into arrays in every process; `CppApkAssets.LoadImpl`, the one place a table becomes a buffer,
  now asks `MappedResources.table` first. A stored table (an APK's) is mapped where it lies in the APK
  (`ZipArchiveHandle.dataOffset`), as AppClasses maps DEX files; a deflated one is inflated once into a file named
  by the entry's CRC and length and mapped from there: the framework's in `STATE/cache/framework/` (a standby has no
  APK), another APK's in its cache entry (`arsc/`, swept with it). The buffer is read-only and direct, so the copy
  of `ResourceString.decodeString`, the only reader that asked a buffer for its array, copies a string's bytes out
  first when there is none. Anything that fails answers null and the table is read as before; a beamed instance may
  read `cache/framework` (the launcher's profile) and never writes it, so it maps the table once any other launch
  or training has left it; the Linux image keeps the file from its training (a volume mounted at its `work/cache`
  hides it, and the first run inflates it into the volume). A table inflated for a cache folder that cannot be
  written serves that launch from the heap. Old framework tables, one per framework jar ever used, are not
  removed. `-Dapksim.mappedResources=false`, or `APKSIM_MAPPED_RESOURCES=0` in the environment, reads tables into
  arrays again (A/B; a standby keeps the environment it was started with, so `--stop-standbys` between runs).
  `scripts/check-mapped-resources.sh [APK...]` (CI, `MappedResourcesCheck`) decodes every string of the framework's
  table and the APKs' both ways and compares them, with a deflated copy of the first APK's table.
  docs/perf-plan.md, "Resources mapped", has the measurements.
