#!/bin/bash
# Checks how a recording's passwords are sealed and opened (dev.apksim.SecretsCheck, on a plain JVM) and `apksim rec
# secrets`, with a key file in a temporary folder (never the Keychain). Needs work/apksim.jar. Exits 1 on any failure.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
"$JDK/bin/java" -cp "$ROOT/work/apksim.jar" dev.apksim.SecretsCheck
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
mkdir -p "$T/state/secrets"
run() { APKSIM_SECRETS_KEY_FILE="$T/key" "$JDK/bin/java" -Dapksim.state="$T/state" -Dapksim.secrets.dir="$T/dir" -cp "$ROOT/work/apksim.jar" dev.apksim.Secrets "$@" 2>&1 || true; }
fails=0
check() { if [[ "$2" == *"$3"* ]]; then echo "ok   $1"; else echo "FAIL $1: wanted '$3' in: $2"; fails=1; fi; }
check "off at first" "$(run)" "off:"
check "on makes a key" "$(run on)" "on: the private key is in $T/key"
check "its file is the owner's alone" "$(ls -l "$T/key")" "-rw-------"
check "the output does not show it" "x$(run on | grep -cF "$(cat "$T/key")" || true)" "x0"
PUB="$(cat "$T/dir/public.key")"
run off >/dev/null
check "off" "$(run)" "off:"
run on >/dev/null
check "on again uses the same key" "$(cat "$T/dir/public.key")" "$PUB"
: > "$T/state/secrets/0123456789abcdef0123456789abcdef.sealed"
check "list" "$(run list)" "0123456789abcdef0123456789abcdef"
check "forget refuses a path" "$(run forget ../x)" "usage"
check "forget all" "$(run forget all)" "forgot 1 and the key"
check "and the key is gone" "$(ls "$T/key" "$T/dir/public.key" 2>&1 || true)" "No such file"
exit "$fails"
