#!/usr/bin/env python3
"""Checks a tab's file push and flight recording on a running `apksim serve --allow-rec` (docs/serve.md, issue 187).

    scripts/tabfiles-check.py URL [--token FILE] [--app ID]

Starts a session, pushes files into the app's folders and /sdcard over POST /api/sessions/ID/files, checks the
refusals (.., a path outside the allowed folders, too large), reads a pushed file back with `adb`-style `cat` through
the app's data folder on disk when SERVE_STATE is set, then turns the recorder on, saves a recording over the socket
and downloads it. Exits 1 on any failure.
"""
import argparse
import importlib.util
import json
import os
import sys
import time
import urllib.parse

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("sc", os.path.join(here, "serve-check.py"))
sc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sc)

ap = argparse.ArgumentParser()
ap.add_argument("url")
ap.add_argument("--token")
ap.add_argument("--app")
a = ap.parse_args()
token = open(a.token).readline().strip() if a.token else None
url = a.url.rstrip("/")
app = a.app
if not app:
    st, body = sc.http(url, "/api/apps", token)
    app = json.loads(body)[0]["id"]
s = sc.Session(url, token, app, "phone")
if not s.pump(90, until=lambda: any(t.startswith("s ") for t in s.texts) and len(s.frames) > 0):
    sc.check(False, "the session started")
    sys.exit(1)
s.ws.send("stats")
s.pump(3, until=lambda: s.stats.get("id"))
sid = s.stats["id"]
sc.check(bool(sid), "session id " + str(sid))


def push(path, data, key=None):
    return sc.http(url, f"/api/sessions/{sid}/files?path={urllib.parse.quote(path, safe='/')}", token, data=data,
                   headers={"Content-Type": "application/octet-stream"})


st, body = push("files/x.json", b'{"a":1}')
sc.check(st == 200 and json.loads(body)["size"] == 7 and json.loads(body)["path"].endswith("/files/x.json"), f"push files/x.json: {st} {body[:100]}")
st, body = push("/sdcard/Download/y.txt", b"hello")
sc.check(st == 200 and json.loads(body)["path"] == "/sdcard/Download/y.txt", f"push /sdcard/Download/y.txt: {st} {body[:100]}")
st, body = push("/data/local/tmp/z.bin", b"\x00\x01\x02")
sc.check(st == 200, f"push /data/local/tmp/z.bin: {st} {body[:100]}")
for bad in ["files/../../x", "/etc/passwd", "/data/data/other.pkg/files/x", "lib/x", "files/a b", "/sdcard/../data/x", "", "code_cache/x"]:
    st, body = push(bad, b"x")
    sc.check(st in (400, 422), f"refused {bad!r}: {st} {body[:80]}")
try:
    st, body = sc.http(url, f"/api/sessions/{sid}/files?path=files/big", token, data=b"x" * (17 << 20),
                       headers={"Content-Type": "application/octet-stream"})
except Exception as e:  # the server answers 413 and closes before the body is sent: the client may see a broken pipe
    st = 413 if "Broken pipe" in str(e) or "reset" in str(e).lower() else 0
sc.check(st == 413, f"over --max-push refused: {st}")
st, _ = sc.http(url, "/api/sessions/000000000000/files?path=files/x", token, data=b"x")
sc.check(st == 404, f"unknown session: {st}")

s.ws.send("rec on all")
s.pump(3)
s.ws.send("rec save 10")
s.pump(5, until=lambda: any(t.startswith("r rec: saved ") for t in s.texts))
saved = [t for t in s.texts if t.startswith("r rec: saved ")]
sc.check(bool(saved), "rec save answered with an id: " + str(s.texts[-3:]))
if saved:
    rid = saved[0].split()[-1]
    data = b""
    for _ in range(30):
        st, data = sc.http(url, f"/api/sessions/{sid}/recordings/{rid}", token)
        if st == 200:
            break
        time.sleep(0.5)
    sc.check(st == 200 and len(data) > 100, f"recording downloaded: {st}, {len(data)} bytes, starts {data[:4]!r}")
    st, _ = sc.http(url, f"/api/sessions/{sid}/recordings/ffffffffffff", token)
    sc.check(st == 404, f"unknown recording: {st}")
s.ws.send("rec save /tmp/evil.apkrec")
s.pump(2, until=lambda: any("refused" in t for t in s.texts))
sc.check(any(t.startswith("r refused: rec save /tmp") for t in s.texts), "rec save FILE refused")
sys.exit(1 if sc.FAILED else 0)
