#!/usr/bin/env python3
"""Checks kept sessions on a running `apksim serve --keep-max N --max 2` (or more; docs/serve.md "Kept sessions", issue 221).

    scripts/kept-check.py URL --state DIR [--exec PREFIX] [--token FILE] [--app ID]

DIR is the server's state folder (`work/` in a checkout, `~/.apksim` for a release, `/opt/apksim/work` in the image),
where the check looks at the running app's data slot; with `--exec "docker exec NAME"` it looks inside that container. A session with `keep=KEY` gets files pushed into its app's data and /sdcard; once it ends, a
session with the same key starts on them, one with another key does not, a second session on a key in use runs without
them (or gets 409 with `keepBusy=refuse`), what a reopened session changes is kept in turn, and `DELETE /api/kept/KEY`
forgets it. Exits 1 on any failure.
"""
import argparse
import glob
import importlib.util
import json
import os
import secrets
import shlex
import subprocess
import sys
import time
import urllib.parse
import urllib.request

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("sc", os.path.join(here, "serve-check.py"))
sc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sc)

ap = argparse.ArgumentParser()
ap.add_argument("url")
ap.add_argument("--state", required=True)
ap.add_argument("--exec", dest="exec_prefix", help="run the slot reads through this, e.g. 'docker exec NAME'")
ap.add_argument("--token")
ap.add_argument("--app")
a = ap.parse_args()
token = open(a.token).readline().strip() if a.token else None
url = a.url.rstrip("/")
app = a.app
if not app:
    st, body = sc.http(url, "/api/apps", token)
    app = json.loads(body)[0]["id"]
run = secrets.token_hex(4)  # keys of this run only: an earlier run's kept data does not count
K1, K2 = "check-" + run + "-1", "check-" + run + "-2"


def delete(path):
    h = {"Authorization": "Bearer " + token} if token else {}
    req = urllib.request.Request(url + path, headers=h, method="DELETE")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def kept(key):
    st, body = sc.http(url, "/api/kept/" + key, token)
    return json.loads(body) if st == 200 else {"status": st}


def start(key, extra=""):
    """A session with keep=KEY, up to its first frame; (session, its `d` message)."""
    s = sc.Session(url, token, app + "&keep=" + urllib.parse.quote(key) + extra, "phone")
    s.pump(120, until=lambda: any(t.startswith("s ") for t in s.texts) and len(s.frames) > 0)
    s.ws.send("stats")
    s.pump(3, until=lambda: s.stats.get("id"))
    d = [json.loads(t[2:]) for t in s.texts if t.startswith("d ")]
    return s, (d[0] if d else {})


def push(s, path, data):
    sid = s.stats["id"]
    return sc.http(url, f"/api/sessions/{sid}/files?path={urllib.parse.quote(path, safe='/')}", token, data=data,
                   headers={"Content-Type": "application/octet-stream"})


def on_disk(name):
    """What the running sessions' seed slots hold: (files/NAME contents, /sdcard/NAME contents), one per slot."""
    if a.exec_prefix:
        script = ('for s in "$0"/cache/data/*/ks*-0; do [ -d "$s" ] || continue; '
                  'f=$(cat "$s/files/$1" 2>/dev/null || printf "\\001"); d=$(cat "$s.device/sdcard/$1" 2>/dev/null || printf "\\001"); '
                  'printf "%s\\t%s\\n" "$f" "$d"; done')
        r = subprocess.run(shlex.split(a.exec_prefix) + ["sh", "-c", script, a.state, name], capture_output=True, text=True)
        out = []
        for line in r.stdout.splitlines():
            f, d = (line.split("\t") + [""])[:2]
            out.append((None if f == "\x01" else f, None if d == "\x01" else d))
        return out
    out = []
    for slot in glob.glob(os.path.join(a.state, "cache/data/*/ks*-0")):
        f, sd = os.path.join(slot, "files", name), os.path.join(slot + ".device", "sdcard", name)
        out.append((open(f).read() if os.path.exists(f) else None, open(sd).read() if os.path.exists(sd) else None))
    return out


def end(s, key):
    """Closes the tab, then waits until the key is free again (its data packed)."""
    s.ws.send("", op=8, raw=b"")
    s.ws.s.close()
    for _ in range(120):
        if not kept(key).get("inUse"):
            return True
        time.sleep(0.5)
    return False


st, _ = sc.http(url, "/api/kept/bad%20key", token)
sc.check(st == 400, f"a key with a space refused: {st}")
st, _ = sc.http(url, "/api/kept/.hidden", token)
sc.check(st == 400, f"a key starting with a dot refused: {st}")
dots = sc.WS(url, f"/ws?app={app}&keep=..", {"Origin": sc.parts(url)[2], **({"Cookie": "apksim_token=" + token} if token else {})})
sc.check(dots.status == 400, f"/ws with keep=.. refused before the upgrade: {dots.status}")
sc.check(kept(K1).get("packages") == [], "nothing kept under a new key: " + str(kept(K1)))
st, _ = delete("/api/kept/" + K1)
sc.check(st == 404, f"DELETE of a key with nothing kept: {st}")
bad = sc.WS(url, f"/ws?app={app}&keep=a/b", {"Origin": sc.parts(url)[2], **({"Cookie": "apksim_token=" + token} if token else {})})
sc.check(bad.status == 400, f"/ws with a bad key refused before the upgrade: {bad.status}")

# 1. A new key: data written, then the tab closes.
s1, d = start(K1)
sc.check(d.get("kept") == "new", "first session on K1: " + str(d))
sc.check(push(s1, "files/kept.txt", b"one")[0] == 200 and push(s1, "/sdcard/kept.txt", b"sd-one")[0] == 200, "files pushed")
sc.check(kept(K1).get("inUse") is True, "K1 in use while its session runs")
st, _ = delete("/api/kept/" + K1)
sc.check(st in (404, 409), f"DELETE while in use refused or nothing yet: {st}")
# A second tab on the same key: without the kept data, or 409 when it asks.
refused = sc.WS(url, f"/ws?app={app}&keep={K1}&keepBusy=refuse", {"Origin": sc.parts(url)[2], **({"Cookie": "apksim_token=" + token} if token else {})})
sc.check(refused.status == 409, f"keepBusy=refuse on a key in use: {refused.status}")
s2, d = start(K1)
sc.check(d.get("kept") == "busy", "second session on K1 at once: " + str(d))
s2.ws.s.close()
time.sleep(1)
sc.check(end(s1, K1), "K1 free once its session ended")
k = kept(K1)
sc.check(len(k.get("packages", [])) == 1 and k["packages"][0]["size"] > 0, "K1 kept one package: " + str(k))

# 2. The same key again: its data is there; what it changes is kept in turn.
s3, d = start(K1)
sc.check(d.get("kept") == "reopened", "K1 again: " + str(d))
disk = on_disk("kept.txt")
sc.check(("one", "sd-one") in disk, "its app data and /sdcard are back: " + str(disk))
sc.check(push(s3, "files/kept.txt", b"two")[0] == 200, "changed")
st, _ = delete("/api/kept/" + K1)
sc.check(st == 409, f"DELETE while in use: {st}")
sc.check(end(s3, K1), "K1 free again")

# 3. Another key: none of it.
s4, d = start(K2)
sc.check(d.get("kept") == "new", "K2: " + str(d))
disk = on_disk("kept.txt")
sc.check(disk and all(x == (None, None) for x in disk), "K2's app has none of K1's data: " + str(disk))
sc.check(end(s4, K2), "K2 ended")

# 4. K1 once more: the reopened session's change was kept.
s5, d = start(K1)
sc.check(d.get("kept") == "reopened", "K1 a third time: " + str(d))
disk = on_disk("kept.txt")
sc.check(("two", "sd-one") in disk, "the second session's change was kept: " + str(disk))
# The page's Start again: the tab closes and the next session starts at once, while the last one still packs.
sc.check(push(s5, "files/kept.txt", b"three")[0] == 200, "changed again")
s5.ws.send("", op=8, raw=b"")
s5.ws.s.close()
s5b, d = start(K1)
sc.check(d.get("kept") == "reopened", "K1 restarted at once: " + str(d))
disk = on_disk("kept.txt")
sc.check(("three", "sd-one") in disk, "the restarted session has the last change: " + str(disk))
sc.check(end(s5b, K1), "K1 ended")

# 5. Forgotten: gone, and a session on it starts new.
st, body = delete("/api/kept/" + K1)
sc.check(st == 200 and json.loads(body).get("forgot") == 1, f"DELETE K1: {st} {body[:80]}")
sc.check(kept(K1).get("packages") == [], "K1 has nothing now")
s6, d = start(K1)
sc.check(d.get("kept") == "new", "K1 after DELETE: " + str(d))
disk = on_disk("kept.txt")
sc.check(disk and all(x == (None, None) for x in disk), "and none of its old data: " + str(disk))
sc.check(end(s6, K1), "ended")
for key in (K1, K2):
    delete("/api/kept/" + key)
sys.exit(1 if sc.FAILED else 0)
