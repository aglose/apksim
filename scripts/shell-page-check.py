#!/usr/bin/env python3
"""The page's "adb shell" card on a running `apksim serve` (issue 397), in Chrome over the DevTools protocol.

    scripts/shell-page-check.py URL [--app ID] [--headed]

Opens the page on the test app, waits for the stream, then uses the card as a person would: a line typed into its box
(with the `adb shell` prefix a person pastes), Run, and the output shown; a refused line shown with its exit status;
and `window.apksimShell(line)`, the same for a host page's script. Needs Chrome. Exits 1 on any failure.
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
ap.add_argument("--headed", action="store_true")
a = ap.parse_args()
url = a.url.rstrip("/")
app = a.app or json.loads(urllib.request.urlopen(url + "/api/apps").read())[0]["id"]
tmp = tempfile.mkdtemp(prefix="shell-page-check-")
cp = bp.free_port()
chrome = subprocess.Popen([bp.CHROME, f"--remote-debugging-port={cp}", f"--user-data-dir={tmp}", "--no-first-run", "--no-default-browser-check",
                           "--window-size=1200,1000", *([] if a.headed else ["--headless=new"]), "about:blank"],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    until(lambda: urllib.request.urlopen(f"http://127.0.0.1:{cp}/json/version").status == 200, 20)
    cdp = bp.Cdp(cp)
    t = cdp.call("Target.createTarget", {"url": "about:blank"})
    s = cdp.call("Target.attachToTarget", {"targetId": t["targetId"], "flatten": True})["sessionId"]
    cdp.call("Page.navigate", {"url": f"{url}/?app={app}"}, s)
    check(until(lambda: cdp.ev(s, "window.apksimStats().frames > 0"), 90), "the page streams the session")
    check(until(lambda: cdp.ev(s, "!document.getElementById('shellCard').hidden"), 10), "the adb shell card shows")
    hint = cdp.ev(s, "document.getElementById('shellHint').textContent")
    check("am start" in hint and "settings" in hint, f"its hint says what may run ({hint})")

    def run(line):
        cdp.ev(s, "document.getElementById('shellOut').textContent = ''; document.getElementById('shellLine').value = " + json.dumps(line)
               + "; document.getElementById('shellRun').click(); true")
        return until(lambda: (lambda o: o if o.count("\n") >= 2 or "error" in o or "exit" in o else None)(cdp.ev(s, "document.getElementById('shellOut').textContent")), 20) or ""

    run("settings put secure apksim_page_check 5")
    time.sleep(0.5)
    out = run("adb shell settings get secure apksim_page_check")
    check(out.startswith("$ settings get secure apksim_page_check\n5\n"), f"a typed line runs and its output shows ({out!r})")
    out = run("adb -s localhost:5555 shell am broadcast -a dev.apksim.testapp.SET_FLAG --es name page --ez value true")
    check("result=7" in out, f"a broadcast reaches the app ({out[-60:]!r})")
    out = run("ls /sdcard")
    check("not allowed from a page" in out and "(exit 126)" in out, f"a refused line shows why ({out!r})")
    cdp.ev(s, "window.__sh = null; window.apksimShell('settings get secure apksim_page_check').then(a => { window.__sh = JSON.stringify(a); }); true")
    r = until(lambda: cdp.ev(s, "window.__sh"), 20)
    check(r and json.loads(r).get("out") == "5\n" and json.loads(r).get("status") == 0, f"window.apksimShell answers ({r})")
finally:
    chrome.terminate()
    try:
        chrome.wait(10)
    except subprocess.TimeoutExpired:
        chrome.kill()
    shutil.rmtree(tmp, ignore_errors=True)
sys.exit(0 if OK else 1)
