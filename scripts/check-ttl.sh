#!/bin/bash
# Checks the time to live of apps added to serve (Catalog.expireAt, docs/pr-preview.md): an expired id is unknown, its APK
# stays while a session holds it and goes after, and ttl bounds are refused (dev.apksim.serve.CatalogTtlCheck). Exits 1 if
# any check fails. Needs work/apksim.jar (scripts/build.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
exec "$JDK/bin/java" -cp "$ROOT/work/apksim.jar" dev.apksim.serve.CatalogTtlCheck
