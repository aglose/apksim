#!/bin/bash
# Spike S1: both hand-offs with the instance's half inside each sandbox profile (s1probe.m). Run from the checkout.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
P="$ROOT/work/s1probe"
APK="$ROOT/testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk"
run_in() {  # PROFILE-NAME COMMAND: the command inside that profile
  case "$1" in
    default) APKSIM_SANDBOX_TEST="$2" "$ROOT/apksim" --headless "$APK" ;;
    beam) APKSIM_BEAM_SANDBOX=1 APKSIM_SANDBOX_TEST="$2" "$ROOT/apksim" --headless "$APK" ;;
    deny-lookup) sandbox-exec -p '(version 1)(allow default)(deny mach-lookup)' /bin/sh -c "$2" ;;
    allow-prefix) sandbox-exec -p '(version 1)(allow default)(deny mach-lookup)(allow mach-lookup (global-name-prefix "dev.apksim.spike."))' /bin/sh -c "$2" ;;
    deny-iokit) sandbox-exec -p '(version 1)(allow default)(deny iokit-open (iokit-user-client-class "IOSurfaceRootUserClient"))' /bin/sh -c "$2" ;;
  esac
}
for prof in default beam deny-lookup allow-prefix deny-iokit; do
  exec 3< <("$P" serve)
  read -r NAME <&3
  mach="$(run_in $prof "'$P' send $NAME" 2>&1 | tail -1)"
  hostm="$(cat <&3)"
  exec 3< <(run_in $prof "'$P' make" 2>&1)
  while read -r ID <&3 && [[ ! "$ID" =~ ^[0-9]+$ ]]; do :; done  # past the launcher's own lines
  glob="$("$P" read "$ID" 2>&1)"
  cat <&3 >/dev/null
  printf '%-13s mach: [%s | %s]  global: [instance %s | %s]\n' "$prof" "$mach" "$hostm" "$ID" "$glob"
done
