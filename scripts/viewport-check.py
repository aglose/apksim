#!/usr/bin/env python3
"""`viewport W H` on a running instance's frame stream (issue 499), over --control: frames shrink by a whole factor
once the tab shows them at about half size or less (never up, the device's size in the header's upper 16 bits), and
`viewport 0 0` restores full size. Usage: viewport-check.py PORT"""
import socket, struct, sys, threading, time

port = int(sys.argv[1])
s = socket.create_connection(("127.0.0.1", port))
f = s.makefile("rb")
s.sendall(b"stream 80\n")
f.readline()
sizes = []
devices = []

def reader():
    while True:
        h = f.read(4)
        if len(h) < 4:
            return
        b = f.read(struct.unpack(">i", h)[0])
        w, h = struct.unpack(">ii", b[:8])
        # A scaled frame: its own size below, the device's in the upper 16 bits (0 when not scaled).
        sizes.append((w & 0xFFFF, h & 0xFFFF))
        devices.append(((w >> 16) or (w & 0xFFFF), (h >> 16) or (h & 0xFFFF)))

threading.Thread(target=reader, daemon=True).start()
fails = 0
full_size = [None]

def step(cmd, expect):
    global fails
    if cmd:
        s.sendall(cmd.encode() + b"\n")
    sizes.clear()
    devices.clear()
    time.sleep(1.5)
    got = sizes[-1] if sizes else None
    if devices and full_size[0] and devices[-1] != full_size[0]:
        print("FAIL device size in the header", devices[-1])
        fails += 1
    ok = expect(got) if callable(expect) else got == expect
    print(("ok  " if ok else "FAIL"), cmd or "(no command)", got)
    fails += not ok

step(None, lambda g: g is not None)
full = sizes[-1]
full_size[0] = full
W, H = full
half = (W // 2 & ~1, H // 2 & ~1)
step(f"viewport {W // 2} {H // 2}", half)
step(f"viewport {W * 52 // 100} {H * 52 // 100}", half)  # a hair over half (a page's margins): still half
step(f"viewport {W * 6 // 10} {H * 6 // 10}", full)  # between half and full: full
step(f"viewport {W // 2} {H // 4}", (W // 4 & ~1, H // 4 & ~1))  # the smaller of the two, aspect kept
step("viewport 0 0", full)
step(f"viewport {W * 2} {H * 2}", full)  # never up
s.sendall(b"viewport 10 10\n")
sys.exit(1 if fails else 0)
