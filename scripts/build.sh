#!/bin/bash
# Compiles the runtime into work/apksim.jar.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
CP="$(ls "$ROOT"/work/lib/*.jar | tr '\n' ':')"
rm -rf "$ROOT/work/classes"
mkdir -p "$ROOT/work/classes"
# Signatures of libraries that APKs bring (libwebrtc), compiled against but not packaged.
rm -rf "$ROOT/work/stubs"
mkdir -p "$ROOT/work/stubs"
"$JDK/bin/javac" -nowarn -Xlint:none --release 17 -cp "$CP" -d "$ROOT/work/stubs" $(find "$ROOT/runtime/stubs" -name "*.java")
find "$ROOT/runtime/src/main/java" -name "*.java" | sed "s/.*/\"&\"/" > "$ROOT/work/sources.txt"
"$JDK/bin/javac" -nowarn -Xlint:none --release 17 -cp "$CP:$ROOT/work/stubs" -d "$ROOT/work/classes" @"$ROOT/work/sources.txt"
cp -R "$ROOT/runtime/src/main/resources/." "$ROOT/work/classes/"
# Write beside it and rename, so instances already running keep reading the jar they started with.
"$JDK/bin/jar" cf0 "$ROOT/work/apksim.jar.new" -C "$ROOT/work/classes" .
mv -f "$ROOT/work/apksim.jar.new" "$ROOT/work/apksim.jar"
