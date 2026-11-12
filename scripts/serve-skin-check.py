#!/usr/bin/env python3
"""Skins on `apksim serve` (issue 246, docs/serve.md "Skins"), its API and its page in Chrome over the DevTools protocol:

    scripts/serve-skin-check.py APK
    scripts/serve-skin-check.py --write-fixture DIR             (the fixture skin into DIR/fixture, for a server's --skins)
    scripts/serve-skin-check.py --api URL [--token FILE]          (the endpoints only, on a running serve --skins DIR)

A fixture skin (scripts/skin-window-check.py's: a red phone frame, a rounded hole, a camera hole; the phone device) in
a folder given with `--skins`, the default store pointed at an empty folder. Checked:
  - GET /api/skins lists it with its screen, frame size and cutout; GET /api/skins/fixture/frame.png is the image with an
    ETag, 304 when it matches; an unknown skin or image is a 404; /api/devices lists the skin by its title;
  - a catalog app with `-- --skin fixture` reports it (and its device) in /api/apps/ID; POST /api/apps with
    `--skin nope` is a 400 naming the known skins;
  - the page with `&skin=fixture`: the frame is drawn, the canvas sits at the frame's screen rectangle (scaled), and a
    click at the canvas's centre lands at the app's centre (the instance's `stats` lastDown); the app was told the
    cutout (`insets`); `&skin=off` draws none.
The first form needs Chrome and a Mac (the page's start), like scripts/beam-size-check.py; CI runs the API part against
the Linux image (ci-smoke.sh, its serve part).
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
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(ROOT, "scripts", file))
    m = importlib.util.module_from_spec(spec)
    if name == "sw":  # skin-window-check runs main() at import: take its fixture helpers only
        src = open(spec.origin).read().split("\ndef main():")[0]
        exec(compile(src, spec.origin, "exec"), m.__dict__)
        return m
    spec.loader.exec_module(m)
    return m


bp = load("bp", "beam-parity.py")
sw = load("sw", "skin-window-check.py")
OK = True


def check(c, what):
    global OK
    OK &= bool(c)
    print(("ok   " if c else "FAIL ") + what, flush=True)


def until(f, seconds):
    end = time.time() + seconds
    while time.time() < end:
        try:
            v = f()
        except Exception:
            v = None
        if v:
            return v
        time.sleep(0.2)
    return f()


TOKEN = {}


def get(url, headers=None):
    req = urllib.request.Request(url, headers={**TOKEN, **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def post(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={**TOKEN, "Content-Type": "application/json",
                                                                                "Origin": url.split("/api")[0]})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def api_checks(url, catalog_skin=True):
    """The endpoints; the id of an app whose catalog line has `-- --skin fixture` (or the first app)."""
    # ---- the API
    st, _, body = get(url + "/api/skins")
    sk = json.loads(body) if st == 200 else []
    k = next((x for x in sk if x["name"] == "fixture"), None)
    check(k is not None, f"/api/skins lists the fixture from --skins ({[x['name'] for x in sk]})")
    if k:
        check(k["screen"] == {"x": sw.HX, "y": sw.HY, "width": sw.HW, "height": sw.HH, "cornerRadius": sw.HR}, f"  its screen {k['screen']}")
        check(k["frameSize"] == {"width": sw.FW, "height": sw.FH}, f"  its frame size {k['frameSize']}")
        check(k["device"] == "phone" and k["kind"] == "phone" and k["deviceResolved"]["widthPx"] == 822, "  its device, resolved")
        check(k["cutout"] == {"type": "hole", "x": sw.CX, "y": sw.CY, "radius": sw.CR}, f"  its cutout {k['cutout']}")
        check("dir" not in k and "/" not in json.dumps(k).replace("\\/", ""), "  no server path in it")
    st, h, png = get(url + "/api/skins/fixture/frame.png")
    etag = h.get("ETag") or h.get("Etag")
    check(st == 200 and png[:4] == b"\x89PNG" and h.get("Content-Type", "").startswith("image/png") and etag, f"frame.png served with an ETag ({st}, {etag})")
    check(get(url + "/api/skins/fixture/frame.png", {"If-None-Match": etag})[0] == 304, "  304 when the ETag matches")
    check(get(url + "/api/skins/fixture/mask.png")[0] == 404, "  a skin without a mask: mask.png is a 404")
    check(get(url + "/api/skins/nope/frame.png")[0] == 404 and get(url + "/api/skins/fixture/skin.json")[0] == 404,
          "  an unknown skin or file is a 404")
    st, _, body = get(url + "/api/devices")
    dev = json.loads(body)
    check(any(d.get("skin") == "fixture" and d.get("title") == "Fixture phone" and d["name"] == "phone" for d in dev),
          f"/api/devices lists the skin by its title ({dev})")
    apps = json.loads(get(url + "/api/apps")[2])
    app = apps[0]["id"]
    info = json.loads(get(url + "/api/apps/" + app)[2])
    if catalog_skin:
        check(info.get("skin") == "fixture" and info.get("device") == "phone", f"a catalog app's --skin and its device in /api/apps/ID ({info})")
    st, msg = post(url + "/api/apps", {"url": "http://127.0.0.1:9/x.apk", "options": ["--skin", "nope"]})
    check(st == 400 and "fixture" in msg and "nope" in msg, f"POST /api/apps with --skin nope: 400 naming the known skins ({st} {msg.strip()})")
    st, msg = post(url + "/api/apps", {"url": "http://127.0.0.1:9/x.apk", "options": ["--skin", "../etc"]})
    check(st == 400, f"  a path is no skin name ({st})")
    return app


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    if sys.argv[1] == "--write-fixture":
        sw.write_skin(__import__("pathlib").Path(sys.argv[2]))
        return
    if sys.argv[1] == "--api":
        if "--token" in sys.argv:
            TOKEN["Authorization"] = "Bearer " + open(sys.argv[sys.argv.index("--token") + 1]).read().strip()
        api_checks(sys.argv[2].rstrip("/"), catalog_skin=False)
        print("\nall passed" if OK else "\nFAILED")
        sys.exit(0 if OK else 1)
    apk = os.path.abspath(sys.argv[1])
    tmp = tempfile.mkdtemp(prefix="serve-skin-")
    skins = os.path.join(tmp, "skins")
    sw.write_skin(__import__("pathlib").Path(skins))
    empty = os.path.join(tmp, "empty-store")
    os.makedirs(empty)
    catalog = os.path.join(tmp, "apps.txt")
    with open(catalog, "w") as f:
        f.write(f"{apk} -- --skin fixture\n")
    sp, cp = bp.free_port(), bp.free_port()
    url = f"http://127.0.0.1:{sp}"
    env = dict(os.environ, APKSIM_SKINS_DIR=empty, APKSIM_SKINS="")
    prof = os.path.join(tmp, "chrome")
    procs = [subprocess.Popen([os.path.join(ROOT, "apksim"), "serve", "--port", str(sp), "--skins", skins, "--apps", catalog],
                              stdout=subprocess.DEVNULL, stderr=open(os.path.join(tmp, "serve.log"), "w"), env=env)]
    try:
        until(lambda: get(url + "/api/health")[0] == 200, 60)

        app = api_checks(url)

        # ---- the page
        procs.append(subprocess.Popen([bp.CHROME, f"--remote-debugging-port={cp}", f"--user-data-dir={prof}", "--no-first-run",
                                       "--no-default-browser-check", "--disable-backgrounding-occluded-windows",
                                       "--disable-renderer-backgrounding", "--window-size=1000,1000", "about:blank"],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        until(lambda: get(f"http://127.0.0.1:{cp}/json/version")[0] == 200, 20)
        cdp = bp.Cdp(cp)
        t = cdp.call("Target.createTarget", {"url": "about:blank"})
        s = cdp.call("Target.attachToTarget", {"targetId": t["targetId"], "flatten": True})["sessionId"]
        cdp.call("Page.navigate", {"url": f"{url}/?app={app}&skin=fixture"}, s)
        drawn = until(lambda: cdp.ev(s, "window.apksimStats().frames > 0 && window.apksimSkin().drawn && window.apksimSkin()"), 90)
        check(bool(drawn), "the page draws the fixture's frame around the session")
        if drawn:
            b, c = drawn["bezel"], drawn["canvas"]
            scale = b["width"] / (sw.FW * 1782 / sw.HH)
            k_ = 1782 / sw.HH
            hx, hy = sw.HX * k_, sw.HY * k_
            ex, ey = hx + (sw.HW * k_ - 822) / 2, hy + (sw.HH * k_ - 1782) / 2
            check(abs(c["x"] - b["x"] - ex * scale) <= 2 and abs(c["y"] - b["y"] - ey * scale) <= 2,
                  f"the canvas sits at the frame's screen ({c['x'] - b['x']:.1f},{c['y'] - b['y']:.1f} for {ex * scale:.1f},{ey * scale:.1f})")
            check(abs(c["width"] - 822 * scale) <= 2 and abs(c["height"] - 1782 * scale) <= 2, "  at the frame's scale")
            check(abs(b["height"] / b["width"] - sw.FH / sw.FW) < 0.01, "  the frame keeps its shape")
            port, pid = bp.session_port(cdp, s, bp.state_dir())
            ctl = bp.Control(port)
            ins = ctl("insets")
            check("cutout=[" in ins and "corners=" in ins, f"the app was told the skin's cutout and corners ({ins[:160]})")
            cx, cy = c["x"] + c["width"] / 2, c["y"] + c["height"] / 2
            for typ in ("mousePressed", "mouseReleased"):
                cdp.call("Input.dispatchMouseEvent", {"type": typ, "x": cx, "y": cy, "button": "left", "clickCount": 1}, s)
                time.sleep(0.1)
            down = until(lambda: re.search(r"lastDown=(\d+),(\d+)", ctl("stats")), 5)
            check(down and abs(int(down[1]) - 411) <= 3 and abs(int(down[2]) - 891) <= 3,
                  f"a click at the canvas's centre lands at the app's centre ({down and down.group(0)})")
            ctl.close()
        cdp.call("Page.navigate", {"url": f"{url}/?app={app}&skin=off"}, s)
        off = until(lambda: cdp.ev(s, "window.apksimStats().frames > 0 && window.apksimSkin()"), 90)
        check(off and not off["drawn"] and off["active"] == "", f"&skin=off: no frame, though the app's own is fixture ({off})")
    finally:
        for p in reversed(procs):
            p.terminate()
            try:
                p.wait(15)
            except subprocess.TimeoutExpired:
                p.kill()
        shutil.rmtree(tmp, ignore_errors=True)
    print("\nall passed" if OK else "\nFAILED")
    sys.exit(0 if OK else 1)


main()
