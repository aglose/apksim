#!/bin/bash
# Signs an app's login screen in from 1Password, for the owner to run. The secrets go from `op` into shell
# variables and from there straight into the app: to apksim through the clipboard (pasted with the
# `paste` control command, then cleared), to an emulator over adb's stdin. They never appear in a
# command line, a log or this script's output.
#
#   homesafe-signin                     (installed by scripts/install-signin.sh; uses the config below)
#   scripts/signin.sh --item 'op://Private/HomeSafe Frigate' [--server URL] [--sim PORT] [--emulator]
#   homesafe-signin --save [--package PKG]
#
# --save writes apksim's autofill file for the app (work/autofill/PKG.properties, mode 600) instead, so every apksim
# launch signs the app in by itself (see runtime/.../Autofill.java). Delete the file to stop it. The values go from
# `op` straight into that file, never onto a command line or into this script's output.
#
# Defaults come from ~/.config/apksim/signin.conf (ITEM=, SERVER=). With no --sim or --emulator it signs in
# whatever is running: a connected emulator, and apksim instances on control ports 7400-7419.
#
# Field positions are for HomeSafe's sign-in screen on the phone device (822x1782); override with
# --fields 'URL_Y USER_Y PASS_Y CONNECT_Y'. HomeSafe asks for a sign-in on every cold start (unless a
# biometric login was saved), so run this after each launch. Targets not showing the sign-in screen (its
# orange Connect button) are left alone.
set -euo pipefail
ROOT="$(cd "$(dirname "$(readlink -f "$0")")/.." && pwd)"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
ITEM="" SERVER="" SIM="" EMU="" FIELDS="702 892 1042 1253" SUBMIT=1 SAVE="" PKG="com.meticulouscreations.homesafe.preview"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --item) ITEM="$2"; shift 2 ;;
    --server) SERVER="$2"; shift 2 ;;
    --sim) SIM="$2"; shift 2 ;;
    --emulator) EMU=1; shift ;;
    --fields) FIELDS="$2"; shift 2 ;;
    --no-submit) SUBMIT=""; shift ;;  # Fill the fields but leave Connect alone (for testing the script).
    --save) SAVE=1; shift ;;
    --package) PKG="$2"; shift 2 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done
CONF="$HOME/.config/apksim/signin.conf"
if [[ -f "$CONF" ]]; then
  CONF_ITEM="$(sed -n 's/^ITEM=//p' "$CONF" | head -1)"
  CONF_SERVER="$(sed -n 's/^SERVER=//p' "$CONF" | head -1)"
  [[ -n "$ITEM" ]] || ITEM="$CONF_ITEM"
  [[ -n "$SERVER" ]] || SERVER="$CONF_SERVER"
fi
SIMS=()
if [[ -z "$SIM" && -z "$EMU" ]]; then
  [[ "$("$ADB" get-state 2>/dev/null)" == device ]] && EMU=1
  for p in $(seq 7400 7419); do nc -z 127.0.0.1 "$p" 2>/dev/null && SIMS+=("$p"); done
elif [[ -n "$SIM" ]]; then
  SIMS=("$SIM")
fi
if [[ -z "$ITEM" || "$ITEM" == *"<"* ]]; then
  # Find the Login item for this server: only titles, vaults and URLs are read here, never secrets.
  HOST="$(printf '%s' "$SERVER" | sed -E 's#^[a-z]+://##; s#[:/].*##')"
  FOUND="$(op item list --categories Login --format json | python3 -c '
import json, sys
host = sys.argv[1].lower()
items = json.load(sys.stdin)
def text(i):
    urls = " ".join(u.get("href", "") for u in i.get("urls", []))
    return (i.get("title", "") + " " + urls).lower()
hits = [i for i in items if host and host in text(i)] or \
       [i for i in items if any(k in text(i) for k in ("frigate", "homesafe", "percysafe", "surveillance"))]
for i in hits:
    print("op://%s/%s\t%s (%s)" % (i["vault"]["id"], i["id"], i.get("title", ""), i["vault"].get("name", "")))
' "$HOST")"
  COUNT="$(printf '%s' "$FOUND" | grep -c . || true)"
  if [[ "$COUNT" == 1 ]]; then
    ITEM="$(printf '%s' "$FOUND" | cut -f1)"
    echo "Using 1Password item: $(printf '%s' "$FOUND" | cut -f2)"
    mkdir -p "$(dirname "$CONF")"
    if [[ -f "$CONF" ]] && grep -q '^ITEM=' "$CONF"; then
      sed -i '' "s#^ITEM=.*#ITEM=$ITEM#" "$CONF"
    else
      echo "ITEM=$ITEM" >> "$CONF"
    fi
  else
    echo "Could not pick one 1Password item for $HOST. Candidates:" >&2
    printf '%s\n' "${FOUND:-  (none)}" | sed 's/^/  /' >&2
    echo "Put the right one in $CONF as ITEM=op://..., or pass --item." >&2
    exit 2
  fi
fi
if [[ -n "$SAVE" ]]; then
  OUT="$ROOT/work/autofill/$PKG.properties"
  mkdir -p "$(dirname "$OUT")"
  ( umask 077
    AF_SERVER="$SERVER" AF_USER="$(op read "$ITEM/username")" AF_PASS="$(op read "$ITEM/password")" python3 - "$OUT" <<'PY'
import os, sys
def esc(s, key=False):
    out = []
    for ch in s:
        if ch in "\\=:#!" or (key and ch == " ") or (ch == " " and not out):
            out.append("\\" + ch)
        elif ch == "\n": out.append("\\n")
        elif ord(ch) > 126 or ord(ch) < 32: out.append("\\u%04x" % ord(ch))
        else: out.append(ch)
    return "".join(out)
lines = ["# apksim autofill: written by homesafe-signin --save. Delete this file to stop signing in on launch."]
if os.environ["AF_SERVER"]: lines.append(esc("Server URL", True) + "=" + esc(os.environ["AF_SERVER"]))
lines.append("Username=" + esc(os.environ["AF_USER"]))
lines.append("Password=" + esc(os.environ["AF_PASS"]))
lines.append("submit=Connect")
open(sys.argv[1], "w").write("\n".join(lines) + "\n")
PY
  )
  chmod 600 "$OUT"
  echo "saved apksim autofill for $PKG (work/autofill/$PKG.properties); apksim signs in by itself from its next launch"
  exit 0
fi
[[ ${#SIMS[@]} -gt 0 || -n "$EMU" ]] || { echo "Nothing to sign in: no emulator and no apksim control port." >&2; exit 1; }
read -r URL_Y USER_Y PASS_Y CONNECT_Y <<<"$FIELDS"

USERNAME="$(op read "$ITEM/username")"
PASSWORD="$(op read "$ITEM/password")"
trap 'unset USERNAME PASSWORD; printf "" | pbcopy; rm -f "${SHOT:-}"' EXIT

sim() { python3 "$ROOT/scripts/drive.py" "$SIM" "$@" >/dev/null; }
SHOT="$(mktemp -t signin).png"
# True when the screenshot has the sign-in screen's Connect button where it should be.
on_signin_screen() {
  python3 - "$1" "$CONNECT_Y" <<'PY'
import struct, sys, zlib
data = open(sys.argv[1], "rb").read()
w, h, depth, ctype = struct.unpack(">IIBB", data[16:26])
idat, pos = b"", 8
while pos < len(data):
    n, kind = struct.unpack(">I4s", data[pos:pos + 8])
    if kind == b"IDAT": idat += data[pos + 8:pos + 8 + n]
    pos += 12 + n
raw = zlib.decompress(idat)
bpp = {2: 3, 6: 4}[ctype]
stride = w * bpp
y, x = int(sys.argv[2]), 200
rows, prev = [], bytearray(stride)
for r in range(y + 1):
    f, line = raw[r * (stride + 1)], bytearray(raw[r * (stride + 1) + 1:(r + 1) * (stride + 1)])
    for i in range(stride):
        a = line[i - bpp] if i >= bpp else 0
        b, c = prev[i], prev[i - bpp] if i >= bpp else 0
        if f == 1: line[i] = (line[i] + a) & 255
        elif f == 2: line[i] = (line[i] + b) & 255
        elif f == 3: line[i] = (line[i] + (a + b) // 2) & 255
        elif f == 4:
            p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
            line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
    prev = line
R, G, B = prev[x * bpp:x * bpp + 3]
sys.exit(0 if R > 180 and 90 < G < 150 and 60 < B < 120 else 1)
PY
}
sim_paste() {
  printf '%s' "$1" | pbcopy
  sim 'paste' 'wait 300'
  printf '' | pbcopy
}
emu_type() {
  # Read on the device from stdin, so the value is not an argument to any host process.
  printf '%s\n' "$1" | "$ADB" shell 'read -r v; input text "$v"'
}
emu_clear_and_focus() {
  "$ADB" shell input tap 410 "$1"
  sleep 0.5
  "$ADB" shell input keycombination 113 29
  "$ADB" shell input keyevent KEYCODE_DEL
}

for SIM in "${SIMS[@]+"${SIMS[@]}"}"; do
  sim "shot $SHOT"
  if ! on_signin_screen "$SHOT"; then
    echo "apksim (port $SIM): not on the sign-in screen, skipped"
    continue
  fi
  if [[ -n "$SERVER" ]]; then
    sim "tap 410 $URL_Y" 'wait 400' 'key A 1 ctrl' 'key DEL' "type $SERVER"
  fi
  sim "tap 410 $USER_Y" 'wait 400' 'key A 1 ctrl' 'key DEL'
  sim_paste "$USERNAME"
  sim "tap 410 $PASS_Y" 'wait 400' 'key A 1 ctrl' 'key DEL'
  sim_paste "$PASSWORD"
  [[ -n "$SUBMIT" ]] && sim "tap 410 $CONNECT_Y"
  echo "apksim (port $SIM): $([[ -n "$SUBMIT" ]] && echo submitted || echo filled)"
done

if [[ -n "$EMU" ]]; then
  "$ADB" exec-out screencap -p > "$SHOT"
  if ! on_signin_screen "$SHOT"; then
    echo "emulator: not on the sign-in screen, skipped"
    EMU=""
  fi
fi
if [[ -n "$EMU" ]]; then
  if [[ -n "$SERVER" ]]; then
    emu_clear_and_focus "$URL_Y"
    emu_type "$SERVER"
  fi
  emu_clear_and_focus "$USER_Y"
  emu_type "$USERNAME"
  emu_clear_and_focus "$PASS_Y"
  emu_type "$PASSWORD"
  "$ADB" shell input keyevent 111
  sleep 0.5
  [[ -n "$SUBMIT" ]] && "$ADB" shell input tap 410 "$CONNECT_Y"
  echo "emulator: $([[ -n "$SUBMIT" ]] && echo submitted || echo filled)"
fi
