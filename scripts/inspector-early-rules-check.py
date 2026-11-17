#!/usr/bin/env python3
"""Inspector rules sent the moment the control port opens are applied before the app exists (issue 445).

usage: inspector-early-rules-check.py APK [APKSIM]

Starts a headless instance with --network-inspect, sends four `network rule add` commands as soon as the control port
is announced, and checks that every answer arrived within half a second (the launch reaches `framework-ready` at ~1 s; before the fix
the answers waited for Host's loop, after the first activity), and that `network rule list` then holds all four, in order.
Run by hand (about 5 s); the instance is quit at the end.
"""
import re, socket, subprocess, sys, time

apk = sys.argv[1]
apksim = sys.argv[2] if len(sys.argv) > 2 else "./apksim"
p = subprocess.Popen([apksim, "--headless", "--network-inspect", "--control", "auto", "--jvm", "-Dapksim.timing=verbose", apk],
                     stderr=subprocess.PIPE, text=True)
port = None
for line in p.stderr:
    m = re.search(r"control port (\d+)", line)
    if m:
        port = int(m.group(1))
        break
if port is None:
    sys.exit("FAIL: no control port announced")
t0 = time.time()
s = socket.create_connection(("127.0.0.1", port))
f = s.makefile("rw")
for i in range(4):
    f.write("network rule add GET /early%d 200 0 text/plain eA==\n" % i)
    f.flush()
    f.readline()
took = time.time() - t0
ready = False
for line in p.stderr:
    if "framework-ready" in line:
        ready = True
        break
f.write("network rule list\n")
f.flush()
listed = f.readline()
f.write("quit\n")
f.flush()
p.wait(30)
patterns = re.findall(r'"pattern":"([^"]+)"', listed)
ok = patterns == ["/early%d" % i for i in range(4)]
print("rules held:", patterns)
if not ok:
    sys.exit("FAIL: rules missing or out of order")
if took > 0.5:
    sys.exit("FAIL: the four rules took %.2f s to answer: they waited for the app's loop" % took)
if not ready:
    sys.exit("FAIL: the launch never reached framework-ready")
print("OK: four rules answered in %.0f ms, before the framework was ready, listed in order" % (took * 1000))
