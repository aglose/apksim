#!/usr/bin/env python3
"""Checks a tab's `shell` message on a running `apksim serve` (docs/serve.md "Shell commands", issue 397).

    scripts/tabshell-check.py URL [--token FILE] [--app ID] [--off]

Starts a session on the test app and sends `shell ID LINE` over its socket: settings put and get, a broadcast to the
app's flag receiver (result 7), `am start` of one of its activities and of a deep link, then what a page may not run
(another package, a second command, a redirection, any other tool), and `cat` of a file in the app's own folders, each answered with exit status 126 and nothing
done. With --off the server runs with --no-shell and every line is answered with an error. Exits 1 on any failure.
"""
import argparse
import importlib.util
import json
import os
import sys

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("sc", os.path.join(here, "serve-check.py"))
sc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sc)

ap = argparse.ArgumentParser()
ap.add_argument("url")
ap.add_argument("--token")
ap.add_argument("--app")
ap.add_argument("--off", action="store_true", help="the server runs with --no-shell")
ap.add_argument("--package", default="dev.apksim.composeapp")
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
info = json.loads(next(t for t in s.texts if t.startswith("i "))[2:])
sc.check(info.get("shell") == ("off" if a.off else "limited"), f"the session says shell: {info.get('shell')}")
seq = 0


def shell(line, wait=20):
    global seq
    seq += 1
    sid = f"c{seq}"
    s.ws.send(f"shell {sid} {line}")

    def answer():
        for t in s.texts:
            if t.startswith("a ") and json.loads(t[2:]).get("id") == sid:
                return json.loads(t[2:])
        return None
    s.pump(wait, until=answer)
    return answer() or {"error": "no answer"}


if a.off:
    r = shell("settings get secure android_id")
    sc.check("--no-shell" in r.get("error", ""), f"--no-shell refuses: {r}")
    sys.exit(1 if sc.FAILED else 0)

r = shell("settings put secure apksim_tab_check 41")
sc.check(r.get("status") == 0 and r.get("out") == "", f"settings put: {r}")
r = shell("settings get secure apksim_tab_check")
sc.check(r.get("status") == 0 and r.get("out") == "41\n", f"settings get: {r}")
r = shell("settings put global 'apksim tab' \"two words\"")
sc.check(r.get("status") == 126, f"a key with a space is refused: {r}")
r = shell("am broadcast -a dev.apksim.testapp.SET_FLAG --es name tab --ez value true --ei n 3")
sc.check(r.get("status") == 0 and "result=7" in r.get("out", "") and "pkg=" + a.package in r.get("out", ""), f"am broadcast reaches the app's receiver, package set: {r}")
r = shell("am broadcast -a dev.apksim.testapp.NOBODY")
sc.check(r.get("status") == 0 and "result=0" in r.get("out", ""), f"a broadcast nobody receives completes with result 0: {r}")
# An extra whose key or value looks like -p or -n pins nothing: the broadcast still goes to the app's package alone.
for line in ("am broadcast -a dev.apksim.testapp.SET_FLAG --es -p com.other.app", "am broadcast -a dev.apksim.testapp.SET_FLAG --es name -n"):
    r = shell(line)
    sc.check(r.get("status") == 0 and "pkg=" + a.package in r.get("out", ""), f"{line[30:]!r}: still pinned to the app ({str(r)[:140]})")
r = shell(f"am start -W -n {a.package}/.SecondActivity")
sc.check(r.get("status") == 0 and "Status: ok" in r.get("out", ""), f"am start -n: {r}")
# cat (issue 408): the file the receiver wrote, in the app's own files folder, and only files inside the app's folders.
data = "/data/data/" + a.package
shell("am broadcast -a dev.apksim.testapp.SET_FLAG --es name tab --ez value true")
r = shell(f"cat {data}/files/flags.txt")
sc.check(r.get("status") == 0 and r.get("out") == "tab=true source=override\n", f"cat of a file the app wrote: {r}")
r = shell("cat files/flags.txt")
sc.check(r.get("status") == 126, f"a relative path is resolved from / and refused: {r}")
for bad in (f"cat {data}/files/../../../../etc/hosts", f"cat {data}/files/../shared_prefs/x.xml", "cat /etc/hosts", f"cat {data}/files/link-out",
            f"cat {data}/files/missing.txt", "cat", f"cat {data}/files/flags.txt /etc/hosts", f"cat -n {data}/files/flags.txt", f"cat {data}/files > /sdcard/x",
            f"run-as {a.package} cat files/flags.txt", f"cat {data}"):
    r = shell(bad)
    sc.check(r.get("status") == 126 and "not allowed from a page" in r.get("out", ""), f"refused {bad!r}: {str(r)[:140]}")
r = shell("am start -a android.intent.action.VIEW -d apksim://composeapp/trips")
sc.check(r.get("status") == 0, f"am start of a deep link: {r}")
for bad in ["am start -n com.other.app/.Main", "am broadcast -a X -p com.other.app", "am broadcast -a X -n com.other.app/.R",
            "settings put secure a b; ls /", "settings get secure a && ls", "settings get secure a > /sdcard/x", "settings get secure a | cat",
            "ls /sdcard", "apksim quit", "apksim shot /tmp/x.png", "pm list packages",
            "am instrument -w x/y", "am force-stop " + a.package, "am startservice -n " + a.package + "/.X", "am start --eu k file:///etc/passwd",
            "am start intent:#Intent;end", "settings delete secure a", "settings put system font_scale NaN",
            "settings put system font_scale 9", "settings put global animator_duration_scale -1", "settings put nowhere a b", "input tap 1 1", "logcat -d"]:
    r = shell(bad)
    refused = r.get("status") == 126 and "not allowed from a page" in r.get("out", "")
    sc.check(refused, f"refused {bad!r}: {str(r)[:140]}")
s.ws.send("shell bad!id settings get secure a")
s.pump(3, until=lambda: any(t.startswith("r refused: shell") for t in s.texts))
sc.check(any(t.startswith("r refused: shell") for t in s.texts), "a malformed shell message is refused")
r = shell("settings get secure apksim_tab_check")
sc.check(r.get("out") == "41\n", f"the session still answers after the refusals: {r}")
s.ws.send("stats")
s.pump(3, until=lambda: s.stats.get("id"))
sc.check(bool(s.stats.get("id")), "the app is still running")
sys.exit(1 if sc.FAILED else 0)
