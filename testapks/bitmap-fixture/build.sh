#!/bin/bash
# Rebuilds libbitmapfixture.so (bitmapfixture.c) for arm64-v8a and x86_64 with the Android NDK, into the test app's
# jniLibs, where NativeBitmapTest loads it. The libraries are committed so the test app builds without an NDK.
# NDK: $ANDROID_NDK_HOME, else the newest under the SDK's ndk/ (sdkmanager --install "ndk;29.0.14206865").
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
[ -d "$NDK" ] || { echo "no Android NDK (set ANDROID_NDK_HOME)" >&2; exit 1; }
BIN="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)"
OUT="$HERE/../composeapp/app/src/main/jniLibs"
for pair in arm64-v8a:aarch64 x86_64:x86_64; do
  abi="${pair%%:*}" arch="${pair##*:}"
  mkdir -p "$OUT/$abi"
  # API 30: AndroidBitmap_getDataSpace and _compress. 16 KB pages: macOS refuses 4 KB-aligned libraries (no W+X
  # pages). Plain relocations (the NDK packs them as DT_ANDROID_RELR by default). No debug info, so no build paths.
  "$BIN/clang" --target="$arch-linux-android30" -O2 -fPIC -shared -Wall -Werror \
    -Wl,-z,max-page-size=16384 -Wl,--pack-dyn-relocs=none -Wl,--build-id=none -o "$OUT/$abi/libbitmapfixture.so" "$HERE/bitmapfixture.c" -ljnigraphics
  "$BIN/llvm-strip" --strip-unneeded "$OUT/$abi/libbitmapfixture.so"
done
echo "wrote libbitmapfixture.so for arm64-v8a and x86_64 with NDK $(basename "$NDK")"
