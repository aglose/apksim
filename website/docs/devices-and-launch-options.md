# Devices and launch options

## Devices

| `--device` | Display (dp) | Notes |
|---|---|---|
| `phone` (default) | 411 × 891 | System bars and gesture navigation |
| `foldable` | 841 × 701 | System bars and gesture navigation |
| `pixel-10-pro-fold` | 852 × 883 open, 443 × 970 closed | A folding phone at 390 dpi, with an inner and a cover display (below) |
| `galaxy-s25`, `galaxy-s25-plus`, `galaxy-s25-ultra` | 384 × 832 | 1080 × 2340 px at 450 dpi, the resolution Samsung ships them at |
| `galaxy-a56` | 411 × 891 | 1080 × 2340 px at 420 dpi |
| `galaxy-z-fold7` | 750 × 832 open, 411 × 960 closed | Samsung's book fold (below) |
| `galaxy-z-flip7` | 411 × 960 open, 361 × 399 closed | Samsung's clamshell: the fold runs across the screen, and the cover display is the 4.1" FlexWindow |
| `galaxy-z-trifold` | 1080 × 792 open, 411 × 960 closed | Samsung's tri-fold: two folds, at a third and two thirds of the inner display |
| `tablet` | 1280 × 800 | 2560 × 1600 px, the Nexus 10 profile |
| `desktop` | 1440 × 900 | No system bars |
| `tv` | 960 × 540 | 1080p; television UI mode, D-pad, no touchscreen |
| `watch`, `watch-small`, `watch-large` | 227 × 227, 192 × 192, 240 × 240 | Wear OS: a round screen, watch UI mode, no system bars (below) |
| `WxH[@density]` | custom | For example `1024x768@1.5` |

**Folding phones** (`pixel-10-pro-fold`, `galaxy-z-fold7`, `galaxy-z-flip7`, `galaxy-z-trifold`):

- **Displays:** each has an inner display and a cover display, at the panels' published pixels. The Pixel's inner
    display is 2076 × 2152 px with a fold down its middle, its cover display 1079 × 2364 px. The Z Fold7 folds as a
    book too (1968 × 2184 inside, 1078 × 2520 cover). The Z Flip7 folds top to bottom (1078 × 2520 inside, a 947 ×
    1047 FlexWindow). The Z TriFold's 10" inner display (2160 × 1584, wider than tall) has two folds and a tablet's
    320 dpi; its cover display is a phone's. The densities are assumed, not measured on the devices.
- **Folding:** `--fold open|half|closed` at launch; `fold open|half|closed|toggle|angle DEGREES` or Device > Fold /
    Unfold while it runs. The app gets the display change as on a device.
- **What the app sees:** Jetpack WindowManager (`WindowInfoTracker`, Compose's adaptive posture) reports the folds,
    flat or half-opened, through a stand-in `androidx.window.extensions` library: one vertical fold on a book, one
    horizontal fold on a clamshell, two on the tri-fold. Half-open and rotated is tabletop (on the Flip, half-open
    upright is). A hinge-angle sensor (`TYPE_HINGE_ANGLE`) reports the angle.
- **Skins:** `--skin NAME` with the device's name (built in) draws the device around the screen, open and closed.
- **The fold animation:** the Mac window plays each fold the way the device moves. A book's left half swings over
    onto the right one, with the cover display on its back. The Flip's bottom half folds up, and the closed phone then
    turns over to show its FlexWindow. The TriFold folds its left panel in and then its right panel over both. The
    hinge moves as a hand moves it: an even swing, a magnetic snap shut and a small settle, or the pop of the magnets
    letting go and the flat detent. It is rendered in 3D: each panel is a solid slab of the device's outline and real
    thickness, with machined metal sides in the device's colour, its glass back and a hinge barrel, lit by a studio
    light so that metal and glass reflect and the swinging half casts its shadow. The screens glow, going dark and
    lighting up when a real device's do. The window keeps the device's physical size
    on the Mac's screen through the fold.

**Samsung phones** (`galaxy-s25`, `galaxy-s25-plus`, `galaxy-s25-ultra`, `galaxy-a56`) have built-in skins of the
same names, with each phone's punch-hole camera reported to the app as its display cutout.

**Watches** (`watch`, `watch-small`, `watch-large`):

- **What the app sees:** `isScreenRound`, watch UI mode and `android.hardware.type.watch`.
- **Input:** the wheel is the crown (rotary input; `rotary N`), and `key STEM_1`, `STEM_2` and `STEM_PRIMARY` are
    the buttons.
- **Ambient mode:** `ambient on|off` enters it through a stand-in `com.google.android.wearable` library.
- **Shape:** `serve` streams the frame with the corners cleared; the Mac window shows a square.

Density is 2 (xhdpi) unless given (the named phones and folds have their own). You can also switch devices while the app runs, from the Device menu or with the
`device` command.

## Launch options

`apksim [OPTIONS] app.apk`

Options are grouped by what they set. A long explanation sits under its table.

### Start

| Option | Effect |
|---|---|
| `--activity PKG/.Act\|.Act\|CLASS` | Start this activity instead of the manifest's first launcher one, as `am start -n` would |

**`--activity`** takes an activity that is exported or not, named by package and class, as `.Act`, by its full
class name, or by a simple name when only one activity has it. It is for a home app whose debug manifest lists a
test launcher first, or for a screen deep inside the app. `restart` starts it again. An activity the manifest lacks
stops the launch and lists those it has. An app with no launcher activity starts its MAIN/HOME one.

### Display

| Option | Effect |
|---|---|
| `--device NAME\|WxH[@density]\|WxHpx@Ndpi` | The device to simulate (table above); `1920x1080px@240dpi` takes `wm size`/`wm density` output |
| `--device-profile FILE` | Display, `Build` fields, features and settings of a real device, captured with `scripts/device-profile.py [-s SERIAL] > FILE` over adb; options after it win |

### Window

| Option | Effect |
|---|---|
| `--headless` | No window; the app still runs and renders |
| `--window-x N` | The window's horizontal position on screen |
| `--window front\|nofocus\|background\|minimized\|hidden` | How the window shows itself; `front` is the default |

**`--window`** modes: `front` takes focus like any app, and the others never take focus from the app you are in.
`nofocus` shows the window on top, `background` behind your other windows, `minimized` in the Dock, and `hidden` not
at all. The app runs and renders the same in each; click the Dock icon to bring the window forward.
`APKSIM_WINDOW=MODE` sets a default for every launch.

### Look and locale

| Option | Effect |
|---|---|
| `--appearance dark\|light` | Dark or light mode |
| `--locale TAG` | For example `fr-FR`; `ar-XB` is the right-to-left pseudo-locale |
| `--timezone ZONE` | For example `America/New_York` (JDK, `android.icu`, `user.timezone` for Joda-Time) |
| `--font-scale N` | For example `1.3` |

### Device state

| Option | Effect |
|---|---|
| `--permissions ask\|grant\|deny` | Runtime permission prompts; `grant` is like `adb install -g` |
| `--biometrics enrolled\|auto` | Start with a fingerprint enrolled (`auto` also answers every `BiometricPrompt` with a match, for headless walks and tests) |
| `--soft-keyboard` | Draw a soft keyboard for text fields, with real IME insets |
| `--status-bar on\|off`, `--nav-bar gesture\|off` | Whether the device has a status bar and a gesture navigation bar (default: phone, foldable and tablet have both, desktop and TV neither). A bar that is off is not drawn and gives the app no inset |
| `--fresh` | Erase the app's data first, with the data slot's `/sdcard` and Settings values: a new device with the app just installed |
| `--setting NS:KEY=VALUE` | A `Settings.Global`, `Secure` or `System` value (`global:my_flag=1`) set before `Application.onCreate`, as `adb shell settings put` before a first launch would. Repeatable |
| `--sdcard-file PATH=VALUE` | A file written to `/sdcard/PATH` in the data slot before the app starts. Repeatable |
| `--broadcast 'ACTION;es:K=V;ez:K=true'` | One broadcast to the app's own package, after `Application.onCreate` and before the first activity. Repeatable |
| `--sysfs /sys/PATH=VALUE` | A hardware state file under `/sys` that the app reads itself (a camera privacy cover's state, say) answers with `VALUE`; the `sysfs` command changes it while the app runs. Repeatable |

**`--sdcard-file`** is for apps that read a config file once in `Application.onCreate`. It replaces any file the
slot had. `VALUE` is `text:TEXT` or base64, never a host file, with at most 64 KB a file and 72 KB in all. It is
allowed in `serve` catalog lines and URL adds, and in beam.

**`--broadcast`** is for debug receivers that set flag overrides: what
`adb shell am broadcast -a ACTION --es/--ez/--ei/--el/--ef` does. Extras are `es`, `ez`, `ei`, `el` or
`ef:KEY=VALUE`, separated by `;` (no spaces in `serve` catalog lines). It reaches receivers registered at run time
and manifest ones, and logs `[apksim] broadcast ACTION: result=N`. At most 64 broadcasts of 1024 characters each. It
is allowed in `serve` catalog lines and URL adds, and in beam.

### Device identity

| Option | Effect |
|---|---|
| `--serial VALUE` | `Build.getSerial()` and `Build.SERIAL`. By default 16 letters and digits, the same every launch of one data slot (and in test runs), not Robolectric's `unknown` |
| `--feature NAME[=false]` | A system feature that `PackageManager.hasSystemFeature` reports; `=false` takes one away. Repeatable |
| `--camera off\|fake\|fake=IMAGE` | Simulated cameras, a back and a front one (default off) |
| `--bluetooth on\|off` | The Bluetooth adapter at launch (default on, as an emulator boots) |
| `--wifi on\|off`, `--cellular on\|off` | Wi-Fi (default on) and mobile data (default off) at launch; `network wifi\|cellular on\|off` while it runs |
| `--wifi-signal DBM\|level:N` | Wi-Fi's signal at launch (-50 dBm by default); `network wifi signal -70` while it runs |
| `--build FIELD=VALUE` | Any `Build` or `Build.VERSION` field, for example `MODEL=Pixel 9`. Repeatable |

**`--feature`** names a feature such as `android.hardware.camera.any`, which `pm list features` then lists too.
`=false` also removes a default. By default the device has what an emulator of its kind lists for what apksim
simulates: Bluetooth and BLE, Wi-Fi, location, multitouch, a fingerprint sensor and audio output (a TV has leanback
and no touchscreen), and no camera.

**`--camera fake`** declares `android.hardware.camera`, `.any` and `.front`, and lists a back camera (id `0`) and
a front one (`1`) in `CameraManager`.

- **Streams:** 16:9 and 4:3 in YUV_420_888, JPEG and PRIVATE: 1920x1080, 1440x1080, 1280x720, 1024x768, 960x720
    and 640x480. `--camera-sizes WxH[,WxH...]` lists exactly the sizes named instead.
- **Frames:** opening a camera needs the `CAMERA` permission. Camera2 sessions and CameraX (`Preview`,
    `ImageAnalysis`, `ImageCapture`) get 30 fps frames of a moving test pattern, or of the still `IMAGE`.
- **While the app runs:** the `camera pattern|image PATH|off` command and Features > Camera switch the source.
    `off` gives black frames, as Android's camera privacy toggle does.

**`--build`** takes fields such as `VERSION.SECURITY_PATCH=2026-10-05`, but not `VERSION.SDK_INT`: the framework
is API 36. In test runs leave `FINGERPRINT` alone: with a device's, Compose's test rule waits for draw passes that
never come.

### Per-instance costs

| Option | Effect |
|---|---|
| `--max-fps N` | At most N frames a second (1-120), not the display's rate |
| `--video on\|blank\|off` | `blank`: players play at their pace but nothing is decoded or drawn (TextureViews stay empty). `off`: no video decoders are listed, as on a device without them. Launch only for `off` |
| `--audio on\|mute\|off` | `mute` keeps the timing and plays silence; `off` lists no AAC or MP3 decoders |
| `--anim-scale N` | The animator duration scale from the start: 0 jumps animations to their end, 10 slows them. In test runs it is set again at each test's setup |
| `--gles on\|off` | Offscreen GLES (on in test runs by default; off falls back to Robolectric's stand-ins) |

**`--max-fps`** keeps animations at their speed in fewer frames, and the app still sees the display's refresh
rate. It is the biggest saving for many headless instances: the test app's Shader tab costs about 0.7 of a core at
120 fps and 0.2 at 30.

### Native library options

| Option | Effect |
|---|---|
| `--skip-library NAME` | The app's `System.loadLibrary(NAME)` throws `UnsatisfiedLinkError`, as for a library the APK lacks, for an SDK whose native part is optional and that catches it. Repeatable |
| `--move-shared-data` | macOS: load a 4 KB-aligned native library whose writable data shares a 16 KB page with its code by moving that data (experimental). Without it such a library fails with a catchable `UnsatisfiedLinkError` |

### Network and safety

| Option | Effect |
|---|---|
| `--no-network` | Only localhost (127.0.0.1 and ::1) is reachable. macOS only, enforced by the sandbox (not with `--no-sandbox`); in a Linux container use `docker run --network none` |
| `--no-sandbox` | Run without the `sandbox-exec` profile (macOS) |
| `--network record\|replay FILE` | Record the app's HTTP(S) into a HAR file, or answer from one with no backend ([below](network-overview.md)) |
| `--network-profile NAME` | Start with `offline`, `3g`, `lossy`, `latency:MS` or `bandwidth:KBPS` |
| `--network-host H=ADDR[:PORT]` | Send connections for host H to a fixture server instead (`--network-trust CERT` for its certificate) |
| `--header "NAME: VALUE"` | HTTP header sent when launching an APK from an `http(s)://` URL (for example `Authorization: Bearer ...` or `X-JFrog-Art-Api: ...`). Repeatable (up to 16); sent to the URL's origin only (dropped once a redirect changes scheme, host or port, an http to https upgrade included: a server that upgrades first then answers 401). apksim's own processes (the JVM, curl, a warm launch's client) never carry it on their command lines or in a JVM property, but the shell's own `apksim --header ...` is in `ps` while the launcher starts: `--netrc` keeps a credential out of `ps` altogether |
| `--netrc` | Use credentials from `~/.netrc` when launching an APK from an `http(s)://` URL (keeps tokens off the command line) |

**Launching from a URL:** passing an `http://` or `https://` URL in place of a local APK path (`apksim https://...`) downloads the APK into `STATE/cache/downloads/` (LRU-capped at 512 MB via `--jvm -Dapksim.downloadCacheMB=N`) and overlaps cold simulator boot with the download. `--fresh` (or `--jvm -Dapksim.freshDownload=true`) forces a fresh download. Every `--header` and the `.netrc` credentials go to the URL's origin only: redirects are followed one hop at a time on the download itself, and a hop to another scheme, host or port (such as Artifactory redirecting to presigned S3/GCS storage) carries none of them. A cached download is reused whatever the credentials: `--fresh` after changing them.

### Automation options

| Option | Effect |
|---|---|
| `--control PORT\|auto` | Open the [control channel](automation.md) |
| `--control-bind ADDR` | The address the control channel and adb listen on (containers) |
| `--control-token-file FILE` | The control channel answers only connections that first send `auth` and the token in FILE (16+ characters): for a port other programs can reach |
| `--adb PORT\|auto` | Make the instance an [adb device](adb-device.md) |
| `--shot FILE` | Save a screenshot of the first frame |
| `--exit-after-first-frame` | Quit once the first frame is shown (benchmarks, smoke tests) |

### Recording

| Option | Effect |
|---|---|
| `--rec all\|CHANNEL,...` | The flight recorder: keep the last 2 minutes of input, logcat, network, SQLite calls and the screen |

**`--rec`** takes `all` or a list of channels, and `--rec-window SECONDS` sets how much is kept. The recording
has checkpoints to start a replay from, and is saved as one shareable `.apkrec` file by `rec save`, by File > Save
Replay, or by itself when the app crashes ([docs/flight-recorder.md](flight-recorder.md)).

`scripts/replay-adb.py FILE.apkrec -s SERIAL --yes` plays a recording on a device over adb, to tell an app bug
from a simulator one. It replaces the app's data, `/sdcard` files and Settings values on that device, hence `--yes`,
and backs the app's current data up first. Look at the plan with `--dry-run` before.

### Testing

| Option | Effect |
|---|---|
| `--instrument TEST.apk` | Run a test APK against the app ([below](instrumented-tests.md)) |
| `-e KEY VALUE` | An instrumentation argument (`class`, `package`, or any your tests read) |
| `--realtime-clock` | In test runs, the clock follows real time instead of skipping idle time |
| `--precise-waits on\|off\|auto` | With `--realtime-clock`, spin the last part of short waits where the OS wakes sleeps late (VM CI runners); `auto` probes and does nothing on a desktop Mac |
| `--uia engine\|classic` | In test runs, UiAutomator's idle waits, gesture time and accessibility events decided in-process (`engine`, experimental: docs/uiautomator-engine.md); `classic` is the default |
| `--start-activities` | In test runs, an activity the app starts comes up, as on a device, instead of only being recorded |
| `--test-outputs DIR` | In test runs, the Test Storage service's files (what a report library such as Allure writes) go under DIR, laid out as a device's `/sdcard/googletest` |
| `--mock-maker auto\|keep\|subclass\|inline\|NAME` | Mockito's mock maker for the tests; `auto` is the default |
| `--bundles DIR` | In test runs, a [failure bundle](instrumented-tests.md) in DIR for each failing test. `--bundles-on all` for passing tests too, `--bundles-max N` (default 50 a process), `--bundles-redact REGEX` |

**`--mock-maker`** values: `auto` swaps the ART makers a test APK names (mockito-android, dexmaker) for Mockito's
subclass maker. `keep` leaves the APK's. `subclass` and `inline` force `mock-maker-subclass` and
`mock-maker-inline`; `inline` needs Byte Buddy's agent and mocks JDK types only, not the APK's translated classes.
Any other value is used as given.

### Screenshot test options

| Option | Effect |
|---|---|
| `--clock ISO\|epoch-ms\|real` | The wall clock the app sees starts there and runs on (screenshot tests) |
| `--snapshot` | Deterministic drawing for [screenshot tests](screenshot-tests.md): CPU only, carets that don't blink |

### Debugging options

| Option | Effect |
|---|---|
| `--debug PORT` | A JDWP port for "Remote JVM Debug", with source-level debug info |
| `--jvm ARG` | Pass a JVM option, for example `--jvm -Dapksim.timing=verbose` |

Other commands: `apksim --version`, `apksim --train` (build the startup cache now), and `apksim --stop-standbys`
(stop this install's warm-launch standbys).
