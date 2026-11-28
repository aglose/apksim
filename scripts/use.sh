#!/bin/bash
# apksim use [X.Y.Z|latest|default]: which release this Mac runs. A web app that pins a version older or newer than the
# installed one needs no reinstall: the release is downloaded beside apksim's data (~/.apksim/versions) and the
# installed launcher hands everything to it until another `use`. Homebrew's or install.sh's copy is left as it is.
# Usage (from the launcher): use.sh INSTALL_DIR LAUNCHER [X.Y.Z|latest|default]; APKSIM_STATE_DIR is apksim's data.
set -euo pipefail
REPO=aglose/apksim
ROOT="$1" LAUNCHER="$2" WANT="${3:-}"
STATE="${APKSIM_STATE_DIR:?run as: apksim use}"
fail() { echo "apksim use: $*" >&2; exit 1; }

case "$ROOT" in
  */Caskroom/apksim/*|"$HOME/.local/share/apksim/"*) ;;
  *) fail "works with a Homebrew or install.sh install, not $ROOT" ;;
esac
INSTALLED="$(< "$ROOT/work/release")"
PIN=""; [[ -f "$STATE/use" ]] && PIN="$(< "$STATE/use")"

if [[ -z "$WANT" ]]; then
  if [[ -n "$PIN" ]]; then echo "apksim $PIN (pinned; installed: $INSTALLED; apksim use default goes back)"
  else echo "apksim $INSTALLED (installed)"; fi
  exit 0
fi
if [[ "$WANT" == latest ]]; then
  WANT="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | sed -n 's/.*"tag_name": *"v\{0,1\}\([^"]*\)".*/\1/p' | head -1)"
  [[ -n "$WANT" ]] || fail "could not find the latest release"
fi
[[ "$WANT" == default ]] && WANT="$INSTALLED"
WANT="${WANT#v}"
[[ "$WANT" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "'$WANT' is not a version (apksim use X.Y.Z|latest|default)"

# Download first, so a failure changes nothing.
DIR="$STATE/versions/$WANT"
if [[ "$WANT" != "$INSTALLED" && ! -x "$DIR/apksim" ]]; then
  NAME="apksim-$WANT-macos-arm64"
  BASE="${APKSIM_DOWNLOAD_URL:-https://github.com/$REPO/releases/download/v$WANT}"
  mkdir -p "$STATE/versions"
  TMP="$(mktemp -d "$STATE/versions/.download.XXXXXX")"
  trap 'rm -rf "$TMP"' EXIT
  echo "Downloading apksim $WANT..."
  curl -fL --progress-bar -o "$TMP/$NAME.tar.gz" "$BASE/$NAME.tar.gz" || fail "no release $WANT"
  curl -fsSL -o "$TMP/SHA256SUMS" "$BASE/SHA256SUMS" || fail "no checksums for $WANT"
  (cd "$TMP" && grep " \*\{0,1\}$NAME.tar.gz\$" SHA256SUMS | shasum -a 256 -c -) >/dev/null || fail "checksum mismatch"
  tar -xzf "$TMP/$NAME.tar.gz" -C "$TMP"
  rm -rf "${DIR:?}"
  mv "$TMP/$NAME" "$DIR"
fi

# End what runs the version in use now (these go to it): the beam agent, with its sessions, and warm-launch standbys.
"$LAUNCHER" beam stop --force || echo "apksim use: the beam agent did not stop; quit it before using the page" >&2
"$LAUNCHER" --stop-standbys >/dev/null 2>&1 || true

mkdir -p "$STATE"
if [[ "$WANT" == "$INSTALLED" ]]; then rm -f "$STATE/use"
else echo "$WANT" > "$STATE/use.tmp" && mv "$STATE/use.tmp" "$STATE/use"; fi
echo "Now using apksim $WANT. Reload the page."
