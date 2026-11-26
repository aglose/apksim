#!/usr/bin/env python3
"""A window Android draws in software reaches the screen, and is drawn again after an invalidate.

A window without FLAG_HARDWARE_ACCELERATED (android:hardwareAccelerated="false", which is every window of an app
targeting API 13 or lower) has no ThreadedRenderer, so View.updateDisplayListIfDirty records nothing for it, and before
HwRenderer recorded such windows itself with View.draw, they drew blank: every app targeting API 13 or lower showed a
white screen while taking input. On a running test app (scripts/ci-smoke.sh starts one) this opens apksim://software
(SoftwareWindowActivity: one background colour, a 200 dp square of another drawn in onDraw), takes a `shot`, taps
"Swap" and takes another: each must be mostly its background colour with the square's (a 400x400 px square at density
2) in it, and the label must say the views are not hardware accelerated, as on a device.

Usage: software-window-check.py PORT OUT_DIR [--instance-dir DIR]
  OUT_DIR is where the shots are read; --instance-dir is where the instance writes them (the Linux image: /work, OUT_DIR
  mounted there). Without it both are OUT_DIR.
"""
import argparse, socket, struct, sys, time, zlib
from pathlib import Path

ap = argparse.ArgumentParser()
ap.add_argument("port", type=int)
ap.add_argument("out")
ap.add_argument("--instance-dir")
args = ap.parse_args()
out = Path(args.out).resolve()
inst = args.instance_dir or str(out)

sock = socket.create_connection(("127.0.0.1", args.port), timeout=60)
f = sock.makefile("rw")


def say(cmd):
    f.write(cmd + "\n")
    f.flush()
    answer = f.readline().strip()
    if answer.startswith("error:") and not cmd.startswith("nodes"):
        sys.exit(f"{cmd}: {answer}")
    return answer


def read_png(path):
    d = Path(path).read_bytes()
    pos, idat = 8, b""
    while pos < len(d):
        n = struct.unpack(">I", d[pos:pos + 4])[0]
        t, body = d[pos + 4:pos + 8], d[pos + 8:pos + 8 + n]
        if t == b"IHDR":
            w, h, depth, ctype = struct.unpack(">IIBB", body[:10])
            assert depth == 8 and ctype in (2, 6), (depth, ctype)
        if t == b"IDAT":
            idat += body
        pos += 12 + n
    bpp = 4 if ctype == 6 else 3
    raw = zlib.decompress(idat)
    rows, prev, i = [], bytearray(w * bpp), 0
    for _ in range(h):
        flt = raw[i]; line = bytearray(raw[i + 1:i + 1 + w * bpp]); i += 1 + w * bpp
        for k in range(len(line)):
            a = line[k - bpp] if k >= bpp else 0
            b = prev[k]
            c = prev[k - bpp] if k >= bpp else 0
            if flt == 1: line[k] = (line[k] + a) & 255
            elif flt == 2: line[k] = (line[k] + b) & 255
            elif flt == 3: line[k] = (line[k] + (a + b) // 2) & 255
            elif flt == 4:
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[k] = (line[k] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(line); prev = line
    return w, h, rows, bpp


def counts(path, colours):
    w, h, rows, bpp = read_png(path)
    n = {c: 0 for c in colours}
    for row in rows:
        for x in range(0, w * bpp, bpp):
            px = (row[x], row[x + 1], row[x + 2])
            for c in colours:
                if max(abs(px[k] - c[k]) for k in range(3)) <= 2:
                    n[c] += 1
    return w * h, n


def rgb(v):
    return ((v >> 16) & 255, (v >> 8) & 255, v & 255)


# SoftwareWindowActivity's colours: (background, square) before and after one swap.
STATES = [(rgb(0x2e7d32), rgb(0xef6c00)), (rgb(0x1565c0), rgb(0xad1457))]
failures = []
say("open apksim://software")
for _ in range(50):
    if "software: hw=" in say("nodes"):
        break
    time.sleep(0.2)
say("settle 5000")
for step, (bg, sq) in enumerate(STATES):
    nodes = say("nodes")
    want = f"software: hw=false, swaps {step}"
    if want not in nodes:
        failures.append(f"label is not '{want}': {nodes[:300]}")
    name = f"software-{step}.png"
    say(f"shot {inst}/{name}")
    total, n = counts(out / name, [bg, sq])
    print(f"software window, swaps {step}: background {n[bg] * 100 // total}% of {total} px, square {n[sq]} px")
    if n[bg] < total * 0.4:
        failures.append(f"swaps {step}: the background colour covers {n[bg]} of {total} px (the window drew blank?)")
    if not 150_000 <= n[sq] <= 170_000:
        failures.append(f"swaps {step}: the square has {n[sq]} px, not ~160000 (400x400)")
    if step == 0:
        say("tap-text Swap")
        say("settle 5000")
say("back")
if failures:
    print("\n".join(failures), file=sys.stderr)
    sys.exit(1)
print("software window: drawn, and drawn again after an invalidate")
