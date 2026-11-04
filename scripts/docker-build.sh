#!/bin/bash
# Builds the linux/amd64 apksim image (default tag apksim:latest; release.yml publishes it as ghcr.io/aglose/apksim).
# Only the files the image needs go into the build context, not work/cache. Needs a Docker engine (Colima with --vz-rosetta on a Mac) and buildx.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAG="${1:-apksim:latest}"
shift || true  # anything else goes to the build, e.g. --build-arg TRAIN=0 or --build-arg VERSION=0.3.0
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
cp "$ROOT/scripts/train.sh" "$ROOT/scripts/instrument.py" "$ROOT/scripts/drive.py" "$CTX/"
cp "$ROOT/work/apksim.jar" "$CTX/apksim.jar"
cp "$ROOT/testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk" "$CTX/train.apk"
"${BUILD[@]}" "$@" --load --platform linux/amd64 -t "$TAG" -f "$ROOT/docker/Dockerfile" "$CTX"
rm -rf "$CTX"
