#!/usr/bin/env python3
"""Moving a running session between the server and this Mac (docs/beam.md, phase 4), in Chrome over DevTools:

    scripts/beam-move-check.py [APK]   (default work/testapp.apk)

A session on the server, streaming the test app's Shader tab, moves to this Mac: frames go on until the Mac's first
one, which follows within a moment (no blank frame: the canvas is never cleared or covered, and the gap between the
server's last frame and the Mac's first is measured), the server's session then ends and input reaches the Mac's.
It moves back the same way; a move the agent refuses (all its sessions in use) leaves it where it was; and with the
agent killed mid-session the page offers the server, which runs it. A serve with --allow-beam and an agent on loopback
ports, Chrome with a fresh profile; the page's origin is allowed for the run and revoked after.
"""
import importlib.util
import json
import os
import shutil
import signal
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
# The page's draws, by the socket they came from (the current one's URL), and whether anything covered the canvas.
PROBE = """(() => { window.__draws = []; const od = draw;
  window.draw = (i, w, h) => { window.__draws.push([performance.now(), ws && ws.url.includes(":%d/") ? "mac" : "server"]); od(i, w, h); };
  window.__covered = 0; setInterval(() => { if (!document.getElementById("overlay").hidden) window.__covered++; }, 20); return 1; })()"""


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


def up(port, path):
    try:
        urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=2)
        return True
    except OSError:
        return False


def main():
    apk = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.join(ROOT, "work/testapp.apk")
    apksim = os.path.join(ROOT, "apksim")
    sp, gp, cp = bp.free_port(), bp.free_port(), bp.free_port()
    url = f"http://127.0.0.1:{sp}"
    subprocess.run([apksim, "beam", "trust", "allow", url], capture_output=True)
    prof = tempfile.mkdtemp(prefix="beam-move-chrome-")
    server = subprocess.Popen([apksim, "serve", "--port", str(sp), "--allow-beam", "--beam-port", str(gp), "--apps", apk],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    # In a process group of its own: killing it kills its instances' clients too, so nothing is left running.
    agent = subprocess.Popen([apksim, "beam", "--port", str(gp), "--max", "1"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                             start_new_session=True)
    procs = [server, agent]

    def status():
        return len(json.load(urllib.request.urlopen(url + "/api/status"))["sessions"])
    try:
        until(lambda: up(sp, "/api/health") and up(gp, "/beam/hello"), 30)
        app = json.load(urllib.request.urlopen(url + "/api/apps"))[0]["id"]
        procs.append(subprocess.Popen([bp.CHROME, f"--remote-debugging-port={cp}", f"--user-data-dir={prof}", "--no-first-run",
                                       "--no-default-browser-check", "--disable-backgrounding-occluded-windows",
                                       "--disable-renderer-backgrounding", "--window-size=900,1000", "about:blank"],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        until(lambda: up(cp, "/json/version"), 20)
        cdp = bp.Cdp(cp)
        cdp.call("Browser.grantPermissions", {"permissions": ["loopbackNetwork"], "origin": url})
        t = cdp.call("Target.createTarget", {"url": "about:blank"})
        s = cdp.call("Target.attachToTarget", {"targetId": t["targetId"], "flatten": True})["sessionId"]
        try:  # start on the server: the menu's choice is remembered per browser profile
            cdp.call("Page.navigate", {"url": f"{url}/?app={app}"}, s)
            until(lambda: cdp.ev(s, '!document.getElementById("runOn").hidden'), 20)
        except RuntimeError:
            pass
        cdp.ev(s, 'document.getElementById("stop").click(); document.getElementById("runOn").value = "server"; pref("server"); start(); 1')
        check(until(lambda: cdp.ev(s, "window.apksimStats().frames > 0") and status() == 1, 40), "a session on the server")
        cdp.ev(s, PROBE % gp)
        cdp.ev(s, 'send("touch down 720 1690"); send("touch up 720 1690"); 1')  # the Shader tab: frames without end
        time.sleep(2)

        def log_count(text):
            return cdp.ev(s, f'document.getElementById("log").textContent.split({json.dumps(text)}).length - 1')

        def moved(to):
            line = f"moved to {'this Mac' if to == 'mac' else 'the server'}"
            n = log_count(line)
            t0 = cdp.ev(s, "performance.now()")
            cdp.ev(s, f'window.__draws = []; window.__covered = 0; document.getElementById("runOn").value = "{to}"; '
                      f'document.getElementById("runOn").onchange(); 1')
            done = until(lambda: log_count(line) > n, 40)
            time.sleep(1)
            d = cdp.ev(s, "window.__draws")
            other = "server" if to == "mac" else "mac"
            last_old = max((x[0] for x in d if x[1] == other), default=None)
            first_new = min((x[0] for x in d if x[1] == to), default=None)
            covered = cdp.ev(s, "window.__covered")
            return done, t0, last_old, first_new, covered, d

        done, t0, last_old, first_new, covered, d = moved("mac")
        check(done, "moved to this Mac")
        if last_old and first_new:
            check(first_new - last_old < 150, f"  the server's frames went on until the Mac's first (gap {first_new - last_old:.0f} ms; the move took {first_new - t0:.0f} ms)")
        else:
            check(False, f"  frames from both sides ({len(d)} draws)")
        check(covered == 0, "  nothing covered the canvas meanwhile (no blank frame)")
        check(until(lambda: status() == 0, 10), "  the server's session ended")
        before = cdp.ev(s, "window.__draws.length")
        cdp.ev(s, 'send("touch down 733 1533"); send("touch up 733 1533"); 1')  # the Mac's app starts at Home: its FAB counts
        check(until(lambda: cdp.ev(s, "window.__draws.length") > before, 5), "  input reaches the Mac's session")
        cdp.ev(s, 'send("touch down 720 1690"); send("touch up 720 1690"); 1')  # the Shader tab there too, for the move back
        time.sleep(2)

        done, t0, last_old, first_new, covered, d = moved("server")
        check(done and last_old and first_new and first_new - last_old < 150 and covered == 0,
              f"moved back to the server, frames on until its first ({(first_new or 0) - (last_old or 0):.0f} ms gap)")
        check(status() == 1, "  one session, on the server")
        cdp.ev(s, 'send("touch down 720 1690"); send("touch up 720 1690"); 1')  # frames without end again, on the server

        # A move the agent refuses (its one session taken by another tab): it stays.
        t2 = cdp.call("Target.createTarget", {"url": f"{url}/?app={app}"})
        s2 = cdp.call("Target.attachToTarget", {"targetId": t2["targetId"], "flatten": True})["sessionId"]
        time.sleep(3)
        time.sleep(3)  # the Mac's earlier session lets go of the agent's one place once its instance has gone

        def s2_on_mac():
            cdp.ev(s2, '(async () => { const r = document.getElementById("runOn"); r.value = "mac"; pref("mac"); document.getElementById("stop").click(); start(); return 1; })()')
            return until(lambda: cdp.ev(s2, "!ended && ws && ws.url.includes(':%d/') && window.apksimStats().frames > 0" % gp), 20)
        check(until(s2_on_mac, 40), "another tab takes the agent's one session")
        cdp.call("Page.bringToFront", {}, s)  # the other tab came to the front: a hidden tab shows no frames
        cdp.ev(s, 'document.getElementById("runOn").value = "mac"; document.getElementById("runOn").onchange(); 1')
        stayed = until(lambda: "it stays on the server" in cdp.ev(s, 'document.getElementById("log").textContent'), 30)
        frames = cdp.ev(s, "window.__draws.length")
        time.sleep(1)
        check(stayed and cdp.ev(s, 'document.getElementById("runOn").value') == "server" and cdp.ev(s, "window.__draws.length") > frames,
              "a move the full agent refuses leaves the session on the server, streaming")
        cdp.ev(s2, 'document.getElementById("stop").click(); 1')
        cdp.call("Target.closeTarget", {"targetId": t2["targetId"]})
        time.sleep(2)

        # The agent killed mid-session: the page offers the server.
        done, *_ = moved("mac")
        check(done, "moved to this Mac again")
        os.killpg(agent.pid, signal.SIGKILL)
        offered = until(lambda: cdp.ev(s, '!document.getElementById("overlay").hidden && document.getElementById("overlayBtn").textContent'), 15)
        check(offered == "Run on the server instead", f"with the agent killed the page offers the server ({offered})")
        cdp.ev(s, 'document.getElementById("overlayBtn").click(); 1')
        check(until(lambda: status() == 1 and cdp.ev(s, "!ended && window.apksimStats().frames > 0"), 40), "  which runs it")
        cdp.ev(s, 'document.getElementById("stop").click(); 1')
        time.sleep(1)
    finally:
        for p in reversed(procs):
            if p.poll() is None:
                p.terminate()
        for p in procs:
            try:
                p.wait(15)
            except subprocess.TimeoutExpired:
                p.kill()
        subprocess.run([apksim, "beam", "trust", "revoke", url], capture_output=True)
        shutil.rmtree(prof, ignore_errors=True)
    print("beam move check", "passed" if OK else "FAILED")
    sys.exit(0 if OK else 1)


if __name__ == "__main__":
    main()
