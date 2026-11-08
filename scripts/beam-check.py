#!/usr/bin/env python3
"""Checks `apksim beam` (docs/beam.md) against a server that offers it, both started here on loopback.

    scripts/beam-check.py APK [--apksim ./apksim] [--server-port 47480] [--agent-port 47481]

Acts as the page does (each request with its page's Origin): the server's /api/beam and an app's SHA-256, size and
bytes; the agent's /beam/hello, a site that is not allowed refused, `apksim beam trust allow` letting it in, an
upload whose hash is not the one named refused, the APK sent once and found by its hash the second time, grants used
once and only by their own origin, a session that streams frames, takes a tap and ends with its tab, and `revoke`
refusing the site again. A second agent answers the prompt with "Always Allow" (`APKSIM_BEAM_CONSENT=allow`, for
checks only) and keeps that, and a site saved as refused stays refused.

Then what the Mac keeps (docs/beam.md phase 1c): `config` read at the agent's start; a build kept while a session of
it runs (another session's end sweeps meanwhile) and deleted with its cache entry once the last ends with `keep-days
0`; kept with a nonzero hold and found by hash again; a session kept with "keep on" (its slot renamed and listed by
`saved`, its build surviving the sweep), `run` reopening it with what the test app's slot screen wrote (APK: the test
app); `forget` erasing it and the next sweep its build; `save-sessions on` keeping every session; one agent per state
folder; `sweep` leaving a running agent's builds alone.

Everything runs in a state folder of its own (`APKSIM_HOME`, removed at the end), so the Mac's own agent, trusted
sites and builds are not touched; its instances therefore start without the class cache or startup cache.
"""
import argparse
import hashlib
import http.client
import importlib.util
import json
import os
import glob
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("servecheck", os.path.join(HERE, "serve-check.py"))
sc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sc)
check, WS = sc.check, sc.WS

A, B = "https://beam-check-a.example", "https://beam-check-b.example"
STATE = tempfile.mkdtemp(prefix="beam-check-")
ENV = dict(os.environ, APKSIM_HOME=STATE)
PKG = "dev.apksim.composeapp"
# The test app's slot screen (--activity SlotStateActivity) on the phone: its "Write" button.
WRITE = (130, 536)


def req(port, method, path, origin=None, body=None, headers=None, host=None):
    """(status, headers, body) of one request to 127.0.0.1:port, as a page on `origin` would send it."""
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=60)
    h = {"Host": host or f"127.0.0.1:{port}"}
    if origin:
        h["Origin"] = origin
    if isinstance(body, (dict, list)):
        body = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    h.update(headers or {})
    try:
        c.request(method, path, body=body, headers=h)
    except (BrokenPipeError, ConnectionResetError):
        pass  # refused before the body was read (the agent answers and closes): the answer is still there
    r = c.getresponse()
    data = r.read()
    c.close()
    return r.status, {k.lower(): v for k, v in r.getheaders()}, data


def wait_up(port, path, seconds=60):
    end = time.time() + seconds
    while time.time() < end:
        try:
            if req(port, "GET", path)[0] == 200:
                return True
        except OSError:
            time.sleep(0.3)
    return False


def trust(apksim, *args):
    return beam(apksim, "trust", *args)


def beam(apksim, *args, timeout=60):
    return subprocess.run([apksim, "beam", *args], capture_output=True, text=True, env=ENV, timeout=timeout)


def agent(apksim, port, consent, *extra):
    env = dict(ENV, APKSIM_BEAM_CONSENT=consent)
    log = open(os.path.join(STATE, f"agent-{port}-{time.time():.0f}.log"), "w")
    p = subprocess.Popen([apksim, "beam", "--port", str(port), *extra], env=env, stdout=subprocess.DEVNULL, stderr=log, text=True)
    p.logfile = log.name
    if not wait_up(port, "/beam/hello"):
        p.kill()
        sys.exit("the agent did not come up: " + open(log.name).read()[-2000:])
    return p


def until(pred, seconds):
    end = time.time() + seconds
    while time.time() < end:
        if pred():
            return True
        time.sleep(0.2)
    return pred()


def build_file(sha):
    return os.path.join(STATE, "beam", "uploads", sha + ".apk")


def cache_entries(sha):
    """The build's caches: the agent's translated classes (moved there as each session ends) and any apksim cache entry
    (a beamed instance keeps its own in its scratch; `beam run`, an ordinary launch, makes one)."""
    return glob.glob(os.path.join(STATE, "cache", sha + ".apk-*")) + glob.glob(os.path.join(STATE, "beam", "cache", sha))


def build_classes(sha):
    return glob.glob(os.path.join(STATE, "beam", "cache", sha, "classes", "*", "*.pack"))


def sandbox_checks(apksim, apk):
    """The beam sandbox's profile (docs/beam.md "The beam sandbox"), probed with shell commands run inside it."""
    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen(8)
    lport = listener.getsockname()[1]
    standby = os.path.join(tempfile.gettempdir(), f"apksim-standby-beamcheck{os.getpid()}.sock")
    unix = socket.socket(socket.AF_UNIX)
    try:
        os.unlink(standby)
    except OSError:
        pass
    unix.bind(standby)
    unix.listen(1)
    os.makedirs(os.path.join(STATE, "beam"), exist_ok=True)
    os.makedirs(os.path.join(STATE, "autofill"), exist_ok=True)
    other = os.path.join(STATE, "cache", "data", "dev.example", "default")
    os.makedirs(other, exist_ok=True)
    os.makedirs(os.path.join(STATE, "cache", "data", "dev.example", "beam-0"), exist_ok=True)
    for f in (os.path.join(STATE, "beam", "trust.json"), os.path.join(STATE, "autofill", "x.properties"), os.path.join(other, "x")):
        if not os.path.exists(f):
            open(f, "w").write("secret\n")
    probes = [
        ("allowed", "write its own scratch", "touch $S/tmp/$$/probe"),
        ("allowed", "write a beam data slot", "touch $S/cache/data/dev.example/beam-0/probe"),
        ("allowed", "read apksim's jar", "head -c 4 $R/work/apksim.jar"),
        ("allowed", "reach the internet's resolver", "nslookup -timeout=3 example.com"),
        ("denied", "read the agent's trusted sites", "cat $S/beam/trust.json"),
        ("denied", "read saved autofill sign-ins", "cat $S/autofill/x.properties"),
        ("denied", "read another app's data", "cat $S/cache/data/dev.example/default/x"),
        ("denied", "write the shared class cache", "mkdir -p $S/cache/classes/probe"),
        ("denied", "write another app's data", "touch $S/cache/data/dev.example/default/probe"),
        ("denied", "write another process's scratch", "touch $S/tmp/1/probe"),
        ("denied", "edit the agent's trusted sites", "echo x >> $S/beam/trust.json"),
        ("denied", "read a file in the home folder", "head -c 1 $HOME/Library/Preferences/.GlobalPreferences.plist"),
        ("denied", "list the home folder", "ls $HOME"),
        ("denied", "connect to a loopback port (another instance, the agent)", f"nc -z -w 2 127.0.0.1 {lport}"),
        ("denied", "connect to a standby's socket", f"nc -U -z {standby}"),
    ]
    script = "S=$1; R=$2\n" + "\n".join(
        f"if ( {cmd} ) >/dev/null 2>&1; then echo 'allowed {i}'; else echo 'denied {i}'; fi" for i, (_, _, cmd) in enumerate(probes))
    os.makedirs(os.path.join(STATE, "tmp", "1"), exist_ok=True)
    env = dict(ENV, APKSIM_BEAM_SANDBOX="1", APKSIM_SANDBOX_TEST=f"sh -c {sh_quote(script)} probe {sh_quote(os.path.realpath(STATE))} "
               f"{sh_quote(os.path.realpath(os.path.dirname(HERE)))}")
    r = subprocess.run([apksim, apk], capture_output=True, text=True, timeout=60, env=env)
    got = dict(line.split(" ", 1)[::-1] for line in r.stdout.split("\n") if line.strip())
    for i, (want, what, _) in enumerate(probes):
        check(got.get(str(i)) == want, f"sandbox: a beamed app may{'' if want == 'allowed' else ' not'} {what} ({got.get(str(i))})")
    listener.close()
    unix.close()
    os.unlink(standby)


def sh_quote(s):
    return "'" + s.replace("'", "'\\''") + "'"


def live_sandbox_checks(pid):
    """A running beamed instance, over its own control port: a screenshot into the agent's folder fails, into its own
    scratch works (the instance's own file operations, from inside the JVM)."""
    f = os.path.join(STATE, "tmp", str(pid), "control.port")
    if not os.path.exists(f):
        check(False, f"sandbox: the beamed instance's control port ({f})")
        return
    c = socket.create_connection(("127.0.0.1", int(open(f).read().strip())), timeout=30)
    io = c.makefile("rw")

    def cmd(line):
        io.write(line + "\n")
        io.flush()
        return io.readline().strip()
    bad = cmd(f"shot {os.path.join(STATE, 'beam', 'probe.png')}")
    good = cmd(f"shot {os.path.join(STATE, 'tmp', str(pid), 'probe.png')}")
    c.close()
    check(bad.startswith("error") and not os.path.exists(os.path.join(STATE, "beam", "probe.png")),
          f"sandbox: a beamed instance cannot write into the agent's folder ({bad[:80]})")
    check(not good.startswith("error") and os.path.exists(os.path.join(STATE, "tmp", str(pid), "probe.png")),
          f"sandbox: and writes its own scratch ({good[:60]})")


def download_checks(apksim, apk, sha, size):
    """Phase 2 (docs/beam.md): the agent downloads a build from the page's server with the server's ticket, in a state
    folder, server and agent of their own (tickets of 4 s, for expiry). A stand-in for single sign-on in front of the
    server (401 to anyone without its cookie) makes the agent ask the tab to send the APK instead."""
    import http.server
    import threading
    import urllib.request
    state = tempfile.mkdtemp(prefix="beam-check-dl-")
    env = dict(os.environ, APKSIM_HOME=state, APKSIM_BEAM_TICKET_SECONDS="4", APKSIM_BEAM_CONSENT="allow")
    sp, gp = free_port(), free_port()
    procs = []

    class SignIn(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(401)
            self.send_header("Content-Type", "text/html")
            self.end_headers()
            self.wfile.write(b"<html>sign in</html>")

        def log_message(self, *a):
            pass
    proxy = http.server.ThreadingHTTPServer(("127.0.0.1", 0), SignIn)
    threading.Thread(target=proxy.serve_forever, daemon=True).start()
    S, P = f"http://127.0.0.1:{sp}", f"http://127.0.0.1:{proxy.server_address[1]}"
    uploads = os.path.join(state, "beam", "uploads")
    try:
        procs.append(subprocess.Popen([apksim, "serve", "--port", str(sp), "--allow-beam", "--beam-port", str(gp), "--apps", apk],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env))
        procs.append(subprocess.Popen([apksim, "beam", "--port", str(gp)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env))
        check(wait_up(sp, "/api/health") and wait_up(gp, "/beam/hello"), "download: a server and an agent of its own")
        app = json.loads(req(sp, "GET", "/api/apps")[2])[0]["id"]
        st, _, body = req(sp, "GET", f"/api/apps/{app}/beam")
        info = json.loads(body)
        ticket = info.get("ticket", "")
        check(st == 200 and info.get("sha256") == sha and ticket.startswith("v1.") and info.get("ticketSeconds") == 4,
              f"download: the app's beam answer carries a ticket ({ticket[:24]}...)")
        st, h, body = req(sp, "GET", f"/api/beam/apk?ticket={ticket}")
        check(st == 200 and hashlib.sha256(body).hexdigest() == sha and h.get("content-type", "").startswith("application/vnd.android"),
              "download: the ticket alone downloads that APK (no token)")
        altered = ticket[:-1] + ("A" if ticket[-1] != "A" else "B")
        check(req(sp, "GET", f"/api/beam/apk?ticket={altered}")[0] == 403, "download: an altered ticket is refused")
        check(req(sp, "GET", "/api/beam/apk")[0] == 403, "download: no ticket, no APK")
        other = ticket.replace(f".{sha}.", f".{'0' * 64}.")
        check(req(sp, "GET", f"/api/beam/apk?ticket={other}")[0] == 403, "download: a ticket naming another build is refused")
        start = {"sha256": sha, "name": "beam-check.apk", "size": size, "options": [], "ticket": ticket}

        # Behind a sign-in the agent cannot pass: the tab sends the APK.
        st, _, body = req(gp, "POST", "/beam/start", P, dict(start, url=P + "/api/beam/apk"))
        g = json.loads(body) if st == 200 else {}
        check(g.get("need") == "upload" and "401" in g.get("why", ""), f"download: behind a sign-in the agent asks the tab ({g.get('why')})")
        st, _, body = req(gp, "POST", f"/beam/upload?upload={g.get('upload')}", P, open(apk, "rb").read())
        check(st == 200 and "token" in json.loads(body), "download: and the tab's upload starts the session")
        for f in glob.glob(os.path.join(uploads, "*")):
            os.remove(f)  # the agent has no build again

        # From the page's own server: the agent downloads it itself, then finds it by its hash.
        st, _, body = req(gp, "POST", "/beam/start", S, dict(start, url=S + "/api/beam/apk"))
        g = json.loads(body) if st == 200 else {}
        check(g.get("via") == "download" and "token" in g and os.path.exists(os.path.join(uploads, sha + ".apk")),
              f"download: the agent downloads the build from the page's server ({g.get('via')})")
        st, _, body = req(gp, "POST", "/beam/start", S, dict(start, url=S + "/api/beam/apk"))
        check(json.loads(body).get("via") == "cache", "download: the second visit transfers nothing")
        # A build named by a hash the download does not have: not stored, the tab asked.
        st, _, body = req(gp, "POST", "/beam/start", S, dict(start, sha256="1" * 64, url=S + "/api/beam/apk"))
        g = json.loads(body) if st == 200 else {}
        check(g.get("need") == "upload" and "SHA-256" in g.get("why", "") and not os.path.exists(os.path.join(uploads, "1" * 64 + ".apk")),
              f"download: a download whose hash is not the one named is not kept ({g.get('why', '')[:60]})")
        # Only from the page's own site.
        st, _, body = req(gp, "POST", "/beam/start", P, dict(start, sha256="2" * 64, url=S + "/api/beam/apk"))
        g = json.loads(body) if st == 200 else {}
        check(g.get("need") == "upload" and "own site" in g.get("why", ""), f"download: not from another site than the page's ({g.get('why')})")
        st, _, body = req(gp, "POST", "/beam/start", S, dict(start, sha256="3" * 64, url=S + "/api/other"))
        g = json.loads(body) if st == 200 else {}
        check(g.get("need") == "upload" and g.get("why"), f"download: nor any other address of it ({g.get('why')})")
        # An expired ticket.
        for f in glob.glob(os.path.join(uploads, "*")):
            os.remove(f)
        time.sleep(5)
        check(req(sp, "GET", f"/api/beam/apk?ticket={ticket}")[0] == 403, "download: an expired ticket is refused")
        st, _, body = req(gp, "POST", "/beam/start", S, dict(start, url=S + "/api/beam/apk"))
        g = json.loads(body) if st == 200 else {}
        check(g.get("need") == "upload" and "403" in g.get("why", ""), f"download: and the agent then asks the tab ({g.get('why')})")
    finally:
        proxy.shutdown()
        for p in procs:
            p.terminate()
        for p in procs:
            try:
                p.wait(15)
            except subprocess.TimeoutExpired:
                p.kill()
        subprocess.run([apksim, "--stop-standbys"], env=env, capture_output=True)
        shutil.rmtree(state, ignore_errors=True)


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def begin(gp, origin, start, apk):
    """A session grant for this build, sending the APK when the agent asks; (grant, sent)."""
    st, _, body = req(gp, "POST", "/beam/start", origin, start)
    g = json.loads(body) if st == 200 else {}
    sent = g.get("need") == "upload"
    if sent:
        st, _, body = req(gp, "POST", f"/beam/upload?upload={g['upload']}", origin, apk)
        g = json.loads(body) if st == 200 else {}
    return g, sent


def open_session(gp, grant, origin):
    """The session for a grant, pumped to its first frame and its instance's pid."""
    w = WS(f"http://127.0.0.1:{gp}", f"/ws?app={grant.get('app')}&token={grant.get('token')}", {"Origin": origin})
    s = sc.Session.__new__(sc.Session)
    s.ws, s.frames, s.codec, s.acking, s.offset, s.best_rtt = w, [], "jpeg", True, 0.0, float("inf")
    s.texts, s.stats, s.ack_delay, s.acks = [], {}, 0, []
    if w.status == 101:
        s.pump(120, until=lambda: len(s.frames) > 0)
        s.pump(6, until=lambda: s.stats.get("pid", 0) > 0)
    return s


def alive(pid):
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def close_session(s):
    """Closes the tab; whether its instance has gone (the agent then saves or erases its slot and sweeps)."""
    pid = s.stats.get("pid", 0)
    s.ws.s.close()
    return pid > 0 and until(lambda: not alive(pid), 20)


def session_info(s):
    for t in s.texts:
        if t.startswith("i "):
            return json.loads(t[2:])
    return {}


def slots(prefix):
    return glob.glob(os.path.join(STATE, "cache", "data", PKG, prefix + "*"))


def saved_ids(apksim):
    out = beam(apksim, "saved").stdout.splitlines()
    return [line.split()[0] for line in out[1:] if line.strip()] if out and out[0].startswith("ID") else []


def stop(p):
    p.terminate()
    try:
        p.wait(20)
    except subprocess.TimeoutExpired:
        p.kill()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    ap.add_argument("--apksim", default=os.path.join(os.path.dirname(HERE), "apksim"))
    ap.add_argument("--server-port", type=int, default=47480)
    ap.add_argument("--agent-port", type=int, default=47481)
    a = ap.parse_args()
    sp, gp, apksim = a.server_port, a.agent_port, a.apksim
    with open(a.apk, "rb") as f:
        apk = f.read()
    sha, size = hashlib.sha256(apk).hexdigest(), len(apk)
    with zipfile.ZipFile(a.apk) as z:
        if not any(b"SlotStateActivity" in z.read(n) for n in z.namelist() if n.endswith(".dex")):
            sys.exit(f"{a.apk} has no SlotStateActivity: build the test app from this checkout "
                     "(testapks/composeapp: ./gradlew :app:assembleDebug)")
    # An agent refuses anything but loopback.
    r = subprocess.run([apksim, "beam", "--bind", "0.0.0.0"], capture_output=True, text=True, timeout=30, env=ENV)
    check(r.returncode == 2, "apksim beam refuses --bind (loopback only)")

    sandbox_checks(apksim, a.apk)
    download_checks(apksim, a.apk, sha, size)
    server = subprocess.Popen([apksim, "serve", "--port", str(sp), "--allow-beam", "--beam-port", str(gp), "--apps", a.apk],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=ENV)
    g = None
    try:
        if not wait_up(sp, "/api/health"):
            sys.exit("the server did not come up")
        st, _, body = req(sp, "GET", "/api/apps")
        app = json.loads(body)[0]["id"]

        # ---- the server's side
        st, _, body = req(sp, "GET", "/api/beam")
        check(st == 200 and json.loads(body).get("port") == gp, f"server: /api/beam names the agent's port ({body[:60]!r})")
        st, _, body = req(sp, "GET", f"/api/apps/{app}/beam")
        info = json.loads(body) if st == 200 else {}
        check(info.get("sha256") == sha and info.get("size") == size, "server: an app's SHA-256 and size")
        st, h, body = req(sp, "GET", f"/api/apps/{app}/apk")
        check(st == 200 and hashlib.sha256(body).hexdigest() == sha, "server: the APK's bytes")
        st, h, _ = req(sp, "GET", "/")
        check(f"http://127.0.0.1:{gp}" in h.get("content-security-policy", ""), "server: the page's CSP lets it reach the agent")
        st, _, _ = req(sp, "GET", "/api/apps/nosuchapp/apk")
        check(st == 404, "server: no bytes for an app it does not have")

        # ---- an agent whose prompt says no (APKSIM_BEAM_CONSENT=deny)
        g = agent(apksim, gp, "deny")
        st, h, body = req(gp, "GET", "/beam/hello", A)
        hello = json.loads(body)
        check(st == 200 and hello.get("protocol") == 1 and hello.get("trusted") is False, f"agent: hello ({body!r})")
        check(h.get("access-control-allow-origin") == A, "agent: any page may read its hello")
        st, h, _ = req(gp, "OPTIONS", "/beam/start", A, headers={"Access-Control-Request-Method": "POST",
                                                                  "Access-Control-Request-Private-Network": "true"})
        check(st == 204 and h.get("access-control-allow-private-network") == "true", "agent: answers preflights")
        start = {"sha256": sha, "name": "beam-check.apk", "size": size, "options": []}
        st, _, _ = req(gp, "POST", "/beam/start", None, start)
        check(st == 403, "agent: a request without a page's Origin is refused")
        st, _, _ = req(gp, "GET", "/beam/hello", A, host="evil.example")
        check(st == 403, "agent: a request for another Host is refused (DNS rebinding)")
        st, _, body = req(gp, "POST", "/beam/start", A, start)
        check(st == 403, f"agent: a site its owner did not allow is refused ({body[:60]!r})")
        st, _, _ = req(gp, "POST", "/beam/start", A, dict(start, options=["--jvm", "-Xmx1g"]))
        check(st in (400, 403), "agent: launch options outside serve's list are refused")

        r = trust(apksim, "allow", A)
        check(r.returncode == 0, "trust allow " + A)
        st, _, body = req(gp, "GET", "/beam/hello", A)
        check(json.loads(body).get("trusted") is True, "agent: hello says the allowed site is trusted")

        # An upload whose bytes are not what the page named.
        fake = hashlib.sha256(b"not this apk").hexdigest()
        st, _, body = req(gp, "POST", "/beam/start", A, dict(start, sha256=fake))
        grant = json.loads(body) if st == 200 else {}
        check(grant.get("need") == "upload", "agent: an APK it does not have is asked for")
        st, _, body = req(gp, "POST", f"/beam/upload?upload={grant.get('upload')}", A, apk)
        check(st == 400 and b"SHA-256" in body, f"agent: an APK whose hash is not the one named is refused ({body[:70]!r})")

        # The real one: sent once (unless an earlier run left it), then found by its hash.
        st, _, body = req(gp, "POST", "/beam/start", A, start)
        grant = json.loads(body) if st == 200 else {}
        if grant.get("need") == "upload":
            up = grant["upload"]
            st, _, _ = req(gp, "POST", f"/beam/upload?upload={up}", B, apk)
            check(st == 403, "agent: an upload grant does not work for another site")
            # The grant was used up by that refusal too: a page starts again.
            st, _, body = req(gp, "POST", "/beam/start", A, start)
            up = json.loads(body)["upload"]
            st, _, body = req(gp, "POST", f"/beam/upload?upload={up}", A, apk)
            check(st == 200 and "token" in json.loads(body), "agent: the APK is taken once its hash matches")
            st, _, _ = req(gp, "POST", f"/beam/upload?upload={up}", A, apk)
            check(st == 403, "agent: an upload grant is used once")
        st, _, body = req(gp, "POST", "/beam/start", A, start)
        grant = json.loads(body) if st == 200 else {}
        check("token" in grant and "need" not in grant, "agent: a build it has is not sent again")

        # Session grants: one origin, one app, one use.
        o = {"Origin": B}
        w = WS(f"http://127.0.0.1:{gp}", f"/ws?app={grant.get('app')}&token={grant.get('token')}", o)
        check(w.status == 403, "agent: a session grant does not work for another site")
        st, _, body = req(gp, "POST", "/beam/start", A, start)
        grant = json.loads(body)
        w = WS(f"http://127.0.0.1:{gp}", f"/ws?app={grant['app']}", {"Origin": A})
        check(w.status == 403, "agent: a session needs its grant")
        w = WS(f"http://127.0.0.1:{gp}", f"/ws?app={grant['app']}&token={grant['token']}", {"Origin": A})
        check(w.status == 101, "agent: the session opens with its grant")
        s = sc.Session.__new__(sc.Session)
        s.ws, s.frames, s.codec, s.acking, s.offset, s.best_rtt = w, [], "jpeg", True, 0.0, float("inf")
        s.texts, s.stats, s.ack_delay, s.acks = [], {}, 0, []
        t0 = time.time()
        s.pump(120, until=lambda: len(s.frames) > 0)
        check(len(s.frames) > 0, f"agent: frames arrive ({time.time() - t0:.2f} s to the first)")
        s.pump(1.5)
        after = s.after(["touch down 733 1533", "touch up 733 1533"], 2)
        check(len(after) > 0, "agent: a tap changes the screen")
        s.pump(3, until=lambda: s.stats.get("pid", 0) > 0)
        pid = s.stats.get("pid", 0)
        live_sandbox_checks(pid)
        w2 = WS(f"http://127.0.0.1:{gp}", f"/ws?app={grant['app']}&token={grant['token']}", {"Origin": A})
        check(w2.status == 403, "agent: a session grant is used once")
        w.s.close()
        gone = False
        for _ in range(100):
            try:
                os.kill(pid, 0)
            except OSError:
                gone = True
                break
            time.sleep(0.2)
        check(pid > 0 and gone, f"agent: the instance (pid {pid}) ends with its tab")

        trust(apksim, "revoke", A)
        st, _, _ = req(gp, "POST", "/beam/start", A, start)
        check(st == 403, "agent: a revoked site is refused again")
        stop(g)

        # ---- an agent whose prompt says "Always Allow" (APKSIM_BEAM_CONSENT=allow)
        g = agent(apksim, gp, "allow")
        st, _, body = req(gp, "POST", "/beam/start", B, start)
        check(st == 200, "agent: the prompt's Always Allow lets a site in")
        check(B in trust(apksim, "list").stdout, "agent: and keeps it (trust list)")
        trust(apksim, "deny", A)
        st, _, _ = req(gp, "POST", "/beam/start", A, start)
        check(st == 403, "agent: a site saved as refused is not asked again")

        # ---- what the Mac keeps (phase 1c)
        r = subprocess.run([apksim, "beam", "--port", str(gp + 2)], capture_output=True, text=True, env=ENV, timeout=30)
        check(r.returncode == 2 and "already runs" in r.stderr, "keep: one agent per state folder")
        r = beam(apksim, "sweep")
        check("sweeps by itself" in r.stdout, "keep: sweep leaves a running agent's builds alone")
        stop(g)
        r = beam(apksim, "config", "keep-days", "0")
        check(r.returncode == 0 and "keep-days 0" in r.stdout, f"keep: config keep-days 0 ({r.stdout.strip()!r})")
        g = agent(apksim, gp, "allow")
        check("keep-days 0" in open(g.logfile).read(), "keep: the agent reads the config at its start")
        g1, _ = begin(gp, B, start, apk)
        s1 = open_session(gp, g1, B)
        check(len(s1.frames) > 0 and session_info(s1).get("keep") is False, f"keep: a session runs, not kept by default ({session_info(s1)})")
        g2, sent = begin(gp, B, start, apk)
        check(not sent and "token" in g2, "keep: a second session finds the running one's build by its hash")
        s2 = open_session(gp, g2, B)
        check(len(s2.frames) > 0, "keep: the second session runs")
        check(close_session(s2), "keep: the second session ends")
        time.sleep(2)  # its end's sweep, with keep-days 0
        check(os.path.exists(build_file(sha)), "keep: the build stays while a session of it runs")
        check(build_classes(sha), "keep: a session's translated classes go into its build's cache when it ends")
        check(not glob.glob(os.path.join(STATE, "cache", sha + ".apk-*")) and not glob.glob(os.path.join(STATE, "cache", "classes", "*", "*.pack")),
              "keep: a beamed instance wrote nothing into the caches other launches share")
        check(close_session(s1), "keep: the first session ends")
        check(until(lambda: not os.path.exists(build_file(sha)) and not cache_entries(sha), 15),
              "keep: with keep-days 0 the build and its cache entry go once its last session ends")
        check(not slots("beam-") or all(not os.listdir(d) or os.listdir(d) == [".lock"] for d in slots("beam-")),
              "keep: the sessions' data slots are erased")
        stop(g)

        g = agent(apksim, gp, "allow", "--keep-days", "1")
        g3, sent = begin(gp, B, start, apk)
        check(sent and "token" in g3, "keep: a deleted build is asked for again")
        s3 = open_session(gp, g3, B)
        check(close_session(s3), "keep: a session with keep-days 1 ends")
        time.sleep(2)
        check(os.path.exists(build_file(sha)), "keep: with a nonzero hold the build stays after its session")
        g4, sent = begin(gp, B, start, apk)
        check(not sent and "token" in g4, "keep: and a session within the hold finds it by its hash")
        stop(g)

        # A saved session: "keep on" from the tab, with keep-days 0 (from the config) for its build.
        g = agent(apksim, gp, "allow")
        slot_start = dict(start, options=["--activity", "SlotStateActivity"])
        g5, _ = begin(gp, B, slot_start, apk)
        s5 = open_session(gp, g5, B)
        s5.ws.send("keep on")
        s5.pump(3, until=lambda: "k on" in s5.texts)
        check("k on" in s5.texts, "keep: the tab's keep on is answered")
        s5.pump(2)
        after = s5.after([f"touch down {WRITE[0]} {WRITE[1]}", f"touch up {WRITE[0]} {WRITE[1]}"], 2)
        check(len(after) > 0, "keep: Write on the slot screen changes it")
        check(close_session(s5), "keep: the kept session ends")
        check(until(lambda: len(saved_ids(apksim)) == 1, 10), "keep: apksim beam saved lists it")
        ids = saved_ids(apksim)
        sid = ids[0] if ids else "none"
        check(os.path.isdir(os.path.join(STATE, "cache", "data", PKG, f"k{sid}-0")), f"keep: its slot is renamed to k{sid}-0")
        time.sleep(2)
        check(os.path.exists(build_file(sha)), "keep: a saved session's build survives the sweep (keep-days 0)")
        stop(g)
        g = None
        r = beam(apksim, "sweep")
        check(r.returncode == 0 and os.path.exists(build_file(sha)), "keep: and apksim beam sweep keeps it too")

        # run: the saved session in a headless local launch, with what it left.
        cp = gp + 3
        rp = subprocess.Popen([apksim, "beam", "run", sid, "--headless", "--control", str(cp)], env=ENV,
                              stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
        drive = os.path.join(HERE, "drive.py")
        nodes = ""
        for _ in range(60):
            r = subprocess.run([sys.executable, drive, str(cp), "wait 1500", "nodes"], capture_output=True, text=True, timeout=60)
            if "at app start" in r.stdout:
                nodes = r.stdout
                break
            time.sleep(1)
        subprocess.run([sys.executable, drive, str(cp), "quit"], capture_output=True, text=True, timeout=30)
        try:
            code = rp.wait(30)
        except subprocess.TimeoutExpired:
            rp.kill()
            code = -1
        check("at app start: setting=written" in nodes and "sdcard=written" in nodes,
              f"keep: run reopens it with the data it left ({[x for x in nodes.split(';') if 'app start' in x][:1]})")
        check(code == 0, f"keep: run ends with the instance's exit code ({code})")

        r = beam(apksim, "forget", sid)
        check(r.returncode == 0 and not slots(f"k{sid}") and saved_ids(apksim) == [], "keep: forget erases its data")
        r = beam(apksim, "sweep")
        check(not os.path.exists(build_file(sha)) and not cache_entries(sha), "keep: and its build goes at the next sweep (keep-days 0)")

        # save-sessions on: every session kept.
        beam(apksim, "config", "save-sessions", "on")
        g = agent(apksim, gp, "allow")
        g6, _ = begin(gp, B, start, apk)
        s6 = open_session(gp, g6, B)
        check(session_info(s6).get("keep") is True, "keep: with save-sessions on the tab is told it is kept")
        check(close_session(s6), "keep: that session ends")
        check(until(lambda: len(saved_ids(apksim)) == 1, 10), "keep: and is saved without asking")
        for i in saved_ids(apksim):
            beam(apksim, "forget", i)
    finally:
        if g:
            stop(g)
        stop(server)
        subprocess.run([apksim, "--stop-standbys"], env=ENV, capture_output=True)
        shutil.rmtree(STATE, ignore_errors=True)
    if sc.FAILED:
        print(f"{len(sc.FAILED)} check(s) failed")
        sys.exit(1)
    print("beam check passed")


if __name__ == "__main__":
    main()
