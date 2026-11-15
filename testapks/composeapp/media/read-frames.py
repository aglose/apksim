#!/usr/bin/env python3
"""Decodes a sample with ffmpeg and reads each frame's index the way MediaActivity does (scripts may use it to check a
regenerated sample): a 140x40 RGB picture, stripe centres in the top half, mid grey below. Prints the indices."""
import subprocess, sys

W, H = 140, 40

def index(px):
    def lum(x, y):
        r, g, b = px[(y * W + x) * 3: (y * W + x) * 3 + 3]
        return (r * 299 + g * 587 + b * 114) // 1000
    y, low = 10, 30
    black, white = lum(10, y), lum(130, y)
    if white - black < 100 or abs(lum(70, low) - (black + white) // 2) > 40:
        return -1
    n = 0
    for s in range(1, 6):
        n = n * 2 + (1 if lum(s * 20 + 10, y) > (black + white) // 2 else 0)
    return n

raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", sys.argv[1], "-vf", f"scale={W}:{H}", "-f", "rawvideo",
                      "-pix_fmt", "rgb24", "-"], capture_output=True, check=True).stdout
frames = [raw[i:i + W * H * 3] for i in range(0, len(raw), W * H * 3)]
print([index(f) for f in frames])
