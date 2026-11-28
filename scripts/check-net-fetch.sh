#!/bin/bash
# Checks `--network replay IN --network-unmatched fetch:OUT` (dev.apksim.net.FetchCheck, docs/network.md "Fetching what a
# recording lacks"): what IN has is answered from IN; what it lacks is fetched from a server on this machine (plain and TLS),
# answered and kept in OUT, redacted as a recording is; a repeat reaches the server once; a server that is down gives the 599
# and an unmatched entry; IN is left as it was. Then that the policy without --network replay stops the launch, and a
# server that signs in with a session cookie over three launches: the cookie is kept in OUT.secrets (owner only, never in
# OUT), a replayed sign-in gives it to the app so later fetches are signed in, and a 401/403 fetched after a sign-in
# replayed without it (or a 401 given to the replayed cookie) is not kept, but counted.
# Needs work/apksim.jar (scripts/build.sh). Exits 1 on any failure.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
"$JDK/bin/java" -cp "$ROOT/work/apksim.jar" dev.apksim.net.FetchCheck
"$JDK/bin/java" -cp "$ROOT/work/apksim.jar" dev.apksim.net.FetchCheck no-replay
DIR="$(mktemp -d)"
trap 'rm -rf "$DIR"' EXIT
for launch in 1 2 3; do
  "$JDK/bin/java" -cp "$ROOT/work/apksim.jar" dev.apksim.net.FetchCheck session "$launch" "$DIR"
done
