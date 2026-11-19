#!/usr/bin/env python3
"""A serve tab's sound (issue 532): /ws?audio=stream gets `u` messages while the app plays, `audio stream off` stops
them, the `i` message says `"audio":"stream"`, and a session started without the option gets none until it sends
`audio stream on`.

  scripts/serve-audio-check.py URL APP_ID [--token FILE]
--open URL: a deep link that plays sound (default: the test app's apksim://media?sample=aac-lc.m4a).
"""
import importlib.util, json, sys, time

spec = importlib.util.spec_from_file_location("sc", __file__.replace("serve-audio-check.py", "serve-check.py"))
sc = importlib.util.module_from_spec(spec)
sys.argv, argv = sys.argv[:1], sys.argv[1:]
spec.loader.exec_module(sc)
url, app = argv[0], argv[1]
token = open(argv[argv.index("--token") + 1]).read().strip() if "--token" in argv else None
open_url = argv[argv.index("--open") + 1] if "--open" in argv else "apksim://media%3Fsample%3Daac-lc.m4a"


def run(audio):
    s = sc.Session(url, token, app, "phone", path=f"/ws?app={app}&device=phone&open={open_url}" + ("&audio=stream" if audio else ""))
    s.pump(15, until=lambda: any(t.startswith("i ") for t in s.texts))
    info = json.loads(next(t for t in s.texts if t.startswith("i "))[2:])
    return s, info


s, info = run(True)
sc.check(info.get("audio") == "stream", "the i message says audio: stream (%r)" % info.get("audio"))
s.pump(12, until=lambda: any(t.startswith("u ") for t in s.texts))
us = [t for t in s.texts if t.startswith("u ")]
sc.check(bool(us), "the app's sound arrives as u messages (%d)" % len(us))
if us:
    f = us[0].split(" ")
    sc.check(abs(int(f[1]) / 1e6 - time.time()) < 60 and int(f[3]) >= 8000 and 1 <= int(f[4]) <= 8, "a u message carries epoch microseconds, a rate and channels")
s.ws.send("audio stream off")
s.pump(1)
n = len([t for t in s.texts if t.startswith("u ")])
s.pump(2)
sc.check(len([t for t in s.texts if t.startswith("u ")]) - n <= 3, "audio stream off stops them")
s.ws.send("audio stream on")
s.pump(1)
s.ws.send("audio mute")
s.pump(0.5)
refused = [t for t in s.texts if t.startswith("r refused")]
sc.check(not refused, "audio stream on/off/mute are accepted (no refusal) %s" % refused[:2])
s2, info2 = run(False)
s2.pump(6)
sc.check(not [t for t in s2.texts if t.startswith("u ")], "a session that did not ask hears nothing")
s2.ws.send("audio stream on")  # asked for later: the sound starts with what plays from then on
s2.ws.send("open " + open_url.replace("%3F", "?").replace("%3D", "="))
s2.pump(15, until=lambda: any(t.startswith("u ") for t in s2.texts))
sc.check(any(t.startswith("u ") for t in s2.texts), "audio stream on mid-session starts it")
sys.exit(1 if sc.FAILED else 0)
