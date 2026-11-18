#!/usr/bin/env python3
"""The page's `viewport` (issue 499) on a running `apksim serve`, in Chrome over the DevTools protocol.

    scripts/viewport-page-check.py URL [--app ID] [--codec jpeg|h264] [--headed]

Opens the page on a phone app in a window that shows the display at about half size: the page must send `viewport`,
frames must arrive smaller than the display while the canvas keeps the display's size, and a click must land where it
was made in device pixels (the instance's `stats` lastDown, read with ctl.py). Then the window grows until the display
is shown 1:1, and frames must be full size again. Needs Chrome and ctl.py's view of the instance (same state folder).
"""
import argparse
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location("bp", os.path.join(ROOT, "scripts", "beam-parity.py"))
bp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bp)
OK = True


def check(c, what):
    global OK
    print(("ok   " if c else "FAIL ") + what, flush=True)
    OK = OK and bool(c)


def until(f, seconds):
    end = time.time() + seconds
    while time.time() < end:
        try:
            v = f()
            if v:
                return v
        except Exception:
            pass
        time.sleep(0.2)
    return None


ap = argparse.ArgumentParser()
ap.add_argument("url")
ap.add_argument("--app")
ap.add_argument("--codec", default="h264")
ap.add_argument("--headed", action="store_true")
a = ap.parse_args()
url = a.url.rstrip("/")
app = a.app or json.loads(urllib.request.urlopen(url + "/api/apps").read())[0]["id"]
tmp = tempfile.mkdtemp(prefix="viewport-page-check-")
cp = bp.free_port()
chrome = subprocess.Popen([bp.CHROME, f"--remote-debugging-port={cp}", f"--user-data-dir={tmp}", "--no-first-run", "--no-default-browser-check",
                           "--window-size=1200,1000", *([] if a.headed else ["--headless=new"]), "about:blank"],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    until(lambda: urllib.request.urlopen(f"http://127.0.0.1:{cp}/json/version").status == 200, 20)
    cdp = bp.Cdp(cp)
    t = cdp.call("Target.createTarget", {"url": "about:blank"})
    s = cdp.call("Target.attachToTarget", {"targetId": t["targetId"], "flatten": True})["sessionId"]
    cdp.call("Emulation.setDeviceMetricsOverride", {"width": 1200, "height": 1000, "deviceScaleFactor": 1, "mobile": False}, s)
    codec = "&codec=" + a.codec if a.codec else ""
    cdp.call("Page.navigate", {"url": f"{url}/?app={app}{codec}"}, s)
    stats = lambda: json.loads(cdp.ev(s, "JSON.stringify(window.apksimStats())"))
    check(until(lambda: stats()["frames"] > 0, 90), "the page streams the session")
    st = until(lambda: (lambda x: x if x["viewport"] and x["streamWidth"] and x["streamWidth"] < x["width"] else None)(stats()), 15) or stats()
    print("   ", {k: st[k] for k in ("width", "height", "streamWidth", "streamHeight", "viewport", "codec")})
    W, H = st["width"], st["height"]
    check(st["viewport"].startswith("viewport "), "the page sent its viewport")
    check(0 < st["streamWidth"] <= W // 2 and 0 < st["streamHeight"] <= H // 2, "frames arrive at half the display's size")
    check(abs(st["streamWidth"] / st["streamHeight"] - W / H) < 0.02, "with the display's aspect ratio")
    canvas = json.loads(cdp.ev(s, "JSON.stringify([canvas.width, canvas.height])"))
    check(canvas == [W, H], f"the canvas keeps the display's size ({canvas})")
    # A click at 30%/40% of the canvas lands at 30%/40% of the display.
    r = json.loads(cdp.ev(s, "JSON.stringify((b => [b.left, b.top, b.width, b.height])(canvas.getBoundingClientRect()))"))
    x, y = r[0] + r[2] * 0.3, r[1] + r[3] * 0.4
    for kind in ("mousePressed", "mouseReleased"):
        cdp.call("Input.dispatchMouseEvent", {"type": kind, "x": x, "y": y, "button": "left", "clickCount": 1}, s)
    time.sleep(1)
    pid = json.loads(cdp.ev(s, "JSON.stringify(window.apksimSession || {})")).get("pid")
    out = subprocess.run([sys.executable, os.path.join(ROOT, "scripts", "ctl.py"), f"@{pid}", "stats"], capture_output=True, text=True).stdout
    down = None
    for part in out.replace(",", " ").replace("=", " ").split("lastDown")[1:2]:
        nums = [float(v) for v in part.replace(":", " ").replace("x", " ").split()[:2]]
        down = nums
    check(down and abs(down[0] - W * 0.3) < 8 and abs(down[1] - H * 0.4) < 8, f"the click lands in device pixels ({down} for {W * 0.3:.0f},{H * 0.4:.0f}; {out.strip()[:200]})")
    # Room for the whole display at 1:1: full size again.
    cdp.call("Emulation.setDeviceMetricsOverride", {"width": 1400, "height": 2100, "deviceScaleFactor": 1, "mobile": False}, s)
    st = until(lambda: (lambda x: x if x["streamWidth"] == x["width"] and x["streamHeight"] == x["height"] else None)(stats()), 15) or stats()
    print("   ", {k: st[k] for k in ("width", "height", "streamWidth", "streamHeight", "viewport")})
    check(st["streamWidth"] == W and st["streamHeight"] == H, "shown 1:1, frames are full size again")
    cdp.call("Emulation.setDeviceMetricsOverride", {"width": 1200, "height": 1000, "deviceScaleFactor": 1, "mobile": False}, s)
    st = until(lambda: (lambda x: x if x["streamWidth"] < x["width"] else None)(stats()), 15) or stats()
    check(st["streamWidth"] < W, "smaller again after the window shrinks")
finally:
    chrome.terminate()
    try:
        chrome.wait(10)
    except subprocess.TimeoutExpired:
        chrome.kill()
    shutil.rmtree(tmp, ignore_errors=True)
sys.exit(0 if OK else 1)
