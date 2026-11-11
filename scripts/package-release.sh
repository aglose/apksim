#!/bin/bash
# Packages a built checkout as a self-contained release for Apple Silicon Macs: the launcher, the runtime jar and
# libraries, the native runtime, a JDK 24 runtime (jlink), and the test app (which trains the startup cache on the
# first launch). Users need nothing else installed, not even the Xcode command-line tools.
# Usage: scripts/package-release.sh VERSION [OUTDIR]   (after scripts/setup.sh, or its steps without training)
# Writes OUTDIR/apksim-VERSION-macos-arm64.tar.gz (default OUTDIR: dist) and prints its SHA-256.
# An installed release keeps what it writes in ~/.apksim, not in its own folder (see the launcher), so the folder can
# be read-only, as under Homebrew.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:?usage: package-release.sh VERSION [OUTDIR]}"
OUTDIR="${2:-$ROOT/dist}"
fail() { echo "package-release.sh: $*" >&2; exit 1; }
source "$ROOT/scripts/jdk.sh"
[[ "$(jdk_major "$JDK")" == 24 ]] || fail "$JDK is not a JDK 24 (the startup cache needs 24)"
for f in work/apksim.jar work/runtime/apksim-client work/runtime/libsimwindow.dylib work/runtime/libandroid_runtime.dylib \
         work/runtime/icu/icudt.dat; do
  [[ -e "$ROOT/$f" ]] || fail "$f is missing: run scripts/setup.sh first"
done
TESTAPK="$ROOT/testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk"
[[ -f "$TESTAPK" ]] || TESTAPK="$ROOT/work/testapp.apk"
[[ -f "$TESTAPK" ]] || fail "no test app: build testapks/composeapp or run scripts/fetch-testapp.sh"

NAME="apksim-$VERSION-macos-arm64"
mkdir -p "$OUTDIR"
OUTDIR="$(cd "$OUTDIR" && pwd)"
STAGE="$(mktemp -d "$OUTDIR/stage.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
D="$STAGE/$NAME"
mkdir -p "$D/scripts" "$D/work/lib"

cp "$ROOT/apksim" "$ROOT/LICENSE" "$ROOT/NOTICE" "$ROOT/THIRD-PARTY-NOTICES.md" "$ROOT/README.md" "$D/"
# train.sh builds the startup cache; ctl.py and drive.py drive running instances; instrument.py (with affected.py) runs
# test suites; snapshot.py screenshot tests.
cp "$ROOT/scripts/train.sh" "$ROOT/scripts/ctl.py" "$ROOT/scripts/drive.py" "$ROOT/scripts/instrument.py" \
  "$ROOT/scripts/affected.py" "$ROOT/scripts/snapshot.py" "$ROOT/scripts/crawl.py" "$ROOT/scripts/device-profile.py" "$ROOT/scripts/replay-adb.py" "$D/scripts/"
cp "$ROOT/work/apksim.jar" "$D/work/"
# Robolectric's native runtime is unpacked into work/runtime (prepare-runtime.sh) and never read from its jar.
for j in "$ROOT"/work/lib/*.jar; do
  [[ "$(basename "$j")" == nativeruntime-dist-* ]] && continue
  cp -L "$j" "$D/work/lib/"
done
cp -RL "$ROOT/work/runtime" "$D/work/runtime"
cp "$TESTAPK" "$D/work/testapp.apk"

# The JDK: every module, so --debug (JDWP), the foreign function API and anything an app's libraries reach for work,
# except jlink and jpackage (which needs it). Temurin 24 has no jmods: jlink then links from the JDK's own run-time
# image (JEP 493), which cannot include jdk.jlink.
# Not compressed (a compressed image loads classes more slowly; the tarball is compressed anyway).
MODULES="$("$JDK/bin/java" --list-modules | sed 's/@.*//' | grep -vx 'jdk.jlink\|jdk.jpackage' | paste -sd, -)"
MP=(); [[ -d "$JDK/jmods" ]] && MP=(--module-path "$JDK/jmods")
"$JDK/bin/jlink" "${MP[@]+"${MP[@]}"}" --add-modules "$MODULES" --strip-debug --no-man-pages --no-header-files \
  --output "$D/work/jre"
# The launcher reads the JDK from work/jdk.path, relative to its folder.
printf 'work/jre\n' > "$D/work/jdk.path"
# Marks an installed release: state goes to ~/.apksim. Also what --version prints.
printf '%s\n' "$VERSION" > "$D/work/release"

TAR="$OUTDIR/$NAME.tar.gz"
tar -C "$STAGE" -czf "$TAR.tmp" "$NAME"
mv -f "$TAR.tmp" "$TAR"
(cd "$OUTDIR" && shasum -a 256 "$NAME.tar.gz")
du -h "$TAR" | cut -f1
