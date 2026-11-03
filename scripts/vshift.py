#!/usr/bin/env python3
"""Finds the vertical shift that best aligns SIM.png onto EMU.png (ignoring the top 72 px). Usage: vshift.py EMU SIM [RANGE]"""
import sys
import numpy as np
from PIL import Image
a = np.asarray(Image.open(sys.argv[1]).convert("L"), dtype=np.float32)
b = np.asarray(Image.open(sys.argv[2]).convert("L"), dtype=np.float32)
best = None
rng = int(sys.argv[3]) if len(sys.argv) > 3 else 120
for s in range(-rng, rng + 1):
    if s >= 0:
        x, y = a[72 + s:], b[72:a.shape[0] - s]
    else:
        x, y = a[72:a.shape[0] + s], b[72 - s:]
    e = np.abs(x - y).mean()
    if best is None or e < best[1]:
        best = (s, e)
print(f"sim content is {best[0]} px higher than emulator; residual {best[1]:.2f}")
