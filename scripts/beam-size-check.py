#!/usr/bin/env python3
"""Sizing a session on this Mac (docs/beam.md, phase 3), in Chrome over the DevTools protocol:

    scripts/beam-size-check.py [APK]   (default work/testapp.apk, whose top bar shows its window size in dp)

With the page in Fill mode: after every resize of the browser window the canvas's backing size is the stage's CSS size
times the pixel ratio, and the frames are that size (one frame pixel per screen pixel), and the app's own window is the
stage's size in dp; a new pixel ratio re-densities the display; dragging the simulator window shows its size in the tab
within a moment, the tab sending no size back ("Fit to tab" offered, which takes it back); a hundred resizes from both
sides at once end with both agreeing and no size sent after them. The simulator window is shown without focus and
closed (which, with the tab attached, only hides it). A serve with --allow-beam and an agent on loopback ports, Chrome with a fresh profile; the page's origin
is allowed for the run and revoked after.
"""
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location("bp", os.path.join(ROOT, "scripts/beam-parity.py"))
bp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bp)
OK = True


def check(c, what):
    global OK
    OK &= bool(c)
    print(("ok   " if c else "FAIL ") + what, flush=True)


def until(f, seconds):
    end = time.time() + seconds
    while time.time() < end:
        v = f()
        if v:
            return v
        time.sleep(0.1)
    return f()


def main():
    apk = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.join(ROOT, "work/testapp.apk")
    apksim = os.path.join(ROOT, "apksim")
    sp, gp, cp = bp.free_port(), bp.free_port(), bp.free_port()
    url = f"http://127.0.0.1:{sp}"
    subprocess.run([apksim, "beam", "trust", "allow", url], capture_output=True)
    prof = tempfile.mkdtemp(prefix="beam-size-chrome-")
    procs = [subprocess.Popen([apksim, "serve", "--port", str(sp), "--allow-beam", "--beam-port", str(gp), "--apps", apk],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL),
             subprocess.Popen([apksim, "beam", "--port", str(gp)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)]
    try:
        for port, path in ((sp, "/api/health"), (gp, "/beam/hello")):
            until(lambda: _up(port, path), 30)
        app = json.load(urllib.request.urlopen(url + "/api/apps"))[0]["id"]
        procs.append(subprocess.Popen([bp.CHROME, f"--remote-debugging-port={cp}", f"--user-data-dir={prof}", "--no-first-run",
                                       "--no-default-browser-check", "--disable-backgrounding-occluded-windows",
                                       "--disable-renderer-backgrounding", "--window-size=1000,900", "about:blank"],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        until(lambda: _up(cp, "/json/version"), 20)
        cdp = bp.Cdp(cp)
        cdp.call("Browser.grantPermissions", {"permissions": ["loopbackNetwork"], "origin": url})
        t = cdp.call("Target.createTarget", {"url": "about:blank"})
        s = cdp.call("Target.attachToTarget", {"targetId": t["targetId"], "flatten": True})["sessionId"]
        win = cdp.call("Browser.getWindowForTarget", {"targetId": t["targetId"]})["windowId"]
        cdp.call("Page.navigate", {"url": f"{url}/?app={app}"}, s)
        until(lambda: _ev(cdp, s, '!document.getElementById("runOn").hidden'), 20)
        cdp.ev(s, '(async () => { const r = document.getElementById("runOn"); if (r.value !== "mac") { r.value = "mac"; await r.onchange(); } return 1; })()')
        t0 = time.time()
        check(until(lambda: _ev(cdp, s, "window.apksimStats().frames > 0 && !document.getElementById('macRow').hidden"), 40),
              f"a session on this Mac ({time.time() - t0:.2f} s), its display controls shown")
        port, pid = bp.session_port(cdp, s, bp.state_dir())
        ctl = bp.Control(port)
        cdp.ev(s, 'document.getElementById("fitMode").value = "fill"; document.getElementById("fitMode").onchange(); 1')

        def agrees():
            f = cdp.ev(s, "window.apksimFill()")
            return f if f["canvas"] == f["spec"]["px"] and f["sent"] == f["spec"]["spec"] else None

        def app_size():
            m = re.search(r"(?:compact|medium|expanded) (\d+)×(\d+)", ctl("nodes"))
            return (int(m.group(1)), int(m.group(2))) if m else None

        for i, (w, h) in enumerate([(1000, 900), (1300, 820), (760, 1000), (1100, 700)]):
            cdp.call("Browser.setWindowBounds", {"windowId": win, "bounds": {"width": w, "height": h, "windowState": "normal"}})
            f = until(agrees, 10)
            check(f is not None, f"fill {w}x{h} window: the canvas is the stage times the pixel ratio and the frames that size ({f and f['canvas']} for {f and f['spec']['spec']})")
            if f:
                css_ok = f["css"] == f"{f['spec']['w']}px {f['spec']['h']}px"
                check(css_ok, f"  shown at the stage's CSS size, unscaled ({f['css']})")
                got = until(lambda: app_size() == (f["spec"]["w"], f["spec"]["h"]) and app_size(), 5)
                check(got, f"  the app's window is the stage's size in dp ({app_size()} for {f['spec']['w']}x{f['spec']['h']})")

        # Another pixel ratio (a display of another density): the display follows.
        cdp.call("Emulation.setDeviceMetricsOverride", {"width": 0, "height": 0, "deviceScaleFactor": 1, "mobile": False}, s)
        f = until(lambda: (lambda x: x if x and x["spec"]["d"] == 1 else None)(agrees()), 10)
        check(f is not None, f"a pixel ratio of 1: the display re-densities to it ({f and f['spec']['spec']})")
        cdp.call("Emulation.clearDeviceMetricsOverride", {}, s)
        f = until(lambda: (lambda x: x if x and x["spec"]["d"] != 1 else None)(agrees()), 10)
        check(f is not None, f"and back ({f and f['spec']['spec']})")

        # The simulator window, dragged: the tab shows its size and sends none back.
        before = cdp.ev(s, "window.apksimFill()")
        check(ctl("window show") == "ok", "the simulator window shows (Show in Simulator, here without focus)")
        time.sleep(1)
        check(ctl("window 420x760").startswith("ok"), "the simulator window is dragged to 420x760 points")
        changed = until(lambda: (lambda x: x if x["canvas"] != before["canvas"] else None)(cdp.ev(s, "window.apksimFill()")), 5)
        check(changed is not None, f"the tab shows the window's size ({changed and changed['canvas']}, was {before['canvas']})")
        time.sleep(1)
        after = cdp.ev(s, "window.apksimFill()")
        check(after["sends"] == before["sends"], f"the tab sent no size back ({after['sends'] - before['sends']} sent)")
        check(after["fitShown"], "and offers Fit to tab")
        cdp.ev(s, 'document.getElementById("fitTab").click(); 1')
        check(until(agrees, 10) is not None, "Fit to tab takes the size back")

        # Both views at once: no loop.
        for i in range(50):
            cdp.call("Browser.setWindowBounds", {"windowId": win, "bounds": {"width": 900 + (i % 7) * 40, "height": 760 + (i % 5) * 30}})
            ctl(f"window {400 + (i % 6) * 30}x{700 + (i % 4) * 25}")
        cdp.call("Browser.setWindowBounds", {"windowId": win, "bounds": {"width": 1050, "height": 860}})
        time.sleep(1.5)
        n = cdp.ev(s, "window.apksimFill().sends")
        time.sleep(2)
        check(cdp.ev(s, "window.apksimFill().sends") == n, f"a hundred resizes from both sides: no size sent once they stop ({n} in all)")
        if not agrees():
            cdp.ev(s, 'document.getElementById("fitTab").click(); 1')
        check(until(agrees, 10) is not None, "and both views agree on the tab's size")
        # Its close button, while the tab is attached, only hides it: the session goes on in the tab.
        check(ctl("window close") == "ok", "the simulator window's close button")
        time.sleep(1.5)
        state = ctl("window")
        frames0 = cdp.ev(s, "window.apksimStats().frames")
        ctl("appearance toggle")  # the whole screen changes: a new frame if the session still streams
        time.sleep(1)
        check("hidden" in state and ctl("ping") and cdp.ev(s, "!ended && window.apksimStats().frames") > frames0,
              f"hides it while the tab is attached, and the session goes on ({state})")
        ctl("appearance toggle")

        # Back to the device's size.
        cdp.ev(s, 'document.getElementById("fitMode").value = "device"; document.getElementById("fitMode").onchange(); 1')
        check(until(lambda: cdp.ev(s, "canvas.width + 'x' + canvas.height") == "822x1782", 10), "Device size: the phone again (822x1782)")
        ctl.close()
        cdp.ev(s, 'document.getElementById("stop").click(); 1')
        time.sleep(1)
    finally:
        for p in reversed(procs):
            p.terminate()
        for p in procs:
            try:
                p.wait(15)
            except subprocess.TimeoutExpired:
                p.kill()
        subprocess.run([apksim, "beam", "trust", "revoke", url], capture_output=True)
        shutil.rmtree(prof, ignore_errors=True)
    print("beam size check", "passed" if OK else "FAILED")
    sys.exit(0 if OK else 1)


def _up(port, path):
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=2)
        return True
    except OSError:
        return False


def _ev(cdp, s, expr):
    try:
        return cdp.ev(s, expr)
    except RuntimeError:
        return False


if __name__ == "__main__":
    main()
