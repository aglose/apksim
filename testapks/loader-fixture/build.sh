#!/bin/bash
# Rebuilds libloaderfixture.so (loaderfixture.c) for arm64-v8a and x86_64 with the Android NDK, into the test app's
# jniLibs, where NativeLoaderTest loads it. The libraries are committed so the test app builds without an NDK.
# NDK: $ANDROID_NDK_HOME, else the newest under the SDK's ndk/ (sdkmanager --install "ndk;29.0.14206865").
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_HOME:-$( (ls -d "$SDK"/ndk/* 2>/dev/null || true) | sort -V | tail -1)}"
[ -d "$NDK" ] || { echo "no Android NDK under $SDK/ndk (install one, or set ANDROID_NDK_HOME)" >&2; exit 1; }
BIN="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)"
OUT="$HERE/../composeapp/app/src/main/jniLibs"
for pair in arm64-v8a:aarch64 x86_64:x86_64; do
  abi="${pair%%:*}" arch="${pair##*:}"
  mkdir -p "$OUT/$abi"
  # 16 KB pages: macOS refuses 4 KB-aligned libraries (no W+X pages). No debug info, so no build paths inside.
  "$BIN/clang" --target="$arch-linux-android29" -O2 -fPIC -shared -fstack-protector-all -Wall -Werror \
    -Wl,-z,max-page-size=16384 -Wl,--build-id=none -o "$OUT/$abi/libloaderfixture.so" "$HERE/loaderfixture.c" -lz
  "$BIN/llvm-strip" --strip-unneeded "$OUT/$abi/libloaderfixture.so"
done
echo "wrote libloaderfixture.so for arm64-v8a and x86_64 with NDK $(basename "$NDK")"
