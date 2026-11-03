#!/bin/bash
# One-time setup: fetch dependencies, unpack the runtime, build everything, build the startup cache.
# Needs: Apple Silicon, Xcode tools (clang), Homebrew openjdk (24), gradle, the Android SDK build of the test app.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
gradle -q deps --console=plain
for a in work/lib/*.aar; do unzip -p "$a" classes.jar > "${a%.aar}.jar"; done
scripts/prepare-runtime.sh >/dev/null
scripts/build.sh
(cd testapks/composeapp && ./gradlew -q :app:assembleDebug)
scripts/train.sh
