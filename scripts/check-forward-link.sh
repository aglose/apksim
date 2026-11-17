#!/bin/bash
# Checks the forward link (dev.apksim.net.ForwardLink, issue 426) with a stand-in for the beam agent and a local HTTP
# server: a request out and its answer back, the agent's refusal, no agent, an oversized body, the link ending while a
# request is pending. Exits 1 if any check fails. Needs work/apksim.jar (scripts/build.sh).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/jdk.sh"
exec "$JDK/bin/java" -cp "$ROOT/work/apksim.jar" dev.apksim.net.ForwardLinkCheck
