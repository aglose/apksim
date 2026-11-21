#!/bin/bash
# Spike S2: Compose refresh modes, each on a fresh instance of build A with the FAB tapped twice (count 2).
cd "$(dirname "$0")/../.." || exit 1
P=7461
H=work/hotswap
start() {
  python3 scripts/drive.py $P quit >/dev/null 2>&1; sleep 1.5
  ./apksim --headless --control $P $H/A.apk > $H/run-$1.log 2>&1 &
  for i in $(seq 1 60); do
    python3 scripts/drive.py $P nodes 2>/dev/null | grep -q 'FAB clicks' && return 0
    sleep 0.5
  done
  echo "instance did not start"; return 1
}
d() { python3 scripts/drive.py $P "$@" | cut -c1-160; }
sw() { python3 $H/swap.py $P "$@" | cut -c1-140; }
for mode in compose compose-reset recreate; do
  for round in 1 2 3; do
    echo "### C/$mode round $round"; start c$mode$round
    d 'tap 733 1486' 'tap 733 1486' 'settle 3000'
    sw $H/C.apk $mode "FAB taps"
    d 'nodes'
    echo "# then D, then C again, same instance"
    sw $H/D.apk $mode "Hot row"
    sw $H/C.apk $mode "FAB taps"
  done
done
python3 scripts/drive.py $P quit >/dev/null 2>&1
