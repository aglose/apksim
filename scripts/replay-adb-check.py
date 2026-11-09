#!/usr/bin/env python3
"""Checks scripts/replay-adb.py's plan on a synthetic bundle (no device, no apksim): gestures, keys, text, device
commands, what is skipped and the report's honesty about the network. `python3 scripts/replay-adb-check.py`."""
import importlib.util
import io
import json
import os
import struct
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("replay_adb", os.path.join(HERE, "replay-adb.py"))
ra = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ra)

bad = 0


def check(ok, what):
    global bad
    print(("ok   " if ok else "FAIL ") + what)
    bad += 0 if ok else 1


def utf(s):
    b = s.encode()
    return struct.pack(">H", len(b)) + b


ev = [
    {"t": 1000, "ev": "down", "x": 100, "y": 200, "v": 0},
    {"t": 1050, "ev": "up", "x": 100, "y": 200, "v": 0},
    {"t": 1500, "ev": "down", "x": 400, "y": 1200, "v": 0},
    {"t": 1600, "ev": "move", "x": 400, "y": 900, "v": 0},
    {"t": 1800, "ev": "up", "x": 400, "y": 600, "v": 0},
    {"t": 2000, "ev": "char", "x": 0, "y": 0, "v": 104},
    {"t": 2050, "ev": "char", "x": 0, "y": 0, "v": 105},
    {"t": 2100, "ev": "char", "redacted": True},
    {"t": 2500, "ev": "back", "x": 0, "y": 0, "v": 0},
    {"t": 2600, "cmd": ["rotate", "landscape"]},
    {"t": 2700, "cmd": ["appearance", "dark"]},
    {"t": 2800, "cmd": ["location", "1", "2"]},
    {"t": 2900, "ev": "touch2-down", "x": 1, "y": 2, "v": 0},
]
man = {"format": "apksim-flight-recording/1", "package": "dev.example.app",
       "checkpoint": {"device": {"width": 822, "height": 1782, "density": 2.0}}}
state = struct.pack(">i", 1) + utf("dev.example.app.Main") + struct.pack(">i", 5) + b"12345" + struct.pack(">i", -1)
buf = io.BytesIO()
with zipfile.ZipFile(buf, "w") as z:
    z.writestr("manifest.json", json.dumps(man))
    z.writestr("input.jsonl", "\n".join(json.dumps(e) for e in ev))
    z.writestr("checkpoint/state.bin", state)
p = ra.Plan(zipfile.ZipFile(buf))
kinds = [(s[1], s[2]) for s in p.steps]
check(p.stack == [{"class": "dev.example.app.Main", "intent": 5, "state": -1}], "state.bin read")
check(kinds[0] == ("tap", (100, 200)), "short press is a tap")
check(kinds[1][0] == "swipe" and kinds[1][1] == (400, 1200, 400, 600, 300), "drag is one swipe with its duration")
check(kinds[2] == ("text", "hix"), "characters join; a redacted one is x")
check(kinds[3] == ("key", 4), "back is keyevent 4")
check(kinds[4][0] == "shell" and "user_rotation 1" in kinds[4][1], "rotate")
check(kinds[5] == ("shell", "cmd uimode night yes"), "appearance")
check(any("location" in s for s in p.skipped) and any("two-finger" in s for s in p.skipped), "unsupported input is listed")
check(any("network: live" in n for n in p.notes), "report says the network was live")
check(ra.parse_properties("global/a=b\\=c\n\\!system/x=\n") == {"global/a": "b=c", "!system/x": ""}, "settings.properties")
sys.exit(1 if bad else 0)
