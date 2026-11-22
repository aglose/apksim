#!/bin/bash
# Checks that a URL launch's credentials (--header, --netrc) reach only the first origin of its redirects, on curl's
# path and the pure-Java one (dev.apksim.DownloadAuthCheck, two loopback servers). Exits 1 if any check fails.
# Needs work/apksim.jar (scripts/build.sh) and /usr/bin/curl (without it both passes take the Java path).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
CP="$ROOT/work/apksim.jar:$(ls "$ROOT"/work/lib/*.jar | tr "\n" ":")"
exec "$JDK/bin/java" -cp "$CP" dev.apksim.DownloadAuthCheck
