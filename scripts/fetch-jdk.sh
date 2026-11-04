#!/bin/bash
# Downloads Eclipse Temurin 24 for Apple Silicon into work/jdk-24 and checks its SHA-256. scripts/setup.sh runs it
# when no JDK 24 is installed (Homebrew's openjdk has moved past 24; see scripts/jdk.sh for why 24).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION=24.0.2+12
URL="https://github.com/adoptium/temurin24-binaries/releases/download/jdk-24.0.2%2B12/OpenJDK24U-jdk_aarch64_mac_hotspot_24.0.2_12.tar.gz"
SHA256=db2ba6f72c19ad8b742303a504f58474bceeb94174a185de5f095c1d45577f1c
OUT="$ROOT/work/jdk-24"
if [[ -x "$OUT/Contents/Home/bin/javac" ]]; then echo "$OUT already has a JDK"; exit 0; fi
mkdir -p "$ROOT/work"
TMP="$(mktemp -d "$ROOT/work/jdk-download.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
echo "Downloading Temurin $VERSION (136 MB)..."
curl -fL --progress-bar -o "$TMP/jdk.tar.gz" "$URL"
if [[ "$(shasum -a 256 "$TMP/jdk.tar.gz" | cut -d' ' -f1)" != "$SHA256" ]]; then
  echo "fetch-jdk.sh: checksum mismatch for $URL" >&2; exit 1
fi
mkdir "$TMP/x"
tar -xzf "$TMP/jdk.tar.gz" -C "$TMP/x"
rm -rf "$OUT"
mv "$TMP/x/jdk-$VERSION" "$OUT"
"$OUT/Contents/Home/bin/java" -version 2>&1 | head -1
