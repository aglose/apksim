#!/bin/bash
# Checks the virtual AudioTrack output of hosts with no sound device (dev.apksim.video.VirtualOut, issue 545): the head
# moves at the track's rate only while playing, a blocking write waits for room, an underrun is counted once, pause
# stops the head, flush resets it and a blocked writer returns when paused. Exits 1 if any check fails. Needs
# work/apksim.jar (scripts/build.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
exec "$JDK/bin/java" -cp "$ROOT/work/apksim.jar" dev.apksim.video.VirtualOutCheck
