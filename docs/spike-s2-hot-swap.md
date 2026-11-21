# Spike S2: hot swap of a rebuilt APK into a running instance

Question: when the app is rebuilt, can apksim swap the changed classes into the running instance (on its own JDK 24
HotSpot) instead of relaunching it, and how often can it?

Answer: yes for method-body edits, which is most one-line UI edits. A changed string, colour or click handler in a
composable or in plain Views reaches the screen 150-290 ms after the command, with the app's state kept. An edit that
adds a lambda or a function falls back to a relaunch, and in Kotlin that is a common edit. Every redefinition needs
stack map frames: JDK 24 verifies redefined classes even though apksim loads its classes unverified. ASM computes the
frames, and 99.6% of the loaded translated classes pass the verifier with them.

Everything here is a prototype and opt-in: the `hotswap` control command does nothing unless it is sent, and no default
changed.

## What was built

- `native/hotswap.c` (`libhotswap.dylib`, Mac only): JVMTI `RedefineClasses` from inside the JVM, with no agent and no
  `-javaagent`. It asks for `can_redefine_classes` in the live phase at the first swap, which HotSpot allows, so warm
  launches (standbys) work too. A launch that never swaps never asks for the capability. Once HotSpot has it, the JIT
  keeps the dependencies redefinition needs; that cost was not measured.
- `HotSwap.java`, which runs the control command `hotswap NEW.apk [none|redraw|compose|compose-reset|recreate] [force]`:
  1. Opens the new APK (`AppClasses.isolated`) and compares the two builds. A DEX file the old build has an identical
     copy of (same checksum and size) is skipped. In the other files, each class's `ClassHash` (index-independent) is
     compared with the old build's. A debug build of the test app has 9 DEX files, and a one-line edit changes only one
     of them: the app module's, 0.9 MB of 37 MB.
  2. For each changed class that is loaded: translates it from the new APK with the same translator, and compares it
     with the current translation. Classes identical once translated are skipped (`ClassHash` covers some things the
     translation drops). The rest go through a shape check, the one
     `JvmtiRedefineClasses::compare_and_normalize_class_versions` makes: same superclass, interfaces, class modifiers,
     fields, methods with their modifiers, and nest attributes. Loaded classes that came from a jar on apksim's own
     class path rather than from the APK (Robolectric's Guava, for example) are left alone.
  3. Any loaded class whose shape changed means nothing is swapped. The answer reads `RELAUNCH NEEDED` and names each
     class and the reason. Otherwise every compatible class is redefined in one `RedefineClasses` call, after ASM has
     computed its frames (below).
  4. Changed classes that are not loaded yet, and added classes, are served from the new APK from then on
     (`AppClasses.swapped`, checked before the old APK in `open`). This was checked: `SecondActivity` changed while
     not loaded, then opened, showed the new label.
  5. Refresh, so the screen shows the new code:
     - `none` and `redraw`: the new code runs only the next time something calls it.
     - `recreate`: the control command of that name (configuration-change recreation: saved state and ViewModels are
       kept).
     - `compose`: every composition each running `Recomposer` knows, subcompositions included, gets
       `CompositionImpl.invalidateAll()`, found by reflection. State is kept.
     - `compose-reset`: Compose's own `HotReloader` save-and-dispose and load-and-compose, the path Live Edit takes.
       State is lost, `rememberSaveable` included.
  6. Answers one line with the counts, what fell back and why, and the time each step took.
- `hotswap selftest [PREFIX]`: redefines each loaded APK class, one at a time, with its own code plus computed frames,
  and counts what the verifier refuses. The code is the same, so the app runs on unchanged.
- `java -cp work/apksim.jar:work/lib/* dev.apksim.HotSwap OLD.apk NEW.apk`: the same comparison offline, with each
  changed class marked as body-only or with its shape change.
- `scripts/spike-s2/`: `builds.sh` makes the edited builds, `swap.py` sends a swap and polls `nodes` until a text
  shows, and `scen3.sh` runs the Compose refresh comparison.

## Stack map frames: the expected problem, and its fix

apksim loads translated classes with no `StackMapTable` and `-XX:-BytecodeVerificationRemote`, so they are never
verified. `RedefineClasses` on JDK 24 verifies the new bytes anyway. The first swap failed with
`JVMTI_ERROR_FAILS_VERIFICATION`, and `-Xlog:verification` showed "Verifying class ... with new format": the split
verifier, which needs frames. `-XX:-BytecodeVerificationRemote` does not reach this path, and nothing else at run time
turns it off. (`-Xverify:none` is deprecated and was not tried.) This is the same wall Mockito's inline mock maker hits.

Fix: `ClassWriter(COMPUTE_FRAMES)` on the translated bytes (`HotSwap.withFrames`), resolving supertypes through the
app's class loader. It takes a few ms per class. Measured with `hotswap selftest` on the test app after the Home and
Trips tabs had been visited:

| loaded APK classes | accepted | VerifyError | frames could not be computed | from a jar, skipped |
|---|---|---|---|---|
| 4,031 | 4,016 (99.6%) | 14 | 1 | 131 |

- The app's own package: 62 of 62 accepted.
- The 14 refused are all libraries: Compose's `Recomposer` and `AndroidComposeView`, Room, kotlinx.coroutines,
  savedstate, tracing. Two kinds of `VerifyError`, both from code the translator relies on never being verified:
  - "Bad type on operand stack: Object is not assignable to String" (`Recreator.reflectiveNew`): a register the
    translator types as `Object` at a merge, passed where a `String` is wanted, with no `checkcast`.
  - "Bad local variable type: top at locals[1]" (`Trace.isEnabledFallback`, in a handler): a register that is not
    assigned on every path into a handler that reads it. Dalvik's verifier accepts that; the JVM's does not.
- The frames failure is `TypeNotPresentException`: a supertype that is not on the class path (BouncyCastle, from an
  OkHttp adapter).

Neither kind affects app edits so far. A product should fix them in the translator, since they are latent bugs if
verification is ever turned on: a `checkcast` where a merged reference meets a typed use, and zero or null stores at
method entry for registers a handler reads. Until then, such a class falls back to a relaunch.

## Measurements

Mac (Apple Silicon), headless, worktree build with no AOT cache (`train.sh` not run), warm launches. Test app debug
build: 34,066 classes in 9 DEX files. Each scenario starts from a fresh instance of build A. "On screen" is the time
from sending the command until `nodes` (the accessibility tree, read after layout) shows the new text, polled every
~5 ms. In every run it was found on the first poll after the answer, so the frame came within one loop turn.

| edit (build) | refresh | changed classes (loaded / redefined) | command answered | on screen | state |
|---|---|---|---|---|---|
| plain View label `"Stack $depth"` → `"Level $depth"` (B), on `SecondActivity` | recreate | 1 / 1 | 185 ms | 194 ms | tap count kept (saved state) |
| same | none | 1 / 1 | 151 ms | after the next tap | - |
| Compose string `"FAB clicks:"` → `"FAB taps:"` (C) | compose (invalidateAll) | 6 / 4 | 154, 197, 169 ms | 192, 236, 200 ms | count 2 kept |
| same | compose-reset (HotReloader) | 6 / 4 | 188, 167, 164 ms | 241, 211, 209 ms | **count reset to 0** |
| same | recreate | 6 / 4 | 250, 196, 197 ms | 291, 235, 236 ms | count kept |
| then D (an extra `Text` in the same item), then C again, same instance | each mode | 6 / 4 | 121-141 ms | 151-173 ms | as above |
| click lambda body `count++` → `count += 2` (F) | none | 8 / 4 | 209 ms | next tap adds 2 | count kept |
| a new `item { Text(..) }` (E) | - | 10 + 1 added | 154 ms | RELAUNCH NEEDED | - |
| a new `@Composable` function (G) | - | 17 + 1 added | 216 ms | RELAUNCH NEEDED | - |

Where the time goes, from the command's own report for the Compose case:
- Opening the APK and comparing the changed DEX file: 92-170 ms, usually 105-130 ms. Offline, the comparison alone
  takes ~134 ms on a cold JVM; hashing all 34k classes would be 4.3 s.
- Translation, shape check and frames: 7-23 ms.
- `RedefineClasses`: 12-18 ms.
- Refresh: `invalidateAll` 3-9 ms, `recreate` 32-49 ms.

What's left of the ~150-240 ms is the client's round trips and Host's next frame. The first swap of an instance is
~40 ms slower (cold JIT in the swap path).

For comparison, not measured here:
- A relaunch: AGENTS.md has a warm launch's first frame at ~0.22 s for the test app and ~0.36 s for the owner's R8
  app, with the AOT cache. This worktree had none, so its launch times would not be representative. A relaunch also
  loses in-memory state (`remember`, scroll, navigation not in saved state) and needs the screen navigated again.
- The Gradle side: an incremental `:app:assembleDebug` for a one-string edit took 1.4-1.8 s here (warm daemon). A
  build cache hit, such as reverting an edit, took 0.7 s. So in the edit-to-screen loop the build dominates, and the
  swap adds ~0.2 s.

## Fallbacks seen, from the test app's debug builds

Kotlin 2 with D8 (no R8). What each edit changed in the DEX:

1. **Body only, swappable**: the string or colour in a composable (C), an extra call inside an existing lambda (D), a
   lambda's body (F), a plain View's code (B). One edit touches several classes:
   - The composable's own class: `MainActivityKt` (the static methods D8 made from the lambdas), `ComposableSingletons$MainActivityKt`.
   - Lambda classes such as `MainActivityKt$Root$5$1$2$2$1$2$1`.
   - Inline-expanded classes such as `MainActivityKt$Root$lambda$11$0$1$1$0$$inlined$items$default$4`.
   - **Every composable later in the same file**, even with the line count unchanged. The Compose compiler's
     `sourceInformation` strings hold character offsets: in C, `MainActivityKt$Trips$1$1$1$2$1` changed only from
     `"C165@8675L11:MainActivity.kt#w65oax"` to `"C165@8673L11..."`. These are still body changes, and swap fine.
2. **New synthetic lambda, relaunch** (E, and part of G). D8 numbers a class's synthetic lambdas
   (`$$ExternalSyntheticLambdaN`) in order, so adding one renumbers those after it. The old
   `ComposableSingletons$MainActivityKt$$ExternalSyntheticLambda2` turns from a `Function2` with
   `invoke(Object,Object)` into a `Function3` (a hierarchy change), `...Lambda3` is a new class, and
   `ComposableSingletons$MainActivityKt` gains a field and two methods for the new composable lambda. In G, eight of
   `MainActivityKt$$ExternalSyntheticLambda19..27` changed interfaces, fields and constructors.
3. **New function, relaunch** (G): `MainActivityKt` gains `CountLabel(ILComposer;I)V` and its
   `CountLabel$lambda$0`. HotSpot refuses added methods (`UNSUPPORTED_REDEFINITION_METHOD_ADDED`;
   `-XX:+AllowRedefinitionToAddDeleteMethods` would allow private ones, but it is deprecated and was not tried).
4. **False positives, harmless**: `Db` and one lambda class differ by `ClassHash` but not once translated (E, G). That
   is likely line mapping of inlined code, which shifts when the file grows a line. They are skipped.
5. **Verifier refusals**: none among app edits here. In libraries, 15 of 4,031 classes (above). A library edit in a
   multi-module app would meet them.
6. **Not the APK's class**: 131 loaded classes come from a jar on apksim's class path before the APK's copy (Guava,
   for example). A change to them in the APK has no effect, in a running instance or a relaunch, so they are
   reported, not swapped.

Not tried: field or constant changes, which would be schema changes (except where Kotlin inlines a `const val` at its
use sites, a body change there); a new class used from changed code (the loader would serve it, but no edit here
produced a swappable one); R8 builds (renaming makes almost every edit reshuffle names, so expect relaunches); the
owner's app; Linux (`hotswap.c` is not in the Linux build).

So of six one-line edits, four swapped and two needed a relaunch. Adding a lambda or a composable call with a trailing
lambda is a frequent Compose edit, so expect a relaunch for a good share of edits unless the renumbering is handled.

## What a product implementation needs

1. **The decision ladder**: swap when every loaded changed class is body-only; then refresh with Compose
   `invalidateAll` for a composition, else `recreate` for an activity whose `onCreate` code changed (a plain View edit
   is not seen until that code runs again); else relaunch. Never swap part of a build: the prototype refuses the whole
   swap if one loaded class cannot go (`force` overrides this, for diagnosis only). Choose the refresh from what
   changed: a class reachable only from `onCreate` needs `recreate`, a Compose class needs `invalidateAll`; doing
   both is safe and costs ~40 ms.
2. **Synthetic lambda renumbering**, which would turn the largest class of fallbacks into swaps. Match old and new
   `$$ExternalSyntheticLambdaN` and Kotlin lambda classes by content (interface, captured fields, the target they
   call), not by name. Then redefine the old names with the matching new bodies, and serve the new names to call
   sites. Call sites in redefined bodies name classes, so the swap would rewrite those references to the matched old
   names. This is how Live Edit works around the same D8 behaviour. Without it, expect relaunches.
3. **Frames**: keep `COMPUTE_FRAMES` for redefinitions. Its `getCommonSuperClass` loads classes, which may load an old
   version of a class the swap is about to serve new. Register `swapped` before computing frames, or resolve supertypes
   from the DEX (`Hierarchy`) without loading. Fix the translator's two unverifiable shapes (above) so library classes
   can be swapped too.
4. **Comparing builds faster**: keep each DEX file's per-class hashes from the last swap, so only the new build is
   hashed (about half of today's ~120 ms). Watch for the APK file being written while it is opened: copy it first or
   wait for the Gradle task to finish. Map the new APK once and keep it (the prototype keeps the latest one for classes
   served later; earlier ones should be closed).
5. **The class cache and hints**: classes served from a swapped build are not recorded in `ClassCache`, and the next
   launch, which is cold for the new APK anyway, translates them again. Nothing is stale, but a product could put the
   new translations in the cache for the next launch.
6. **The JIT**: `RedefineClasses` deoptimizes dependent compiled code. Redefining 4,031 classes one by one took 48 s
   (~12 ms each, mostly the safepoint), and one batch of 4 took 12-18 ms. Batch always. The JIT cost of holding
   `can_redefine_classes` after the first swap was not measured: compare `frames`/`fps` before and after a swap.
7. **Linux**: add `hotswap.c` to `native/linux/` (the JVMTI code is portable) for the image.
8. **What it cannot see**: resources (layouts, strings.xml, drawables) are not classes. A resource-only edit changes
   `resources.arsc`/`res/`, not DEX, and needs a resource reload (a new `AssetManager` and `recreate`) or a relaunch.
   This spike did not look at it. Manifest changes always need a relaunch.
