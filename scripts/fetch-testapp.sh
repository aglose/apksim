#!/bin/bash
# Downloads the released build of the test app (testapks/composeapp) into work/testapp.apk and checks it against the
# release's SHA256SUMS. scripts/setup.sh uses it to train the startup cache when there is no Android SDK to build one.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAG=v0.1.0
NAME=apksim-testapp-0.1.0.apk
BASE="https://github.com/aglose/apksim/releases/download/$TAG"
OUT="$ROOT/work/testapp.apk"
mkdir -p "$ROOT/work"
TMP="$(mktemp -d "$ROOT/work/testapp-download.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
curl -fsSL -o "$TMP/$NAME" "$BASE/$NAME"
curl -fsSL -o "$TMP/SHA256SUMS" "$BASE/SHA256SUMS"
(cd "$TMP" && grep " \*\{0,1\}$NAME\$" SHA256SUMS | shasum -a 256 -c -) >/dev/null \
  || { echo "fetch-testapp.sh: checksum mismatch for $NAME" >&2; exit 1; }
mv "$TMP/$NAME" "$OUT"
echo "$OUT"
