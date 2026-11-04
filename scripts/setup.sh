#!/bin/bash
# One-time setup: fetch dependencies, unpack the runtime, build everything, build the startup cache.
# Needs an Apple Silicon Mac with the Xcode command-line tools. Everything else is found or fetched:
# - a JDK 24 (scripts/jdk.sh); without one, Temurin 24 is downloaded into work/jdk-24
# - Gradle: the test app's wrapper, so no Gradle install is needed
# - the Android SDK, only to build the test app; without it, setup downloads the released test app instead
#   (ANDROID_HOME, ANDROID_SDK_ROOT or ~/Library/Android/sdk)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
fail() { echo "setup.sh: $*" >&2; exit 1; }
step() { echo "==> $*"; }

[[ "$(uname -s)" == Darwin ]] || fail "this script is for macOS; for Linux see docs/linux-docker.md"
[[ "$(uname -m)" == arm64 ]] || fail "apksim needs an Apple Silicon (arm64) Mac"
xcode-select -p >/dev/null 2>&1 && command -v clang >/dev/null \
  || fail "the Xcode command-line tools are missing: run  xcode-select --install"
[[ "$ROOT" != *" "* ]] || fail "move the folder to a path without spaces (Robolectric breaks on them)"

step "JDK 24"
if ! (source scripts/jdk.sh) >/dev/null 2>&1; then scripts/fetch-jdk.sh; fi
source scripts/jdk.sh
echo "using $JDK"

GRADLEW="$ROOT/testapks/composeapp/gradlew"
step "Dependencies (Robolectric, android-all) into work/lib"
JAVA_HOME="$JDK" "$GRADLEW" -q -p "$ROOT" deps --console=plain
for a in work/lib/*.aar; do [[ -e "$a" ]] && unzip -p "$a" classes.jar > "${a%.aar}.jar"; done

step "Native runtime"
scripts/prepare-runtime.sh >/dev/null
step "Runtime jar"
scripts/build.sh

step "Test app (used to train the startup cache)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
TESTAPK=""
if [[ -d "$SDK/platforms" ]]; then
  [[ -f testapks/composeapp/local.properties ]] || printf 'sdk.dir=%s\n' "$SDK" > testapks/composeapp/local.properties
  (cd testapks/composeapp && JAVA_HOME="$JDK" ./gradlew -q :app:assembleDebug)
  TESTAPK="$ROOT/testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk"
else
  echo "no Android SDK found (set ANDROID_HOME to build the test app); downloading the released one"
  scripts/fetch-testapp.sh
  TESTAPK="$ROOT/work/testapp.apk"
fi

step "Startup cache (scripts/train.sh)"
scripts/train.sh "$TESTAPK"

if git rev-parse --git-dir >/dev/null 2>&1; then
  git config core.hooksPath scripts/hooks   # refuses direct pushes to main (pull requests only)
fi
step "Done. Try:  ./apksim $TESTAPK"
