# Data slots, external storage, device paths and sysfs files

Agent reference notes, moved out of AGENTS.md so they are read only when needed. Quoted section names
("Video", "Warm launches") are sections of these files: AGENTS.md's index says which file has each.

## Code notes
- `Storage`: per-PID scratch under `work/tmp` (swept on next launch) and persistent app data under
  `work/cache/data/<pkg>/default` (or `instance-N` when several run); `--fresh` empties the slot it takes first.
  `SlotDevice`: what a device keeps outside the app's data, beside the slot in `<slot>.device/`, taken with the slot
  before Application.onCreate (warm or cold): `sdcard/` is external storage (`/sdcard`, its public folders such as
  Download inside it, mounted; Robolectric's was two temp folders per process, "removed"; the File overloads too:
  `getExternalStorageState(File)` for anything under it, emulated for it and the app's external folder; it is also the framework's first external folder, `UserEnvironment.getExternalDirs`, whose empty list made `Environment.isExternalStorageManager()` throw, and all-files access is its op's default, so that answers false as on a device: `apksim://slot`'s storage line), and `settings.properties`
  the Settings.Global/Secure/System values that differ from the defaults (by the app or `adb shell settings put`;
  Robolectric's static maps, read every 250 ms from Host's loop and on exit (Host.exit, the warm client hanging up,
  a JVM shutdown hook for SIGTERM and the app's System.exit), as no write hook exists, and written a
  last time by `SlotDevice.close` at the start of `SimTestEnvironment.resetState`, which then ends it: the reset puts the
  defaults back, and a flush after it (the warm client hanging up after Host.run threw) erased the file; not
  animator_duration_scale or font_scale, which `animscale`/`fontscale` own). Apps keep a device id in /sdcard and a
  setup-done flag in Settings.Global on purpose; before, every launch lost them. `--fresh`, `ctl.py erase` and serve's
  cleanup clear it with the slot; `--sdcard-file PATH=text:TEXT|BASE64` (repeatable; `SdcardSeeds`, 64 KB a file, 72 KB in all, inline so serve and beam allow it) writes a file into the slot's `sdcard/` before onCreate, over what the slot had; `--broadcast 'ACTION;es|ez|ei|el|ef:K=V'` (repeatable, 64 at most, 1024 characters each: issue 422, each costs one ordered `sendBroadcast` before the first activity, and the `i` message says `maxBroadcasts`; `LaunchBroadcasts`, from `Host.run` just before the start activity, so after Application.onCreate; ordered, package-set, runtime and manifest receivers; logs `[apksim] broadcast ACTION: result=N`; the test app's `ComposeApp` has a `dev.apksim.testapp.SET_FLAG` receiver) sends one to the app; `--setting NS:KEY=VALUE` (repeatable) sets a value before onCreate, kept like a
  `settings put`, and in test runs at each test's setup (one for animator_duration_scale or font_scale runs the
  control's code, `setAnimatorScale`/`setFontScale`, and is not kept; `--anim-scale` wins over an
  animator_duration_scale seed; the font scale is `--font-scale`, else the last
  font_scale seed, with Settings.System FONT_SCALE beside it (as `fontscale` now sets too), set again after each setQualifiers that resets it: Host.run's, and a test's setup in SimRunner,
  so the test's activities get it too). Test runs otherwise start each test from a clean device.
  `scripts/slot-check.sh` checks it (CI: `ci-smoke.sh`). Files written beside their final name and renamed into place get `Storage.tmpTag()`, PID plus a random part:
  containers sharing a cache volume repeat PIDs (the image's JVM is PID 1).
- `DevicePaths`, `native/devicepaths.c` (`libdevicepaths`): the app's external storage is at a device's path. Before,
  Environment and the app's external folders answered the host folder of the data slot
  (`/Users/NAME/.apksim/cache/data/PKG/crawl-0.device/sdcard`), which an app shows (a "data folder" setting): it
  differed between instances and named the host's user. Now `SlotDevice.take` tells the framework
  `/storage/emulated/0` (Environment's directory and public directory, `UserEnvironment.getExternalDirs`, the storage
  state tables), and the library, put in front of libc in each instance's JVM by the launcher (`DYLD_INSERT_LIBRARIES` after
  `sandbox-exec` on macOS, beside libloopback; `LD_PRELOAD` on Linux, the image builds it), rewrites a path under
  `/storage/emulated/0`, `/storage/self/primary`, `/sdcard` or `/mnt/sdcard` to the slot's `sdcard` folder in every
  file call the process makes (`open`, `stat`, `opendir`, `rename`, `mkdir`, `remove`, `fopen`, `mkstemp` and its
  variants, xattrs, the `*at` and 64-bit variants; glibc's old `__xstat` family through `stat`, and `__xstat` when an
  old glibc has no `stat`; on the Mac `copyfile`, `renamex_np`, the plain `realpath` and UNIX03 `fopen` symbols that
  `bionic_lookup`'s dlsym hands an app's native code; and calls libc makes inside itself, which a preload or dyld
  interposing never sees, by wrapping their entry: `creat`, `scandir`, `glob` (matches unmapped back), `nftw`/`ftw`
  (the callback sees device paths), `fts_open` (its root only), `inotify_add_watch` on Linux), so the JDK,
  Robolectric's SQLite and the app's ELF code all reach it; `realpath` of a device path answers a device path (also
  in its buffer when it fails), and so does `getcwd` inside it, so `getCanonicalPath` shows nothing of the host. A
  device path is folded lexically first (`..` included: `/storage/emulated/0/..` is `/storage/emulated`, as the
  device resolves it), and the rest of `/storage` (`/storage`, `/storage/emulated`, `/storage/self`) is a skeleton of
  empty folders in the scratch folder (`STATE/tmp/PID/device-root`), so the parents exist and `toRealPath` and
  `getFileStore`, which walk them, work. Java names the folders once the slot is taken (`DevicePaths.map`) and checks
  that a marker written to the slot's folder is found through `/storage/emulated/0`; without the library in the
  process (an old standby, `-Dapksim.devicePaths=false`) the app is told the host folder, as before, and stderr says
  `[apksim] device paths off: REASON`. `APKSIM_DEVICE_PATHS=0` does the same without the line, and launches cold (a
  standby's JVM has the library from its start; the standby that launch spawns does not inherit the variable). On
  Linux the launcher puts the library in `LD_PRELOAD` once, whatever an inherited value holds. apksim's own code
  (adb's `/sdcard`, a tab's pushes, seeds) reaches it either way. Not mapped: a relative path that climbs out after a
  `chdir` into external storage, a symlink's target read back (an absolute device target is written as the host
  path, which a moved slot leaves dangling), a descriptor's path (`fcntl F_GETPATH`, `/proc/self/fd`), the entries
  `fts_read` walks, child processes (`ProcessBuilder.directory` in external storage fails), and test runs (they keep
  Robolectric's temp folders). `getExternalFilesDir(null)` is Robolectric's `.../Android/data/PKG` (a device adds
  `/files`), before and after. `apksim://slot`'s "paths:" and "fileops:" lines (mkdir, write, rename, list, copy,
  canonical and real paths, the parent and `..`, a SQLite database, delete in external storage), `scripts/slot-check.sh`
  (also with `APKSIM_DEVICE_PATHS=0`).
- `SysFiles`: hardware state files under `/sys` an app reads itself (a camera privacy cover's `cam_privacy_state`;
  issue 376). `--sysfs /sys/PATH=VALUE` (repeatable, `-Dapksim.sysfs.N`, warm launches too) and the `sysfs` command
  (`sysfs`, `sysfs PATH`, `sysfs PATH VALUE...`, `sysfs clear PATH|all`; chain `key CAMERA` for the app to re-read) back a path with a file in the
  process's scratch folder (value plus a newline). The translator sends the path String of `new File(String)`,
  `new FileInputStream(String)` and `new FileReader(String)` through `SysFiles.map`, so File.exists, FileReader and
  `File.toPath()` reads see it; `Paths.get`/`Path.of` and `File(parent, child)` are not mapped. Only `/sys/` paths (at most 32 entries); a page may carry `--sysfs` (8 seeds, values to 128 characters) and send `sysfs /sys/PATH VALUE` and `sysfs clear /sys/PATH` (`SysFiles.validPath`: 1-16 plain segments, no `.`/`..`; a tab's runs of spaces in a value become one); the app sees the backing file's path from `File.getPath`. Seeded just before Application.onCreate, so the constructor and attachBaseContext read nothing. The translator's output for every class calling those three constructors changed on purpose.
  Running instances only (seeded in `Host` after `SlotDevice.take`). The test app's `apksim://slot` shows
  `/sys/devices/apksim-test/state` ("sysfs: 1") with a Reread button.
