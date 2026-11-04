# Sourced by the build scripts: sets JDK to the JDK 24 that builds and runs apksim.
# JDK 25 and later run apksim but cannot build its startup cache: their AOT record mode verifies the translated app
# classes, which carry no stack map frames, and rejects them. Homebrew no longer has a JDK 24, so scripts/setup.sh
# fetches one into work/jdk-24 when none is found (scripts/fetch-jdk.sh).
# Order (Mac): $APKSIM_JDK, work/jdk-24, Homebrew's openjdk if it is 24, /usr/libexec/java_home -v 24, $JAVA_HOME if 24.
# On Linux: $APKSIM_JDK, else $JAVA_HOME.

jdk_major() {  # the feature version of the JDK at $1, from its release file
  local v
  v="$(sed -n 's/^JAVA_VERSION="\([0-9]*\).*/\1/p' "$1/release" 2>/dev/null)"
  printf '%s' "${v:-0}"
}

find_jdk() {
  local root="$1" c
  if [[ -n "${APKSIM_JDK:-}" ]]; then printf '%s' "$APKSIM_JDK"; return; fi
  if [[ "$(uname -s)" != Darwin ]]; then printf '%s' "${JAVA_HOME:-}"; return; fi
  for c in "$root/work/jdk-24/Contents/Home" \
           /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
           /opt/homebrew/Cellar/openjdk/24*/libexec/openjdk.jdk/Contents/Home \
           "$(/usr/libexec/java_home -v 24 2>/dev/null)" \
           "${JAVA_HOME:-}"; do
    [[ -n "$c" && -x "$c/bin/javac" && "$(jdk_major "$c")" == 24 ]] && { printf '%s' "$c"; return; }
  done
  return 0  # none found: the check below says what to do (a failing status would end set -e scripts silently)
}

JDK="$(find_jdk "$ROOT")" || JDK=""
if [[ -z "$JDK" || ! -x "$JDK/bin/javac" ]]; then
  echo "No JDK 24 found. Run scripts/setup.sh (it fetches one into work/jdk-24), or set APKSIM_JDK to a JDK 24 home." >&2
  exit 1
fi
if [[ "$(uname -s)" == Darwin && "$(jdk_major "$JDK")" != 24 ]]; then
  echo "warning: $JDK is JDK $(jdk_major "$JDK"), not 24: apksim runs, but scripts/train.sh fails on 25 and later." >&2
fi
