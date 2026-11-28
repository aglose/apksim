# Releases (Homebrew, install.sh) and the Linux image

Agent reference notes, moved out of AGENTS.md so they are read only when needed. A section another doc or a
code comment cites as `AGENTS.md "Name"` is in one of these files: AGENTS.md's index says which.

## Releases (Homebrew, install.sh)
`scripts/package-release.sh VERSION` turns a built checkout (`scripts/setup.sh --no-train`) into
`dist/apksim-VERSION-macos-arm64.tar.gz`: the launcher, `apksim.jar`, `work/lib` (without `nativeruntime-dist`, whose
fonts and natives are already in `work/runtime`; android-all without its `native/` and `icu/`, which FastNativeLoader
reads from `work/runtime`, and Conscrypt with only its osx-aarch_64 library), `work/runtime` (only the fonts `fonts.xml`
names: six of the seven Noto Sans CJK weights and some Latin families are never opened), a jlink'ed JDK 24 in
`work/jre` (`work/jdk.path`), `work/testapp.sha256`, and `work/release` (the version). The test app is not in it
(`dist/apksim-testapp-VERSION.apk` beside it, a release file of its own): the first `train.sh` of an install downloads
it into `STATE/testapp-VERSION.apk` (`APKSIM_DOWNLOAD_URL` for a mirror or `file://` folder) and checks it against
that hash; offline, training fails with a line saying so and touches `STATE/train.failed` (so does a failed record or create stage of a background training): a launch tries again at most once an hour (the launcher passes `--background` and stays quiet meanwhile), `apksim --train` at once. 471 MB → ~312 MB (0.32.0 against this
change, measured locally). `work/release` makes the launcher keep its state (`STATE`: AOT cache,
`cache/`, `tmp/`, screenshots, autofill) in `~/.apksim` rather than `work/` (`APKSIM_HOME` overrides either way;
`train.sh` and `ctl.py` follow the same rule), so the install folder can be read-only. The sandbox allows writes to
`STATE`, but never to what runs outside it (`scripts/check-sandbox-writes.sh`, Mac only, in CI's `startup-gate`
job and the release's Mac job): the install folder (a Homebrew install is outside the home folder, which alone was
denied before 0.71.0, so an app could rewrite its launcher), Homebrew's prefix, `/usr/local` and `/Applications`,
every `$TMPDIR/apksim-<12 hex>` link to a `work/lib` (training's launches load the jars it names; the launcher also
replaces one that points anywhere else), in `STATE` the startup cache (`app.aot*`), `testapp-*` and training's logs
and pid file, and in a checkout `work/`'s build outputs (`apksim.jar`, `lib`, `runtime`, the JDK and `jdk.path`,
`release`). Training's stages run outside the sandbox, so on macOS they work in a folder of their own in the home
folder (`~/.apksim-train.XXXXXX`, removed after), on a copy of the test app checked against the release's hash, with
a class cache, extracted libraries and resource table made there rather than read from `STATE/cache`, which apps may
write; the files of the class cache and table that `STATE` lacks are then put there, each by rename (a running
instance maps the table). Still app-writable, and only read by sandboxed launches: `STATE/cache` (one app could plant translated classes
for another), and the standby sockets in `$TMPDIR`. A standby keeps the profile it booted with (`--stop-standbys`). Standbys are keyed by install folder plus `STATE`. A release has no AOT cache (it is tied to its folder and
JDK): the first launch without one, and the beam agent when it starts (`start_training` in the launcher; never a beamed launch, whose sandbox refuses the record stage's writes: issue 559), starts `train.sh --headless` detached (`STATE/train.pid`, log `STATE/train.log`)
and goes on without it; a headless-trained cache is as fast as a windowed one (1.73 against 1.72 s). The launcher
follows a symlink to itself (Homebrew's and install.sh's `bin/apksim`). The tarball's `scripts/` must hold every sibling a shipped script imports or opens by
name (`package-release.sh` fails otherwise: 0.60.0 lacked `subsystem-audit.py`, which `crawl.py --keep-data` loads), and
release.yml's installed-release check runs a short `crawl.py --keep-data` from the unpacked, read-only tree.
`.github/workflows/release.yml`: a `v*` tag builds and checks the tarball on an Apple Silicon runner and makes a
*draft* release; publishing it pushes `packaging/homebrew/apksim.rb` (filled in) to the `aglose/homebrew-apksim` tap
as `Casks/apksim.rb` (it pushes with the `TAP_DEPLOY_KEY` secret: a deploy key with write access to the tap only); users run `brew tap aglose/apksim`, `brew trust aglose/apksim` (Homebrew 7 refuses casks from untrusted taps:
"Refusing to load cask ... from untrusted tap"; a local `brew tap-new` tap is trusted, so a local test does not show it), then
`brew install apksim`
(a third-party tap has no shorter form: Homebrew takes `user/tap/name` or a bare name from its own repos). A cask, not a formula: Homebrew installs a bottle-less formula
only with current Command Line Tools ("CLT does not support macOS 26" stopped it here). The cask clears the quarantine
flag, since the release is not notarized. Homebrew 7 deprecates cask `postflight` blocks for `postflight_steps`.

## Linux / Docker
`docker/Dockerfile` builds a headless image for linux/amd64 (`scripts/docker-build.sh`; Colima with Rosetta on this
Mac) or linux/arm64 (`scripts/docker-build.sh TAG --platform linux/arm64`: native in Colima here, no Rosetta).
`HostOs` picks `.so` vs `.dylib` and the APK ABI (`x86_64` on x86_64 Linux, `arm64-v8a` on the Mac and arm64 Linux);
on Linux the window, Metal and VideoToolbox are skipped (headless, CPU shaders; MediaCodec's video decoders are
libavcodec there, see "Video"). `native/linux/` holds the
bionic→glibc shim and a JNI bridge; `elfloader.c` is shared, with `#ifdef`s. On arm64 Linux, glibc owns TPIDR_EL0, so
the loader rewrites Android code's `mrs xN, tpidr_el0` into `adrp` of a read-only page after each module (stack guard at
+0x28, TLSDESC offsets from it), and the shim boxes `pthread_mutex_t`/`pthread_attr_t` (glibc's are bigger there).
Robolectric's Android 15+ runtime is android-all's `libandroid_runtime`; our android-all build has none for
linux/aarch64, so arm64 images take it from its respin (`gradle linuxArm64Runtime` into `work/linux-arm64`, see
`build.gradle.kts`). Google's Android build tools are x86_64-only on Linux: arm64 CI jobs get the test APKs from an
x86_64 job, and have no adb. Use JDK 24 there: JDK 25's AOT record mode rejects translated classes. See docs/linux-docker.md.
Memory in the image (docs/linux-docker.md "Memory", docs/perf-plan.md "Memory in the Linux image"): an idle instance of
the test app is ~330 MB resident on arm64, ~200 of it private (413 and 324 before the heap trim, the cache in place and
the launcher's `-XX:TrimNativeHeapInterval=5000`). `stats` footprintMb is VmRSS there, which counts shared file pages:
compare the anonymous part (`/proc/PID/smaps_rollup`) or PSS when the question is what one more instance costs, and
read it 40 s after the first frame (settling work refills eden once after the first trim). Under Rosetta the amd64
image holds 160-180 MB more, the translator's.
zlib imports (`libz.so` is a system library to the loader) resolve in the host's `libz.so.1` (`bionic_lookup`'s last step): a Temurin JVM keeps
its zlib private, so `dlsym(RTLD_DEFAULT)` found none and inflate/deflate were stubs (issue 148). Not built or run on a Linux host yet.
Colima shares only the home folder with its VM, so mount folders under it (`work/...`), not the scratchpad in
/private/tmp: those mounts are empty in the container. Tag images you build for a check with a name of your own
(`apksim:ci` and `apksim:latest` are shared). Releases publish the image as `ghcr.io/aglose/apksim:VERSION`, both architectures under one tag (`release.yml`
builds both side by side, checks each in parallel legs, then pushes `VERSION-amd64` and `VERSION-arm64` and joins them; `:latest` moves on publishing), with `instrument.py`, `snapshot.py` and `drive.py` on its `PATH` for other
repositories' CI (`--entrypoint instrument.py`, `--entrypoint snapshot.py`). `.github/actions/linux-build` is the Linux build both workflows share.
