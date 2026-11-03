#!/bin/bash
# Builds the native window. The app's own dylib must be arm64 like the JVM.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
mkdir -p "$ROOT/work/runtime"
clang -O2 -fobjc-arc -dynamiclib -I"$JDK/include" -I"$JDK/include/darwin" \
  -framework Cocoa -framework QuartzCore -framework IOSurface -framework AVFoundation -framework CoreMedia -framework CoreVideo -framework UniformTypeIdentifiers \
  -o "$ROOT/work/runtime/libsimwindow.dylib.new" "$ROOT/native/simwindow.m"
clang -O2 -dynamiclib -Wno-deprecated-declarations -I"$JDK/include" -I"$JDK/include/darwin" \
  -o "$ROOT/work/runtime/libelfloader.dylib.new" "$ROOT/native/elfloader.c" "$ROOT/native/bionic.c" "$ROOT/native/bionic_net.c" "$ROOT/native/jnibridge.c"
clang -O2 -fobjc-arc -dynamiclib -I"$JDK/include" -I"$JDK/include/darwin" -framework Metal -framework Foundation \
  -o "$ROOT/work/runtime/libgpushader.dylib.new" "$ROOT/native/gpushader.m"
clang -O2 -fobjc-arc -dynamiclib -I"$JDK/include" -I"$JDK/include/darwin" \
  -framework VideoToolbox -framework CoreMedia -framework CoreVideo -framework CoreFoundation -framework Accelerate \
  -o "$ROOT/work/runtime/libvtdecoder.dylib.new" "$ROOT/native/videotoolbox.m"
clang -O2 -fobjc-arc -dynamiclib -I"$JDK/include" -I"$JDK/include/darwin" \
  -framework AudioToolbox -framework CoreAudio -framework Foundation \
  -o "$ROOT/work/runtime/libsimaudio.dylib.new" "$ROOT/native/audio.m"
# Rename into place, so running instances keep the libraries they mapped.
for l in libsimwindow libelfloader libgpushader libvtdecoder libsimaudio; do mv -f "$ROOT/work/runtime/$l.dylib.new" "$ROOT/work/runtime/$l.dylib"; done
