#!/bin/bash
# What an app in apksim's ordinary sandbox may write (macOS only: the launcher's sandbox-exec profile, through
# APKSIM_SANDBOX_TEST): its state folder and the temp folder, never what runs outside the sandbox: an installed
# release's folder (Homebrew's is outside the home folder), Homebrew's prefix, /usr/local, /Applications, the link to
# apksim's libraries, the startup cache and the test app training builds it from, a checkout's build outputs.
# Stand-ins for an install and a checkout in a temporary folder outside the home folder, and a temporary HOME.
set -euo pipefail
[[ "$(uname -s)" == Darwin ]] || { echo "check-sandbox-writes: macOS only, skipped"; exit 0; }
cd "$(dirname "$0")/.."
T="$(cd "$(mktemp -d)" && pwd -P)"
trap 'rm -rf "$T"' EXIT
export HOME="$T/home"
unset APKSIM_HOME APKSIM_NO_PIN APKSIM_SANDBOXED APKSIM_NOCACHE
fails=0
check() { if [[ "$2" == *"$3"* ]]; then echo "ok   $1"; else echo "FAIL $1: wanted '$3' in: $2"; fails=1; fi; }

# A folder the launcher runs as far as its sandbox: a client that starts no training, a library to link, an APK to name.
standin() {
  mkdir -p "$1/scripts" "$1/work/runtime" "$1/work/lib"
  cp apksim "$1/"; cp scripts/use.sh "$1/scripts/"
  printf '#!/bin/sh\n' > "$1/work/runtime/apksim-client"; chmod +x "$1/work/runtime/apksim-client"
  : > "$1/work/lib/stand-in.jar"; : > "$1/work/apksim.jar"
  rm -f "${TMPDIR:-/tmp}"/apksim-"$(printf '%s' "$1" | shasum | cut -c1-12)"
}
: > "$T/app.apk"
# Each line: a label, then a command run inside the sandbox; the command echoes "wrote" when the write got through.
inside() {
  local root="$1" script=""; shift
  while [[ $# -gt 0 ]]; do script+="if ( $2 ) 2>/dev/null; then echo \"$1: wrote\"; else echo \"$1: refused\"; fi
"; shift 2; done
  APKSIM_STANDBY=0 APKSIM_SANDBOX_TEST="$script" "$root/apksim" "$T/app.apk" 2>&1 || true
}

INST="$T/Caskroom/apksim/9.9.9/apksim-9.9.9-macos-arm64"
standin "$INST"; echo 9.9.9 > "$INST/work/release"
STATE="$HOME/.apksim"; mkdir -p "$STATE"; : > "$STATE/app.aot"; : > "$STATE/testapp-9.9.9.apk"
# The temp folder outside the stand-in prefix (the prefix is $T, refused as a whole).
TMPROBE="$(cd "${TMPDIR:-/tmp}" && pwd -P)/apksim-sandbox-probe.$$"
trap 'rm -rf "$T" "$TMPROBE"' EXIT
LINK="$(cd "${TMPDIR:-/tmp}" && pwd -P)/apksim-$(printf '%s' "$INST" | shasum | cut -c1-12)"
out="$(inside "$INST" \
  state ": > \"$STATE/tmp/probe\"" \
  temp ": > \"$TMPROBE\"" \
  launcher ": >> \"$INST/apksim\"" \
  scripts ": > \"$INST/scripts/probe\"" \
  prefix ": > \"$T/Caskroom/probe\"" \
  cache ": > \"$STATE/app.aot\"" \
  testapp ": > \"$STATE/testapp-9.9.9.apk\"" \
  download ": > \"$STATE/testapp.download.1\"" \
  liblink "rm \"$LINK\" && ln -s \"$T\" \"$LINK\"" \
  home ": > \"$HOME/probe\"")"
check "an install: the state folder is written" "$out" "state: wrote"
check "an install: the temp folder is written" "$out" "temp: wrote"
check "an install: its launcher is not" "$out" "launcher: refused"
check "an install: its scripts are not" "$out" "scripts: refused"
check "an install: Homebrew's prefix is not" "$out" "prefix: refused"
check "an install: the startup cache is not" "$out" "cache: refused"
check "an install: the test app is not" "$out" "testapp: refused"
check "an install: a test app download is not" "$out" "download: refused"
check "an install: the link to its libraries is not replaced" "$out" "liblink: refused"
check "an install: the home folder is not" "$out" "home: refused"

# The real places other programs run from: only refusals are tried, and anything that got through is removed.
for P in /opt/homebrew /usr/local /Applications; do
  [[ -d "$P" && -w "$P" ]] || continue
  probe="$P/.apksim-sandbox-probe.$$"
  out="$(inside "$INST" "$P" ": > \"$probe\"")"
  [[ -e "$probe" ]] && rm -f "$probe"
  check "an install: $P is not written" "$out" "$P: refused"
done

CO="$T/checkout"
standin "$CO"
out="$(inside "$CO" \
  cache ": > \"$CO/work/cache-probe\"" \
  jar ": > \"$CO/work/apksim.jar\"" \
  lib ": > \"$CO/work/lib/x.jar\"" \
  runtime ": > \"$CO/work/runtime/x.dylib\"" \
  source ": > \"$CO/apksim\"")"
check "a checkout: work/ is written" "$out" "cache: wrote"
check "a checkout: work/apksim.jar is not" "$out" "jar: refused"
check "a checkout: work/lib is not" "$out" "lib: refused"
check "a checkout: work/runtime is not" "$out" "runtime: refused"
check "a checkout: its launcher is not" "$out" "source: refused"
exit "$fails"
