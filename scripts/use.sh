#!/bin/bash
# apksim use [X.Y.Z|latest|default]: which release this Mac runs. A web app that pins a version older or newer than the
# installed one needs no reinstall: the release is downloaded into ~/.apksim-versions and the installed launcher
# hands everything to it until another `use`. Homebrew's or install.sh's copy is left as it is.
# ~/.apksim-versions is not under ~/.apksim: apps' sandboxes may write that, and what is here runs unsandboxed.
# Usage (from the launcher): use.sh INSTALL_DIR LAUNCHER [X.Y.Z|latest|default]
set -euo pipefail
REPO=aglose/apksim
ROOT="$1" LAUNCHER="$2" WANT="${3:-}"
VERSIONS="$HOME/.apksim-versions"
# The oldest release with both `beam stop` and the inspector setting `apksim inspect` writes.
MIN=0.37.0
fail() { echo "apksim use: $*" >&2; exit 1; }

case "$ROOT" in
  */Caskroom/apksim/*|"$HOME/.local/share/apksim/"*) ;;
  *) fail "works with a Homebrew or install.sh install, not $ROOT" ;;
esac
INSTALLED="$(< "$ROOT/work/release")"
PIN=""; [[ -f "$VERSIONS/use" ]] && PIN="$(< "$VERSIONS/use")"
[[ "$PIN" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || PIN=""

if [[ -z "$WANT" ]]; then
  if [[ -n "$PIN" && -x "$VERSIONS/$PIN/apksim" ]]; then
    echo "apksim $PIN (pinned; installed: $INSTALLED; apksim use default goes back)"
  elif [[ -n "$PIN" ]]; then echo "apksim $INSTALLED (installed; the pinned $PIN is missing: apksim use $PIN fetches it)"
  else echo "apksim $INSTALLED (installed)"; fi
  exit 0
fi
if [[ "$WANT" == latest ]]; then
  WANT="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | sed -n 's/.*"tag_name": *"v\{0,1\}\([^"]*\)".*/\1/p' | head -1)" || fail "could not find the latest release"
  [[ -n "$WANT" ]] || fail "could not find the latest release"
fi
[[ "$WANT" == default ]] && WANT="$INSTALLED"
WANT="${WANT#v}"
[[ "$WANT" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "'$WANT' is not a version (apksim use X.Y.Z|latest|default)"
IFS=. read -r MAJOR MINOR _ <<< "$WANT"
IFS=. read -r _ MIN_MINOR _ <<< "$MIN"
[[ "$WANT" == "$INSTALLED" ]] || (( 10#$MAJOR > 0 || 10#$MINOR >= MIN_MINOR )) || fail "releases before $MIN cannot be used"

# Download first, so a failure changes nothing.
DIR="$VERSIONS/$WANT"
if [[ "$WANT" != "$INSTALLED" && ! -x "$DIR/apksim" ]]; then
  NAME="apksim-$WANT-macos-arm64"
  BASE="${APKSIM_DOWNLOAD_URL:-https://github.com/$REPO/releases/download/v$WANT}"
  mkdir -p "$VERSIONS"
  TMP="$(mktemp -d "$VERSIONS/.download.XXXXXX")"
  trap 'rm -rf "$TMP"' EXIT
  echo "Downloading apksim $WANT..."
  curl -fL --progress-bar -o "$TMP/$NAME.tar.gz" "$BASE/$NAME.tar.gz" || fail "could not download $WANT (no such release, or no network)"
  curl -fsSL -o "$TMP/SHA256SUMS" "$BASE/SHA256SUMS" || fail "no checksums for $WANT"
  (cd "$TMP" && grep " \*\{0,1\}$NAME.tar.gz\$" SHA256SUMS | shasum -a 256 -c -) >/dev/null || fail "checksum mismatch"
  tar -xzf "$TMP/$NAME.tar.gz" -C "$TMP"
  rm -rf "${DIR:?}"
  mv "$TMP/$NAME" "$DIR"
fi

# What runs now: its launcher ends it, below.
OLD="$LAUNCHER"; [[ -n "$PIN" && -x "$VERSIONS/$PIN/apksim" ]] && OLD="$VERSIONS/$PIN/apksim"

# The pin first: a page asking the agent while it is stopped then starts the new release, not the old one again.
mkdir -p "$VERSIONS"
if [[ "$WANT" == "$INSTALLED" ]]; then rm -f "$VERSIONS/use"
else echo "$WANT" > "$VERSIONS/use.tmp" && mv "$VERSIONS/use.tmp" "$VERSIONS/use"; fi

# End the beam agent, with its sessions, and warm-launch standbys.
APKSIM_NO_PIN=1 "$OLD" beam stop --force || echo "apksim use: the beam agent did not stop; quit it before using the page" >&2
APKSIM_NO_PIN=1 "$OLD" --stop-standbys >/dev/null 2>&1 || true
echo "Now using apksim $WANT. Reload the page."
