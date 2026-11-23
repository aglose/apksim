#!/bin/bash
# Compiles the runtime into work/apksim.jar.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
CP="$(ls "$ROOT"/work/lib/*.jar | tr '\n' ':')"
rm -rf "$ROOT/work/classes"
mkdir -p "$ROOT/work/classes"
# Signatures of libraries that APKs bring (libwebrtc), compiled against but not packaged.
rm -rf "$ROOT/work/stubs"
mkdir -p "$ROOT/work/stubs"
"$JDK/bin/javac" -nowarn -Xlint:none --release 22 -cp "$CP" -d "$ROOT/work/stubs" $(find "$ROOT/runtime/stubs" -name "*.java")
find "$ROOT/runtime/src/main/java" -name "*.java" | sed "s/.*/\"&\"/" > "$ROOT/work/sources.txt"
"$JDK/bin/javac" -nowarn -Xlint:none --release 22 -cp "$CP:$ROOT/work/stubs" -d "$ROOT/work/classes" @"$ROOT/work/sources.txt"
cp -R "$ROOT/runtime/src/main/resources/." "$ROOT/work/classes/"
# Write beside it and rename, so instances already running keep reading the jar they started with.
"$JDK/bin/jar" cf0 "$ROOT/work/apksim.jar.new" -C "$ROOT/work/classes" .
mv -f "$ROOT/work/apksim.jar.new" "$ROOT/work/apksim.jar"
# Offline framework-jar trampoline flattener (B2): builds work/runtime/android-all-flat.jar when shadows or the jar change.
ALL_JAR="$(ls "$ROOT"/work/lib/android-all-instrumented-*.jar 2>/dev/null | sort -V | tail -1 || true)"
if [[ -n "$ALL_JAR" && "${APKSIM_SKIP_FLATTEN:-0}" != 1 ]]; then
  mkdir -p "$ROOT/work/runtime"
  "$JDK/bin/java" -XX:+UseSerialGC -Xmx2g -XX:TieredStopAtLevel=1 \
    -cp "$ROOT/work/apksim.jar:$CP" dev.apksim.FrameworkFlattener \
    "$ALL_JAR" "$ROOT/work/runtime/android-all-flat.jar" "$ROOT/work/apksim.jar" "$ROOT/work/lib"
  # MimeMap reads these as class resources from the flat jar (issue 599): dropping them breaks MimeTypeMap.
  for f in android debian vendor; do
    unzip -Z1 "$ROOT/work/runtime/android-all-flat.jar" "res/$f.mime.types" >/dev/null \
      || { echo "build.sh: res/$f.mime.types missing from android-all-flat.jar" >&2; exit 1; }
  done
fi
# The launcher runs the JDK it was built with (it reads this with a builtin: no fork per launch).
# A JDK inside this folder (setup's work/jdk-24) is recorded relative to it, so moving the folder keeps it.
if [[ "$(uname -s)" == Darwin ]]; then printf '%s\n' "${JDK#"$ROOT/"}" > "$ROOT/work/jdk.path"; fi
