#!/usr/bin/env python3
"""Checks that a running `apksim serve` shows its tab the web links an app opens, and takes a sign-in's way back (issue 336).

    scripts/serve-link-check.py URL [--token FILE] [--app ID] [--state DIR]

The app must be the test app. A session opens `apksim://signin` and taps "Sign in", which opens an authorize page
outside the app with `redirect_uri=apksim://signin-callback`: the tab must get `d {"link": ..., "redirect": ...}`.
The tab then sends the final address as `open URL`, as the page's paste box does, and the screen must change. With
`--state DIR` (the server's state folder, for a serve on this machine) the check also asks the instance for its nodes:
the waiting activity itself must show the code, not a second copy of it. Exits 1 on any failure.
"""
import argparse
import glob
import importlib.util
import json
import os
import socket
import sys

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("sc", os.path.join(here, "serve-check.py"))
sc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sc)

ap = argparse.ArgumentParser()
ap.add_argument("url")
ap.add_argument("--token")
ap.add_argument("--app")
ap.add_argument("--state")
a = ap.parse_args()
token = open(a.token).readline().strip() if a.token else None
url = a.url.rstrip("/")
app = a.app or json.loads(sc.http(url, "/api/apps", token)[1])[0]["id"]


def instance_nodes():
    """The nodes of the newest instance under the state folder, over its control port."""
    ports = sorted(glob.glob(os.path.join(a.state, "tmp", "*", "control.port")), key=os.path.getmtime)
    if not ports:
        return ""
    with socket.create_connection(("127.0.0.1", int(open(ports[-1]).read().strip())), timeout=30) as s:
        s.sendall(b"nodes\n")
        f = s.makefile("r", encoding="utf-8")
        return f.readline()


s = sc.Session(url, token, app, "phone")
sc.check(s.ws.status == 101, f"session opened ({s.ws.status})")
sc.check(s.pump(180, lambda: len(s.frames) > 0), "first frame")
s.after(["open apksim://signin"], 2.5)
s.after(["touch down 411 915", "touch up 411 915"], 0.2)


def link():
    for t in s.texts:
        if t.startswith("d ") and '"link"' in t:
            return json.loads(t[2:])
    return None


sc.check(s.pump(10, lambda: link() is not None), "the tab is told of the link the app opened")
d = link() or {}
sc.check(d.get("link", "").startswith("https://example.com/authorize?") and "redirect_uri=" in d.get("link", ""),
         f"it is the authorize page ({d.get('link', '')[:80]})")
sc.check(d.get("redirect") == "apksim://signin-callback", f"its way back is named ({d.get('redirect')})")
frames = s.after(["open apksim://signin-callback?code=check42"], 3)
sc.check(len(frames) > 0, f"the pasted final address changes the screen ({len(frames)} frames)")
if a.state:
    nodes = instance_nodes()
    sc.check("signin: code=check42 (instances 1, new intents 1)" in nodes,
             "the waiting activity got the code in onNewIntent, no second copy (" + nodes.strip()[:140] + ")")
# Asked again, the app's next sign-in is told again.
s.texts.clear()
s.after(["touch down 411 915", "touch up 411 915"], 0.2)
sc.check(s.pump(10, lambda: link() is not None), "a second sign-in is told too")
s.ws.s.close()
sc.finish()
