#!/bin/bash
# Checks what serve and beam copy out of a JVM crash report (dev.apksim.serve.Crash.hsErrLines, issue 523) on synthetic
# reports: the header, the crashing thread's lines and frames, each section's limit with siginfo kept, nothing from the
# registers or the environment, the total under the log endpoint's tail, and the file deleted
# (dev.apksim.serve.CrashCheck), and that `apksim --crash-summary` words the same reports as Crash.hsErrReason does. Exits 1 if any check fails. Needs work/apksim.jar (scripts/build.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
exec "$JDK/bin/java" -Dapksim.launcher="$ROOT/apksim" -cp "$ROOT/work/apksim.jar" dev.apksim.serve.CrashCheck
