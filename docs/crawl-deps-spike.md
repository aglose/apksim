# Spike: which sources each crawled screen depends on (2026-11-21)

Question from Greenroom: for each screen of a crawl (`scripts/crawl.py`), which of the app's source files and
resources does it depend on, so that a later commit's changed files pick only the screens worth walking again?

**Answer: yes for both, with a prototype on this branch.** A running instance launched with
`--jvm -Dapksim.deps=true` answers `deps read` (app classes whose code ran) and `deps resources` (resources resolved,
as `type/name`) for the span since the last `restart` or `deps reset`. Overhead is within run-to-run noise.

## What was tried

**Classes (MS1).** The test-impact probes already exist (`dex/Probes`, docs/affected-tests.md): with
`-Dapksim.deps=true` every method of a class in the probed packages (`-Dapksim.deps.packages`, default the app
package's first two parts) starts with two static reads, and the first run of a class in a span adds its name to a set
and stores the span's epoch in the class. `Probes.mark()` returns the set and moves the epoch on. Until now only
`Instrument`'s listener called it, per test. The prototype adds:

- `Probes.command`, reached from `DeviceControls` as the control command `deps`: `deps` (on/off, packages, epoch),
  `deps reset`, `deps read` (the set, not reset), `deps mark` (both at once), `deps resources [all]`. One line each,
  as every control answer: `deps N: a.B,a.C`.
- `Host.restart` opens a new span after the old stack is destroyed and before the new start activity is made. Without
  that, the previous screen's `onStop`/`onDestroy` landed in the next screen's set (the first run showed
  `SecondActivity` in the AppCompat screen's set and `PlayerViewActivity` in the drawer's). The crawler's `reach()`
  always begins with `restart`, so the span is exactly the screen's path replay plus its settling.

**Resources (MS2).** Every resource lookup of the framework passes through Robolectric's `CppAssetManager2`, which is
a plain library class (not instrumented, so no shadow), loaded from the app class loader. Two of its methods are the
places a resource is *resolved* rather than only named: `GetResource` (every `getValue`/`getString`/`getDrawable`/
`getLayout`/`openRawResource`, and each reference `ResolveReference` follows, so `@color/x` inside a style or a layout
attribute) and `GetBag(int, List)` (styles, themes, and each parent style). The prototype is apksim's copy of
Robolectric 4.17's file (the same way `CppApkAssets` is copied, ahead on the class path) with one line in each:
`if (ResourceHits.ON) ResourceHits.hit(resid)`, before `GetBag`'s cache, so a cached bag still counts. `ResourceHits`
keeps an open-addressed int set under one lock; names are looked up only when read
(`Resources.getResourceTypeName/EntryName`), and `GetResourceName` is deliberately not hooked, as apksim's own `nodes`
and `uiautomator dump` name view ids through it. Framework resources (`0x01......`) are left out unless `all`.
Compose's `painterResource` and `stringResource`, layout inflation (`<include>` too), theme attributes resolved
through `obtainStyledAttributes`, and drawables referenced from other drawables all came through.

## Results on the test app

Seven screens through `restart URL` + `settle`, read after each (`/tmp` script, not committed; per screen classes in
the app's package, resources in the app's packages and its libraries):

| Screen | Classes | Source files (affected.py's map) | Resources |
| --- | --- | --- | --- |
| app start (before the first screen) | 44 | `ComposeApp.kt`, `StartupProvider.kt`, `OnceOnlyProvider.kt`, `SlotStateActivity.kt`, `NetworkActivity.kt` (receivers it registers) + the home screen's | 12: `style/Theme.ComposeApp`, `string/app_name`, `mipmap/ic_launcher`, WorkManager's bools... |
| Home (`composeapp/home`) | 28 | `MainActivity.kt` | `style/Theme.ComposeApp` |
| Trips | 33 | `MainActivity.kt` (the `Trips` composable's lambdas) | + `string/default_error_message` |
| Settings | 59 | `MainActivity.kt`, `Settings.kt` | `style/Theme.ComposeApp` |
| `apksim://stack` (Views) | 8 | `SecondActivity.kt`, `Settings.kt` (see below) | 1 |
| `apksim://appcompat` | 2 | `AppCompatCheckActivity.kt` | 114: `color/appcompat_check_mode`, `drawable/appcompat_check_square`, AppCompat's styles, layouts, dimens |
| `apksim://player` | 2 | `PlayerViewActivity.kt` | 168: `layout/player_view_surface`, `raw/video1080`, Media3's `layout/exo_*`, `drawable/exo_*`, `style/ExoStyledControls.*` |
| `apksim://drawer` | 2 | `DrawerActivity.kt` | `dimen/def_drawer_elevation`, `style/Theme.ComposeApp` |

The sets differ as the sources say: Trips adds only the `Trips` composable's classes, Settings adds `Settings.kt`, each
Views screen is its own activity's file, and each screen's resources are the ones its file references (the AppCompat
check's colour and vector, the player's layout and clip). `Settings.kt` in the stack screen's set is a callback object
of the Settings screen (`DeviceFeatures$8$1`) that ran after the restart: code that outlives its screen is attributed to
the next one, which only makes that screen walk again more often, never less.

**Start versus screen.** The start set is everything before the first activity: the Application, content providers
(AndroidX Startup, DI graphs built in `onCreate`), receivers. Its classes not in the start screen's set (16 here:
`ComposeApp`, both providers, `GreetingClient`, ...) are start-only. So a span "app start" (launch to the first
activity) should be recorded once per walk and any change to its files means every screen (they all run after it).
Subtracting the start set from each screen is wrong for screens that share classes with the start screen: Home's
28 classes are all in the start set, and Home does depend on `MainActivity.kt`. Keep the per-screen sets whole.

**Cost.** Four interleaved pairs of cold launches (`APKSIM_STANDBY=0`; probes need a cold launch today) on a busy Mac
(load 8-15), class caches warm for both, player screen left out (its looping video sometimes does not settle, with or
without probes):

| | first frame, median (runs) | six-screen walk, median (runs) |
| --- | --- | --- |
| off | 1876 ms (1874, 1780, 1877, 2861) | 1.84 s (1.82, 1.90, 1.85, 1.83) |
| `-Dapksim.deps=true` | 1725 ms (1724, 1819, 1725, 1616) | 1.92 s (1.94, 1.90, 1.84, 2.22) |

No difference above noise at launch; the walk is ~4% slower at the median, inside the spread. The commands
themselves: `deps read` 1.0 ms, `deps resources` 2.1 ms (168 names, 5.5 KB), `deps resources all` 5.6 ms (21 KB),
`deps reset` 0.2 ms. The real cost is elsewhere: probed classes have their own class cache (`Probes.cacheKey`), so the
first probed launch of a build translates every class (as after a rebuild: ~1.15 s more on a warm launch of the test app, AGENTS.md), and a probed launch cannot be
handed to a standby (the property is read as the JVM boots).

## Proposed design

Control commands (as prototyped): `deps reset`, `deps read`, `deps resources [all]`; `restart` starts a span by itself
when recording is on. A launch option `--deps` (rather than `--jvm -Dapksim.deps=true`), allowed by serve and mcp, and
standbys keyed by it as they are by `--no-network`, so recorded walks stay warm.

`crawl.json`:

```json
"deps": {"packages": "dev/apksim/composeapp/", "apk": "SHA-256",
         "start": {"classes": [...], "resources": [...]}},
"screens": {"S3": {..., "deps": {"classes": [...], "resources": ["layout/trip_row", "string/title"]}}}
```

Classes, not files, are stored: the class → file map is the APK's (`affected.source_map`: DEX source file, package
and Kotlin's SMAP), worked out by the caller, so the crawl needs no repository. Resources map to files by
`res/<type>[-qualifiers]/<name>.*` for file types (layout, drawable, mipmap, raw, xml, anim, font, menu, navigation,
color with a file) and, for value types (string, color, dimen, style, bool, integer, array, plurals), every
`res/values*/*.xml` that defines `name="..."` (every qualifier variant: the hit says which entry, not which
configuration won). Selection: a screen walks again when a changed file maps to one of its classes or resources, or to
the start set, or cannot be mapped at all.

## What a changed file cannot be mapped through (fall back to a full walk)

- **Build and packaging:** `AndroidManifest.xml`, `build.gradle(.kts)`, `settings.gradle`, `gradle.properties`, version
  catalogs, ProGuard/R8 rules, dependency versions. Library code is never probed, and library resources map to no app
  file: a dependency change is a full walk.
- **`assets/`:** `AssetManager.open` is not recorded (only `res/raw` through `openRawResource` is). Hookable later in
  the asset-manager shadow; not done.
- **Generated code:** Hilt/Dagger factories, Room DAOs, KSP/KAPT output, `BuildConfig`, `R`, view and data binding.
  They are probed (they are in the app package) but their source file is under `build/`, and the file the developer
  changed (an annotated class) may not run on the screen. Map `build/generated` hits to "full walk", or to the
  annotated class by name convention.
- **Code that does not run:** constants inlined at compile time (`const val`, Java `static final` primitives and
  Strings) are copied into the reading class, so their own file never shows; a Java class whose static fields are read
  but whose code ran only in an earlier span (`<clinit>` runs once a process); interfaces with no default methods.
  A file of only constants or type declarations should select everything that its package's screens use, or a full walk.
- **R8 builds:** without a mapping file the class names are renamed, merged and inlined, and source file names are
  `SourceFile` (`affected.source_map` then gives no key). Crawl a debug build for maps, or retrace with `mapping.txt`
  (class merging and inlining still lose files).
- **Native code** (`.so`, JNI), **work after `settle`** (a WorkManager job, a later sync), and **app start**: a change
  to a start-set file is a full walk.
- **Resource caveats:** the theme is resolved when an activity is made (fine with `restart` per screen, but an in-app
  navigation inside one activity reuses it); Compose's per-composition `ResourceIdCache` and `ImageVectorCache` mean a
  painter is recorded once per activity, again fine with a restart per screen. Ids (`id/...`) are names, not files:
  ignore them, or map them to `ids.xml` and the layouts declaring `@+id/`.

## What it would cost to build

- Runtime (prototype here, ~0.5 day to finish): the `--deps` launcher option and standby key, serve/mcp option rows
  (`launch-options.tsv`), a check script (`deps` on the test app's screens, as above, in `ci-smoke.sh`), AGENTS.md.
- `crawl.py` (~0.5 day, by the session that owns it): `deps read` and `deps resources` after each screen's settle, the
  start span after launch, the fields above.
- Mapping and selection (~1 day): resources to files from the repository's `res/` folders, classes through
  `affected.source_map`, the fall-back rules above, a `crawl.py --affected-by` or Greenroom's own selector, and a check
  that breaks one file and expects only its screens.

## Prototype on this branch

`runtime/src/main/java/dev/apksim/dex/Probes.java` (`command`, `peek`), `DeviceControls.java` (`deps`),
`Host.java` (`restart` opens a span), `dev/apksim/ResourceHits.java`, and
`org/robolectric/res/android/CppAssetManager2.java` (Robolectric 4.17's file from its `robolectric-4.17` tag with the
two hooks). Off unless `-Dapksim.deps=true`: the hooks are a `static final` false otherwise.

```bash
./apksim --headless --control 7400 --jvm -Dapksim.deps=true --jvm -Dapksim.deps.packages=dev.apksim.composeapp testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk
python3 scripts/drive.py 7400 'restart apksim://appcompat' 'settle 5000' 'deps read' 'deps resources' quit
```
