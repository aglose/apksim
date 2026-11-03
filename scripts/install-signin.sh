#!/bin/bash
# Installs `homesafe-signin` on the PATH and creates its config if missing. Run once.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ln -sfn "$ROOT/scripts/signin.sh" /opt/homebrew/bin/homesafe-signin
ln -sfn "$ROOT/scripts/signin.sh" /opt/homebrew/bin/homesafe-login
mkdir -p "$HOME/.config/apksim"
CONF="$HOME/.config/apksim/signin.conf"
if [[ ! -f "$CONF" ]]; then
  cat > "$CONF" <<'CONF'
# homesafe-signin defaults. ITEM is a 1Password item reference (in 1Password: open the item, then
# "Copy Secret Reference" on any field and drop the field name). Only the reference is stored here.
ITEM=op://<vault>/<item>
SERVER=https://<frigate-host>:8971
CONF
  chmod 600 "$CONF"
fi
echo "installed: $(command -v homesafe-signin)  config: $CONF"
