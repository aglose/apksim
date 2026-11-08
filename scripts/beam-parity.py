#!/usr/bin/env python3
"""Phase 1b's bench (docs/beam.md): one app in its own Mac window against the same app beamed into a Chrome tab,
driven by the same scripted input and measured the same way.

    scripts/beam-parity.py APK [--runs 2] [--seconds 5] [--chrome PATH] [--agent-opt=--bitrate=20000]...
                               [--scenarios shader,fling,drag] [--json FILE]

Each run does the window, then the tab (interleaved runs, as the machine's load drifts). Per scenario:
- screen: new frames on N% of display refreshes while animating, and frames replaced before being shown. The window
  counts its display link's vsyncs (`frames`' screen part); the tab counts requestAnimationFrame ticks the same way
  (the page's `apksimPacing`), so both say what reached the screen.
- host: the instance's own frame rate and intervals (`frames`), i.e. whether the app drew as fast in both.
- cpu: cores used over the scenario (from CPU time): the instance, and for the tab also the agent and Chrome.
- the tab's present -> drawn latency, encode time and frame size (the page's figures), and input -> drawn for taps
  made in the page.

Input goes to each instance's own control port (the same commands for both), not through the page, so only the
frame's way to the screen differs. Scenarios: `shader` (the test app's Shader tab, unthrottled), `fling` (flings on
the Home list every 0.5 s), `drag` (slow drags, no fling, over 1.2 s each, one after another).

Uses this checkout's state folder (the AOT cache and class cache, as a real launch), a serve on loopback with
`--allow-beam`, an agent with the serve page's origin allowed for the run (revoked after) and Chrome with a fresh
profile, kept from throttling while covered. The Mac window opens with `--window nofocus` (on top, not focused).
"""
import argparse
import glob
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
import importlib.util

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
spec = importlib.util.spec_from_file_location("servecheck", os.path.join(HERE, "serve-check.py"))
sc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sc)

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
SHADER, HOME = (720, 1690), (130, 1690)
FAB = (733, 1533)


def state_dir():
    if os.environ.get("APKSIM_HOME"):
        return os.environ["APKSIM_HOME"]
    return os.path.expanduser("~/.apksim") if os.path.exists(os.path.join(ROOT, "work/release")) else os.path.join(ROOT, "work")


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Control:
    """An instance's --control channel: one command, one answer line."""

    def __init__(self, port, seconds=60):
        end = time.time() + seconds
        while True:
            try:
                self.s = socket.create_connection(("127.0.0.1", port), timeout=2)
                break
            except OSError:
                if time.time() > end:
                    raise
                time.sleep(0.02)
        self.s.settimeout(60)
        self.f = self.s.makefile("rw")

    def __call__(self, cmd):
        self.f.write(cmd + "\n")
        self.f.flush()
        return self.f.readline().strip()

    def close(self):
        try:
            self.s.close()
        except OSError:
            pass


def cpu_seconds(pids):
    """Total CPU time (user + system) of these processes, in seconds."""
    pids = [str(p) for p in pids if p]
    if not pids:
        return 0.0
    out = subprocess.run(["ps", "-o", "time=", "-p", ",".join(pids)], capture_output=True, text=True).stdout
    total = 0.0
    for t in out.split():
        parts = [float(x) for x in re.split("[:-]", t)]
        sec = 0.0
        for x in parts:
            sec = sec * 60 + x
        total += sec
    return total


def rss_mb(pids):
    pids = [str(p) for p in pids if p]
    if not pids:
        return 0
    out = subprocess.run(["ps", "-o", "rss=", "-p", ",".join(pids)], capture_output=True, text=True).stdout
    return sum(int(x) for x in out.split()) // 1024


def parse_frames(reply):
    """(host fps, p95 ms, screen new %, replaced) from a `frames` reply."""
    fps = re.search(r"avg=([\d.]+) fps", reply)
    p95 = re.search(r"p95=([\d.]+)ms", reply)
    scr = re.search(r"new frame on (\d+)% of vsyncs.*replaced before shown=(\d+)", reply)
    return (float(fps.group(1)) if fps else 0.0, float(p95.group(1)) if p95 else 0.0,
            int(scr.group(1)) if scr else None, int(scr.group(2)) if scr else None)


def scenario_input(ctl, name, seconds):
    """Drives one scenario for `seconds`."""
    end = time.time() + seconds
    if name == "shader":
        time.sleep(seconds)
    elif name == "fling":
        up = True
        while time.time() < end:
            ctl("swipe 411 1300 411 500" if up else "swipe 411 500 411 1300")
            up = not up
            time.sleep(0.5)
    elif name == "drag":
        up = True
        while time.time() < end:
            # A timed swipe is answered at once and runs on the instance's clock: wait it out (1.2 s, a 120 ms hold).
            ctl("swipe 411 1300 411 500 1200" if up else "swipe 411 500 411 1300 1200")
            up = not up
            time.sleep(1.4)


def setup(ctl, name):
    ctl(f"tap {SHADER[0]} {SHADER[1]}" if name == "shader" else f"tap {HOME[0]} {HOME[1]}")
    time.sleep(2.5)


class Cdp:
    """Chrome over the DevTools protocol: commands answered in order, events kept aside."""

    def __init__(self, port):
        ver = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json/version", timeout=10))
        path = "/" + ver["webSocketDebuggerUrl"].split("/", 3)[3]
        self.ws = sc.WS(f"http://127.0.0.1:{port}", path, {})
        self.id = 0

    def call(self, method, params=None, session=None, timeout=60):
        self.id += 1
        msg = {"id": self.id, "method": method, "params": params or {}}
        if session:
            msg["sessionId"] = session
        self.ws.send(json.dumps(msg))
        end = time.time() + timeout
        while time.time() < end:
            m = self.ws.recv(max(0.05, end - time.time()))
            if not m or m[0] != 1:
                continue
            d = json.loads(m[1])
            if d.get("id") == self.id:
                if "error" in d:
                    raise RuntimeError(f"{method}: {d['error']}")
                return d.get("result", {})
        raise TimeoutError(method)

    def ev(self, session, expr, timeout=60):
        r = self.call("Runtime.evaluate", {"expression": expr, "awaitPromise": True, "returnByValue": True}, session, timeout)
        if "exceptionDetails" in r:
            raise RuntimeError(r["exceptionDetails"].get("exception", {}).get("description") or r["exceptionDetails"].get("text"))
        return r["result"].get("value")


def window_run(apk, scenarios, seconds):
    port = free_port()
    t0 = time.time()
    p = subprocess.Popen([os.path.join(ROOT, "apksim"), "--control", str(port), "--window", "nofocus", apk],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    ctl = Control(port)
    while re.search(r"\bframes=0\b", ctl("stats")) and time.time() - t0 < 60:
        time.sleep(0.02)
    first = time.time() - t0
    pid = int(re.search(r"pid=(\d+)", ctl("stats")).group(1))
    out = {"firstFrameS": round(first, 2)}
    time.sleep(2)
    for name in scenarios:
        setup(ctl, name)
        ctl("frames")
        c0 = cpu_seconds([pid])
        w0 = time.time()
        scenario_input(ctl, name, seconds)
        reply = ctl("frames")
        cores = (cpu_seconds([pid]) - c0) / (time.time() - w0)
        fps, p95, new, replaced = parse_frames(reply)
        out[name] = {"screenNewPct": new, "replaced": replaced, "hostFps": fps, "hostP95Ms": p95,
                     "cores": round(cores, 2), "rssMb": rss_mb([pid]), "frames": reply}
    ctl("quit")
    ctl.close()
    try:
        p.wait(20)
    except subprocess.TimeoutExpired:
        p.kill()
    return out


def session_port(cdp, s, state, seconds=30):
    """The control port and PID of the tab's own session's instance: the PID from the session's stats (the page keeps
    the last as apksimSession), the port from that instance's tmp folder. Never another instance that happens to run."""
    end = time.time() + seconds
    while time.time() < end:
        pid = cdp.ev(s, "window.apksimSession && window.apksimSession.pid || 0")
        f = os.path.join(state, "tmp", str(pid), "control.port")
        if pid > 0 and os.path.exists(f):
            try:
                return int(open(f).read().strip()), pid
            except ValueError:
                pass
        time.sleep(0.2)
    raise RuntimeError("the session's instance did not say its pid")


def tab_run(cdp, server_url, app_id, scenarios, seconds, state, agent_pid, chrome_pids):
    t = cdp.call("Target.createTarget", {"url": "about:blank"})
    s = cdp.call("Target.attachToTarget", {"targetId": t["targetId"], "flatten": True})["sessionId"]
    cdp.call("Page.enable", {}, s)
    cdp.call("Page.bringToFront", {}, s)
    # Loaded without ?app= (which would start it at once): the page set up, then the app chosen and one Start timed.
    cdp.call("Page.navigate", {"url": f"{server_url}/"}, s)
    end = time.time() + 30
    while time.time() < end:
        try:
            if cdp.ev(s, f'!document.getElementById("runOn").hidden && [...document.getElementById("app").options].some(o => o.value === {json.dumps(app_id)})'):
                break
        except RuntimeError:
            pass
        time.sleep(0.2)
    on = cdp.ev(s, 'document.getElementById("runOn").value')
    if on != "mac":
        cdp.ev(s, '(async () => { const r = document.getElementById("runOn"); r.value = "mac"; await r.onchange(); return r.value; })()')
    cdp.ev(s, f'(() => {{ const a = document.getElementById("app"); a.value = {json.dumps(app_id)}; a.dispatchEvent(new Event("change")); return 1; }})()')
    t0 = time.time()
    cdp.ev(s, 'document.getElementById("start").click()')
    while time.time() - t0 < 60 and not cdp.ev(s, "window.apksimStats().frames > 0"):
        time.sleep(0.05)
    out = {"firstFrameS": round(time.time() - t0, 2), "runOn": cdp.ev(s, 'document.getElementById("runOn").value'),
           "codec": cdp.ev(s, "window.apksimStats().codec")}
    # On this Mac, not a fallback to the server (the menu would still say "On this Mac").
    agent_port = cdp.ev(s, "beam.port")
    if not cdp.ev(s, f"!!(ws && ws.url.startsWith('ws://127.0.0.1:{agent_port}/') && !ended && window.apksimStats().frames > 0)"):
        raise RuntimeError("the tab's session is not on this Mac: " + cdp.ev(s, 'document.getElementById("log").textContent')[-300:])
    port, pid = session_port(cdp, s, state)
    ctl = Control(port)
    time.sleep(2)
    # Taps made in the page: input -> the next new frame drawn in the tab.
    for _ in range(5):
        cdp.ev(s, f'(markInput(), send("touch down {FAB[0]} {FAB[1]}"), send("touch up {FAB[0]} {FAB[1]}"), 1)')
        time.sleep(0.6)
    out["tapToDrawnMs"] = cdp.ev(s, "window.apksimStats().inputMs")
    for name in scenarios:
        setup(ctl, name)
        ctl("frames")
        procs = [pid, agent_pid] + chrome_pids()
        c0 = {p: cpu_seconds([p]) for p in procs}
        w0 = time.time()
        cdp.ev(s, f"window.__pacing = window.apksimPacing({int(seconds * 1000)}); 1")
        scenario_input(ctl, name, seconds)
        pace = cdp.ev(s, "window.__pacing", timeout=seconds + 30)
        reply = ctl("frames")
        wall = time.time() - w0
        st = cdp.ev(s, "window.apksimStats()")
        fps, p95, _, _ = parse_frames(reply)
        out[name] = {"screenNewPct": pace["newPct"], "replaced": pace["replaced"], "refreshHz": pace["hz"], "hostFps": fps,
                     "hostP95Ms": p95, "cores": round((cpu_seconds([pid]) - c0[pid]) / wall, 2),
                     "agentCores": round((cpu_seconds([agent_pid]) - c0[agent_pid]) / wall, 2),
                     "chromeCores": round(sum(cpu_seconds([p]) - c0.get(p, cpu_seconds([p])) for p in procs[2:]) / wall, 2),
                     "presentToDrawnMs": st["frameMs"], "encodeMs": st["encodeMs"], "kbPerFrame": round(st["kb"] or 0, 1),
                     "rssMb": rss_mb([pid]), "frames": reply}
    ctl.close()
    cdp.ev(s, 'document.getElementById("stop").click()')
    time.sleep(1.5)
    cdp.call("Target.closeTarget", {"targetId": t["targetId"]})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--runs", type=int, default=2)
    ap.add_argument("--seconds", type=float, default=5)
    ap.add_argument("--chrome", default=CHROME)
    ap.add_argument("--agent-opt", action="append", default=[], help="an option for apksim beam, as --agent-opt=--bitrate=20000")
    ap.add_argument("--scenarios", default="shader,fling,drag")
    ap.add_argument("--json")
    a = ap.parse_args()
    apk = os.path.abspath(a.apk)
    scenarios = a.scenarios.split(",")
    state = state_dir()
    apksim = os.path.join(ROOT, "apksim")
    sp, gp, cp = free_port(), free_port(), free_port()
    server_url = f"http://127.0.0.1:{sp}"
    agent_opts = [x for o in a.agent_opt for x in o.split("=", 1)]
    procs, profile = [], tempfile.mkdtemp(prefix="beam-parity-chrome-")
    trusted_before = subprocess.run([apksim, "beam", "trust", "list"], capture_output=True, text=True).stdout
    subprocess.run([apksim, "beam", "trust", "allow", server_url], capture_output=True, check=True)
    results = {"window": [], "tab": []}
    try:
        procs.append(subprocess.Popen([apksim, "serve", "--port", str(sp), "--allow-beam", "--beam-port", str(gp), "--apps", apk],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        agent = subprocess.Popen([apksim, "beam", "--port", str(gp)] + agent_opts, stdout=subprocess.DEVNULL,
                                 stderr=open(os.path.join(profile, "agent.log"), "w"))
        procs.append(agent)
        for port, path in ((sp, "/api/health"), (gp, "/beam/hello")):
            for _ in range(200):
                try:
                    urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=2)
                    break
                except OSError:
                    time.sleep(0.2)
        app_id = json.load(urllib.request.urlopen(server_url + "/api/apps"))[0]["id"]
        chrome = subprocess.Popen([a.chrome, f"--remote-debugging-port={cp}", f"--user-data-dir={profile}", "--no-first-run",
                                   "--no-default-browser-check", "--disable-backgrounding-occluded-windows",
                                   "--disable-renderer-backgrounding", "--disable-background-timer-throttling",
                                   "--window-size=900,1000", "about:blank"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        procs.append(chrome)
        for _ in range(100):
            try:
                urllib.request.urlopen(f"http://127.0.0.1:{cp}/json/version", timeout=1)
                break
            except OSError:
                time.sleep(0.2)
        cdp = Cdp(cp)
        cdp.call("Browser.grantPermissions", {"permissions": ["loopbackNetwork"], "origin": server_url})

        def chrome_pids():
            out = subprocess.run(["pgrep", "-f", f"user-data-dir={profile}"], capture_output=True, text=True).stdout
            return [int(x) for x in out.split()]

        # A first launch of each, not measured: the class cache and the standby, as a launch after the first.
        print("warming up", flush=True)
        window_run(apk, [], 0)
        tab_run(cdp, server_url, app_id, [], 0, state, agent.pid, chrome_pids)
        for i in range(a.runs):
            print(f"run {i + 1}: window", flush=True)
            results["window"].append(window_run(apk, scenarios, a.seconds))
            print(f"run {i + 1}: tab", flush=True)
            results["tab"].append(tab_run(cdp, server_url, app_id, scenarios, a.seconds, state, agent.pid, chrome_pids))
    finally:
        for p in reversed(procs):
            p.terminate()
        for p in procs:
            try:
                p.wait(15)
            except subprocess.TimeoutExpired:
                p.kill()
        if server_url not in trusted_before:
            subprocess.run([apksim, "beam", "trust", "revoke", server_url], capture_output=True)
        shutil.rmtree(profile, ignore_errors=True)

    print()
    print(f"{'scenario':8} {'view':6} {'screen new %':>12} {'replaced':>8} {'host fps':>8} {'p95 ms':>7} {'cores':>6} "
          f"{'agent':>6} {'chrome':>6} {'present->drawn':>14} {'encode':>6} {'KB':>5}")
    for name in scenarios:
        for view in ("window", "tab"):
            for r in results[view]:
                m = r[name]
                print(f"{name:8} {view:6} {str(m['screenNewPct']):>12} {str(m['replaced']):>8} {m['hostFps']:8.1f} "
                      f"{m['hostP95Ms']:7.1f} {m['cores']:6.2f} {m.get('agentCores', ''):>6} {m.get('chromeCores', ''):>6} "
                      f"{(str(round(m['presentToDrawnMs'], 1)) if m.get('presentToDrawnMs') else ''):>14} "
                      f"{(str(round(m['encodeMs'], 1)) if m.get('encodeMs') else ''):>6} {m.get('kbPerFrame', ''):>5}")
    for view in ("window", "tab"):
        print(view, "first frame (s):", [r["firstFrameS"] for r in results[view]],
              *(["tap -> drawn (ms):", [r.get("tapToDrawnMs") for r in results[view]]] if view == "tab" else []))
    if a.json:
        with open(a.json, "w") as f:
            json.dump(results, f, indent=1)


if __name__ == "__main__":
    main()
