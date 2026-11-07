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
# Whether the last 16 KB page of a library's code also holds the start of its first writable segment.
shares_page() {
  local end=0 rw=0 type off va pa fsz msz f1 f2
  while read -r type off va pa fsz msz f1 f2 _; do
    [[ "$type" == LOAD ]] || continue
    if [[ "$f2" == E ]]; then end=$((va + msz - 1)); fi
    if [[ "$f1" == RW && $rw == 0 ]]; then rw=$((va)); fi
  done < <("$BIN/llvm-readelf" -lW "$1")
  (( end && rw && end / 16384 == rw / 16384 ))
}
for pair in arm64-v8a:aarch64 x86_64:x86_64; do
  abi="${pair%%:*}" arch="${pair##*:}"
  mkdir -p "$OUT/$abi"
  # 16 KB pages: macOS refuses 4 KB-aligned libraries (no W+X pages). No debug info, so no build paths inside.
  "$BIN/clang" --target="$arch-linux-android29" -O2 -fPIC -shared -fstack-protector-all -Wall -Werror \
    -Wl,-z,max-page-size=16384 -Wl,--build-id=none -o "$OUT/$abi/libloaderfixture.so" "$HERE/loaderfixture.c"
  "$BIN/llvm-strip" --strip-unneeded "$OUT/$abi/libloaderfixture.so"
  # 4 KB pages on purpose (pagefixture.c): code sharing a 16 KB page with RELRO, and with writable data (-z norelro),
  # which the loader moves on macOS.
  for v in 4k:check4k: norelro:checkNoRelro:-Wl,-z,norelro; do
    name="${v%%:*}" rest="${v#*:}"
    method="${rest%%:*}" extra="${rest#*:}"
    "$BIN/clang" --target="$arch-linux-android29" -O2 -fPIC -shared -Wall -Werror -DFIXTURE_METHOD="$method" -DFIXTURE_LIB="libpagefixture$name.so" \
      -Wl,-z,max-page-size=4096 ${extra:+"$extra"} -Wl,--build-id=none -o "$OUT/$abi/libpagefixture$name.so" "$HERE/pagefixture.c"
    "$BIN/llvm-strip" --strip-unneeded "$OUT/$abi/libpagefixture$name.so"
  done
  # Moved data an adr can no longer reach (pagefixturefar.c), and C++ exceptions through a moved personality slot
  # (pagefixturecxx.cpp, libc++ linked in).
  "$BIN/clang" --target="$arch-linux-android29" -O2 -fPIC -shared -Wall -Werror -Wl,-z,max-page-size=4096 \
    -Wl,-z,norelro -Wl,--build-id=none -o "$OUT/$abi/libpagefixturefar.so" "$HERE/pagefixturefar.c"
  for pad in 0 4096; do
    "$BIN/clang++" --target="$arch-linux-android29" -O2 -fPIC -shared -Wall -Werror -static-libstdc++ -DTEXT_PAD=$pad \
      -Wl,-z,max-page-size=4096 -Wl,-z,norelro -Wl,--build-id=none -o "$OUT/$abi/libpagefixturecxx.so" "$HERE/pagefixturecxx.cpp"
    shares_page "$OUT/$abi/libpagefixturecxx.so" && break
  done
  for name in far cxx; do "$BIN/llvm-strip" --strip-unneeded "$OUT/$abi/libpagefixture$name.so"; done
  # The point of each: code and data on one 16 KB page (on arm64; x86_64's are stand-ins built the same way).
  for name in 4k norelro far cxx; do
    shares_page "$OUT/$abi/libpagefixture$name.so" || { echo "libpagefixture$name.so ($abi): no 16 KB page with code and data" >&2; exit 1; }
  done
done
echo "wrote libloaderfixture.so and libpagefixture{4k,norelro,far,cxx}.so for arm64-v8a and x86_64 with NDK $(basename "$NDK")"
