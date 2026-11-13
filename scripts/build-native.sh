#!/bin/bash
# Builds the native window. The app's own dylib must be arm64 like the JVM.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"   # JNI headers
mkdir -p "$ROOT/work/runtime"
clang -O2 -fobjc-arc -dynamiclib -I"$JDK/include" -I"$JDK/include/darwin" \
  -framework Cocoa -framework QuartzCore -framework Metal -framework IOSurface -framework AVFoundation -framework CoreMedia -framework CoreVideo -framework UniformTypeIdentifiers -framework AuthenticationServices \
  -o "$ROOT/work/runtime/libsimwindow.dylib.new" "$ROOT/native/simwindow.m"
clang -O2 -dynamiclib -Wno-deprecated-declarations -I"$JDK/include" -I"$JDK/include/darwin" \
  -o "$ROOT/work/runtime/libelfloader.dylib.new" "$ROOT/native/elfloader.c" "$ROOT/native/bionic.c" "$ROOT/native/bionic_net.c" "$ROOT/native/jnibridge.c" "$ROOT/native/jnigraphics.c"
clang -O2 -fobjc-arc -dynamiclib -I"$JDK/include" -I"$JDK/include/darwin" -framework Metal -framework Foundation \
  -o "$ROOT/work/runtime/libgpushader.dylib.new" "$ROOT/native/gpushader.m"
clang -O2 -fobjc-arc -dynamiclib -I"$JDK/include" -I"$JDK/include/darwin" \
  -framework VideoToolbox -framework CoreMedia -framework CoreVideo -framework CoreFoundation -framework Accelerate \
  -o "$ROOT/work/runtime/libvtdecoder.dylib.new" "$ROOT/native/videotoolbox.m"
# apksim serve's H.264 stream (dev.apksim.video.H264Encoder).
clang -O2 -fobjc-arc -dynamiclib -I"$JDK/include" -I"$JDK/include/darwin" \
  -framework VideoToolbox -framework CoreMedia -framework CoreVideo -framework CoreFoundation \
  -o "$ROOT/work/runtime/libh264enc.dylib.new" "$ROOT/native/h264enc.m"
clang -O2 -fobjc-arc -dynamiclib -I"$JDK/include" -I"$JDK/include/darwin" \
  -framework AudioToolbox -framework CoreAudio -framework Foundation \
  -o "$ROOT/work/runtime/libsimaudio.dylib.new" "$ROOT/native/audio.m"
# Warm launches (Standby.java): the standby's socket hand-off and the launcher's client.
clang -O2 -dynamiclib -I"$JDK/include" -I"$JDK/include/darwin" -o "$ROOT/work/runtime/libstandby.dylib.new" "$ROOT/native/standby.c"
clang -O2 -o "$ROOT/work/runtime/apksim-client.new" "$ROOT/native/apksim-client.c"
# --no-network: Java's IPv4 loopback connects over IPv4 sockets, which the sandbox allows (loopback.c).
clang -O2 -dynamiclib -o "$ROOT/work/runtime/libloopback.dylib.new" "$ROOT/native/loopback.c"
mv -f "$ROOT/work/runtime/apksim-client.new" "$ROOT/work/runtime/apksim-client"
# Rename into place, so running instances keep the libraries they mapped.
for l in libsimwindow libelfloader libgpushader libvtdecoder libh264enc libsimaudio libstandby libloopback; do mv -f "$ROOT/work/runtime/$l.dylib.new" "$ROOT/work/runtime/$l.dylib"; done
