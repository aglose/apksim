# Another API level (`--api 30`): a feasibility spike

apksim runs every APK on one framework, Robolectric's `android-all` for API 36 (`SimRunner.OneSdk`, `Host`'s
`@Config(sdk = 36)`), whatever the APK targets. A consumer's app targets 28 and its device tests run on API 30
emulators, so the question was whether apksim could run the API 30 (Android 11) framework instead, and what an
`--api 30` option would cost. This is the record of a one-day spike (2026-11-12): what ran, what broke in the order it
broke, what each breakage is, and a recommendation. No feature came out of it.

The hacks are on the branch `api30-spike-hack` (one commit, **not for merging**): 16 files, 70 lines added. They are
the cheapest change that got past each failure (a version check, a skip), not a design.

## Result

On the test app (`testapks/composeapp`, built from the same commit), Mac, headless, cold launches:

| Milestone | API 30 |
|---|---|
| Sandbox boots (Robolectric's compat native runtime loaded) | yes |
| `Application.onCreate` | yes |
| First frame | yes; cold with an AOT cache trained for it, six interleaved launches on a loaded machine: median 1.54 s (1.35-1.98), API 36 in the same checkout 1.92 s (1.74-2.76). Not like for like, see below |
| Screenshots, taps, typing, navigation, device controls over `--control` | yes, see below |
| androidTest suite, 415 tests | **266 passed, 72 failed**, 75 skipped, 2 ignored (API 36, same build: 338 passed, 0 failed, same 77 not run) |

**Every result here was obtained with the API 36 jar last on the class path** as a fallback for classes API 30
lacks (#3 below), and with the features of #4, #6, #7 and #9 switched off. Without the fallback the same build stops
before `Application.onCreate`.

Driven over `--control` in one session of 52 commands, each answered and none with `error` (the transcript is in the
pull request): `settle`, `insets`, `nodes`, `tap`, `shot`, `type`, `swipe`, `pinch`, `key`, `appearance dark`, `rotate`,
`config`, `device tablet|phone`, `open URL`, `windows`, `back`, `activity`, `fontscale`, `locale`, `recreate`,
`process-death`, `home`/`resume`, `memory`, `permission`, `notifications`, `location`, `clipboard`, `network
offline|online`, `bluetooth`, `bars`, `thermal`, `timezone`, `a11y`, `debug layout`, `viewtree`, `restart`, `stats`.
The FAB counts taps, the Trips rename and typed text show in `nodes`, and the app's Settings tab reads
`android.os.Build.VERSION.SDK_INT = 30`.

Measured as interleaved runs of one short script (restart, two tab taps, two flings) on fresh instances of both
levels: frame pacing was the same (119.3 and 119.3 fps on API 30, 113.9 and 120.2 on API 36, p50 8.3 ms on all four),
and API 30's footprint was ~25 MB larger (366 and 367 MB against 346 and 337). The launch and footprint figures flatter
API 30 somewhat: its launch skips work API 36 does (the GpuBitmaps shadows, the BLE reset, AppCallbacks, the
soft-keyboard tick).

Against API 36's pixels the same three screens differ in ~6% of pixels: the two 48 px system bar strips (an app
targeting 36 is edge-to-edge on API 35+ and draws behind them; on API 30 the window paints them), and anti-aliasing
and shadow differences of an older Skia elsewhere. That is the level showing, not a fault.

The test app's Shader tab crashes on API 30 with `NoSuchMethodError: RuntimeShader.<init>(String)`: the app uses an
API 33 class without a version check while declaring minSdk 26. A device does the same.

**Not tried:** a Mac window (everything was `--headless`), warm launches from a standby, the Linux image, any APK but
the test app, `--camera fake`, network record/replay, video, the flight recorder, `serve`/`beam`, `--snapshot`,
`--realtime-clock`, `--uia engine`.

## How it was run

- The framework: `org.robolectric:android-all-instrumented:11-robolectric-6757853-i7` from Maven Central (121 MB),
  the coordinate Robolectric 4.17's SDK list has for API 30, pre-instrumented like the API 36 one, so `FlatLoader`
  loads it from the application class path as before.
- The native runtime: below API 35 Robolectric does not use android-all's own `libandroid_runtime` but
  `librobolectric-nativeruntime` from `nativeruntime-dist-compat` (already unpacked into `work/runtime`, and already
  in the Linux image for both CPUs), with ICU data `icu/icudt68l.dat` from the same jar (25 MB) instead of the
  framework jar's. Natives are then reached through Robolectric's `ShadowNative*` shadows and
  `org.robolectric.nativeruntime.*Natives` classes, not bound on the framework's own native methods.
- The hack branch's commit does not set these up. From a checkout of it with `work/` prepared as usual:
  ```bash
  mkdir -p work/lib30 && for f in work/lib/*.jar work/lib/*.aar; do case "$f" in *android-all-instrumented*) ;; *) ln -s "$(cd work/lib && pwd)/$(basename "$f")" work/lib30/;; esac; done
  curl -L -o work/lib30/android-all-instrumented-11-robolectric-6757853-i7.jar https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/11-robolectric-6757853-i7/android-all-instrumented-11-robolectric-6757853-i7.jar
  unzip -o -j work/lib/nativeruntime-dist-compat-*.jar icu/icudt68l.dat -d work/runtime/icu
  scripts/build.sh && scripts/build-native.sh
  APKSIM_API=30 APKSIM_STANDBY=0 ./apksim --headless --control auto APK
  APKSIM_API=30 APKSIM_STANDBY=0 python3 scripts/instrument.py APP.apk TEST.apk --xml out.xml --test-timeout 180
  # an AOT cache for it, in a state folder of its own:
  APKSIM_API=30 APKSIM_API_AOT=1 APKSIM_HOME=$PWD/work/state30 APKSIM_STANDBY=0 scripts/train.sh --headless APK
  ```
- `APKSIM_API=30 ./apksim ...` on the hack branch: the class path takes `work/lib30` (the API 30 jar beside the other
  jars) instead of `work/lib`, `-Drobolectric.enabledSdks=30`, `-Dapksim.api=30`, no AOT cache unless trained for it.

## Breakages, in the order met

Size is the work to do it properly: S under a day, M a few days, L more than a week.

| # | What failed | Mechanism | Size |
|---|---|---|---|
| 1 | `TypeNotPresentException: android.app.ActivityClient` while Robolectric built its shadow map | `@Implements(ActivityClient.class)` on one of apksim's shadows names a class API 30 lacks; reading the annotation throws. `className = "..."` with `minSdk` instead. | S |
| 2 | Robolectric could not find the framework jar | The launcher puts every jar of `work/lib` on the class path and names that folder as Robolectric's dependency folder; `enabledSdks=36`, three `@Config(sdk = 36)` and `OneSdk` fix the level. A folder (or jar) per level and one switch. | S |
| 3 | `NoClassDefFoundError: android/view/RoundedCorner`, then `android/bluetooth/IBluetoothScan`, then `android/net/wifi/WifiAvailableChannel` from `Class.getDeclaredMethods` | Robolectric reads a shadow's methods reflectively, which resolves every type in every signature. Two of apksim's shadows name API 31+ types, and so do Robolectric's own: 52 of its shadow classes name 101 classes API 30 lacks (`api-refs.py` on `shadows-framework-4.17.jar`), `ShadowWifiManager` first. An ordinary Robolectric test never sees this because its class path has the compile SDK's `android.jar`; the flat class path here has only the one framework. Hacked the same way: the whole API 36 jar last on the class path, so classes the level lacks come from it. Every later result depends on this. With it, no shadow fails to load (the hack would drop one that did, and print it); without it (`APKSIM_API_NOFALLBACK=1`) three of apksim's are dropped (`ShadowSimActivityClient`, `ShadowSimBluetoothAdapter`, `ShadowSimDisplay`) and the launch dies in `ShadowWifiManager` (`WifiManager$LocalOnlyConnectionFailureListener`) before `Application.onCreate`. Done properly: a small jar of only the classes signatures name, generated from the newer jar and checked by the scan. | S-M. Until then, code that asks "does class X exist" gets yes for every API 31-36 class, and calling into one runs API 36 code on an API 30 framework |
| 4 | `NoClassDefFoundError: IBluetoothScan$Default` in `Ble.<clinit>` | The BLE stand-in answers the binders of API 33+ (`IBluetoothScan`, `AttributionSource`, `BluetoothStatusCodes`); on API 30 scanning is part of `IBluetoothGatt`, with other signatures. Skipped: no scripted BLE. | M |
| 5 | `NoSuchFieldError: NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED`; then `RippleDrawable.STYLE_SOLID`, `WifiSsid.fromBytes`, `NetworkCapabilities.setTransportInfo(WifiInfo)` ("argument type mismatch": WifiInfo is a TransportInfo from 31) | Direct uses of API 31-33 members on start-up paths. One version check each. | S each |
| 6 | `UnsatisfiedLinkError: RecordingCanvas.$$robo$$nCreateDisplayListCanvas$nativeBinding`, then `ClassCastException: ShadowNativeRecordingCanvas cannot be cast to ShadowGpuRecordingCanvas` | `ShadowGpuRecordingCanvas.Recording` replaces Robolectric's shadow of `RecordingCanvas`. On API 35+ that costs nothing, natives being bound on the framework class (`callNativeMethodsByDefault`); below, Robolectric's shadow *is* the native implementation, and a shadow of ours that replaces one without extending it loses the natives. Both skipped: no GpuBitmaps. Needs a shadow per runtime kind. | S-M |
| | **First frame** | | |
| 7 | `NoSuchMethodError: InsetsController.getRequestedVisibleTypes()` in `SoftKeyboard.tick` | The insets classes were reworked in API 34 (`InsetsSource` ids, `getOrCreateSource`, requested visible types). `SoftKeyboard` and `SkinShape` write them directly. Skipped: no drawn soft keyboard or IME insets, no cutout or rounded corners. | M |
| 8 | `NoSuchMethodError: WindowInsets.getRoundedCorner(int)` (`insets` command), `ViewRootImpl.getOnBackInvokedDispatcher()` (`back`) | API 31 and 33. Below 33 back is only the key event, which the old path already sends. | S |
| | **Input, navigation, device controls** | | |
| 9 | Nearly every test at setup (197 of 200 in a first run with an older test APK, not kept): `NoSuchFieldException: mCallbacksController` | `AppCallbacks` reaches into `Application` by field name; API 30 keeps the list in `mComponentCallbacks`. Reflection by name: the reference scan below cannot see it. Turned off for the spike. | S |
| 10 | 28 tests: `NoSuchMethodError: AccessibilityNodeInfo.setQueryFromAppProcessEnabled`, `ViewRootImpl.getDirectAccessibilityConnectionId()` | UiAutomation here links each window's root node to its views through API 34's in-process ("direct") accessibility connection. Below 34 there is none: it needs a stand-in for the accessibility service connection registered with `AccessibilityInteractionClient`, answering from each window's `AccessibilityInteractionConnection`, which is what a device's UiAutomation has. With the calls skipped, queries find only root nodes. | M-L |
| 11 | 10 tests: every `AndroidBitmap_*` call answers `-2` (JNI exception) | `NativeBitmaps` fails to initialise: its static `SPACES` table reads `ColorSpace.Named.BT2020_PQ` and `BT2020_HLG`, which API 30's enum lacks (`NoSuchFieldError` in `<clinit>`), so every call into the class throws. Guard those two entries, and `compress()`'s `Bitmap.Config.RGBA_1010102` case (33). (The `switch` on the config is not the cause: javac's lookup class catches a missing constant.) Not fixed in the spike. | S |

## The suite's 72 failures by cause

| Tests | Cause | From |
|---|---|---|
| 28 | UiAutomator finds no node below a window's root (`UiAutomatorConformanceTest` 9, `CoveredWindowsTest` 7, `UiAutomatorTest` 5, `StackWindowsTest` 3, `PopupTouchTest` 2, `UiaEngineTest` 2) | #10 |
| 24 | `BleTest`: no BLE stand-in (and 170 of the run's 209 s are its timeouts) | #4 |
| 10 | `NativeBitmapTest` | #11 |
| 7 | `AppCallbacksConformanceTest` 5, `LifecycleConformanceTest` 2: callbacks do not carry over between tests | #9 |
| 1 | `WindowDrawTest`: a Scroller-driven move never runs: `ShadowSimViewRootImpl.draw` is `@Implementation(minSdk = 34)` (the method's signature changed) | gated shadow |
| 1 | `SystemBarsTest.legacyFullscreenFlagsHideTheBars`: insets stay (0, 0) | #7's insets model |
| 1 | `SystemSourcesTest`: the network's `WifiInfo` | #5's skip |

Everything else passed, which includes the Compose rule, Espresso, ActivityScenario, the translator and native loader
fixtures, Room, DataStore, WorkManager, Fragment, Navigation, RecyclerView and Material 3 conformance, bound services,
the Timeout rule, shell commands, Mockito, offscreen GLES, device identity, install state, time zones, and system bars
but one.

## By inspection

**What apksim references that API 30 lacks.** `scripts/api-refs.py FRAMEWORK.jar [apksim.jar]` compares what
apksim.jar links against with what a framework jar has (constant pools and member tables, no class loading). Against
the API 30 jar, for an apksim.jar built from `main` (the pull request's base, not the hack branch, whose jar has
two of the fields replaced by literals): 26 of 208 source files, with 1 shadow of a missing class, 2 shadows whose
signatures name one, 13 `@Implementation` methods that match no method of the shadowed class (Robolectric skips those
silently; 10 more than on API 36), 6 more that are gated by their own `minSdk`, 12 missing classes, 47 missing methods
and 12 missing fields. With `--base` (the API 36 jar) it also lists 9 supertypes that classes apksim uses do not have
at 30, among them `WifiInfo` as a `TransportInfo` (#5) and `TypedArray` as an `AutoCloseable` (a try-with-resources
on one fails). Against the API 36 jar it reports none of these but the 3 methods that are unbound today. By area:

| Area | What is missing at 30 |
|---|---|
| `TrackedRuntimeShader`, `GpuEffects`, `GpuRenderNode` | `RuntimeShader`'s public API (33), `RenderEffect` (31), `RenderNode.setRenderEffect`, `BitmapShader` filter modes (33). The translator's rewrites of `new RuntimeShader` and `RenderEffect.create*` only matter to an app that calls those APIs, which an app on SDK_INT 30 does not unless it forgot the check; they need a level check, no more. |
| `SkinShape`, `SoftKeyboard`, `SystemBars`' immersive handling | The API 34 insets classes (#7). |
| `ble/`, `Bluetooth` | `IBluetoothScan`, `AttributionSource`, the 4-argument GATT write callbacks, `BLUETOOTH_SCAN`/`CONNECT` (31): on API 30 scanning needs location permission only (#4). |
| `ShadowSimUiAutomation`, `Uia` | The direct accessibility connection (#10), `getWindowTransformationSpec` (33), `executeShellCommandRw(e)` (31), `clearCache`, `getRootInActiveWindow(int)`. |
| `Host`, `TestActivities` | `OnBackInvokedDispatcher` (33), `ActivityClient` (31), `RoundedCorner` (31). |
| `camera/`, `Cameras` | Constructors of `TotalCaptureResult` and `StreamConfigurationMap` with other parameter lists: `--camera fake` would fail at its first frame. |
| `ShadowSimConnectivityManager`, `SimWifi`, `HostNetworks` | `registerBestMatchingNetworkCallback` (31), `WifiSsid.fromBytes` (33), WifiInfo as TransportInfo (31), one capability constant. |
| `BackgroundWork` | `JobParameters`' constructor, `JobInfo.isExpedited`/`isUserInitiated`. |
| `ShadowSimSurface`, `ShadowSimBiometricPrompt`, `ShadowSimSSLSockets` | Native or private methods whose signatures differ, so the shadow method stops binding. |
| `ShadowSimViewRootImpl` | `draw` is gated at 34 and `setFrame` at 36 (other signatures below): no draw after a traversal in test runs, and no window-position sync, which is what puts a centred dialog's nodes and `getLocationOnScreen` where it is drawn. The spike opened no dialog in a running instance. |
| `NativeBitmaps` | `ColorSpace.Named.BT2020_PQ`/`BT2020_HLG` (34) in a static table, `Bitmap.Config.RGBA_1010102` (33) (#11). |
| `FlightRecorder`, `DeviceControls` | `SQLiteDatabase.beginTransactionReadOnly` (35), `Intent.getParcelableExtra(String, Class)` (33). |

The scan does not see a constant whose value changed, nor framework members reached by name: about 200 call sites use `ReflectionHelpers` or
`java.lang.reflect` with a string (#9 was one), and each needs reading. Nor does it see behaviour: Robolectric's own
shadows branch on the API level in hundreds of places, and what AGENTS.md records about them (which sticky broadcasts
it never sends, how its Choreographer and looper behave, what its window session does) was traced on API 36 only.

**HWUI.** `HwRenderer` (HardwareRenderer, RenderNode, FrameDrawingCallback: all API 29) drew every frame unchanged
through the compat runtime's HWUI, at the same pace. `ShadowGpuRecordingCanvas` needs a variant that extends
Robolectric's native shadow below 35 (#6); until then scaled bitmaps stay on the CPU. `GpuRenderNode`'s effect layers
and `TrackedRuntimeShader` have nothing to do below 31/33. `GpuCanvas` (shapes and paths) references nothing missing.
The Mac window, Metal presentation and `wshot` are level-independent code, but were not run.

**AOT cache, standbys, class cache.**
- The AOT cache is tied to the class path, so each level needs its own (`app-30.aot` and its `.root`), trained the
  same way: `train.sh` worked unchanged against API 30 and gave an 83 MB cache (API 36: 108 MB) and the cold
  first frames above. A release would train two in the background, or only the default until `--api` is first used.
- A standby boots one framework, so the level has to be part of its key beside the sandbox flags, and the launcher
  must send a launch only to a standby of its level. Two levels in use means two idle standbys (~410 MB each).
- The class cache is keyed by apksim.jar's hash and each entry is checked against the framework's "is X an interface"
  answers, so one cache served both levels in the spike without a wrong class. Once any translator rewrite depends on
  the level, the level belongs in the key (as `-debug` and peephole already have their own).
- Per-APK caches (manifest, `.so` files, DEX) and data slots do not depend on the level.

**Linux image.** It already carries `librobolectric-nativeruntime.so` for x86_64 and aarch64 (the Dockerfile checks
both libraries exist), so it needs the API 30 jar, `icudt68l.dat` and the loader's branch: about 150 MB more. The
arm64 special case (android-all's own runtime from a respin) does not apply below 35. Not built or run.

**Size on disk.** +121 MB for the jar and +25 MB of ICU data per extra level, in a release tarball and in the image.

## What stays impossible, or not worth doing

- **Features Android 11 does not have** are not gaps: predictive back, `RuntimeShader`, `RenderEffect`, rounded-corner
  APIs, the photo picker, notification permission, BLE permissions. (Display cutouts are there: `Display.getCutout()`
  and `WindowInsets.getDisplayCutout()` exist at 30; what `SkinShape` needs is another way to set them, #7.) An app running here on API
  30 takes its pre-31 paths, as on its emulator.
- **Robolectric below API 35 is a different native runtime**, built by Robolectric from its own tree and shared by
  API 26-34, not Android 11's. HWUI, Skia, fonts and ICU are that runtime's whichever level runs, so pixels never
  match an API 30 device the way API 36's match the API 36 emulator; text metrics and shadows are close, not equal.
- **One process is one level.** The framework is on the application class path (`FlatLoader`), so a level is chosen
  at launch and a standby or test pool process cannot change it.
- **Levels below 30** were not looked at. Each is another jar and another round of the table above, growing with the
  distance from 36.

## Cost of an `--api 30`

Estimates, from what the spike touched:

| Piece | Size |
|---|---|
| Launcher option, jar per level (`gradle deps`, `prepare-runtime.sh`, release tarball, Dockerfile), loader branch, caches and standbys keyed by level | M |
| The 100-odd references of the scan and the reflective call sites: a version check or a second implementation each; `api-refs.py` in CI per shipped level so new ones fail a pull request | M |
| The class-path fallback replaced by a generated jar of only the classes shadows' signatures name (#3), so nothing of API 36 can run | S-M |
| Accessibility for UiAutomator below 34 (#10) | M-L |
| BLE on API 30's binders (#4) | M |
| Insets model below 34: soft keyboard, skins' cutouts, legacy fullscreen flags (#7) | M |
| Camera, GpuBitmaps, jobs, the rest of the table | M together |
| Ground truth: what docs/ci-testbed.md and docs/conformance.md established against the API 36 emulator, checked again against an API 30 one; the test app's suite on both levels in CI (+~1 min a run) | M, then continuous |

About four to six weeks for parity with what API 36 does today, and after that every feature that touches the
framework is written and checked for two shapes of it. That standing cost, not the port, is what "large" means here.

Restricted to **test runs** (`--instrument --api 30`), the list shrinks: test runs launch cold, so no standby keying;
they have no window, so no Metal, skins or drawn soft keyboard; GpuBitmaps is an optimisation they do not need.
What is left is the plumbing, the scan's list, AppCallbacks, accessibility (#10) and, if the suite uses them, BLE and
camera: two to three weeks.

## Recommendation

**Do not build `--api 30` now. Build it for test runs only, and only if the target-SDK gates and the API 30 emulator
comparison leave failures that the level explains.**

- The spike says it is feasible and smaller than its "L" suggested: a day of hacks ran the app and 79% of the suite's
  passing tests, and 69 of the 72 failures have four causes with known mechanisms (accessibility, BLE, one class's
  static table, one reflective field). Nothing found is a wall, with one qualification: all of it ran on the class-path
  fallback of #3, and what breaks once only the classes signatures need are supplied has not been run.
- But the case for it is fidelity on a target-28 app, and most of that distance is not the level. An app targeting 28
  on API 36 differs from the same app on API 30 mainly through target-SDK-gated behaviour, which the framework applies
  itself once Robolectric's compat answers follow the manifest's targetSdk (the gates work under way), and through
  apksim-versus-device gaps that exist at either level, which the API 30 emulator comparison finds one by one. What
  only another framework gives is the residue: code that branches on `SDK_INT` (the app's own and AndroidX's) and
  platform behaviour that changed without a gate. Nobody has measured how much of the consumer's suite that is.
- So the order is: land the gates and the emulator comparison, run the consumer's suite, and sort its remaining
  failures. If a meaningful share reproduce on an API 36 emulator too, the level is not the cause and `--api 30`
  would not have fixed them. If they pass on an API 36 emulator's target-28 run and fail only here, fix the gap. If
  they depend on `SDK_INT` being 30, that is the evidence to spend the two to three weeks on `--instrument --api 30`.
- Running instances (`--api 30` for the window, standbys, `serve`) should wait for a consumer who needs to look at an
  app on Android 11 rather than test it; nothing asked for that.

Cheap things worth keeping regardless: `scripts/api-refs.py` (it also lists three shadow methods that bind to nothing
on API 36 today: `ShadowSimSurface.nHwuiCreate` and `nHwuiSetSurface`, `ShadowSimUiAutomation.clearCachedSubtree`),
and the finding in #3 and #6 that below API 35 apksim's shadows must extend Robolectric's native ones.
