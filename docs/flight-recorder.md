# Flight recorder: the last N seconds, saved as a shareable bundle

A tester finds a bug, saves what the device did over the last minute or two, and sends the file to an engineer. The
engineer sees what happened (the screen as a movie, the input, logcat, network calls, database calls) and replays it:
starts the app from a checkpoint just before the bug and carries on using it from there.

This page covers the recording side, which is built: channels you switch on and off while the app runs, checkpoints,
and the saved bundle. Replay in apksim is `apksim replay BUNDLE` ([replay.md](replay.md)); on a device over adb,
`scripts/replay-adb.py` (below).

## Turning it on

At launch:

```
./apksim --rec all app.apk                        # every channel
./apksim --rec input,logcat,net app.apk           # some
./apksim --rec all --rec-window 300 --rec-checkpoint 15 app.apk
```

While the app runs (control channel, `adb shell apksim rec ...`, or `scripts/ctl.py`):

```
rec                              status (one line): channels, counts, checkpoints, last save
rec on [CHANNEL...]              start channels (none named: all)
rec off [CHANNEL...]             stop them (what they kept stays until it ages out, and can be saved)
rec window SECONDS               how much the rings keep (default 120)
rec checkpoint now               take a checkpoint
rec checkpoint every SECONDS     periodic checkpoints (default 30 while input is recorded)
rec checkpoint off               only when asked
rec checkpoint verify [ID]       read a checkpoint back as a replay would
rec save [SECONDS] [FILE]        save the last SECONDS (default: the window) to FILE
rec clear                        forget everything kept (adb's own logcat and a --network record file stay)
```

`rec save` answers at once with the file name. A worker thread writes the file, and `rec status` reports when it is
done (`last save: FILE: 107 KB in 32 ms`). Each save writes its own part file and moves it into place when it is
complete; a default name is reserved at once as an empty file (so two saves in the same second, even from two
instances sharing the folder, get `-2`, `-3`... names), and `quit` (or closing the window) waits for saves still being
written. Without FILE, bundles go to `work/screenshots/<package>-<time>.apkrec`
(`~/.apksim/screenshots` for an installed release), next to screenshots and screen recordings. A FILE must be
somewhere the launcher's sandbox lets the app write, which means under the state folder.

In the Mac window: **File > Save Replay** (Cmd+Shift+S) saves the window. File > Show Screenshots and Recordings
opens the folder.

The `--rec*` options are read after the hand-off, so a recording launch still goes to a warm standby.

## Channels

| Channel | What it keeps | Where it hooks |
|---|---|---|
| `input` | Every touch, two-finger touch, wheel, key, character, Back (also one that only closes the software keyboard) and window resize the app is given, stamped with the app's `SystemClock.uptimeMillis` and the wall clock. Also the device commands a replay must repeat: rotate, appearance, locale, font scale, device, size, open URL, push, permission, location, biometrics, recreate, process-death, home/lock and back, network conditions, clock, and so on. | `Host.dispatch` (one place for the Mac window, `--control`, adb and `drive`) and `Host.runCommand` |
| `logcat` | The app's `android.util.Log` lines while the channel was on | `AdbLog`'s ring (20k lines), which the channel turns on; each line now carries its wall time. The ring goes on for `adb logcat` with the channel off, so a save keeps only lines from when it was on (`rec.Spans`) |
| `net` | Every HTTP(S) exchange while the channel was on, as a HAR: HTTP/1.1, HTTP/2 and so gRPC, WebSocket messages, with bodies, the exchanges that failed and why, exchanges still going on at the save (marked unfinished), and every connection with its TLS and ALPN (`Authorization`/`Cookie`/`Set-Cookie` and `--network-redact` names redacted as in `--network record`) | The network tap ([network.md](network.md) "Two mechanisms"): the app's own connections, read as they pass. Nothing stands between the app and the server, so the channel changes nothing the app can see: no connection is closed, proxied or made over another protocol, at launch or when it is turned on later. Turned on in a running app, connections already open are not read (HTTP/2 cannot be read from the middle); the answer says how many, and `network reconnect` closes them for who wants everything from then on. A `--network record FILE` launch is read as it is: `rec off net` does not pause the file. An exchange belongs to the channel if the channel was on when its request began |
| `db` | Each SQLite call: database file, thread, operation, SQL (placeholders kept, literals written into it left out: `'…'`, `X'…'`, `…` for numbers), the number of bound arguments (for a `SQLiteStatement` too, read when it runs), column names, time in µs (a transaction's begin includes waiting for its lock), rows changed or rowid, or the error | The translator sends app calls to `SQLiteDatabase` (execSQL, rawQuery*, query*, insert*, replace*, update*, delete, transactions) and `SQLiteStatement` (execute*, simpleQueryFor*) to `dev.apksim.rec.Db`. With the channel off, each hook is just the original call after one field read. |
| `db+values` | As `db`, plus bound values, ContentValues, simpleQuery results, and the SQL as written | the same |
| `screen` | Turning it on, or `rec clear`, redraws the windows so a still screen has a first frame, and so does a screen that goes still right after a frame the rate limit passed over, so the last state of every change is kept. 5 frames a second, scaled to at most 960 px on the long side, JPEG quality 70 (`-Dapksim.rec.screenFps`, `-Dapksim.rec.screenMax`, read per launch) | The raster thread copies a presented frame at most 5 times a second; a low-priority thread scales and encodes it |

Covered by `db`: Room's framework driver, SQLDelight's Android driver, `SQLiteOpenHelper` code. Not covered: a bundled
SQLite (androidx.sqlite's `BundledSQLiteDriver`, which ships its own native library), or SQLite reached from native
code.

Every ring has a byte budget: input 16 MB, db 16 MB, screen 64 MB, net 64 MB with bodies, URLs and headers. When a ring hits its budget
the oldest items go first (an item bigger than the whole budget goes too), and a saved bundle's manifest says how many
were dropped.

## Checkpoints

A checkpoint is what a replay starts from:

- the activity stack, bottom first: each activity's class, its intent (parcelled) and its saved instance state (the
  Bundle from `onSaveInstanceState`, parcelled), in `state.bin`;
- the app's data folder and its `.device` folder (SharedPreferences, databases, files, `/sdcard`, Settings values),
  cloned with `copyfile(COPYFILE_CLONE)`. On APFS no bytes are copied (1 GB in 4 ms); on Linux it is a plain copy;
- the device as the app sees it: display size, density, font scale, Robolectric's qualifier string (size, density,
  orientation, night mode, locale) and the `config` command's text.

Saving instance state works as it does on Android when the user leaves for a moment and comes back. The visible
activities are paused and stopped, every activity saves its state, and they are restarted and resumed, all in one turn
of the main thread, so no frame shows it. Afterwards the stack is put back as after any other return to the app
(`Host.arrange`: windows shown, touchable and in order; a backgrounded app stays in the background).
**The app does see one thing: its onPause/onStop/onStart/onResume run.**
This is the only way a checkpoint shows in the app. A screen that refreshes in onResume refreshes. That is why
checkpoints are not taken on every input:

- the first comes once the first frame is drawn and the app is at rest;
- after that, every `--rec-checkpoint` seconds (default 30), only if there was input since the last one, and only
  with the app at rest;
- when an input arrives after more than that interval without one, the checkpoint is taken first, then the input is
  delivered (logged as `before input`): the state a replay starts from is the one the input met;
- and on `rec checkpoint now`.

**At rest** means: no finger down, no input for 1.5 s, the screen still (fewer than 5 frames drawn in those 1.5 s (video, camera and WebRTC pictures published count too: a frame cap does not hide them),
which a blinking caret stays under and a transition, a spinner or a video does not), and, when the `net` channel or the
inspector reads the network, no request begun or ended in those 1.5 s and none under 10 s old still unanswered. A
checkpoint that is due waits for that, however long: while a video plays, or on a screen that never stops moving, none
is taken, and `rec` says so (`one is due, waiting 40 s for the app to be at rest: the screen is drawing`). A replay
then starts from the last one taken, and `rec checkpoint now` takes one whatever the app is doing. Only the first
checkpoint has a limit: 15 s after it is due it is taken once there has been no input for a second, since without one
a recording cannot be replayed at all. Before (issue 320) one came
a second after the first input in 30 s, in the middle of what that input started: an app loading its next screen
dropped the load, and a player being started stopped after its first picture.

The test app takes 7-13 ms per checkpoint (state 3-7 ms). Checkpoints live in the instance's scratch folder
(`work/tmp/<pid>/rec/`). The recorder keeps those inside the window plus the newest one before it, so a save of the
whole window always has a checkpoint to start from, and the rings keep back to that checkpoint. A checkpoint a save is
still reading is deleted only once the last such save is done, also after `rec clear`.

**Databases.** Stopping the activities does not stop the app's workers, so each SQLite database the app has open
(the framework's list of open databases, so no file is opened to find them: closing any descriptor of a file drops
every POSIX lock the process holds on it, SQLite's included) is opened a second time (read-only, through the
framework's SQLite, which shares its locks between connections) and held in a read transaction while the folder is
cloned. While a reader holds a snapshot, SQLite copies no WAL frame past it into the database file and does not
restart the WAL, so the database file and its WAL clone hold one commit's state between them (a rollback-journal
database cannot be written meanwhile at all). For those databases the clone's `-shm` and `-journal` files are left
out, and SQLite recovers from the WAL on open; a database cloned as a plain file keeps its journal. The APFS clone
opens no file (checked: a held fcntl lock survives it). Where cloning is not possible (Linux, another volume) a `cp`
process copies the folder, for the same reason; without one, the open databases are left out and noted. An app that loads its own SQLite library (androidx.sqlite's bundled driver, SQLCipher)
has its databases cloned as plain files: a second SQLite library in the process closing a file would drop the app's
library's POSIX locks on it. The checkpoint's notes say so. Settings values are written out first. Files other than
databases (SharedPreferences, files/) are cloned one by one, as a power cut would leave them.

`rec checkpoint verify` reads a checkpoint back as a replay will: every intent (extras included) and Bundle is
unparcelled with the app's class loader and every value read, Bundles, arrays and lists inside them too, so a
Parcelable the app cannot recreate shows up now rather than at replay time. Parcelables' own fields are not looked
into.

## The bundle (`.apkrec`, a zip)

| Entry | What |
|---|---|
| `manifest.json` | `format: apksim-flight-recording/1`, package, version, APK name and SHA-256, the window (`from`/`to` epoch ms), the device at save, channels, counts, the checkpoint used (id, wall and uptime, stack, its device), notes (input left out, items dropped, a missing checkpoint), and `crash` (a stack trace) for a crash bundle |
| `input.jsonl` | From the checkpoint on: `{"t": uptimeMs, "w": wallMs, "ev": "down", "type": 0, "x": 300, "y": 1690, "v": 0}` or `{"t":…, "cmd": ["rotate", "landscape"]}` |
| `logcat.txt` | threadtime lines from the checkpoint on |
| `network.har` | HAR 1.2, readable by Chrome DevTools, Charles, and `--network replay` |
| `db.jsonl` | one call per line |
| `screen.avi`, `screen-times.txt` | Motion-JPEG at 5 fps from the window's start to the save (each frame repeated until the next, so the timing is real; frames from before a rotation or resize fitted into the last frame's size), and when each frame kept was presented |
| `checkpoint/state.bin` | int count, then per activity (bottom first): UTF class name, int length + intent parcel, int length + Bundle parcel (-1: could not be parcelled, see notes) |
| `checkpoint/stack.json`, `checkpoint/data.zip` | the stack's class names; the data in `SlotPack`'s format (`package`, `slot/…`, `device/…`), which `apksim beam`'s handoff already unpacks |

The saved window starts at the checkpoint before it, so `input.jsonl` always starts at a state you can restore. If
the oldest checkpoint is after the window's start, the manifest says how far in a replay would begin. If there is no
checkpoint at all, the bundle can be read but not replayed.

**A crash saves a bundle by itself.** When the app crashes with any channel on, the window is saved as
`work/screenshots/<package>-<time>-crash.apkrec`, with the stack trace in its manifest. This happens before the process ends, and
before the scratch folder that holds the checkpoints is deleted (`Crashes.terminate`), after up to 2 s for network
answers the app already has and the network layer is still writing down. A crash before the first activity (in the
Application's constructor or onCreate) saves what the launch's `net` and `db` channels have, with no checkpoint,
input or screen. Under `apksim serve --allow-rec` and `apksim beam` the bundle goes to the session's recordings folder
as `RID.apkrec` instead (`-Dapksim.rec.crashId`, `--rec-crash-id`, `--rec-crash-dir`), and the tab's end message names
RID (docs/serve.md, issue 516).

## Privacy

A bundle is meant to be sent to someone else, so by default it leaves out:

- characters and printable keys typed while a password field has the focus (a View with a password input type or
  `PasswordTransformationMethod`, or a focused, editable Compose node with password semantics), looked at for every
  character. These are recorded as `{"redacted": true}`, and the manifest counts them (`counts.redactedInput`);
- pasted text (Cmd+V, `paste`), wherever it goes: `{"pasted": true, "redacted": true}` per character;
- that same text in the requests that carried it (a sign-in's body holds the password its field was typed into): the
  recorder keeps each stretch of left-out characters in memory (the last 64; Delete takes the last one back; a touch,
  another key, a key with Ctrl or Meta held, or other text ends the stretch), and a save writes every request begun after it was typed whose body or URL holds it
  with as many `x` in its place, which is what a replay types. Each character is found as itself, percent-encoded
  (`+` for a space) or as a JSON escape (`\uXXXX` too), in any mix, and not inside a longer word ("pass" in
  "passport"; a word does begin right after `%XX`, `\uXXXX`, `\n`, `\r`, `\t`, `\b` or `\f`). The entry is marked `_apksimRedactedInput` and `counts.harRedactedInput` counts them. Only with the
  `input` channel on (without it nothing knows what was typed), only text of 4 characters or more (shorter turns up by
  chance), only in a request's URL and body (not its headers, a compressed body, WebSocket messages or answers), and
  not in a body over 32 MB. A run edited after a tap (Delete once the field was touched again) is not the field's text
  and is not found: compare `counts.redactedInput` with `counts.harRedactedInput` before sending a bundle of a sign-in. `rec clear` does not forget
  what was typed (it is in memory only). To type the real password in a replay, see [replay.md](replay.md) "Passwords in
  a recording": its names and lengths are the manifest's `secrets`, its value is never in the bundle;
- clipboard text (`clipboard set TEXT` is kept as its length);
- `Authorization`, `Proxy-Authorization`, `Cookie` and `Set-Cookie` headers (`--network-redact` adds more names);
- database values, unless `db+values` is on: bound arguments, ContentValues, query results, and literals written into
  the SQL itself.

Still in a bundle: response bodies, the app's data folder (`checkpoint/data.zip`), the screen, anything the app
logs, and typed text a request carries in another form (hashed, encrypted, a token made from it), or sent by a request
that began before the save's window or was typed with keys whose characters the key map does not give. A password
that did not come through input dispatch is left out of requests when apksim's autofill put it in its field, when it is
in a View's password field as a touch or a key begins, or when it was the clipboard's while a password field had the
focus (Ctrl+V, the Paste menu); one that an accessibility `setText` put in a Compose field is not. And a checkpoint is the app's own state, taken as it is:
`checkpoint/state.bin` (the saved instance state, which can hold a password field's text) and the data folder
(whatever the app stored) are not searched. Treat a bundle from a signed-in session like the device itself.

## Limits

- Replay is [replay.md](replay.md)'s.
- Text that reaches the app without passing through input dispatch (an accessibility `setText`, autofill) is not in
  the input journal; its effects are in the checkpoints that follow.
- Pasted characters are kept only as placeholders, so a replay pastes something else.
- `net` cannot start while replaying (`--network replay`): there is no network to read. Network conditions are no
  obstacle: a shaped connection keeps the app's own TLS and is read as any other.
- `net` reads what goes through the hooks ([network.md](network.md) "Clients"): not Cronet's native stack, NIO
  channels or native code's sockets.
- The rings live in this process. A crash saves them, but a `kill -9` loses them.
- Not recorded: sensors, camera frames, audio, location fixes (location changes made with the `location` command are
  recorded), notifications posted, WorkManager and alarm timing.

## Replay on a real device: `scripts/replay-adb.py`

To tell whether a bug is the app's or the simulator's, a recording can be played on a device over adb:

```
scripts/replay-adb.py session.apkrec -s SERIAL [--apk APK] [--pace 2] [--capture device.mp4] [--logcat device.log]
scripts/replay-adb.py session.apkrec --dry-run       # the plan and the report, no device
```

It replaces the app's data, `/sdcard` files and Settings values on the device, so it needs `--yes`, and it saves the app's current data first (`--backup-dir DIR`, default `./replay-backup-PKG-TIME`; `--no-backup` skips it). It finds out whether the data can be written at all before it changes anything. It checks the installed APK against `apkSha256` (`--apk` installs a file after checking it against the same hash),
stops the app, puts the checkpoint's data back (`data.zip`'s app folder through `run-as`, so the build must be
debuggable, or straight over adb where that may write; `/sdcard` by push; Settings values by `settings put`), sets
the checkpoint's display with `wm size` / `wm density` (reset at the end; `--scale-coordinates` scales the input
instead), starts the checkpoint's top activity with `am start -n`, and plays `input.jsonl` at its recorded offsets
(`--pace` divides them): a tap or a drag as `input tap` / `input swipe` with the gesture's duration, Back/Home/keys
as keyevents, typed characters as `input text`, rotate/appearance/locale/font scale/animation scale/open URL as
`settings`, `cmd` and `am` calls. `--capture` records the device screen next to the bundle's `screen.avi`.

Its report says what was not done. The network is live (`network.har` cannot be given to a device); the intent's
extras and the saved instance state are parcels the script does not unparcel, so only the activity's class is
started; two-finger gestures, `location`, `push`, biometrics and the other commands adb cannot do are listed and
skipped; characters left out of the recording (password fields, pasted text) are typed as `x`; `input text` drops
characters other than letters, digits and `. , _ @ -` and a space. A drag is played as a swipe between its end
points (`--path-steps N` cuts it into N). `scripts/replay-adb-check.py` checks the plan on a synthetic bundle.

Beam sessions record `input` by default now (`apksim beam config rec input|all|off|CHANNELS`, read at the agent's
start), so a session nobody planned to replay has something to `rec save`. An app's own launch options that name
`--rec` win.

## Replay in apksim

Built: `apksim replay BUNDLE.apkrec` ([replay.md](replay.md)) restores the checkpoint's data and stack, plays
`input.jsonl` at its offsets through the Host's input path with the network answered from `network.har`, and
reports where the screen first differed from the recorded frames. For it, a checkpoint's device now also names the
device preset, its rotation, the time zone, the animator scale and the launch's `--feature` flags, and the manifest
the APK's path (`apkPath`) and signers (`apkSigners`).

## Checks

`scripts/rec-check.py APP.apk [OUT]` (the test app). It launches with `--rec all` and the network fixture mapped in,
and uses the Trips tab (SQLite) and the network screen (OkHttp and HttpURLConnection). It sets the clipboard, turns
the screen channel off and on, takes a checkpoint and reads it back, then saves. It checks the bundle: manifest, input
events and commands, clipboard text left out, logcat, both fetches in the HAR with Authorization redacted, db calls
with no values, a well-formed AVI, and data.zip holding the trips database. A second launch crashes on purpose and
must leave a crash bundle with the exception and a checkpoint.
