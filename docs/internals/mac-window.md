# The Mac window, launch options, device identity and the test app's deep-link screens

Agent reference notes, moved out of AGENTS.md so they are read only when needed. A section another doc or a
code comment cites as `AGENTS.md "Name"` is in one of these files: AGENTS.md's index says which.

## Simulator features (Mac window)
The window is resizable: dragging an edge resizes the display (`Device` rewrites the whole qualifier string:
smallest width, size bucket and orientation follow), applied at most every 100 ms while dragging and once at the end;
meanwhile the last frame stays pinned top left. A resize waiting in the window's input queue takes the next one's size
(`post` in `simwindow.m`): a drag posts one per pixel moved, and applied one by one they ran for ~30 s after a 2 s drag,
each snapping the window back to its stale size. Each resize keeps the last frame up, unscaled at the top left, until the new
size's first frame is presented and the layer is that size (`pinnedForResize`): cleared at the resize, the window flashed black ten times a second. Activities get the configuration change as on a device in a freeform
window: recreated, or resized in place when they declare `screenSize` in configChanges. Zoom only
scales the window. A display bigger than the Mac's screen (desktop, TV) opens zoomed out to fit.
Menus as in the iOS Simulator: File (Save Screen Cmd+S, Save Screen with Skin Cmd+Opt+S, Record Screen Cmd+R, Save Replay Cmd+Shift+S: the flight recorder), Device (Device Type > Phone/Foldable/
Tablet/Desktop/TV Cmd+Shift+1-5, Pixel 10 Pro Fold Cmd+Shift+6, the Galaxy S25/S25+/S25 Ultra/A56, Z Fold7/Flip7/TriFold
Cmd+Shift+7-9, Skin > None and the stored skins (menu tags from `SimWindow.TAG_SKIN_FIRST`), Back, Home Cmd+Shift+H, Lock
Cmd+L, Volume Cmd+Opt+=/-, Rotate Cmd+Left/Right), Features (Dark Appearance Cmd+Shift+A, Larger/Smaller/Default
Text Cmd+Shift+=/-/0, Language and Region..., Location > None/Custom/City Run/Bicycle/Freeway, Fingerprint >
Enrolled/Matching Cmd+Opt+M/Non-matching Cmd+Opt+N, Network > Online/Offline/3G/Lossy/Wi-Fi/Mobile Data, Camera > Off/Test Pattern/Image
File... (`--camera fake`), Open URL... Cmd+Shift+O), Window (Actual Size Cmd+1, Fit Screen
Cmd+3, Zoom In/Out Cmd+=/-, Minimize Cmd+M; the app menu has Hide Cmd+H), Debug (Slow Animations Cmd+T, Simulate Memory Warning Cmd+Shift+M, Show Layout Bounds,
Accessibility Audit), and Device > Status Bar / Navigation Bar (see `bars`). Permission requests
and biometric prompts show as sheets on the window; notifications as a banner (click opens it).
Saves go to `work/screenshots` (the sandbox keeps the app out of ~/Desktop). Option-drag pinches around the screen
centre, Option-Shift-drag pans with two fingers, trackpad pinch/rotate map to two fingers. The status bar (clock,
Wi-Fi, battery, dark icons when the app asks for light bars) and gesture handle are drawn over the app as layers,
each only while the device has it and the app has not hidden it (`wshot` shows them, `shot` does not). The Mac and app clipboards stay in sync both ways. http(s)/mailto links the
app opens outside itself go to the Mac's browser/mail app. A browser sign-in comes back (issue 336,
`DeviceControls.openOnHost`): a web link with a `redirect_uri`/`redirect_url` that one of the app's own VIEW/BROWSABLE
filters takes (a custom scheme; an https App Link never: `ASWebAuthenticationSession` needs an Associated Domains entitlement for its host, so it goes the way below, issue 436) runs in `ASWebAuthenticationSession`
(`SimWindow.webAuth`), in the Mac window and in a beamed instance (its window is hidden, not absent), and the callback
URL is delivered as `open URL` does; cancelled delivers nothing. Without a window (`apksim serve`), or when this Mac
cannot wait for the redirect, the link goes to the session's tab: the instance's `hostwatch` control connection
(`HostLinks`: `o URL [REDIRECT]`), `Session.watchLinks`, `d {"link","redirect"}`, and the page's card with the link and,
for a sign-in, a box whose address is sent back as `open URL` (`scripts/serve-link-check.py`, CI). With neither, the link
opens as any other and a stderr line says to paste the final address into Open URL.... The redirect reaches the waiting
activity because Host honours launch modes for a running activity (`Host.deliverToRunning`: singleTask/singleInstance,
singleTop on top, CLEAR_TOP with SINGLE_TOP: those above finish and it gets onNewIntent; before, every start stacked a
copy, which test runs alone did not). The test app's `apksim://signin` (`SignInActivity`, singleTask, and
`SignInRedirectActivity`) does it as Auth0 and AppAuth do: "signin: code=abc (instances 1, new intents 1)". Not checked:
a session finished by hand against a real provider (it starts; the consent dialog needs a person), and the session
inside the beam sandbox. The keyboard sends Control shortcuts, Shift selection,
Option+arrow (as Ctrl+arrow: by word), Home/End/Page/Forward Delete as Android key events. Launch options:
`--activity PKG/.Act|.Act|CLASS` (`AppInfo`: start that activity, exported or not, instead of the manifest's first
MAIN/LAUNCHER one, as `am start -n` would: a debug manifest's test LAUNCHER listed before a home app's MAIN/HOME
entry, or a screen deep inside; a simple name such as `SecondActivity` works when one activity has it; with a MAIN
filter it gets MAIN and that filter's LAUNCHER or HOME category, else the component alone; an alias stays the intent's
component and its targetActivity is made (the test app's `.SecondAlias`). `restart` starts it again
and `process-death` rebuilds it; one the manifest lacks stops the launch with exit 2 and the list; not in test runs.
With no LAUNCHER activity the first MAIN/HOME one starts), `--appearance dark`, `--locale de-DE`, `--timezone America/New_York`, `--clock 2026-01-01T09:41:00Z`, `--font-scale 1.3`, `--permissions ask|grant|deny` (grant = `adb install -g`),
`--biometrics enrolled`, `--bluetooth on|off`, `--sensor NAME=VALUE`, `--status-bar on|off`, `--nav-bar gesture|off`, `--snapshot` (screenshot tests: cold launch), `--window front|nofocus|background|minimized|hidden` (default for every launch:
`APKSIM_WINDOW`), and device identity (`DeviceIdentity`, set just before Application.onCreate in running instances,
warm or cold, and in test runs): `--serial VALUE` (Build.getSerial() and Build.SERIAL; by default 16 letters and
digits hashed from the data slot's name, so one slot keeps one serial and `instance-1` gets another, and test runs get
the `default` slot's: Robolectric's `unknown` failed a backend's format check), system features (`SystemFeatures`:
Robolectric's few (touchscreen, wifi*, telephony*), then what an emulator of the device's kind lists for what apksim
simulates: bluetooth, bluetooth_le, location*, multitouch*, faketouch, fingerprint, audio.output, both screen
orientations; a TV drops touchscreen, telephony, GPS and fingerprint and has leanback and type.television, a desktop
adds type.pc; no camera* unless `--camera fake` (see "Camera"). The `device`
command re-applies them for the new kind. `pm list features` and getSystemAvailableFeatures list them), `--feature
NAME[=false]` (repeatable, after the defaults: `=false` takes one away, a default too) and
`--build FIELD=VALUE` (repeatable: any public static Build or Build.VERSION field, `MODEL=Pixel 9`,
`VERSION.SECURITY_PATCH=...`, typed by the field; not VERSION.SDK_INT, and leave FINGERPRINT alone in test runs,
see "Instrumentation tests"; Robolectric resets them between tests, and they are set again for each). `DeviceIdentityTest`
checks them (CI runs it with the options). `--setting global|secure|system:KEY=VALUE` (repeatable) puts a Settings value
before onCreate, as `adb shell settings put` before a first launch would (`SlotDevice`; kept with the slot, and in test
runs set at each test's setup: `SlotStateTest`). `global:bluetooth_on` is refused (use `--bluetooth on|off`) and not kept per slot: the adapter's state follows the launch. A running instance takes its data slot in the same hook (`Host.usePersistentDataDir`,
before onCreate, not in `Host.run` after it), so what onCreate writes is kept, it finds the slot's /sdcard and Settings
values (`SlotDevice`, with the --setting seeds over them), and `APKSIM_LOGCAT` shows its log.
Then, as in test runs, what installing gives the app (`InstallState`, see "Instrumentation tests"): normal permissions
granted (one app's onCreate threw "Need BLUETOOTH permission" before), an ANDROID_ID (from the data slot, as the
serial; only if the slot's settings or a seed did not already give one, so the slot keeps it, and an
`android_id` set with `settings put` or `--setting secure:android_id=...` wins), the host's networks, and the app's content providers created (AndroidX Startup's initializers ran never
before, providers being made only when first queried); a provider that fails is logged and skipped
(`-Dapksim.providerFailures=fail` crashes the launch as a device would: FATAL EXCEPTION, exit 10, see `Crashes`; log
stays the default here, as a provider apksim cannot start may be apksim's gap, and such apps ran before). The standby's warm-up loads Conscrypt's
native library, which OkHttp's startup initializer asks for (~125 ms of a warm launch otherwise). `apksim://install`
(`InstallStateActivity`) shows what onCreate saw (and "installed: set": the app's PackageInfo firstInstallTime and lastUpdateTime are the launch time on the app's clock, `--clock` included, stamped by `InstallState` on every launch, not kept with a data slot; issue 505) and binds to a service nothing provides (`ci-smoke.sh` checks it). Window modes (`applyWindowMode` in `simwindow.m`): only `front` takes focus; `nofocus` shows the
window on top, `background` behind every other window (orderBack), `minimized` in the Dock, `hidden` not at all; a
Dock icon click shows it focused. The window server activates a newly launched regular app just after it finishes
launching, asked or not, so the other modes start as an accessory app and turn regular (Dock icon) in
applicationDidFinishLaunching. Hidden or minimized, there is no display vsync and Host paces on its grid; App Nap is
held off (`beginActivityWithOptions`) in every mode, since it throttles the timers of covered or hidden apps. A
permission or biometric sheet on a window the user cannot see bounces the Dock icon. Test-app Settings tab (scroll down) has a pinch area and, under "Device features", "Background and keys" and "Media", Camera/Locate/Notify/Copy/Unlock/
Pick file/Choose trip (in-app result)/Share/Background/Keystore/Bind buttons for these (Bind: "bind: null
binding to .NullBindingService"), and "Play clip" (ExoPlayer, a 3 s
HEVC + AAC clip into a TextureView; "clip: ended at 3.0 s after N s, video video/hevc, audio audio/mp4a-latm" when it
plays through); Trips has a "Trip note" field with
imePadding; the app follows dark mode and handles `apksim://composeapp/<tab>` deep links. `apksim://network` opens
`NetworkActivity` (HTTPS fetches with OkHttp and HttpURLConnection, ConnectivityManager's state; see "Network";
`apksim://network?url=URL` adds "Get" and "Get URL" for any address and shows the protocol OkHttp got;
`apksim://grpc`, `apksim://websocket` and `apksim://media` are the protocol and media screens, see "Test APKs").
`apksim://bitmaps` opens `ViewBitmapsActivity` (Views only: a stretched BitmapDrawable background, FIT_XY and
CENTER_CROP ImageViews, `drawBitmap(Bitmap, Matrix, Paint)`, a mutable bitmap redrawn every frame; `?animate=0` holds
still for pixel comparisons, `?video=1` adds the clip scaled up in a large TextureView), the GpuBitmaps check on
`--device tablet`.
`apksim://bluetooth` opens `BluetoothActivity`: "bluetooth: on, scanner yes", the ACTION_STATE_CHANGED broadcasts
received ("broadcasts: TURNING_OFF OFF") and the Bluetooth features (`scripts/adb-check.py` switches it with `svc`).
`apksim://timer` opens `TimerActivity`: a 60 s CountDownTimer, SystemClock's drift from the wall clock, counts of
onUserInteraction, touches and keys, a "Sleep worker" (SystemClock.sleep on a thread) and "Sleep on main" (an uptime
deadline waited for with SystemClock.sleep); `scripts/timer-check.py
PORT [--adb SERIAL]` drives it (~70 s).
`apksim://timezone` opens `TimeZoneActivity`: each zone source (JDK, android.icu, `user.timezone`, Joda-Time 2.10's
default) on a line, and ACTION_TIMEZONE_CHANGED heard as joda-time-android hears it ("tz changes: 1 to Asia/Tokyo";
`ci-smoke.sh` drives it).
Time zones (`TimeZones`): `--timezone ZONE` is applied before the Application exists, warm or cold, and in test runs at
each setup: the JDK's default, android.icu's (its cached default dropped: a standby may have worked it out before the
launch), the `user.timezone` property (Joda-Time up to 2.10 reads it before the JDK's default and caches the result; a
standby's JVM had set it to the host's, so Joda dates were a day off), the status bar clock and logcat's timestamps.
`timezone ZONE` does the same while the app runs and broadcasts ACTION_TIMEZONE_CHANGED with EXTRA_TIMEZONE, as a
device does (joda-time-android sets Joda's default from it; plain Joda keeps its cached zone, as on a device). Without
`--timezone` nothing is changed.
`apksim://composeapp/trips?overlay=1` adds an empty, non-focusable overlay window after the activity's window (issue 123); `?overlay=full` adds a full-screen `FLAG_NOT_TOUCHABLE` one (issue 323: a window flagged so takes no touch or wheel, inside it or not, as InputDispatcher decides; `ci-smoke.sh` taps through it);
`scripts/overlay-input-check.py PORT` types into the Trip note field under it over `--control` and expects "ab", so typing
that reaches the wrong window fails it (`keyTarget` in `Host`: keys go to the topmost shown, sized, focusable window).
`apksim://popup-trace` opens `PopupTraceActivity`: "Trace twice" begins one Trace async section twice, and dragging
its slider leaves a non-focusable value Popup up, which a tap on "Tap me" outside it must not reach (`ci-smoke.sh`
drives it; `PopupTouchTest` in a test run). The tap also gives the popup ACTION_OUTSIDE, so its `onDismissRequest` runs once ("outside: 1"). "Open menu" shows a framework PopupMenu, whose rows
must read as clickable nodes ("menu: beta" once chosen; `ci-smoke.sh`): a ListView marks its rows clickable for
accessibility only through a delegate it sets while accessibility is on, normally never in a running instance, so `Nodes.listItem`
fills each row's node as `AbsListView.onInitializeAccessibilityNodeInfoForItem` does (`nodes`, `nodes tree`, `tap-text`,
`crawl.py`); before, an overflow menu's or a ListPopupWindow sheet's rows gave the crawler no actions. `uiautomator dump`
and UiAutomation read the framework's own nodes and still show such rows unclickable. `a11y`'s audit, journeys and
failure bundles' nodes.txt see the rows too, and a crawl's screen hashes change for any app with a ListView.
View animations (issue 475): in running instances `SimRunner.realViewAnimations` turns on Robolectric's `ShadowView.setUseRealViewAnimations`, so the framework steps an `Animation` at each frame's drawing time, as on a device. Robolectric's `AnimationRunner` stepped by the Choreographer's frame delay, which Host sets to 1 ms, so a 300 ms AndroidX Fragment transition kept `settle` busy for ~3-7 s after its screen was done (and `apksim mcp` waited ~2.6 s per fragment change). Test runs keep Robolectric's runner (`ShadowView.getAnimations()`); `-Dapksim.realViewAnimations=false` is the old behaviour. The test app's `apksim://view-animations` (`ViewAnimationsActivity`: "Replace" an animated fragment, "Fade" a 300 ms AlphaAnimation: "fade: ended after N ms") As on a device, an animation ends only when its view is drawn: one on a hidden, stopped or detached view waits (Robolectric's runner ended it after N frames regardless). It is checked in `ci-smoke.sh`: Replace settles in under 2 s (2.9 s before, 0.65 s after).
`apksim://drawer` opens `DrawerActivity`: a `DrawerLayout` with three items that count clicks, settling through
ViewDragHelper's Scroller (`DrawerSettleTest`, opt-in, see `--realtime-clock`).
`apksim://interop` opens `InteropActivity`: a LazyColumn of `AndroidView` TextViews ("Row 0".."Row 19", "tapped: Row N" after a tap); `nodes` and `tap-text` reach them because `Nodes.walkView` also walks the child views of a view that has an accessibility provider, as the platform's tree does (issue 478; `ci-smoke.sh`).
`apksim://dialogs[?open=full|modal|nonmodal|compose]` opens `DialogsActivity`: a full-screen dialog, a small
(touch-modal) one, a small FLAG_NOT_TOUCH_MODAL one or a Compose Dialog over it; under all but the third accessibility
reports only the dialog. Each counts taps on its "Count" button, for clicks at a centred dialog's node bounds
(`CoveredWindowsTest`, `adb-check.py`).
`apksim://settings-intent` opens `SettingsIntentActivity`: it starts ACTION_WIFI_SETTINGS and ACTION_SETTINGS, which nothing here resolves, so
startActivity throws ActivityNotFoundException in a running instance (issue 504; "settings: wifi=notfound, settings=notfound";
`ci-smoke.sh`). Only those two actions, and not for a result: other `android.settings.*` actions (app details, overlay
permission) are still dropped with a log line (or answered RESULT_CANCELED for a result), as an app seldom catches them.
Test runs keep Robolectric's recorded start.
`apksim://slot` opens `SlotStateActivity`: the Settings.Global values `apksim_slot_check` and `apksim_slot_seed` and the
file `/sdcard/apksim-slot-check.txt` as Application.onCreate saw them ("at app start") and now, and "Write" to set the
first and the file, plus typed values (Global putInt, Secure putLong, System putFloat; "typed:" reads them back with the
typed getters: the slot keeps text, as Android's provider does, and the getters parse it) (`scripts/slot-check.sh`
drives it across launches, and checks `adb shell settings get` when adb is on PATH).
`apksim://camera[?mode=compatible]` opens `CameraActivity`: CameraX's Preview in a PreviewView (a SurfaceView; `compatible`:
a TextureView) and an ImageAnalysis on the front camera, "camera: N frames, 640x480 YUV, rotation 270"; `?size=1440x1080`
binds the Preview alone to exactly that size (FALLBACK_RULE_NONE: "camera: preview 1440x1080"), `?analysis=WxH` the analysis; the Settings
tab's Camera button opens it once the permission is granted on a device with a camera (`--camera fake`; `CameraTest`).
`apksim://video` opens `VideoActivity`: a 2 s 1080p60 H.264 clip looping full-screen in a TextureView with ExoPlayer,
muted; `video` twice a few seconds apart should show `rendered` going up ~60 a second and `dropped` not at all.
`apksim://video?codec=vp9` plays a 2 s 640x360 VP9 WebM instead; its label reads "video: vp9 selected, frames N"
("unsupported" where no VP9 decoder is listed, as the published Linux image).
`?surface=1` draws into a SurfaceView, `?fit=1` puts the view in a centred 16:9 box as Media3's PlayerView lays it out
(822x462 on the phone, 1462x822 turned), `?ballast=MB` keeps that many MB of small live objects (a large app's heap: a
full collection then takes ~200 ms at 500). The activity handles its own configuration changes, so a rotation resizes
the view under the same codec; the activity below it in the stack is still recreated.
`apksim://player` opens `PlayerViewActivity`: Media3's real `PlayerView` (media3-ui, Views, no controller) playing the
same clip looping and muted; label "player: surface|texture, keep|new, instances N". The default SurfaceView or, with
`?surface=texture`, a TextureView (two layout files: the type is an XML attribute); FIT, so `video` shows 822x462
portrait and 1461x822 landscape. No configChanges: a rotation recreates the activity. By default a new ExoPlayer and
codec per instance; `?keep=1` hands the ExoPlayer to the next instance (`onRetainNonConfigurationInstance`, the
ViewModel-held-player pattern), so `codecsConfigured` stays 1 across rotations (issue 492's player shape).
`apksim://crash?kind=coroutine|post|worker|bare|resume|config|destroy|handled|chained` opens `CrashActivity`, which throws 300 ms later: in
viewModelScope.launch, a posted Runnable, a worker thread, a worker thread with an exception made without a stack trace
(`bare`); `resume`, `config`, `destroy` throw from the second onResume (`recreate`, `restart URL`), onConfigurationChanged
(`appearance dark`, `rotate`) and onDestroy (`restart`); `handled` first sets a default handler that keeps the app
running ("crash: handled by the app ..."), `chained` one that passes it on (`--activity .CrashActivity` is `coroutine`;
`ci-smoke.sh` checks it exits 10 with FATAL EXCEPTION, `handled`, and the test app's attachBaseContext throwing with
`--jvm -Ddev.apksim.testapp.crash_in_attach=true`).
`apksim://system-sources` opens `SystemSourcesActivity`: system state as apps read it and await it in coroutines
(callbackFlow + first(): Wi-Fi's signal level bucketed as `calculateSignalLevel(rssi) / (maxSignalLevel / 4)`, Wi-Fi's, the network's and the battery's
sticky broadcasts, a default and a Wi-Fi network callback, one registered in Application.onCreate, VOLUME_CHANGED_ACTION
after setStreamVolume, a settings observer, an A2DP profile proxy), one line each, "timed out" after 3 s or "error"
(`ci-smoke.sh` checks none does but Bluetooth's, which connects only while Bluetooth is on; `SystemSourcesTest` in a
test run).

Compare mode and previews (docs/pr-preview.md): `/?compare=A,B` is `serve/compare.html`, two ordinary sessions in one tab
with every input sent to both and the differing pixels counted in the page (`window.apksimCompare()`); `scripts/pr-preview.py`
and `.github/actions/preview` put a pull request's build on a serve (with `ttl`, `Catalog.expireAt`) and keep one comment.
`scripts/compare-check.py` checks them (CI: `ci-smoke.sh`, protocol layer; `--chrome` for the page's own count).
Report button and network inspector (same doc; `serve --allow-rec --issue-url`, `serve --inspect-network [optional]`: `optional` lets a session ask with `/ws?inspect=1`, the beam agent's `inspect-network optional` a `/beam/start` with `"inspect": true`; where it is on for all, `/ws?inspect=0` or a start's `"inspect": false` declines it, kept as the launcher's no-op `--no-network-inspect` on the agent, and `/beam/hello` says `inspectOptOut`: issue 389; the `i` message says `inspect` and `inspectAllowed`; `/ws?inspect=SESSION` (beam: `&key=`, issue 497) is a second socket for the inspector alone, `Session.inspectorSocket`, with its own `netwatch` connection to the instance, no frames and no viewer count, docs/serve.md): the page's cards and script are
`serve/report.html`, put into `serve.html` at its two `apksim:report` markers; the instance side is `net/Inspector.java` (`--network-inspect`:
capture in memory, `netwatch` event stream, `inspect watch on|off` (issue 498: opt-in per tab; off sends `n c {total,errors}` at most once a second, on sends the backlog not yet delivered and then `n b [...]` every 250 ms; `Session.netEvent`/`watchTick`, checked by `inspector-check.py`'s `watch_checks`, which needs a serve with `--inspect-network`), `network rule|detail` (answered on the control connection's own thread, not Host's loop, issue 445: a tab's early rules are applied before the first activity, `Session` sends them ahead of `stats`; not logged as control lines nor kept by the flight recorder; `scripts/inspector-early-rules-check.py`), rules answered in `NetEngine.answerRule`, patch rules (issue 373: the real JSON answer merged with an RFC 7386 patch in `recordExchange`, through `ResponsePatch` and `JsonMerge`), forward rules in `answerForward` (`rebase`, issue 427: the app's path and query kept on the target, `Inspector.rebaseUrl`): a matched request sent to an upstream under one of `serve --inspect-forward-allow`'s prefixes, `serve/ForwardAllow`; the rules are one immutable list
swapped whole, so `inspect rule set JSON` replaces them atomically; a rule may carry response headers; `serve --inspect-max-rules N` →
`--network-inspect-rules N`); in a beam session the list is the owner's `apksim beam config forward-allow URL_PREFIX` (`/beam/hello`'s `forward`), read at each use, and the agent sends the request for the sandboxed instance: `--forward-link`, `net/ForwardLink` over the `fwdwatch` control connection, `net/ForwardSend`, `Session.watchForward`; issue 426, docs/beam.md "Forward rules", `scripts/check-forward-link.sh` in CI and `scripts/beam-check.py APK --only forward`; a trusted page can ask the owner to allow one loopback forward/port/host target with `POST /beam/allow` (`Serve.beamAllow`, `Beam.allowedToAllow`: a dialog naming site and target, hello's `allowRequest`; all three lists are read at every start, so no restart; issue 472, `scripts/beam-check.py x --only allow`)); `scripts/inspector-check.py` (`--forward-port N` for the forward round trip), `scripts/check-inspector-rules.sh` (CI: patterns, set, headers, the limit, a swap under matching threads).
