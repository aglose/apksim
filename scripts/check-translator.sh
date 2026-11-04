#!/bin/bash
# Translates the .dex files in testapks/translator-fixture (R8- and D8-built code in shapes the DEX translator once
# miscompiled) and runs them on a plain JVM: exits 1 if any check fails. Needs work/apksim.jar (scripts/build.sh).
#   scripts/check-translator.sh [FILE.dex...]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
CP="${APKSIM_CHECK_CP:-}$ROOT/work/apksim.jar:$(ls "$ROOT"/work/lib/*.jar | tr '\n' ':')"
[[ $# -gt 0 ]] || set -- "$ROOT"/testapks/translator-fixture/*.dex
# Translated classes carry no stack map frames: apksim loads them unverified, as here.
exec "$JDK/bin/java" -XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote -cp "$CP" dev.apksim.dex.TranslatorCheck "$@"
