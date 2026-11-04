#!/bin/bash
# Rebuilds fixture.dex from src/ with R8 (release mode), as a minified app's code is built, and fixture-d8-debug.dex
# with D8 (debug): see src/fixture/Checks.java and scripts/check-translator.sh. The .dex files are committed so the
# check needs no Android SDK.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
ANDROID_JAR="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)/android.jar"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
javac --release 11 -nowarn -d "$OUT/classes" $(find "$HERE/src" -name "*.java")
(cd "$OUT/classes" && jar cf "$OUT/in.jar" .)
mkdir -p "$OUT/dex"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 --release --min-api 26 --lib "$ANDROID_JAR" \
  --pg-conf "$HERE/rules.pro" --output "$OUT/dex" "$OUT/in.jar"
cp "$OUT/dex/classes.dex" "$HERE/fixture.dex"
# The same code through D8 in debug mode, as a debug app or test APK dexes its libraries: paths share one return inside
# a try range (the shape of AndroidX's LocationManagerCompat), which R8 and D8's release mode duplicate.
mkdir -p "$OUT/d8"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --debug --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/d8" "$OUT/in.jar"
cp "$OUT/d8/classes.dex" "$HERE/fixture-d8-debug.dex"
echo "wrote fixture.dex (R8) and fixture-d8-debug.dex (D8 debug), build-tools $(basename "$BT")"
