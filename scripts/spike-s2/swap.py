#!/usr/bin/env python3
"""Spike S2: send `hotswap APK MODE`, then poll `nodes` (or `nodes all`) until TEXT shows; prints both times.
usage: swap.py PORT APK MODE TEXT [PRE_CMD...]"""
import socket, sys, time

port, apk, mode, text = int(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
s = socket.create_connection(("127.0.0.1", port))
f = s.makefile("rw")

def cmd(c):
    f.write(c + "\n"); f.flush()
    return f.readline().strip()

for pre in sys.argv[5:]:
    print(pre, "->", cmd(pre)[:300])
t0 = time.time()
r = cmd(f"hotswap {apk} {mode}")
t1 = time.time()
print("hotswap ->", r)
print(f"command answered after {1000*(t1-t0):.0f} ms")
for i in range(400):
    n = cmd("nodes")
    if text in n:
        t2 = time.time()
        print(f"'{text}' on screen {1000*(t2-t0):.0f} ms after sending (poll {i})")
        break
    time.sleep(0.005)
else:
    print("NOT SEEN; nodes:", n[:400])
