#!/usr/bin/env python3
"""The serve page's start on this Mac (docs/beam.md), in Chrome over the DevTools protocol:

    scripts/beam-page-check.py [APK]   (default work/testapp.apk)

Stop while the agent has not answered yet (its consent prompt: the answer is held in the page), and that a late answer
then opens no session anywhere; an agent that takes one session (`--max 1`) running a second tab's start on the server
instead; a hidden tab's instance drawing a frame a second, its own pace again once the tab is back; and with an agent
that speaks only newer, then only older, protocols than the page (APKSIM_BEAM_PROTOCOLS), "On this Mac" disabled with
which side to update, the session on the server. A serve with
--allow-beam and an agent on loopback ports, Chrome with a fresh profile; the page's origin is allowed for the run and
revoked after.
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
# The agent's answer to /beam/start held back (its consent prompt unanswered) until window.__release().
HOLD_ANSWER = """(() => { const o = okJson; window.okJson = p => { const r = o(p); return r.then(v => v && (v.token || v.need) && !window.__held
  ? new Promise(res => { window.__held = true; window.__release = () => res(v); }) : v); }; return 1; })()"""
CHOOSE_MAC = ('(async () => { const r = document.getElementById("runOn"); '
              'if (r.value !== "mac") { r.value = "mac"; await r.onchange(); } return r.value; })()')


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
        time.sleep(0.2)
    return f()


def up(port, path):
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=2)
        return True
    except OSError:
        return False


def server_sessions(url):
    return len(json.load(urllib.request.urlopen(url + "/api/status"))["sessions"])


def page_log(cdp, s):
    return cdp.ev(s, 'document.getElementById("log").textContent')


def main():
    apk = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.join(ROOT, "work/testapp.apk")
    apksim = os.path.join(ROOT, "apksim")
    sp, gp, cp = bp.free_port(), bp.free_port(), bp.free_port()
    url = f"http://127.0.0.1:{sp}"
    subprocess.run([apksim, "beam", "trust", "allow", url], capture_output=True, check=True)  # the rest needs it
    prof = tempfile.mkdtemp(prefix="beam-page-chrome-")
    procs = [subprocess.Popen([apksim, "serve", "--port", str(sp), "--allow-beam", "--beam-port", str(gp), "--apps", apk],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL),
             subprocess.Popen([apksim, "beam", "--port", str(gp), "--max", "1"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)]
    try:
        check(until(lambda: up(sp, "/api/health") and up(gp, "/beam/hello"), 30), "the server and the agent answer")
        app = json.load(urllib.request.urlopen(url + "/api/apps"))[0]["id"]
        procs.append(subprocess.Popen([bp.CHROME, f"--remote-debugging-port={cp}", f"--user-data-dir={prof}", "--no-first-run",
                                       "--no-default-browser-check", "--disable-backgrounding-occluded-windows",
                                       "--disable-renderer-backgrounding", "--window-size=900,1000", "about:blank"],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        until(lambda: up(cp, "/json/version"), 20)
        cdp = bp.Cdp(cp)
        cdp.call("Browser.grantPermissions", {"permissions": ["loopbackNetwork"], "origin": url})

        def tab():
            t = cdp.call("Target.createTarget", {"url": "about:blank"})
            s = cdp.call("Target.attachToTarget", {"targetId": t["targetId"], "flatten": True})["sessionId"]
            cdp.call("Page.navigate", {"url": f"{url}/?app={app}"}, s)
            time.sleep(3)
            cdp.ev(s, CHOOSE_MAC)
            return s

        # 1. Stop while the agent has not answered (its consent prompt), and a late answer then runs nothing.
        s1 = tab()
        time.sleep(2)
        cdp.ev(s1, 'document.getElementById("stop").click()')  # the page started the app named in its URL by itself
        time.sleep(1.5)
        cdp.ev(s1, HOLD_ANSWER)
        gen0 = cdp.ev(s1, "startGen")
        cdp.ev(s1, 'document.getElementById("start").click()')
        time.sleep(1)
        check(cdp.ev(s1, f"startGen === {gen0} + 1 && macStart === startGen"), "Start asks this Mac (held: no answer)")
        check(cdp.ev(s1, '!document.getElementById("stop").disabled'), "Stop is enabled while a start waits for this Mac")
        cdp.ev(s1, 'document.getElementById("stop").click()')
        time.sleep(0.5)
        check(cdp.ev(s1, 'document.getElementById("stop").disabled && !document.getElementById("start").disabled'), "Stop drops it")
        cdp.ev(s1, "window.__release(); 1")  # the agent answers after Stop
        time.sleep(3)
        check(cdp.ev(s1, "!ws || ws.readyState > 1"), "and the dropped start opens no session when the agent answers late")
        check(server_sessions(url) == 0, "nor on the server")

        # 2. Two tabs on an agent that takes one session: the second runs on the server.
        s2 = tab()
        cdp.ev(s2, 'document.getElementById("start").click()')
        until(lambda: cdp.ev(s2, "window.apksimStats().frames > 0"), 30)
        check(cdp.ev(s2, "window.apksimStats().frames > 0") and "running on this Mac" in page_log(cdp, s2), "the first tab runs on this Mac")
        s3 = tab()
        cdp.ev(s3, 'document.getElementById("start").click()')
        until(lambda: cdp.ev(s3, "window.apksimStats().frames > 0"), 40)
        log3 = page_log(cdp, s3)
        check(cdp.ev(s3, "window.apksimStats().frames > 0") and "running on the server instead" in log3 and server_sessions(url) == 1,
              "the second, refused by the full agent, runs on the server (" + log3.replace("\n", " | ")[-160:] + ")")

        # 3. A hidden tab: its instance draws a frame a second; back in front, at its own pace again. The first tab (on
        # this Mac) shows the Shader tab, which never stops animating, while the third is in front.
        port, _ = bp.session_port(cdp, s2, bp.state_dir())
        ctl = bp.Control(port)

        def fps(seconds=3):  # from `stats`' frame count: `frames` leaves out intervals of 100 ms or more
            n0, t0 = int(re.search(r"frames=(\d+)", ctl("stats")).group(1)), time.time()
            time.sleep(seconds)
            return (int(re.search(r"frames=(\d+)", ctl("stats")).group(1)) - n0) / (time.time() - t0)

        ctl("tap 720 1690")
        time.sleep(2)
        # This display's refresh rate, as the tab sees it (60 Hz or 120): what "its own pace" can reach.
        refresh = cdp.ev(s3, """new Promise(r => { let n = 0; const t0 = performance.now();
          const f = () => { n++; if (performance.now() - t0 < 1000) requestAnimationFrame(f); else r(n * 1000 / (performance.now() - t0)); };
          requestAnimationFrame(f); })""")
        hidden = fps()
        cdp.call("Page.bringToFront", {}, s2)
        time.sleep(2)
        shown = fps()
        ctl.close()
        check(hidden <= 3 and shown >= 0.8 * min(refresh, 120),
              f"a hidden tab's instance draws {hidden:.1f} fps, {shown:.1f} once the tab is back in front (display {refresh:.0f} Hz)")
        for x in (s1, s2, s3):
            cdp.ev(x, 'document.getElementById("stop").click(); 1')

        # 4. Versions: agents with no protocol in common with the page (docs/beam.md, "Versions").
        for rng, says in (("2-3", "this site's apksim is older"), ("0-0", "update apksim (brew upgrade apksim)")):
            agent = procs[1]
            agent.terminate()
            agent.wait(20)
            until(lambda: not up(gp, "/beam/hello"), 10)
            procs[1] = subprocess.Popen([apksim, "beam", "--port", str(gp), "--max", "1"], stdout=subprocess.DEVNULL,
                                        stderr=subprocess.DEVNULL, env=dict(os.environ, APKSIM_BEAM_PROTOCOLS=rng))
            until(lambda: up(gp, "/beam/hello"), 30)
            sv = tab()
            time.sleep(1)
            label = cdp.ev(sv, 'document.querySelector("#runOn option[value=mac]").textContent')
            disabled = cdp.ev(sv, 'document.querySelector("#runOn option[value=mac]").disabled')
            where = cdp.ev(sv, 'document.getElementById("runOn").value')
            check(disabled and says in label and where == "server", f"an agent speaking only {rng}: \"{label}\", disabled, on the server")
            cdp.ev(sv, 'document.getElementById("stop").click(); start(); 1')
            check(until(lambda: cdp.ev(sv, "window.apksimStats().frames > 0 && !ws.onMac"), 40), "  and the app runs on the server")
            cdp.ev(sv, 'document.getElementById("stop").click(); 1')
    finally:
        for p in procs:
            p.terminate()
        for p in procs:
            try:
                p.wait(15)
            except subprocess.TimeoutExpired:
                p.kill()
        subprocess.run([apksim, "beam", "trust", "revoke", url], capture_output=True)
        shutil.rmtree(prof, ignore_errors=True)
    print("beam page check", "passed" if OK else "FAILED")
    sys.exit(0 if OK else 1)


if __name__ == "__main__":
    main()
