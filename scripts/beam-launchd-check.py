#!/usr/bin/env python3
"""`apksim beam install`'s launchd agent (docs/beam.md, "The launchd agent"), installed for the check and removed after:

    scripts/beam-launchd-check.py [APK] [--real]   (default work/testapp.apk)

By default this script plays launchd's part itself: it holds the listening socket and, on a page's first connection,
starts `apksim beam --launchd` with that socket on stdin, as launchd's inetd mode does (and again after the agent
exits), and it checks the jobs `apksim beam install --dry-run` writes with plutil. With --real it installs them with
launchd itself, under a label of its own (dev.apksim.beam.check<pid>), and removes them after.

Either way a state folder of its own (APKSIM_HOME, removed after), a free port and an 8 s idle time: nothing runs once installed; a page's first request starts the agent through launchd and
is answered; a session runs (the APK sent, a first frame); with no page for its idle time the agent exits, and the next
request starts it again; uninstall removes both jobs and their files, and the port no longer answers. The jobs are
removed even when a check fails.
"""
import hashlib
import importlib.util
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
spec = importlib.util.spec_from_file_location("bc", os.path.join(HERE, "beam-check.py"))
bc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bc)  # its helpers (req, WS); its own state folder is not used
check = bc.check
STATE = tempfile.mkdtemp(prefix="beam-launchd-")
ENV = dict(os.environ, APKSIM_HOME=STATE, APKSIM_BEAM_CONSENT="allow")
LABEL = f"dev.apksim.beam.check{os.getpid()}"
SITE = "https://beam-launchd-check.example"
AGENTS = os.path.expanduser("~/Library/LaunchAgents")


def beam(*args):
    return subprocess.run([os.path.join(ROOT, "apksim"), "beam", *args], capture_output=True, text=True, env=ENV, timeout=60)


def agent_running():
    return "agent: running" in beam("status").stdout


def answers(port):
    try:
        return bc.req(port, "GET", "/beam/hello", SITE)[0] == 200
    except OSError:
        return False


def until(f, seconds):
    end = time.time() + seconds
    while time.time() < end:
        if f():
            return True
        time.sleep(0.3)
    return bool(f())


class StandIn:
    """launchd's part: listens, and starts the agent with the listening socket on stdin when a connection waits and no
    agent runs (inetdCompatibility with Wait)."""

    def __init__(self, port, idle):
        self.sock = socket.socket()
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.bind(("127.0.0.1", port))
        self.sock.listen(16)
        self.port, self.idle, self.proc, self.starts, self.stop = port, idle, None, 0, False
        import threading
        threading.Thread(target=self.loop, daemon=True).start()

    def loop(self):
        import select
        while not self.stop:
            if self.proc is not None and self.proc.poll() is None:
                time.sleep(0.2)
                continue
            r, _, _ = select.select([self.sock], [], [], 0.2)
            if r and not self.stop:
                self.starts += 1
                self.proc = subprocess.Popen([os.path.join(ROOT, "apksim"), "beam", "--launchd", "--port", str(self.port),
                                              "--agent-idle", str(self.idle)], stdin=self.sock.fileno(),
                                             stdout=subprocess.DEVNULL, stderr=open(os.path.join(STATE, "agent.log"), "a"), env=ENV)

    def close(self):
        self.stop = True
        if self.proc is not None and self.proc.poll() is None:
            self.proc.terminate()
            self.proc.wait(20)
        self.sock.close()


def main():
    real = "--real" in sys.argv
    args = [a for a in sys.argv[1:] if a != "--real"]
    apk = os.path.abspath(args[0]) if args else os.path.join(ROOT, "work/testapp.apk")
    data = open(apk, "rb").read()
    sha = hashlib.sha256(data).hexdigest()
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    stand_in = None
    jobs = tempfile.mkdtemp(prefix="beam-launchd-jobs-") if not real else AGENTS
    try:
        r = beam("install", "--label", LABEL, "--port", str(port), "--agent-idle", "8", *([] if real else ["--dry-run", jobs]))
        check(r.returncode == 0, f"install ({r.stdout.strip()[:90]}{r.stderr.strip()[:200]})")
        plists = [os.path.join(jobs, LABEL + ".plist"), os.path.join(jobs, LABEL + ".sweep.plist")]
        check(all(os.path.exists(p) and subprocess.run(["plutil", "-lint", p], capture_output=True).returncode == 0 for p in plists),
              "two LaunchAgents written, valid property lists (the agent's and the daily sweep's)")
        agent_plist = json.loads(subprocess.run(["plutil", "-convert", "json", "-o", "-", plists[0]], capture_output=True, text=True).stdout)
        check(agent_plist.get("inetdCompatibility", {}).get("Wait") is True
              and agent_plist.get("Sockets", {}).get("Listeners", {}).get("SockServiceName") == str(port)
              and "--launchd" in agent_plist.get("ProgramArguments", []),
              "the agent's job: socket-activated on its port, the socket handed over inetd's way")
        if not real:
            stand_in = StandIn(port, 8)
        time.sleep(1)
        check(not agent_running(), "nothing runs until a page asks")
        t0 = time.time()
        check(until(lambda: answers(port), 30), f"a page's first request starts the agent ({time.time() - t0:.1f} s)")
        check(agent_running(), "and apksim beam status sees it running")
        # A session through the launchd agent: the build sent by the tab, a first frame.
        start = {"sha256": sha, "name": "launchd-check.apk", "size": len(data), "options": []}
        g, _ = bc.begin(port, SITE, start, data)
        check("token" in g, "a session grant from it")
        sess = bc.open_session(port, g, SITE)
        check(len(sess.frames) > 0, "the session streams")
        bc.close_session(sess)
        t1 = time.time()
        check(until(lambda: not agent_running(), 40), f"with no page for its idle time it exits ({time.time() - t1:.0f} s)")
        t2 = time.time()
        check(until(lambda: answers(port), 30), f"and the next request starts it again ({time.time() - t2:.1f} s)")
        if stand_in:
            check(stand_in.starts == 2, f"started twice in all (first request, then after its idle exit): {stand_in.starts}")
    finally:
        if real:
            r = beam("uninstall", "--label", LABEL)
            check(r.returncode == 0 and not os.path.exists(os.path.join(AGENTS, LABEL + ".plist"))
                  and not os.path.exists(os.path.join(AGENTS, LABEL + ".sweep.plist")), "uninstall removes both jobs")
            uid = str(os.getuid())
            check(subprocess.run(["launchctl", "print", f"gui/{uid}/{LABEL}"], capture_output=True).returncode != 0, "launchd no longer has it")
        else:
            stand_in.close() if stand_in else None
            shutil.rmtree(jobs, ignore_errors=True)
        check(until(lambda: not answers(port), 10), "and nothing answers on its port")
        subprocess.run([os.path.join(ROOT, "apksim"), "--stop-standbys"], env=ENV, capture_output=True)
        shutil.rmtree(STATE, ignore_errors=True)
    if bc.sc.FAILED:
        print(f"{len(bc.sc.FAILED)} check(s) failed")
        sys.exit(1)
    print("beam launchd check passed")


if __name__ == "__main__":
    main()
