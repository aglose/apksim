#!/usr/bin/env python3
"""A `drag` scrolls by the same distance every time (what screenshot specs and scripts/crawl.py rely on).

Usage: drag-check.py PORT [--runs N] [--url URL]

On a running test app (scripts/ci-smoke.sh starts one): N times, `restart` to apksim://floating-tall (a framework
ListView of 200 rows: it takes the touch over at the slop and flings by Android's VelocityTracker; Compose screens have
their own of both, and did not show it), the same `drag`, settle, and the tops of the first labelled nodes: all must be
equal. On 0.58.1 a row landed 227-230 px down over six drags; on a podcast app's settings list, up to 54 px apart.
Before, a drag's moves were read off the clock and Robolectric's velocity tracker (20 samples in 200 ms, not a
device's 100 ms) still saw movement through the 120 ms hold, so the same drag landed up to ~50 px apart.
"""
import argparse, socket, sys

ap = argparse.ArgumentParser()
ap.add_argument("port", type=int)
ap.add_argument("--runs", type=int, default=6)
ap.add_argument("--url", default="apksim://floating-tall")
args = ap.parse_args()

sock = socket.create_connection(("127.0.0.1", args.port), timeout=60)
f = sock.makefile("rw")


def say(cmd):
    f.write(cmd + "\n")
    f.flush()
    return f.readline().strip()


def tops():
    """The screen tops of the first few labelled nodes, as "label@top" (what moved, and by how much)."""
    out = []
    for rec in say("nodes tree").split("\x1e"):
        cols = rec.split("\t")
        if len(cols) > 5 and cols[5].strip():
            out.append(f"{cols[5].strip()}@{cols[4].split(',')[1]}")
    return out[:8]


def ok(cmd):
    reply = say(cmd)
    if reply.startswith("error"):
        print(f"drag-check: {cmd!r}: {reply}", file=sys.stderr)
        sys.exit(1)
    return reply


seen = []
try:
    for i in range(args.runs):
        ok(f"restart {args.url}")
        ok("settle 15000 100")
        ok("drag 411 1300 411 1000 300")
        ok("settle 15000 100")
        seen.append(tops())
        print(f"run {i + 1}: {' '.join(seen[-1][:3])}")
finally:
    say("restart")  # the launcher screen again, for the checks that follow on this instance
if any(s != seen[0] for s in seen) or not seen[0]:
    print("drag-check: the same drag ended in different places", file=sys.stderr)
    sys.exit(1)
print(f"drag-check: {args.runs} drags ended in the same place")
