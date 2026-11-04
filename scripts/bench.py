#!/usr/bin/env python3
"""Times launches: Enter to window, and Enter to first app frame. Usage: bench.py RUNS APK [APK...] [--cold|--warm]

Cold launches start a JVM (APKSIM_STANDBY=0). Warm ones are handed to a standby (Standby.java), the normal case on a
Mac once anything has launched from this folder: each waits until the standby is ready, as it is when launches are
seconds apart. Both are measured unless --cold or --warm is given."""
import os, re, statistics, subprocess, sys, time
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
args = [a for a in sys.argv[1:] if not a.startswith("--")]
modes = [m for m in ("cold", "warm") if "--" + m in sys.argv] or ["cold", "warm"]
runs = int(args[0])
launcher = os.path.join(root, "apksim")
client = os.path.join(root, "work", "runtime", "apksim-client")


def standby_ready(timeout=20):
    sock = subprocess.run([client, "sock", root, "n"], capture_output=True, text=True).stdout.strip()
    if not os.path.exists(sock):
        subprocess.run([launcher, "--spawn-standby", "n"])
    end = time.time() + timeout
    while time.time() < end and not os.path.exists(sock):
        time.sleep(0.05)
    time.sleep(0.3)
    return os.path.exists(sock)


for apk in args[1:]:
    for mode in modes:
        if mode == "warm" and not os.path.exists(client):
            print(f"{apk.split('/')[-1]:32} warm: no standby client (scripts/build-native.sh)")
            continue
        window, frame = [], []
        for _ in range(runs):
            env = dict(os.environ)
            if mode == "cold":
                env["APKSIM_STANDBY"] = "0"
            elif not standby_ready():
                print("standby did not come up", file=sys.stderr)
                break
            env["APKSIM_T0"] = str(int(time.time() * 1000))
            p = subprocess.run([launcher, "--no-network", "--exit-after-first-frame", apk], capture_output=True, text=True, env=env)
            w = re.search(r"window-open\s+(\d+) ms", p.stderr)
            f = re.search(r"first-frame\s+(\d+) ms", p.stderr)
            if w and f:
                window.append(int(w[1])); frame.append(int(f[1]))
            time.sleep(0.5)
        if not frame:
            print(apk.split("/")[-1], mode, "FAILED"); continue
        print(f"{apk.split('/')[-1]:32} {mode}  window median {statistics.median(window):5.0f} ms   first frame median "
              f"{statistics.median(frame):5.0f} ms   min {min(frame)}  max {max(frame)}  ({len(frame)}/{runs} ok)")
