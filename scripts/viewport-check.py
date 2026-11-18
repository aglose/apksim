#!/usr/bin/env python3
"""`viewport W H` on a running instance's frame stream (issue 499), over --control: frames shrink to the size the
tab shows (aspect kept, never up, only when under 75% in both dimensions), `viewport 0 0` restores full size, taps
still land in device pixels. Usage: viewport-check.py PORT"""
import socket, struct, sys, threading, time

port = int(sys.argv[1])
s = socket.create_connection(("127.0.0.1", port))
f = s.makefile("rb")
s.sendall(b"stream 80\n")
f.readline()
sizes = []

def reader():
    while True:
        h = f.read(4)
        if len(h) < 4:
            return
        b = f.read(struct.unpack(">i", h)[0])
        sizes.append(struct.unpack(">ii", b[:8]))

threading.Thread(target=reader, daemon=True).start()
fails = 0

def step(cmd, expect):
    global fails
    if cmd:
        s.sendall(cmd.encode() + b"\n")
    sizes.clear()
    time.sleep(1.5)
    got = sizes[-1] if sizes else None
    ok = expect(got) if callable(expect) else got == expect
    print(("ok  " if ok else "FAIL"), cmd or "(no command)", got)
    fails += not ok

step(None, lambda g: g is not None)
full = sizes[-1]
W, H = full
step(f"viewport {W // 2} {H // 2}", lambda g: g is not None and abs(g[0] - W // 2) <= 2 and abs(g[1] - H // 2) <= 2)
step(f"viewport {W * 9 // 10} {H * 9 // 10}", full)  # not clearly smaller: unchanged
step(f"viewport {W // 2} {H // 4}", lambda g: g is not None and g[1] <= H // 4 + 2 and abs(g[0] / g[1] - W / H) < 0.02)
step("viewport 0 0", full)
step(f"viewport {W * 2} {H * 2}", full)  # never up
s.sendall(b"viewport 10 10\n")
sys.exit(1 if fails else 0)
