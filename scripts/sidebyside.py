#!/usr/bin/env python3
"""Puts an emulator and an apksim screenshot side by side with a difference map, and prints how far apart they are.
Usage: sidebyside.py EMU.png SIM.png OUT.png  (needs Pillow and numpy)"""
import sys
import numpy as np
from PIL import Image, ImageDraw

emu, sim, out = sys.argv[1:4]
a = Image.open(emu).convert("RGB")
b = Image.open(sim).convert("RGB")
if a.size != b.size:
    b = b.resize(a.size)
A = np.asarray(a, dtype=np.int16)
B = np.asarray(b, dtype=np.int16)
d = np.abs(A - B).max(axis=2)
# Ignore the status bar, which the emulator draws over the app and apksim does not.
d[:72] = 0
heat = np.zeros_like(A, dtype=np.uint8)
heat[..., 0] = np.clip(d * 3, 0, 255)
heat[..., 1] = (np.asarray(a.convert("L")) // 4)
heat[..., 2] = heat[..., 1]
w, h = a.size
canvas = Image.new("RGB", (w * 3 + 40, h + 40), (40, 40, 40))
canvas.paste(a, (0, 40))
canvas.paste(b, (w + 20, 40))
canvas.paste(Image.fromarray(heat), (2 * w + 40, 40))
dr = ImageDraw.Draw(canvas)
for x, label in ((10, "emulator (API 36)"), (w + 30, "apksim"), (2 * w + 50, "difference")):
    dr.text((x, 12), label, fill=(255, 255, 255))
canvas.thumbnail((1600, 1600))
canvas.save(out)
print(f"mean diff {d.mean():.2f}  pixels >24 off: {(d > 24).mean() * 100:.1f}%")
