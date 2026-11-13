#!/bin/bash
# Installs the latest apksim release (or APKSIM_VERSION) without Homebrew:
#   curl -fsSL https://raw.githubusercontent.com/aglose/apksim/main/install.sh | bash
# It downloads the release for Apple Silicon Macs, checks it against the release's SHA256SUMS, unpacks it into
# ~/.local/share/apksim/VERSION (APKSIM_PREFIX for another folder) and links ~/.local/bin/apksim (APKSIM_BIN) to it.
# apksim keeps what it writes (caches, app data) in ~/.apksim. To uninstall, delete those three.
set -euo pipefail
REPO=aglose/apksim
PREFIX="${APKSIM_PREFIX:-$HOME/.local/share/apksim}"
BIN="${APKSIM_BIN:-$HOME/.local/bin}"
fail() { echo "install.sh: $*" >&2; exit 1; }

[[ "$(uname -s)" == Darwin && "$(uname -m)" == arm64 ]] || fail "apksim releases are for Apple Silicon Macs (on Linux, see docs/linux-docker.md)"
VERSION="${APKSIM_VERSION:-}"
if [[ -z "$VERSION" ]]; then
  VERSION="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | sed -n 's/.*"tag_name": *"v\{0,1\}\([^"]*\)".*/\1/p' | head -1)"
  [[ -n "$VERSION" ]] || fail "could not find the latest release of $REPO"
fi
VERSION="${VERSION#v}"
NAME="apksim-$VERSION-macos-arm64"
BASE="${APKSIM_DOWNLOAD_URL:-https://github.com/$REPO/releases/download/v$VERSION}"  # a mirror, or a local test

mkdir -p "$PREFIX" "$BIN"
TMP="$(mktemp -d "$PREFIX/.download.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
echo "Downloading apksim $VERSION..."
curl -fL --progress-bar -o "$TMP/$NAME.tar.gz" "$BASE/$NAME.tar.gz"
curl -fsSL -o "$TMP/SHA256SUMS" "$BASE/SHA256SUMS"
(cd "$TMP" && grep " \*\{0,1\}$NAME.tar.gz\$" SHA256SUMS | shasum -a 256 -c -) >/dev/null \
  || fail "checksum mismatch for $NAME.tar.gz"
tar -xzf "$TMP/$NAME.tar.gz" -C "$TMP"
# Only folders that are apksim installs (work/release marks one) are replaced or removed: APKSIM_PREFIX may be shared.
is_install() { [[ -f "$1/work/release" && -x "$1/apksim" ]]; }
if [[ -e "$PREFIX/$VERSION" ]] && ! is_install "$PREFIX/$VERSION"; then
  fail "$PREFIX/$VERSION exists and is not an apksim install; set APKSIM_PREFIX to another folder"
fi
OLD=()
for d in "$PREFIX"/*/; do
  d="${d%/}"
  [[ "$(basename "$d")" != "$VERSION" ]] && is_install "$d" && OLD+=("$d")
done
rm -rf "${PREFIX:?}/$VERSION"
mv "$TMP/$NAME" "$PREFIX/$VERSION"
ln -sfn "$PREFIX/$VERSION/apksim" "$BIN/apksim"
# Earlier versions: stop their standbys (background JVMs that make launches warm), then remove them.
for d in "${OLD[@]+"${OLD[@]}"}"; do
  "$d/apksim" --stop-standbys >/dev/null 2>&1 || true
  rm -rf "$d"
done

echo "Installed apksim $VERSION in $PREFIX/$VERSION"
case ":$PATH:" in
  *":$BIN:"*) echo "Try:  apksim app.apk" ;;
  *) echo "Add $BIN to your PATH (for zsh: echo 'export PATH=\"$BIN:\$PATH\"' >> ~/.zshrc), then:  apksim app.apk" ;;
esac
echo "The first launch builds a startup cache in the background (1-2 minutes, once, downloading the 22 MB test app it"
echo "trains on); apksim --train builds it now."
