# Configuration reference

## Environment variables

| Variable | Effect |
|---|---|
| `APKSIM_HOME` | Where apksim keeps its state: caches, app data, screenshots (default `~/.apksim` for a release, `work/` for a checkout) |
| `APKSIM_STANDBY=0` | Launch cold and start no warm-launch standby |
| `APKSIM_LOGCAT=1` | Copy the app's log output to stderr from the start |
| `APKSIM_FLAT_FRAMEWORK=0` | Use the stock `android-all-instrumented-*.jar` instead of the B2-flattened `work/runtime/android-all-flat.jar` (source checkouts) |
| `APKSIM_JDK` | The JDK 24 that build scripts use (from-source builds) |

## JVM switches

Pass these with `--jvm`, for example `--jvm -Dapksim.vsync=grid`.

| Switch | Effect |
|---|---|
| `-Dapksim.timing=verbose` | Finer startup timing marks |
| `-Dapksim.vsync=grid` | Pace frames on a fixed 120 Hz grid instead of the display's vsync |
| `-Dapksim.classCache=false` | Don't reuse translated classes from earlier launches |
| `-Dapksim.freshDownload=true` | Re-download an `http(s)://` APK URL instead of reusing `cache/downloads/` |
| `-Dapksim.downloadCacheMB=N` | LRU cap in MB for `cache/downloads/` (default 512; 0 for unlimited) |
| `-Dapksim.noCurl=true` | Use the pure-Java parallel ranged HTTP downloader (`-Dapksim.downloadConnections=N`, default 6) instead of `/usr/bin/curl` (useful on high-latency VPNs or custom Java truststores) |
| `-Dapksim.cachedFontMap=false` | Rebuild Android's system font map from `fonts.xml` instead of mapping the cached serialized map |
| `-XX:TieredStopAtLevel=4` | Re-enable C2 compilation on cold launches (cold launches default to C1 after B2 flattening; warm standbys keep C2) |
| `-Dapksim.cpuLayerEffects=true` | Run layer shader effects on the CPU, as HWUI does (for comparisons) |
| `-Dapksim.gpuBitmaps=false` | Resample scaled bitmaps on the CPU |
| `-Dapksim.gpuBitmapsSnap=false` | Leave big scaled bitmap draws that land off whole pixels to the CPU, not snapped |
| `-Dapksim.videoAtViewSize=false` | Keep decoded video at the picture's size when the view is bigger, instead of scaling it on VideoToolbox |
| `-Dapksim.mute=true` | Keep audio timing, but play silence |
| `-Dapksim.audio=false` | Turn off sound and the audio decoders |
| `-Dapksim.runnerApplication=false` | In test runs, ignore the runner's `newApplication` |
| `-Dapksim.crashes=log` | Log an uncaught exception and keep running, instead of ending the app (to look at a broken screen) |
| `-Dapksim.providerFailures=fail` | In running instances, a content provider that throws at startup crashes the launch, as on a device (by default it is logged) |

A launch is warm when it's handed to a pre-booted standby. `--debug`, test runs and most JVM options launch cold,
because the standby read them when it booted. Of the switches above, only `timing`, `vsync`, `classCache`, `crashes` and
`providerFailures` still launch warm.

## Files and folders

| Path, under the state folder | Contents |
|---|---|
| `app.aot` | The JVM startup cache (`apksim --train` builds it) |
| `cache/classes/` | Translated app classes, per DEX file |
| `cache/downloads/` | APKs downloaded from `http(s)://` CLI URLs (LRU-capped at 512 MB) |
| `cache/framework/` | Inflated framework `resources.arsc` and serialized system font map |
| `cache/NAME.apk-KEY/` | Each APK's manifest, native libraries and inflated DEX files, keyed by the APK's contents (below) |
| `cache/data/PKG/` | Each app's persistent data (`default`, or `instance-N` for extra instances) |
| `cache/data/PKG/SLOT.device/` | What that data slot keeps of the device: `sdcard/` (the app's `/sdcard`) and `settings.properties` (Settings values that differ from the defaults) |
| `tmp/PID/` | Per-instance scratch, including `control.port` and `adb.port` |
| `screenshots/` | Saved screenshots, recordings and flight recorder bundles (`.apkrec`) |

The per-APK cache is keyed by contents, so a copy or re-download of the same build reuses its entry:

- Entries unused for 14 days go, then the least recently used until the rest fit in 2 GB
    (`--jvm -Dapksim.apkCacheMB=N`).
- An entry a running instance uses is never removed.
- Entries from earlier versions (`NAME.apk-MTIME`, no `in-use.lock`) go only after 14 days unused.
- It is safe to delete while nothing runs.
