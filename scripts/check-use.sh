#!/bin/bash
# `apksim use` against a stand-in install and release in a temporary HOME: pin, hand-off, back, refusals, and (on a Mac)
# that an app's sandbox cannot write the pin or the releases. No network, no JDK.
set -euo pipefail
cd "$(dirname "$0")/.."
T="$(cd "$(mktemp -d)" && pwd -P)"
trap 'rm -rf "$T"' EXIT
export HOME="$T/home"
unset APKSIM_HOME APKSIM_NO_PIN APKSIM_DOWNLOAD_URL
INST="$HOME/.local/share/apksim/1.0.0"
mkdir -p "$INST/scripts" "$INST/work" "$T/mirror"
cp apksim "$INST/"; cp scripts/use.sh "$INST/scripts/"; echo 1.0.0 > "$INST/work/release"
A="$INST/apksim"
fails=0
check() { if [[ "$2" == *"$3"* ]]; then echo "ok   $1"; else echo "FAIL $1: wanted '$3' in: $2"; fails=1; fi; }
run() { "$A" "$@" 2>&1 || echo "status $?"; }

# A stand-in release that says what it was asked.
release() {
  local n="apksim-$1-macos-arm64"
  mkdir -p "$T/mirror/$n/work"
  printf '#!/bin/bash\necho "stub %s: $*"\n' "$1" > "$T/mirror/$n/apksim"; chmod +x "$T/mirror/$n/apksim"
  tar -czf "$T/mirror/$n.tar.gz" -C "$T/mirror" "$n"
  (cd "$T/mirror" && shasum -a 256 "$n.tar.gz" > SHA256SUMS)
}
export APKSIM_DOWNLOAD_URL="file://$T/mirror"

check "shows the installed release" "$(run use)" "apksim 1.0.0 (installed)"
release 8.0.0
check "pins a release" "$(run use v8.0.0)" "Now using apksim 8.0.0"
check "hands off to the pin" "$(run --version)" "stub 8.0.0: --version"
check "shows the pin" "$(run use)" "apksim 8.0.0 (pinned"
check "inspect goes to the pin" "$(run inspect)" "stub 8.0.0: beam config inspect-network optional"
check "beam install stays with the install" "$(run beam install)" "status"
check "APKSIM_NO_PIN stays with the install" "$(APKSIM_NO_PIN=1 run --version)" "1.0.0"
check "a missing release fails" "$(run use 7.7.7)" "could not download 7.7.7"
check "and keeps the pin" "$(run use)" "apksim 8.0.0 (pinned"
release 9.0.0; echo "0000  apksim-9.0.0-macos-arm64.tar.gz" > "$T/mirror/SHA256SUMS"
check "a bad checksum fails" "$(run use 9.0.0)" "checksum mismatch"
check "an old release is refused" "$(run use 0.36.0)" "before 0.37.0"
check "a bad version is refused" "$(run use ../x)" "is not a version"
check "a bad inspect value is refused" "$(run inspect maybe)" "status 2"
rm -rf "$HOME/.apksim-versions/8.0.0"
check "a missing pin falls back" "$(run --version)" "is missing"
check "goes back" "$(run use default)" "Now using apksim 1.0.0"
check "and the pin is gone" "$(run use)" "apksim 1.0.0 (installed)"
mkdir -p "$HOME/.apksim"; echo 8.0.0 > "$HOME/.apksim/use"
check "a pin in the state folder is ignored" "$(run --version)" "1.0.0"

if [[ "$(uname -s)" == Darwin ]]; then
  # Just enough for a launch to reach the sandbox: an APK to name, and a client that starts no background training.
  : > "$T/app.apk"
  mkdir -p "$INST/work/runtime" "$INST/work/lib"; printf '#!/bin/sh\n' > "$INST/work/runtime/apksim-client"; chmod +x "$INST/work/runtime/apksim-client"
  : > "$INST/work/lib/stand-in.jar"
  mkdir -p "$HOME/.apksim-versions"
  out="$(APKSIM_STANDBY=0 APKSIM_SANDBOX_TEST='
    : > "$HOME/.apksim/probe" && echo state-writable
    echo 8.0.0 > "$HOME/.apksim-versions/use" || echo pin-refused
    mkdir "$HOME/.apksim-versions/8.0.0" || echo release-refused' "$A" "$T/app.apk" 2>&1 || true)"
  # The launcher's link to the stand-in's libraries.
  LINK="${TMPDIR:-/tmp}"; rm -f "${LINK%/}/apksim-$(printf '%s' "$INST" | shasum | cut -c1-12)"
  check "the sandbox writes the state folder" "$out" "state-writable"
  check "the sandbox cannot write the pin" "$out" "pin-refused"
  check "the sandbox cannot add a release" "$out" "release-refused"
fi
exit "$fails"
