#!/usr/bin/env python3
"""The serve page's start on this Mac (docs/beam.md), in Chrome over the DevTools protocol:

    scripts/beam-page-check.py [APK]   (default work/testapp.apk)

Stop while the agent has not answered yet (its consent prompt: the answer is held in the page), and that a late answer
then opens no session anywhere; an agent that takes one session (`--max 1`) running a second tab's start on the server
instead. A serve with --allow-beam and an agent on loopback ports, Chrome with a fresh profile; the page's origin is
allowed for the run and revoked after.
"""
import importlib.util, json, os, shutil, subprocess, sys, tempfile, time, urllib.request
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
spec = importlib.util.spec_from_file_location("bp", os.path.join(ROOT, "scripts/beam-parity.py"))
bp = importlib.util.module_from_spec(spec); spec.loader.exec_module(bp)
apk = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.join(ROOT, "work/testapp.apk"); apksim = os.path.join(ROOT, "apksim")
sp, gp, cp = bp.free_port(), bp.free_port(), bp.free_port()
url = f"http://127.0.0.1:{sp}"
subprocess.run([apksim, "beam", "trust", "allow", url], capture_output=True)
prof = tempfile.mkdtemp()
procs = [subprocess.Popen([apksim, "serve", "--port", str(sp), "--allow-beam", "--beam-port", str(gp), "--apps", apk], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL),
         subprocess.Popen([apksim, "beam", "--port", str(gp), "--max", "1"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)]
ok = True
def check(c, what):
    global ok; ok &= bool(c); print(("ok   " if c else "FAIL ") + what, flush=True)
try:
    time.sleep(3)
    app = json.load(urllib.request.urlopen(url + "/api/apps"))[0]["id"]
    procs.append(subprocess.Popen([bp.CHROME, f"--remote-debugging-port={cp}", f"--user-data-dir={prof}", "--no-first-run", "--no-default-browser-check",
        "--disable-backgrounding-occluded-windows", "--disable-renderer-backgrounding", "--window-size=900,1000", "about:blank"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
    time.sleep(2)
    cdp = bp.Cdp(cp)
    cdp.call("Browser.grantPermissions", {"permissions": ["loopbackNetwork"], "origin": url})
    def tab():
        t = cdp.call("Target.createTarget", {"url": "about:blank"})
        s = cdp.call("Target.attachToTarget", {"targetId": t["targetId"], "flatten": True})["sessionId"]
        cdp.call("Page.navigate", {"url": f"{url}/?app={app}"}, s)
        time.sleep(3)
        cdp.ev(s, '(async () => { const r = document.getElementById("runOn"); if (r.value !== "mac") { r.value = "mac"; await r.onchange(); } return r.value; })()')
        return s
    # 1. Stop while the agent has not answered (its consent prompt): the start is held by a fetch that never ends.
    s1 = tab()
    time.sleep(2)
    cdp.ev(s1, 'document.getElementById("stop").click()')  # the page started the app named in its URL by itself
    time.sleep(1.5)
    # The agent's answer to /beam/start held back (its consent prompt unanswered), released later.
    cdp.ev(s1, """(() => { const o = okJson; window.okJson = p => { const r = o(p); return r.then(v => v && (v.token || v.need) && !window.__held
      ? new Promise(res => { window.__held = true; window.__release = () => res(v); }) : v); }; return 1; })()""")
    gen0 = cdp.ev(s1, "startGen")
    cdp.ev(s1, 'document.getElementById("start").click()')
    time.sleep(1)
    check(cdp.ev(s1, f"startGen === {gen0} + 1 && macStart === startGen"), "Start asks this Mac (held: no answer)")
    check(cdp.ev(s1, '!document.getElementById("stop").disabled'), "Stop is enabled while a start waits for this Mac")
    cdp.ev(s1, 'document.getElementById("stop").click()')
    time.sleep(0.5)
    check(cdp.ev(s1, 'document.getElementById("stop").disabled && !document.getElementById("start").disabled'), "Stop drops it")
    cdp.ev(s1, "window.__release(); 1")  # the agent answers after Stop: the dropped start must not run anywhere
    time.sleep(3)
    check(cdp.ev(s1, '!ws || ws.readyState > 1'), "and the dropped start opens no session when the agent answers late")
    check(len(json.load(urllib.request.urlopen(url + "/api/status"))["sessions"]) == 0, "nor on the server")
    # 2. Two tabs on an agent that takes one session: the second runs on the server.
    s2 = tab()
    cdp.ev(s2, 'document.getElementById("start").click()')
    end = time.time() + 30
    while time.time() < end and not cdp.ev(s2, "window.apksimStats().frames > 0"): time.sleep(0.2)
    check(cdp.ev(s2, "window.apksimStats().frames > 0") and "running on this Mac" in cdp.ev(s2, 'document.getElementById("log").textContent'), "the first tab runs on this Mac")
    s3 = tab()
    cdp.ev(s3, 'document.getElementById("start").click()')
    end = time.time() + 40
    while time.time() < end and not cdp.ev(s3, "window.apksimStats().frames > 0"): time.sleep(0.2)
    log3 = cdp.ev(s3, 'document.getElementById("log").textContent')
    st = json.load(urllib.request.urlopen(url + "/api/status"))
    check(cdp.ev(s3, "window.apksimStats().frames > 0") and "running on the server instead" in log3 and len(st["sessions"]) == 1,
          "the second, refused by the full agent, runs on the server (" + log3.replace("\n", " | ")[-160:] + ")")
finally:
    for p in procs: p.terminate()
    for p in procs:
        try:
            p.wait(15)
        except subprocess.TimeoutExpired:
            p.kill()
    subprocess.run([apksim, "beam", "trust", "revoke", url], capture_output=True)
    shutil.rmtree(prof, ignore_errors=True)
print("beam page check", "passed" if ok else "FAILED")
sys.exit(0 if ok else 1)
