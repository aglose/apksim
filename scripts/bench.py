#!/usr/bin/env python3
"""Times launches: Enter to window, and Enter to first app frame. Usage: bench.py RUNS APK [APK...]"""
import re, statistics, subprocess, sys, time
root = sys.path[0] + "/.."
runs = int(sys.argv[1])
for apk in sys.argv[2:]:
    window, frame = [], []
    for _ in range(runs):
        t0 = int(time.time() * 1000)
        p = subprocess.run([root + "/apksim", "--no-network", "--exit-after-first-frame", apk], capture_output=True, text=True,
                           env={**__import__("os").environ, "APKSIM_T0": str(t0)})
        w = re.search(r"window-open\s+(\d+) ms", p.stderr)
        f = re.search(r"first-frame\s+(\d+) ms", p.stderr)
        if w and f:
            window.append(int(w[1])); frame.append(int(f[1]))
        time.sleep(0.5)
    if not frame:
        print(apk.split("/")[-1], "FAILED"); continue
    print(f"{apk.split('/')[-1]:32} window median {statistics.median(window):5.0f} ms   first frame median {statistics.median(frame):5.0f} ms   min {min(frame)}  max {max(frame)}  ({len(frame)}/{runs} ok)")
