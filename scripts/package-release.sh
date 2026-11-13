#!/bin/bash
# Packages a built checkout as a self-contained release for Apple Silicon Macs: the launcher, the runtime jar and
# libraries, the native runtime and a JDK 24 runtime (jlink). Users need nothing else installed, not even the Xcode
# command-line tools. The test app, which trains the startup cache, is a separate file the first launch downloads.
# Usage: scripts/package-release.sh VERSION [OUTDIR]   (after scripts/setup.sh, or its steps without training)
# Writes OUTDIR/apksim-VERSION-macos-arm64.tar.gz and OUTDIR/apksim-testapp-VERSION.apk (default OUTDIR: dist) and
# prints their SHA-256.
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
  "$ROOT/scripts/affected.py" "$ROOT/scripts/coverage.py" "$ROOT/scripts/snapshot.py" "$ROOT/scripts/crawl.py" "$ROOT/scripts/device-profile.py" "$ROOT/scripts/replay-adb.py" "$ROOT/scripts/mcp.py" "$D/scripts/"
cp "$ROOT/work/apksim.jar" "$D/work/"
# Robolectric's native runtime is unpacked into work/runtime (prepare-runtime.sh) and never read from its jar.
for j in "$ROOT"/work/lib/*.jar; do
  [[ "$(basename "$j")" == nativeruntime-dist-* ]] && continue
  cp -L "$j" "$D/work/lib/"
done
cp -RL "$ROOT/work/runtime" "$D/work/runtime"

# What a Mac never reads, taken out of the staged copy only (the checkout keeps everything):
# - android-all's natives (Windows, Linux, both Macs) and ICU data: FastNativeLoader loads them from work/runtime,
#   unpacked by prepare-runtime.sh. The Linux image drops them the same way (docker/Dockerfile).
# - Conscrypt's natives for other systems: it loads its own from the jar by OS and CPU, osx-aarch_64 here.
# Neither jar is signed, so taking entries out breaks nothing.
ALL_JAR="$(ls "$D"/work/lib/android-all-instrumented-*.jar | sort -V | tail -1)"
zip -q -d "$ALL_JAR" 'native/*' 'icu/*'
! unzip -Z1 "$ALL_JAR" | grep -qE '^(native|icu)/' || fail "$(basename "$ALL_JAR") still has native/ or icu/ entries"
for j in "$D"/work/lib/conscrypt-openjdk-uber-*.jar; do
  OTHERS=($(unzip -Z1 "$j" 'META-INF/native/*' 2>/dev/null | grep -v 'osx-aarch_64' || true))
  [[ ${#OTHERS[@]} -gt 0 ]] && zip -q -d "$j" "${OTHERS[@]}"
  [[ "$(unzip -Z1 "$j" 'META-INF/native/*' 2>/dev/null | wc -l | tr -d ' ')" == 1 ]] \
    || fail "$(basename "$j") should keep exactly its osx-aarch_64 library"
done
for f in libandroid_runtime.dylib librobolectric-nativeruntime.dylib icu/icudt.dat hyphen-data fonts/fonts.xml; do
  [[ -e "$D/work/runtime/$f" ]] || fail "work/runtime/$f is missing: run scripts/prepare-runtime.sh"
done
# - Font files fonts.xml never names: Android's SystemFonts maps only what it lists, so these are never opened (six of
#   the seven Noto Sans CJK weights, ~107 MB, and some Latin families). By rule, not by list, to follow Robolectric
#   upgrades.
FONTS="$D/work/runtime/fonts"
DROPPED=0; DROPPED_BYTES=0
for f in "$FONTS"/*.ttf "$FONTS"/*.ttc "$FONTS"/*.otf; do
  [[ -e "$f" ]] || continue
  n="$(basename "$f")"
  # A <font> element's text is the file name, then its end tag, an <axis> child or a line break.
  awk -v n="$n" '{ i = index($0, ">" n); if (i) { c = substr($0, i + 1 + length(n), 1); if (c == "<" || c == "" || c ~ /[ \t\r]/) f = 1 } } END { exit !f }' \
    "$FONTS/fonts.xml" && continue
  DROPPED_BYTES=$((DROPPED_BYTES + $(stat -f %z "$f"))); DROPPED=$((DROPPED + 1)); rm "$f"
done
for keep in Roboto-Regular.ttf NotoSansCJK-Regular.ttc NotoColorEmoji.ttf; do
  [[ -f "$FONTS/$keep" ]] || fail "fonts/$keep was pruned: fonts.xml no longer names it as expected"
done
LEFT="$(ls "$FONTS" | grep -cE '\.(ttf|ttc|otf)$')"
[[ "$LEFT" -ge 100 ]] || fail "only $LEFT fonts left after pruning: fonts.xml is not what this expects"
echo "fonts: $LEFT kept, $DROPPED not named in fonts.xml left out ($((DROPPED_BYTES / 1000000)) MB)"

# The test app trains the startup cache on the first launch (train.sh), which downloads it from the release and checks
# it against this hash: the tarball, which brew and install.sh verify, carries it, so the download needs no other
# trust. It goes beside the tarball as the release's apksim-testapp-VERSION.apk.
shasum -a 256 "$TESTAPK" | cut -d' ' -f1 > "$D/work/testapp.sha256"

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
cp "$TESTAPK" "$OUTDIR/apksim-testapp-$VERSION.apk"
(cd "$OUTDIR" && shasum -a 256 "$NAME.tar.gz" "apksim-testapp-$VERSION.apk")
du -h "$TAR" | cut -f1
