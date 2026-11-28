# Control commands (`--control`) and what stands behind each

Agent reference notes, moved out of AGENTS.md so they are read only when needed. Quoted section names
("Video", "Warm launches") are sections of these files: AGENTS.md's index says which file has each.

## Control commands
Control commands: `tap x y`, `swipe x1 y1 x2 y2 [ms]` (a finger moving over ms of real time, 300 by default as `input swipe`, lifting at the end point so the release
velocity flings as on a device; Host's `Drag` follows the clock, not a count of queued events; before 2026-11-13 it held
120 ms before every lift, so a timed swipe never flung), `drag x1 y1 x2 y2 [ms]` (the same path, then still at the end for 120 ms and 24 moves, or 250 ms, before lifting:
no fling, so a scroll by the same distance every time; what screenshot specs, crawl.py and the MCP swipe tool send. Its
moves are a fixed series of points, one per 8.3 ms of MS, one per loop turn, not read off the clock: a list that takes
a touch over at the slop starts from the first sample past it, which the clock put anywhere within a step; and
Robolectric's VelocityTracker reads its last 20 samples in 200 ms, so a 120 ms hold of ~16 moves still flung. The same
drag landed up to ~50 px apart before. On a screen slower than 120 fps the moves take longer than MS (a turn each: a
300 ms drag at 20 fps ~1.8 s), and a drag under the touch slop stays down ~0.5 s, near a long press; `scripts/drag-check.py`, in ci-smoke.sh),
`wheel x y amount`, `type text`, `key NAME [count] [ctrl|shift|alt|meta]` (e.g. `key A 1 ctrl`, `key DEL 5`), `back`,
`paste`, `shot file.png` (app pixels), `shot --skin file.png` (in the skin's frame), `wshot file.ppm` (the real window, captured in-process), `activity`, `windows`
(each window's position, size, focus, visibility and owning activity with its lifecycle flags, e.g.
`.MainActivity{stopped}`), `viewtree [FILE]` (`ViewTree`: every view of every window, indented: class, id name, screen
position and size, and only what differs from a plain view: invisible/gone, alpha, translate, z, scale, rotation,
click/longclick listeners, focus; `[compose]` marks an AndroidComposeView and `[AndroidView]` the holders of its
AndroidViews; the reply joins lines with `; `, FILE gets them as lines, `adb shell apksim viewtree` prints lines),
`insets` (system bar insets the app sees), `slop` (gesture thresholds), `frames` (frame-interval percentiles since the
last call, then what reached the screen per display vsync), `fps` (per-stage cost), `display`, `shaders` (which AGSL runs on GPU vs CPU), `stats`
(frames, heap, `footprintMb`: the physical footprint as `footprint PID` shows it (`Footprint`), `cpuMs`, PID, data slot, `clockAheadMs`: how far SystemClock has run ahead of real time, `lastDown`: where the last touch went down, in display pixels, `inputMerged`: queued moves and wheel events merged into a later one), `redraw`, `stream [QUALITY] [h264 [KBPS]]` (the connection becomes a JPEG or H.264 frame stream: see "apksim serve"), whose connection may then send `key`, `pace MS` and `viewport W H` (serve.html sends the canvas's size: a display shown at half size or less is encoded divided by a whole factor, the device's size in the header's upper 16 bits, `0 0` for full; issue 499, docs/serve.md, `scripts/viewport-check.py`, `viewport-page-check.py`), `ping`, `quit`,
`audio [on|mute]` (sound or silence now, open AudioTracks included; off is launch only) and `video [on|blank]` (next codec),
`maxfps [N]` (frame rate cap, 1-120; see "Per-instance costs" below), `animscale N` (animation speed, any time),
`vsync [PERIOD_US SHIFT_US|off]` (pace to a viewer's display: see `Host`).
`gaps` (`Gaps`, see Layout): framework natives with no shadow that this process called, outside the baseline of harmless ones.
`deps [reset|read|mark|resources [all]|start [resources]]` (`dex.Probes`, `ResourceHits`; with `--jvm -Dapksim.deps=true`): the app classes whose code ran and the resources resolved since the last reset or `restart` (each `restart` opens a span), and the app-start span; `crawl.py --record-deps` reads them per screen (docs/crawl.md "Per-screen dependencies").
For screenshot tests (docs/snapshots.md): `settle [MAX_MS] [QUIET_MS] [HORIZON_MS]` (answers once nothing is queued,
dirty or wanting a frame (a vsync requested for HORIZON_MS with no frame drawn, as an open Compose Popup's position polling does, is ignored and the answer says so: issue 612, `Host.busy`) and no main-thread message is due within HORIZON_MS (not counting a caret blink or AndroidX ProfileInstaller's delayed startup write), for QUIET_MS of real time with no frame
drawn; else `error: not settled ...: REASON` ("a frame is wanted" says what wants it when it can: `: a spinning ProgressBar #id at X,Y`, or `: animators running: ...`); while the app is in the background (`home`, `lock`, Back on the root launcher activity) nothing draws and either answer ends `(app in background: ...)`: match the `settled`/`error:` prefix, as mcp.py does), `restart [URL]` (every activity destroyed, the start activity (launcher or
`--activity`) or the URL's started fresh: no saved state, ViewModels cleared), `diff GOLDEN ACTUAL [MAP] [DELTA]` (pixels differing by more
than DELTA, and a difference map), `clock [ISO|epoch-ms|real]` (the app's wall clock starts there and runs on).
`back` on the last activity finishes it and the process exits a second later (`[apksim] exit 0: the last activity,
...`, see `Exits`): there is no soft keyboard for it to dismiss. Except on the task's root activity when it was started
from the launcher intent (MAIN/LAUNCHER, the only activity in the stack) and nothing handles back: as on Android 12+
(`ActivityClientController.onBackPressed` -> `moveTaskToBack`) the task goes to the back, as `home` does without
`onUserLeaveHint` (`Host.moveRootToBack`, `DeviceControls.backToHome`; test runs do the same in `ShadowSimActivityClient`),
the instance stays and `resume` or a click brings it back (issue 480). The launcher test reads the activity's current intent, so one that called `setIntent` with another still finishes (a device uses the task's base intent).
Simulator-style device controls (`DeviceControls.java`; each is also a Mac menu item, see "Simulator features"):
`appearance dark|light|toggle`, `locale fr-FR`, `fontscale 1.3|+|-`, `timezone [ZONE]` (the zone the app sees; set at
launch with `--timezone America/New_York`; see `TimeZones` below), `config` (configuration the app sees),
`fold [open|half|closed|toggle|angle DEGREES]` (a folding device's posture and hinge angle, see `Fold`),
`device [phone|foldable|tablet|desktop|tv|pixel-10-pro-fold|galaxy-...|WxH[@density]]` (switch devices while the app runs: size, density, system
bars, and for TV UI_MODE_TYPE_TELEVISION, no touchscreen and a D-pad), `size WxH` (display size in dp, same device),
`window WxH [MS]` (the Mac window's content in points, as dragging its corner would: checks the drag path; with MS a
live drag over that much real time, 120 steps a second: `scripts/window-resize-check.py PORT`, run by hand on the Mac),
`window front|show|background|minimize|hide` (as `--window`, below; `window` alone reports visible/minimized/hidden,
whether the app is active, the content's size in points and the device pixels per point), `window click X Y` (a left
click at a point of the window's content, in points from its top left, sent through the window as a real one: checks
which view takes it), `skin [NAME|off]` (`Skinning`: a stored skin around the screen; alone, where it falls), `window close` (as the close button: while a stream viewer is attached, apksim beam's
tab, it only hides the window),
`animscale 10|1|0|toggle`, `memory [moderate|low|critical]`, `home`/`resume`, `lock`/`unlock` (a click on the window
also returns), `open URL` (deep link into the app), `clipboard [set TEXT]`, `record start [file.mov|file.mp4]|stop|toggle` (Mac
window only; a `.mp4` name gets MP4, else QuickTime; the last picture is held to the stop time, so the file lasts as long as the recording), `reveal`, `pinch CX CY FROM TO [ms]` (two fingers), `rotate left|right|portrait|landscape`,
`zoom actual|fit|in|out` (window size only), `permission [allow|deny|grant NAME|revoke NAME|reset]`,
`notifications [open N|last|clear]`, `location LAT LNG|run|bike|drive|gpx FILE|none`,
`network [online|offline|3g|lossy|latency MS|bandwidth KBPS|unmatched [clear]|save|reconnect]` (see "Network"),
`network wifi [on|off|toggle|signal DBM|signal level N]` and `network cellular [on|off|toggle]` (`Radios`: Wi-Fi and
mobile data as a phone's two switches, and Wi-Fi's signal; `--wifi on|off`, `--cellular on|off`, `--wifi-signal
DBM|level:N` at launch; Wi-Fi off with mobile data on makes a metered TYPE_MOBILE/TRANSPORT_CELLULAR network the default,
both off is offline as `network offline` is; the signal reaches WifiInfo, the network's capabilities and
RSSI_CHANGED_ACTION; Features > Network > Wi-Fi / Mobile Data, `svc wifi|data`, `cmd wifi set-wifi-enabled`, `cmd phone
data`; mobile data is off by default, as apksim never showed a cellular network; docs/network.md "Wi-Fi and mobile
data", `RadiosTest`),
`biometrics [enroll|unenroll|match|nomatch|cancel]`, `bluetooth [on|off|toggle]` (`Bluetooth`: the adapter, on at
launch as an emulator boots, `--bluetooth off` otherwise; each switch, the app's own BluetoothAdapter.enable()/disable()
included (`ShadowSimBluetoothAdapter`, allowed at any targetSdk as Robolectric allowed it), goes TURNING_ON then ON, or
TURNING_OFF then OFF, with an ACTION_STATE_CHANGED broadcast for each and Settings.Global `bluetooth_on` following
(written even when there is nothing to switch); `toggle` and a `device` switch read the adapter's own state, which is the
only record of it; name Build.MODEL, address
02:00:00:00:00:00 as a device tells apps; Robolectric's LE scanner, which finds nothing; no adapter at all without
android.hardware.bluetooth; in test runs each test starts with the launch's state, and `executeShellCommand("svc
bluetooth disable")` switches it, `BluetoothTest`), `recreate` (as a configuration change: ViewModels kept),
`process-death` (state saved, activities destroyed and rebuilt from it; statics and the Application survive, so this
tests state saving, not a cold start), `debug layout on|off|toggle` (Show layout bounds, Views and Compose; minified
Compose apps need a `recreate`), `a11y [show]` (unlabeled controls, touch targets under 48 dp), `pick FILE...|cancel`
(answers a document/photo picker). Activities started for a result now get one (`ActivityResults`): an in-app
activity's setResult when it finishes; OPEN_DOCUMENT/GET_CONTENT/PICK/PICK_IMAGES open the Mac file panel (sheet;
headless: `pick`) and return `content://dev.apksim.files/N/NAME` (`PickedFiles`: name, size, stream); anything else
leaving the app answers RESULT_CANCELED. The launcher sandbox blocks ~/Documents and ~/Desktop, so pick from
Downloads. ACTION_SEND (and choosers) go to the Mac share menu and the log.
Background work (`BackgroundWork`): started services run (and stop on stopSelf), JobScheduler jobs run once their
delay passes (charging/idle ones wait for `jobs run ID`; WorkManager rides on this), alarms fire on the simulated clock
(Robolectric's auto-schedule) and reach manifest receivers. `jobs [run ID]`, `services`, `alarms` list them.
Bound services (`BoundServices`, from `TestActivities.ShadowSimInstrumentation`'s bindService/unbindService, so in
test runs and running instances alike): Robolectric never runs a service on bind and calls onServiceConnected with a
registered binder, else with (null, null). For a service in the app's own manifest apksim binds as a device does: a
BIND_AUTO_CREATE binding creates it, onBind runs once per intent, and each connection gets onServiceConnected with
that binder or, when onBind returned null, onNullBinding; unbinding calls onUnbind (then onDestroy once no
BIND_AUTO_CREATE binding is left) and never onServiceDisconnected; connections bound without the flag that outlive
the service get onServiceDisconnected and onBindingDied, and are not connected again. As on the API 36 emulator (`NullBindingTest`), the
service class initializes on the main thread, onBind gets the first binding's intent for its filter even if that
binding ended before the service ran, and a connection bound twice hears of a binder once but of a null binding once
per binding (LoadedApk's ServiceDispatcher). Intents a binder was registered for through ShadowApplication (the FCM
service below) stay Robolectric's. A bind no installed service resolves (another app's: a companion app, Play
services) returns false and never calls back, and unbinding that connection calls nothing either, as on a device;
Robolectric returned true and called onServiceConnected(null, null), then onServiceDisconnected(null) on unbind, which
a Kotlin connection's non-null parameters threw on (a running instance died on its first idle; `apksim://install`,
`BindServiceTest`). Broadcasts (`ShadowSimBroadcastReceiver`, issue 452): each ordered delivery to a registered receiver has its own
PendingResult. Robolectric sets it on the receiver object when the delivery is queued, so two ordered broadcasts
(`sendOrderedBroadcast`, `am broadcast`) queued to one receiver before either ran both finished the second's and the
second `finish()` threw "Broadcast already finished", ending the app (`BroadcastQueueTest`). A bound service is not the
instance startService runs, and an `android:process` service gets its local binder, not a proxy.
`push key=value...` or `push {"data":{..},"notification":{..}}` delivers an FCM message as Play Services would: the
c2dm RECEIVE broadcast to the app's FCM receiver, with the messaging service pre-bound (Robolectric connects a binding
only to a registered binder). Checked with the owner's app: its messaging service posts the alert.
`ble [add FILE|PRESET[:ADDR]|remove ADDR|all|rssi ADDR N|notify|set ADDR CHAR HEX|disconnect ADDR]` and `--ble FILE|PRESET`
(`dev.apksim.ble`, docs/ble.md): scripted BLE peripherals that the framework's own BluetoothLeScanner and BluetoothGatt
find and talk to (apksim answers their IBluetoothScan/IBluetoothGatt binders), in running instances and test runs
(`BleTest`; `scripts/ble-check.py` drives `apksim://ble`).
`health [add FILE|PRESET|JSON|clear [TYPE]|list TYPE|types]` and `--health FILE|PRESET` (repeatable; `dev.apksim.health`,
docs/health-connect.md): Health Connect. On API 34+ AndroidX's HealthConnectClient asks `getSystemService("healthconnect")`
(null before: no such service in Robolectric's ServiceManager, so SDK_UNAVAILABLE) and uses the framework's own
HealthConnectManager; `Health.reset` (from DeviceIdentity, before every Application.onCreate, in a try of its own)
publishes a `LazyBinder` whose interface, `HealthService` (a Proxy: methods it lacks answer ERROR_UNSUPPORTED_OPERATION
through their callback or throw UnsupportedOperationException), is made at the first lookup, over a store of the
framework's RecordInternal objects (`Store`, copied through their parcels, so every record type), kept in
`<slot>.device/health.store` in running instances (read on first use, written by `apksim-health-store` after each change
and by `SlotDevice.flush`; tied to the framework build), seeds alone per test in test runs; seeds (`Seeds`, origin
`dev.apksim.health`, preset `week`, ids from their content) replace the last launch's, and one that does not load is exit
2. Insert, read (a filter takes an interval by its start; a write-only app must name itself as the origin; both as an
emulator does), update, delete, change tokens (log of 20,000; older is expired) and aggregates (`Aggregates`: every
AggregationType by reflection, sums split by overlap, session durations less DURATION_EXCLUDE_TYPES, at most 5000
groups; no priority lists) with the platform's permission checks (`HealthConnectMappings`: the type's permission
category); an open filter end the framework filled from the real clock is moved by SimClock's offset (`openEnd`).
Health permissions are runtime permissions (InstallState, `permission grant health.X`), and `ShadowSimPackageManager`
keeps the app's PackageInfo GRANTED flags for them in step with its grants, which AndroidX's getGrantedPermissions
reads. `HealthConnectTest` (18; all but three apksim-only ones pass on an API 36 emulator), `apksim://health`
(`HealthActivity`, ci-smoke.sh, with the week preset's stability across clocks).
`camera [pattern|image PATH|off]` (`Cameras`, see "Camera": what the simulated cameras see; alone, their state: source,
open and streaming devices, frames, images, drops, outputs, then `manager: idLists N availabilityCallbacks N (registered N) announced N opens N open [...]`: what the app asked CameraManager, also logged as `[apksim] camera: ...` lines on stderr outside test runs).
`touchmode [on|off]` (`TouchMode`, issue 424): the display's touch mode as a device's window manager keeps it. A handheld's
windows start in touch mode (Robolectric's window session already did), a touch (`tap`, `drag`, `swipe`, `pinch`, the
window's clicks) enters it, and a navigation key (Tab, D-pad) or a typing key (`key`, `type`, the Mac keyboard: a hardware
keyboard's keys, as `adb shell input text`) leaves it, through ViewRootImpl's own `checkForLeavingTouchModeAndConsume`,
which Host's direct dispatch to the views bypassed; every window is told (`ensureTouchModeLocally`), and the window
session's answer for later windows set (not through IWindowManager.setInTouchMode, which Robolectric's stand-in drops). A
TV starts out of touch mode (`--device tv`, and a `device` switch to or from one); pasted text (`paste`, Cmd+V) keeps
touch mode, as text an IME commits does (FLAG_KEEP_TOUCH_MODE). `TouchMode.start` (each launch and test setup, once the Application is up) also clears
ViewRootImpl's `sCompatibilityDone`: the framework decides once per process, from its first window's app, whether focus is
always assigned (`sAlwaysAssignFocus`, targetSdk below 28), and in a warm launch that window was the standby's warm-up's,
so every warm launch gave the first text field focus at launch and whenever a window gained focus with none (a resume,
a recreation; Compose then scrolled to it), which a cold launch never showed. The command alone reports the mode, "focus
always assigned" when that flag is set, and per window `touch|keys`, the window focus and the focused view's class; in
test runs through `executeShellCommand("apksim touchmode")`. Test runs get the starting mode only (Robolectric's
injection does not leave touch mode on keys). `-Dapksim.touchMode=false` leaves all of it as before (launches cold).
`TouchModeTest`, `ci-smoke.sh` (the Linux image starts no standby unless asked, so the warm case is checked by hand: launch twice,
`restart apksim://composeapp/trips`, `touchmode` must show no `focus=`).
`thermal [none|light|moderate|severe|critical|emergency|shutdown]` (`Thermal`, issue 149): PowerManager's thermal status,
NONE at launch; `addThermalStatusListener(Executor, listener)` registers (Robolectric's shadow covered only the one-argument
overload, and the real one threw "Thermal status listener failed to set"), is called at once with the status and again on
each `thermal` change, and `getCurrentThermalStatus` agrees (`apksim://system-sources` has a "thermal" line).
`sensor [list|accel X Y Z|gyro X Y Z|tilt PITCH ROLL|shake [MS]|light LUX|proximity near|far|CM|pressure HPA|humidity PCT|temperature C|steps N|+N|heart-rate BPM|off|reset|NAME off|on]`
and `--sensor NAME=VALUE` (repeatable; `accel=0:9.8:0`, `tilt=-45:0`; `-Dapksim.sensors`, kept by warm launches) (`Sensors`): SensorManager's sensors, which
Robolectric's ShadowSensorManager has none of (an app coroutine awaiting a first sensor value hung silently: issue 96's
class). A device has what an API 36 emulator lists (`dumpsys sensorservice`: names, types, ranges and resolutions as it
gives them; accelerometer, gyroscope, magnetometer, hardware and software orientation, light, proximity, pressure,
humidity, ambient temperature, the three uncalibrated ones, gravity, linear acceleration, the rotation vectors), plus a
step counter and detector (a phone has them, the emulator does not) and heart rate on a watch; a TV has none, a
desktop no proximity, temperature, humidity, pressure or step sensors; the matching `android.hardware.sensor.*`
features follow (`SystemFeatures`, re-applied by `device` and `sensor NAME off`). `ShadowSimSensorManager` replaces
Robolectric's: each (listener, sensor) registration is a delayed message on its Handler's looper (the main looper
without one), re-posted after each event at the rate asked (SENSOR_DELAY_* or microseconds, clamped to the sensor's
minimum and maximum delay); a continuous sensor delivers at registration, then each period, with
`SystemClock.elapsedRealtimeNanos` timestamps; an on-change one (light, proximity, humidity, temperature, step counter,
heart rate) delivers its value at registration and on each change, none when the value is unchanged; the step detector
fires once per step added. A second registration of a listener for the same sensor returns false and keeps the first,
as the emulator does (SensorsTest checks that and the rest on both). No thread and nothing while nothing is
registered, and the clock is never moved. Values are one pose: gravity in the device's frame, at rest from the display
rotation (portrait upright 0 9.81 0; `rotate right`, ROTATION_90, gives 9.81 0 0), or set by `accel` and `tilt`
(`tilt 0 0` is flat: 0 0 9.81); the magnetometer is the emulator's field (47.75 uT horizontal, 9.88 vertical) in that
frame, and orientation and the rotation vectors come from both through SensorManager's own getRotationMatrix, so the
sensors agree (upright: rotation vector 0.71 0 0 0.71; the emulator 0.68 0 0 0.74 at its slight tilt); `gyro` sets a
rotation rate that stays; `shake` adds a decaying 8 Hz burst to the accelerometer and linear acceleration. Defaults are
the emulator's: light 0, proximity 1 (far, its range), pressure 1013.25, humidity 0, temperature 20 (the emulator
reports -1e28). A sensor that needs a permission (heart rate: BODY_SENSORS; step sensors: ACTIVITY_RECOGNITION,
which binds only apps targeting 29 and up) returns false from registerListener without it, as the sensor service
refuses. `sensor NAME off` makes getDefaultSensor return null and stops that sensor's events. Test runs: `reset()` at
each setup, `executeShellCommand("apksim sensor ...")` as `thermal`; events follow the handler looper's clock, which in
a default test run is simulated, so a test that does not move it sees each registration's first event only, and a continuous sensor there also delivers at most one event per period of real time (Robolectric's unpaused Choreographer moves the clock 15 ms a frame, so a main-looper listener updating a view per event made each frame bring the next event due inside one `idle()` and the first Espresso check hung: `SensorsTest.zz_...`); waiting on
the main thread with a `CountDownLatch` (clock following real time) or `idleFor` delivers them, and
`ShadowLooper.runToEndOfTasks` never returns while a continuous listener is registered. A device switch (and `NAME off`) drops the registrations of sensors the device no longer has; one `Sensor` object per sensor lives as long as the device, so `on` restores it. `heart-rate off` clears the reading (the sensor stays). Under `--snapshot` a continuous sensor delivers its first event only. A sensor's tick is not work that changes the screen: `Host.dueMessage` skips it (before, `settle` never settled while a listener was registered, naming `Sensors$Reg`), and Host's idle sleep is cut to the next tick of a listener on another looper (`nextOtherLooperMs`; a GAME listener on a HandlerThread got ~32 events a second, now 50). Each registration posts on a Handler of its own on the app's looper (an app clearing its handler's messages does not end the stream), tells `onAccuracyChanged(HIGH)` once before its first event as the event queue does, and gives strictly rising timestamps. Sensors that need a permission are hidden from `getDefaultSensor`/`getSensorList` without it (ACTIVITY_RECOGNITION only for apps targeting 29+; heart rate BODY_SENSORS, or on an app targeting 36+ also `android.permission.health.READ_HEART_RATE`: from memory, not checked on a device: the emulator has neither sensor), and an app targeting 31+ without HIGH_SAMPLING_RATE_SENSORS gets the emulator's SecurityException for FASTEST. The `Sensor` objects live for the process (an app static from an earlier test still registers); a negative rate returns false and `flush` true for any registered listener. Robolectric's own test hooks (`shadowOf(sm).sendSensorEventToListeners`, `addSensor`, `getListeners`, `setForceListenersToFail`) do not see these registrations. The control channel's `sensor` is also allowed from a serve tab, values only
(`Session.check`). Not done: Device > Shake in the Mac menu, a tilt panel, `SensorDirectChannel`, trigger sensors
(`requestTriggerSensor` returns false), batching, dynamic sensors, data injection, the emulator's two debug sensors, the
flight recorder (only the commands are kept). The test app's `apksim://edittext` (`EditTextActivity`, a focused View EditText: `settle` must answer despite the caret blink, issue 479; `ci-smoke.sh`) and `apksim://idle-zoo?on=a,b,c` (`IdleZooActivity`, plain Views, one switch and button per source: `ticker` 16 ms and `slowticker` 500 ms reposting Runnables, `caret`, `scroller` (OverScroller through computeScroll, 1000 ms), `animator` (INFINITE ValueAnimator), `viewanim` (300 ms AlphaAnimation), `sensor` (GAME accelerometer listener), `overlay` (0x0 window), `popup`, `worker` (thread posting a no-op every 50 ms), `progress` (indeterminate ProgressBar: turns for as long as it is shown, see `ShadowUiThreadVectorAnimations`); each label says what a device user would call it, "expect idle|busy|busy then idle within N ms", and its counter shows only after Refresh, so nothing changes the screen by itself; `IdleZooTest` is the Espresso side, run on both clocks, busy sources with `-e idleZooBusy true`) and `apksim://sensors[?rate=fastest|game|ui|normal]`
(`SensorsActivity`) lists every sensor with its range, event count and last values; `SensorsTest` (23 tests, 8 need the
`sensor` command; 15 run on the emulator) passes on apksim and on the API 36 emulator, and `ci-smoke.sh` drives the screen (the first event,
gravity per rotation, light and proximity changes, flat, `sensor light off`).
`compat [all]` (`CompatTable`, `CompatShadows`, `runtime/src/main/resources/dev/apksim/compat-table.tsv`): platform compat
changes follow the app's targetSdk. Robolectric's `ShadowCompatibility.isChangeEnabled(long)` answered false for
78294732, compared the target for two ids and otherwise called the real `Compatibility`, whose default delegate says
true: an app targeting 28 got every target-gated behaviour of the latest SDK. The table (355 changes, from an API 36
emulator's `dumpsys platform_compat` by `scripts/gen-compat-table.py [-s SERIAL]`, sorted so a regeneration diffs
cleanly; per-package overrides ignored) gives each id's enableSinceTargetSdk or `disabled`; `ShadowSimCompatibility`
answers disabled → false, since N → min(targetSdk, 36) >= N (as a device's `CompatChange.isEnabled` clamps to the platform's SDK, so the 37- and 10000-gated changes are off for every app, one targeting 37 too), and an id the table lacks as before. 78294732 stays off
(Robolectric's activity controller delivers results itself). The target is the manifest's `uses-sdk` (else
minSdkVersion, else 1), set by `AppInfo.load` per launch (`CompatTable.configure`: a standby boots before the APK is
known, so nothing is kept; with no target known the old answers stand). `CompatChanges.isChangeEnabled(long, pkg, user)`
and `(long, uid)` asked Robolectric's platform_compat stand-in, which said false to everything; they answer for the app's
own package or uid from the same table, for another installed package by its own targetSdk, and for one that is not
installed by the change's default (on unless disabled), as a device does; with the table off, false as before. The lookup is a binary search and an array
increment: no allocation. `compat` lists the changes this process asked about, "NAME (id): on x3, off x1 [since N]",
ids the table lacks as "(not in table)"; `compat all` adds the table's size and the target in use. It works in test runs
too (`executeShellCommand("apksim compat")`; counts restart each test), to see which gates a target-N app hits.
`-Dapksim.compatTable=false` restores the old answers for A/B runs. `scripts/check-compat-table.sh` (CI,
`CompatTableCheck`) checks the logic and that a lookup allocates nothing; `CompatChangeTest` (test app, target 36: a
36-gated change on, a 37-gated one and a disabled one off) passes on the API 36 emulator and here. The table is parsed
in the standby's warm-up (`CompatTable.preload`), not on a launch's main thread.
`keyboard soft on|off` / `keyboard show|hide` (`SoftKeyboard`): as in the iOS Simulator a hardware keyboard counts as
connected, so no soft keyboard by default; with it on (Device > Software Keyboard Cmd+K, `--soft-keyboard`) a focused
text field raises a drawn keyboard and the app gets real IME insets (imePadding/adjustResize), Back closes it first.
`rotary N` (the crown, `SOURCE_ROTARY_ENCODER`/`AXIS_SCROLL`; the wheel sends it on a watch device) and `ambient [on|off]` (Wear OS
ambient mode through `com.google.android.wearable.compat.WearableActivityController`, apksim's stand-in for the watch's shared
library, which androidx.wear.ambient finds; never entered unless asked). `--device watch|watch-small|watch-large`: round
(qualifier `round`), UI_MODE_TYPE_WATCH, `android.hardware.type.watch`, no bars; `FrameStream.roundMask` clears the corners of
streamed frames. Not done: a round mask in the Mac window, tiles and complications, a Wear test app.
`bars [status on|off|toggle] [nav gesture|off|toggle]` (`SystemBars`; Device > Status Bar / Navigation Bar,
`--status-bar on|off`, `--nav-bar gesture|off`): which system bars the device has, overriding the `--device` preset
(phone, foldable, tablet: both; desktop, TV: neither) and kept across `device` switches. A bar that is off is not drawn
and gets no inset (Robolectric's `SystemUi` behaviour NO_STATUS_BAR / NO_NAVIGATION_BAR), as on a device without it;
`bars` alone says what the app sees ("status on (hidden by the app), nav gesture (shown)"). The app hiding the bars needs
no hook of ours: `WindowInsetsController.hide` reaches Robolectric's window session as the window's requested visible
types, which sets the bar invisible in `SystemUi` (inset 0), and ViewRootImpl's compatibility code turns the legacy
`SYSTEM_UI_FLAG_FULLSCREEN`/`HIDE_NAVIGATION`/`IMMERSIVE*` flags and `FLAG_FULLSCREEN` into the same hide;
`SystemBars.drawn` reads that visibility every 50 ms and draws only the bars that show. A swipe in from a hidden bar's
edge (from within 24 dp, moving 24 dp inward; `Host.dispatchTouch`, the app still gets the touch) shows them as the
system does for the window's behaviour: BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE (legacy IMMERSIVE_STICKY) draws them over
the app for 2.25 s with the insets unchanged, anything else calls the window's `show` (a device's `IWindow.showInsets`),
and the deprecated show-by-touch behaviour (legacy flags without IMMERSIVE) shows them on any touch. Test runs keep
Robolectric's no bars unless `--status-bar`/`--nav-bar` ask for them, reset at each test's setup; a test's
`executeShellCommand("apksim bars ...")` changes them for that test (`SystemBarsTest`). The test app's
`apksim://systembars` screen shows its insets ("bars: status=48 nav=48") and has buttons that hide the bars each way.
The Android Keystore (`SimKeyStore`, provider "AndroidKeyStore": KeyStore, AES/HMAC KeyGenerator, EC/RSA
KeyPairGenerator with KeyGenParameterSpec) is a test keystore: keys persist in the app's data folder in the clear and
no use restriction is enforced.
`record` without a Mac window (headless, Linux) writes Motion-JPEG `.avi` (`MjpegRecorder`, 30 fps, Android's JPEG
encoder); with a window `.mov` H.264, or `.avi` if the file name says so. Configuration changes recreate activities as on a device (state not in
`rememberSaveable`/`onSaveInstanceState` resets). A command that throws answers `error: ...` instead of ending the app.
Every answer is one line (`Control.oneLine`: line breaks become `; `), as every client reads one line per command.
`nodes tree [all]` (every accessibility node with depth, class, id and flags, records split by U+001E: what `scripts/crawl.py`
hashes and taps),
`ctl.py apps|container PKG|erase PKG` lists, locates and deletes app data (erase refuses while an instance holds it),
erase with each slot's `<slot>.device/` (its /sdcard and Settings values, `SlotDevice`).
`scripts/ctl.py list` / `ctl.py [@PID|@PORT|@APKNAME] CMD [ARGS] [';' CMD...]` finds running instances by themselves
(each writes `work/tmp/<pid>/control.port`); `--control auto` takes a free port.
Who the control channel lets in (`Control.admit`, 2026-11-19): a connection whose first line is an HTTP request
line is closed unanswered, always: a page in the user's browser can POST to a loopback port, and the lines of its body
ran as commands (checked: `clipboard set` from a `curl` POST with an Origin header). `--control-token-file FILE`
(`-Dapksim.controlTokenFile`, warm launches too; a host app's option, not for serve, beam or mcp) makes every connection
send `auth TOKEN` first (FILE's content, 16+ characters, compared in constant time; answered `ok`; anything else gets one
`error:` line and is closed), streams included; a file that cannot be read stops the launch (exit 2; the launcher's
sandbox profile lets that one file be read wherever it is, but a warm launch's standby has its own profile, so a token
under ~/.config, ~/Documents or ~/Desktop needs a cold launch). The file's mode is the owner's to keep private, and
there is no limit on wrong tokens: use a long random one. Any first line shaped `METHOD TARGET HTTP/N` counts as HTTP. Without the option `auth X`
is answered `ok`, so a client may always send it. `scripts/drive.py` reads `APKSIM_CONTROL_TOKEN_FILE`; `ctl.py`,
`mcp.py` and serve do not send a token yet (they start instances without one). `scripts/check-control-auth.sh` (CI,
`ControlCheck`). The adb port has no such option.
