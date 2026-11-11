#!/bin/bash
# Builds the apksim image (default tag apksim:latest; release.yml publishes it as ghcr.io/aglose/apksim) for linux/amd64,
# or for linux/arm64 with `--platform linux/arm64` (native on Apple Silicon and arm64 runners, no Rosetta).
# Only the files the image needs go into the build context, not work/cache. Needs a Docker engine (Colima with --vz-rosetta on a Mac) and buildx.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAG="${1:-apksim:latest}"
shift || true  # anything else goes to the build, e.g. --build-arg TRAIN=0 or --build-arg VERSION=0.3.0
PLATFORM=(--platform linux/amd64)
for a in "$@"; do [[ "$a" == --platform* ]] && PLATFORM=(); done
# Homebrew's buildx works standalone, without registering it in ~/.docker/config.json.
if docker buildx version >/dev/null 2>&1; then
  BUILD=(docker buildx build)
else
  BUILD=(/opt/homebrew/lib/docker/cli-plugins/docker-buildx build)
fi
CTX="$ROOT/work/docker-ctx"
rm -rf "$CTX"
mkdir -p "$CTX/lib"
cp -R "$ROOT/native" "$CTX/native"
ln "$ROOT"/work/lib/*.jar "$CTX/lib/" 2>/dev/null || cp "$ROOT"/work/lib/*.jar "$CTX/lib/"
cp "$ROOT/apksim" "$CTX/apksim"
cp "$ROOT/LICENSE" "$ROOT/NOTICE" "$ROOT/THIRD-PARTY-NOTICES.md" "$CTX/"
cp "$ROOT/scripts/train.sh" "$ROOT/scripts/instrument.py" "$ROOT/scripts/affected.py" "$ROOT/scripts/snapshot.py" \
  "$ROOT/scripts/crawl.py" "$ROOT/scripts/drive.py" "$ROOT/scripts/journey.py" "$CTX/"
cp "$ROOT/work/apksim.jar" "$CTX/apksim.jar"
# The clips the build stage's decoder check (native/linux/avdecoder_check.c) decodes; they are not shipped.
mkdir -p "$CTX/media"
cp "$ROOT"/testapks/media-fixture/clip.h264 "$ROOT"/testapks/media-fixture/clip.h265 "$CTX/media/"
# arm64: android-all's libandroid_runtime for linux/aarch64 comes from another build of it (build.gradle.kts).
mkdir -p "$CTX/linux-arm64"
if [[ " $* " == *linux/arm64* ]]; then
  if [[ ! -e "$ROOT/work/linux-arm64/libandroid_runtime.so" ]]; then
    if command -v gradle >/dev/null && [[ -n "${CI:-}" ]]; then gradle -q -p "$ROOT" linuxArm64Runtime --console=plain
    else (source "$ROOT/scripts/jdk.sh" && JAVA_HOME="$JDK" "$ROOT/testapks/composeapp/gradlew" -q -p "$ROOT" linuxArm64Runtime --console=plain); fi
  fi
  cp "$ROOT/work/linux-arm64/libandroid_runtime.so" "$CTX/linux-arm64/"
fi
cp "$ROOT/testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk" "$CTX/train.apk"
# CI runners: the build's RUN steps use the host's network. In the build's own network apt-get could not resolve
# Ubuntu's package servers ("Could not resolve 'archive.ubuntu.com'") while the runner itself could.
NETWORK=()
for a in "$@"; do [[ "$a" == --network* ]] && NETWORK=(skip); done
[[ -n "${CI:-}" && ${#NETWORK[@]} -eq 0 ]] && NETWORK=(--network host) || NETWORK=()
"${BUILD[@]}" "$@" ${NETWORK[@]+"${NETWORK[@]}"} --load ${PLATFORM[@]+"${PLATFORM[@]}"} -t "$TAG" -f "$ROOT/docker/Dockerfile" "$CTX"
rm -rf "$CTX"
