#!/usr/bin/env python3
"""Spike S1: interleaved runs of surfacehost's three paths (and none) against a running instance on the Shader tab.

measure.py PORT [ROUNDS] [SECONDS]: for each round, each mode in turn; prints surfacehost's line and the instance's CPU
(its `stats` cpuMs) over the run as a share of one core.
"""
import os, re, subprocess, sys, time

port, rounds, secs = sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 3, float(sys.argv[3]) if len(sys.argv) > 3 else 10
root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
host = os.path.join(root, "work", "surfacehost")


def cpu_ms():
    out = subprocess.run([sys.executable, os.path.join(root, "scripts", "drive.py"), port, "stats"],
                         capture_output=True, text=True).stdout
    return int(re.search(r"cpuMs=(\d+)", out).group(1))


for r in range(rounds):
    for mode in ("none", "global", "mach", "h264"):
        c0, t0 = cpu_ms(), time.time()
        if mode == "none":
            time.sleep(secs)
            line = "none: no host attached"
        else:
            line = subprocess.run([host, port, mode, str(secs)], capture_output=True, text=True).stdout.strip()
        share = (cpu_ms() - c0) / 1000 / (time.time() - t0)
        print(f"round {r + 1} {line}; instance CPU {share * 100:.0f}%", flush=True)
