# The test app, other test APKs and comparing with the emulator

Agent reference notes, moved out of AGENTS.md so they are read only when needed. A section another doc or a
code comment cites as `AGENTS.md "Name"` is in one of these files: AGENTS.md's index says which.

## The test app
- `testapks/composeapp`: the test app, and the sample app Greenroom ships (each release's `apksim-testapp-VERSION.apk`).
  Material 3 Expressive (material3 1.5.0's `MaterialExpressiveTheme`, expressive motion, its own colour roles in
  `ui/theme/Color.kt`, generated from seed #6A3DE8 with the 2025 colour spec's expressive variant; edge to edge). Home
  (`Home.kt`) has a counter card ("FAB clicks: N", its shapes turning a notch per count; first, so a watch shows it), a
  search field, an All/Saved filter and a list of 100 places ("Item N", a segmented list: a tap or the row's heart
  saves one, its arrow opens the place's page; every row has the heart, so saving changes no row's structure, which
  `crawl.py` would otherwise count as a screen per saved row);
  the place's page (`Detail.kt`: a large flexible app bar, a floating toolbar, a date picker, a slider, a single choice,
  checkboxes, a confirmation dialog, a snackbar; Back returns); Trips (`Trips.kt`; SQLite rename: "Italian vacation" ↔
  "Roman vacation"); Settings (grouped sections); and a **Shader** tab, a full-screen unthrottled noise shader used as
  the 120 fps stress test. The top bar's menu has "Clear saved places", "Reset counter" and "About". Nothing on Home,
  Trips or Settings animates at rest (`settle` answers). It is
  adaptive (material3-adaptive window size classes): below 600 dp of width a short navigation bar and a list, from
  600 dp a navigation rail and a grid; the top bar shows the class and window size in dp ("compact 411×891"), which
  `nodes` reads; Settings shows the wall clock in the locale's format. Scripts tap the tabs and the FAB by
  coordinate ("Everyday commands"): keep four equal navigation items and a FAB that covers 733,1533 on the phone.
  Its build needs AGP 9.1 and compile SDK 37 (Compose 1.12, which material3 1.5.0 brings); targetSdk stays 36. `apksim://stack` opens `SecondActivity` (plain views, a
colour and tap count per level) over what is running, and its "Deeper" stacks another, for stack checks;
`apksim://floating` a dialog-themed `FloatingActivity`; `apksim://floating-tall[?bars=hide]` a floating, translucent,
dimming WRAP_CONTENT window with a list taller than the display (`FloatingTallActivity`, issue 506; `ci-smoke.sh` checks its size);
`apksim://bottomsheet?kind=material|compose|plain[&rows=N]` (`BottomSheetActivity`) a bottom sheet over a screen that counts taps
behind it: Material Components' BottomSheetDialog (the test app's only use of that library), Material 3's ModalBottomSheet, or a
framework Dialog with BOTTOM gravity (`ci-smoke.sh` checks it ends at the navigation bar); its sheet has a "Count" button; `--feature dev.apksim.testapp.launch_overlay` has its
Application add an empty 0x0 overlay window before any activity (`ci-smoke.sh`: the first frame still comes). `snapshots/` has its screenshot-test specs
  (`spec.json`: 5 screens × 16 combinations; `ci.json`, the CI's). Build with `./gradlew :app:assembleDebug` inside it. Its `uiserver` module is an instrumentation-only APK, a
  tool's on-device server in miniature, for `scripts/adb-check.py` (`./gradlew :uiserver:assembleDebug`).

## Test APKs
- `testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk`: the main regression app.
- Its protocol and media screens, run by `scripts/media-protocols-check.py APK [--serial SERIAL]` (by hand; not in CI
  yet: it needs a LAN address and the Mac's decoders): `apksim://grpc` (grpc-okhttp: unary, server and bidi streams, a
  failing status, a deadline, 300 KB; TLS and plain h2), `apksim://websocket` (OkHttp: text, binary, a burst, 200 KB,
  close; wss with permessage-deflate and ws), `apksim://media` (every sample in `app/src/main/assets/media`, from
  `testapks/composeapp/media/make-samples.sh`: H.264 baseline and High with forced B-frames, HEVC Main and Main10, VP8,
  VP9, AV1; AAC-LC, HE-AAC v1 and v2 (MP4 and ADTS), MP3, Opus, FLAC; through Media3, through MediaCodec alone on elementary streams, and encode
  round trips) and `apksim://media?only=hls` (HLS over HTTP/2). Their server is `testapks/composeapp/fixture`
  (`./gradlew :fixture:installDist`): gRPC-java's own server and OkHttp's MockWebServer, so the network tap is checked
  against real implementations, with a certificate made per run that the screens trust alone (`cert=` in their deep
  links). Each video sample's frames show their own index as stripes, read back from the TextureView, so a frame
  decoded wrong, out of order, mirrored or not shown is caught; audio is checked by pitch and output rate. The script
  compares every line with an expected table per platform (apksim on the Mac, the API 36 emulator) and, under apksim,
  checks the `--rec net` recording holds the gRPC trailers, WebSocket messages and HLS segments. Known apksim gaps it
  records: no VP8, AV1, Opus or FLAC decoders, and no encoders.
- The owner's own app (R8 and debug builds, the latter 179 MB and exercising method splitting): see
  `~/.config/apksim/NOTES.md`.
- An app whose native libraries are 4 KB-aligned (a debug build with Flipper): libflipper and libc++_shared load, but
  libevent_core/extra need `--move-shared-data`; without it Flipper's uncaught UnsatisfiedLinkError ends the app (exit 10).
- `testapks/loader-fixture`: a native library of loader and bionic checks inside the test app (see "Instrumentation tests").

## Comparing with the emulator
`ApksimCmp36` is an API 36 AVD (Pixel 2 profile, so no cutout) set with `adb shell wm size 822x1782` and
`wm density 320` to match the phone device pixel for pixel; dark mode on, gesture navigation overlay enabled.
`scripts/both.py PORT work/cmp 'tap x y' 'drag x1 y1 x2 y2' 'shot NAME' ...` sends the same input to both and writes
`e-`, `s-` and `c-NAME.png` (side by side plus a difference map; `work/cmp/.venv` has Pillow). `scripts/vshift.py`
measures a vertical offset between two shots. Under heavy machine load the emulator lags by seconds and its system
server can ANR; `both.py` retakes shots until the two agree.
`ApksimCmp30` is the same on Android 11 (`system-images;android-30;google_apis;arm64-v8a`, `pixel_2`, 2 GB), the
ground truth for an app that targets 28 and tests on API 30. `avdmanager create avd` refuses that image here ("Package
path is not valid": the SDK manager's XML is newer than the tool), so the AVD is `ApksimCmp36`'s `config.ini` with
`android-36` replaced (`image.sysdir.1`, `target`) and an `.ini` beside it. Start it on its own port so it never takes
`emulator-5580`'s: `emulator -avd ApksimCmp30 -port 5582 -no-window -no-audio -no-boot-anim -no-snapshot-save -gpu
swiftshader_indirect`, then `wm size 822x1782`, `wm density 320`. Its tablet case is the Nexus 10 the consumer's tests
use, landscape 2560x1600 at density 2 = `--device tablet` here (the emulator side: `wm size 1600x2560`/`2560x1600`,
`wm density 320`, and a `user_rotation` of 1; not measured). The target-28 test app is
`./gradlew -Papksim.targetSdk=28 :app:assembleDebug :app:assembleDebugAndroidTest` in `testapks/composeapp`: its
outputs are `app/build-t28/outputs/apk/{debug/app-t28-debug.apk,androidTest/debug/app-t28-debug-androidTest.apk}`
(a build folder of its own: AGP deletes the other APKs of an outputs folder, so the default `app-debug.apk` would
vanish), the same sources with `src/t28/AndroidManifest.xml` as the debug manifest (legacy Bluetooth permissions only); build
it last and both tasks together. The property takes any target (below `minSdk` 26 too; every one under 31 gets the
`src/t28` overlay): every target draws (README "One framework level"; below 14 windows are drawn in software: `HwRenderer.displayList`). `Target28Test` is its target-dependent checks (run with `-e expectTarget 28`, else skipped
by an assumption; its `probe` prints one `t28.KEY=VALUE` status line per observation, which docs/conformance.md tables for
API 30, API 36 and apksim, with the full-suite comparison). On API 33+ a target-28 app raises the POST_NOTIFICATIONS prompt,
which takes the focus from later tests of an emulator run: close it (Back) while the run goes. `scripts/target-gate-check.sh
DEFAULT.apk T28.apk` checks the notification gate of a running instance (`Notifications.allowed()`); CI's `tests` leg runs
Target28Test, BleTest, BluetoothTest and InstallPermissionsTest on the target-28 pair.
