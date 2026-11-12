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

Then issue 181: `GET /beam/saved` listing a saved session to its own site only; the tab reopening it (`saved`) on a
copy that goes back over the saved slot when it ends, never two at once; and, when the Android SDK's apksigner and
keytool are here, `dataFrom`: a saved session of the test APK signed with a key of the check's own, a newer build
(one asset more, same key) started on a copy of it, and refused when altered after signing, when signed by another
key, or on the debug-signed saved session.

Everything runs in a state folder of its own (`APKSIM_HOME`, removed at the end), so the Mac's own agent, trusted
sites and builds are not touched; its instances therefore start without the class cache or startup cache.
"""
import argparse
import base64
import hashlib
import http.client
import importlib.util
import json
import os
import glob
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.parse
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
    victim = subprocess.Popen(["sleep", "120"])  # another process of the visitor's
    if not subprocess.run(["pbpaste"], capture_output=True).stdout:
        subprocess.run(["pbcopy"], input=b"x")  # something on the clipboard, so an allowed read would show it
    other_sock = os.path.join(tempfile.gettempdir(), f"beam-check-agent{os.getpid()}.sock")
    agent_like = socket.socket(socket.AF_UNIX)
    try:
        os.unlink(other_sock)
    except OSError:
        pass
    agent_like.bind(other_sock)
    agent_like.listen(1)
    import glob as _glob
    tty = next((t for t in sorted(_glob.glob("/dev/ttys[0-9]*")) if os.stat(t).st_uid == os.getuid() and os.access(t, os.R_OK)), None)
    os.makedirs(os.path.join(STATE, "beam"), exist_ok=True)
    os.makedirs(os.path.join(STATE, "autofill"), exist_ok=True)
    other = os.path.join(STATE, "cache", "data", "dev.example", "default")
    os.makedirs(other, exist_ok=True)
    os.makedirs(os.path.join(STATE, "cache", "data", "dev.example", "beam-0"), exist_ok=True)
    os.makedirs(os.path.join(STATE, "cache", "data", "dev.example.empty"), exist_ok=True)
    for f in (os.path.join(STATE, "beam", "trust.json"), os.path.join(STATE, "autofill", "x.properties"), os.path.join(other, "x")):
        if not os.path.exists(f):
            open(f, "w").write("secret\n")
    # The checkout's own work/ (the state folder unless APKSIM_HOME moves it, as here): planted files, removed below.
    rw = os.path.join(os.path.realpath(os.path.dirname(HERE)), "work")
    planted = [os.path.join(rw, "autofill", "beam-check-probe.properties"),
               os.path.join(rw, "cache", "data", "dev.example.beamcheck", "default", "x")]
    made = [f for f in planted if not os.path.exists(f)]
    autofill_was = os.path.isdir(os.path.join(rw, "autofill"))
    for f in made:
        os.makedirs(os.path.dirname(f), exist_ok=True)
        open(f, "w").write("secret\n")
    probes = [
        ("allowed", "write its own scratch", "touch $S/tmp/$$/probe"),
        ("denied", "read sign-ins saved in the apksim folder's work/ (the state folder elsewhere)", "cat $R/work/autofill/beam-check-probe.properties"),
        ("denied", "read app data in the apksim folder's work/", "cat $R/work/cache/data/dev.example.beamcheck/default/x"),
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
        ("denied", "connect to any other local socket (an ssh-agent's, a container engine's)", f"nc -U -z {other_sock}"),
        ("denied", "signal another of the visitor's processes", f"kill -0 {victim.pid}"),
        ("allowed", "signal itself", "sh -c 'kill -0 $$'"),
        # A terminal of the visitor's (opened, not read): a cold-launched instance shares the agent's terminal session.
        *([("denied", f"open a terminal of the visitor's ({tty})", f"exec 3< {tty}")] if tty else []),  # its own pid: the ( ) the probe runs in is a child
        # Its own sandbox is every warm beamed instance's (each spawns the next standby inside its own): a child stands
        # in for another site's session in it.
        ("denied", "signal a process in its own sandbox (another beamed session)", "sleep 3 & kill -0 $!"),
        ("denied", "read the Mac's clipboard (the pasteboard service)", "pbpaste >/dev/null && [ -n \"$(pbpaste)\" ]"),
        ("allowed", "look a name up through the system resolver", "dscacheutil -q host -a name example.com | grep -q ip_address"),
        # The JDK's dual-stack sockets reach 127.0.0.1 as ::ffff:127.0.0.1, which the loopback rule alone let through.
        ("denied", "connect to loopback as ::ffff:127.0.0.1 (IPv6)", f"nc -6 -z -w 2 ::ffff:127.0.0.1 {lport}"),
        ("denied", "write the temp folder (the launcher's link to its libraries is there)", "touch ${TMPDIR:-/tmp}/beam-check-probe"),
        ("denied", "write apksim's own files (an install outside the home folder)", "touch $R/work/beam-check-probe"),
        ("denied", "read another process's scratch", "cat $S/tmp/1/shared.txt"),
        ("denied", "write /tmp", "touch /private/tmp/beam-check-probe"),
        # A new app's folder may be made (for its beam-N slots), but no app's folder renamed (into its scratch, to read
        # it there) or removed.
        ("allowed", "make a new app's data folder", "mkdir $S/cache/data/dev.example.new$$"),
        ("denied", "rename another app's data folder into its scratch", "mv $S/cache/data/dev.example $S/tmp/$$/stolen"),
        ("denied", "remove another app's data folder", "rmdir $S/cache/data/dev.example.empty"),
        # Only a folder: a link named for an app would lead the agent, which unpacks a moved session's data there,
        # into the home folder.
        ("denied", "make an app's data folder a link (to the home folder)", "ln -s $HOME $S/cache/data/dev.example.link$$"),
        ("denied", "make a file where an app's data folder goes", "touch $S/cache/data/dev.example.file$$"),
        ("allowed", "write a moved session's slot (bm + 12 hex)", "mkdir -p $S/cache/data/dev.example/bm0123456789ab-0 && touch $S/cache/data/dev.example/bm0123456789ab-0/.lock"),
        ("denied", "write a slot named like one but not (bm + 11 hex)", "mkdir -p $S/cache/data/dev.example/bm0123456789a-0"),
    ]
    script = "S=$1; R=$2\n" + "\n".join(
        f"if ( {cmd} ) >/dev/null 2>&1; then echo 'allowed {i}'; else echo 'denied {i}'; fi" for i, (_, _, cmd) in enumerate(probes))
    os.makedirs(os.path.join(STATE, "tmp", "1"), exist_ok=True)
    open(os.path.join(STATE, "tmp", "1", "shared.txt"), "w").write("another instance's file\n")
    env = dict(ENV, APKSIM_BEAM_SANDBOX="1", APKSIM_SANDBOX_TEST=f"sh -c {sh_quote(script)} probe {sh_quote(os.path.realpath(STATE))} "
               f"{sh_quote(os.path.realpath(os.path.dirname(HERE)))}")
    try:
        r = subprocess.run([apksim, apk], capture_output=True, text=True, timeout=60, env=env)
    finally:
        for f in made:
            os.remove(f)
        shutil.rmtree(os.path.join(rw, "cache", "data", "dev.example.beamcheck"), ignore_errors=True)
        if not autofill_was:
            try:
                os.rmdir(os.path.join(rw, "autofill"))  # the check made it
            except OSError:
                pass
    got = dict(line.split(" ", 1)[::-1] for line in r.stdout.split("\n") if line.strip())
    for i, (want, what, _) in enumerate(probes):
        check(got.get(str(i)) == want, f"sandbox: a beamed app may{'' if want == 'allowed' else ' not'} {what} ({got.get(str(i))})")
    # Whatever the agent's environment says: an "already sandboxed" marker (ordinary, or the beam one claimed by hand)
    # or APKSIM_NOCACHE (training) never lets a beamed launch run without the beam profile.
    for extra in ({"APKSIM_SANDBOXED": "1"}, {"APKSIM_SANDBOXED": "beam"}, {"APKSIM_NOCACHE": "1"}):
        e = dict(ENV, APKSIM_BEAM_SANDBOX="1", **extra,
                 APKSIM_SANDBOX_TEST="if ( ls $HOME ) >/dev/null 2>&1; then echo unsandboxed; else echo sandboxed; fi")
        r = subprocess.run([apksim, apk], capture_output=True, text=True, timeout=60, env=e)
        check(r.stdout.strip().endswith("sandboxed") and "unsandboxed" not in r.stdout,
              f"sandbox: a beamed launch with {extra} in its environment still runs in it ({r.stdout.strip()[-20:]})")
    listener.close()
    unix.close()
    os.unlink(standby)
    agent_like.close()
    os.unlink(other_sock)
    victim.kill()


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
    # The Mac clipboard stays the Mac's: a beamed app neither reads what the visitor copies nor writes it (the visitor's
    # own clipboard is put back after).
    saved = subprocess.run(["pbpaste"], capture_output=True).stdout
    marker = f"beam-check-clipboard-{os.getpid()}"
    subprocess.run(["pbcopy"], input=marker.encode())
    time.sleep(1.5)
    app_clip = cmd("clipboard")
    cmd("clipboard set beam-check-from-the-app")
    time.sleep(1)
    mac_clip = subprocess.run(["pbpaste"], capture_output=True).stdout.decode(errors="replace")
    subprocess.run(["pbcopy"], input=saved)
    check(marker not in app_clip and "beam-check-from-the-app" not in mac_clip,
          f"sandbox: a beamed app does not see or set the Mac's clipboard (app has {app_clip[:40]!r})")
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
                p.wait(10)
        subprocess.run([apksim, "--stop-standbys"], env=env, capture_output=True)
        shutil.rmtree(state, ignore_errors=True)


def source_checks(apksim, apk, sha, size):
    """Issues 177 and 178: a page names where its build is (`source`, an artifact store), and the agent downloads it
    only from addresses its owner allowed (`apksim beam config allow-download`), following redirects only within them;
    `/beam/hello` says `asking` to the asking page only while the consent dialog is up (a stand-in that answers "Allow
    Once" after 2 s: APKSIM_BEAM_CONSENT=allow-after-2s, for checks only); `apksim beam install --trust` allows a site."""
    import http.server
    import threading
    state = tempfile.mkdtemp(prefix="beam-check-src-")
    env = dict(os.environ, APKSIM_HOME=state, APKSIM_BEAM_CONSENT="allow-after-2s")
    gp = free_port()
    data = open(apk, "rb").read()

    class Store(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            path = self.path
            if path in ("/builds/app.apk", "/other/app.apk", "/builds-other/app.apk") or path.startswith("/builds/app.apk?") \
                    or path == "/builds/qredirect?sig=new":
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")  # what artifact stores send
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            to = {"/builds/redirect": "/builds/app.apk?sig=abc", "/builds/qredirect?sig=old": "?sig=new", "/builds/out": f"http://127.0.0.1:{self.server.server_address[1]}/other/app.apk"}.get(path)
            if to:
                self.send_response(302)
                self.send_header("Location", to)
                self.end_headers()
                return
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.end_headers()
            self.wfile.write(b"<html>sign in</html>")

        def log_message(self, *a):
            pass
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Store)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    U = f"http://127.0.0.1:{srv.server_address[1]}"
    uploads = os.path.join(state, "beam", "uploads")

    def cfg(*args):
        return subprocess.run([apksim, "beam", "config", *args], capture_output=True, text=True, env=env, timeout=60)

    def start(source, sha256=sha):
        st, _, body = req(gp, "POST", "/beam/start", A, {"sha256": sha256, "name": "beam-check.apk", "size": size, "options": [], "source": source})
        return json.loads(body) if st == 200 else {"status": st, "body": body[:200]}

    def clear():
        for f in glob.glob(os.path.join(uploads, "*")):
            os.remove(f)
    procs = []
    try:
        r = cfg("allow-download", "http://artifacts.example/builds/")
        check(r.returncode == 2, "source: no plain-http prefix but on loopback")
        r = cfg("allow-download", "https://u:p@artifacts.example/")
        check(r.returncode == 2, "source: no prefix with a user in it")
        r = cfg("allow-download", U + "/builds")
        check(r.returncode == 0 and f"downloads from: {U}/builds" in r.stdout, f"source: config allow-download ({r.stdout.strip().splitlines()[-1:]})")
        procs.append(subprocess.Popen([apksim, "beam", "--port", str(gp)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env))
        check(wait_up(gp, "/beam/hello"), "source: an agent of its own")

        # The consent dialog's wait: hello says `asking` to the asking page, and only to it.
        got = {}
        t = threading.Thread(target=lambda: got.update(g=start(U + "/other/app.apk")))
        t.start()
        seen_a = until(lambda: json.loads(req(gp, "GET", "/beam/hello", A)[2]).get("asking") is True, 1.8)
        seen_b = json.loads(req(gp, "GET", "/beam/hello", B)[2]).get("asking")
        t.join(60)
        after = json.loads(req(gp, "GET", "/beam/hello", A)[2]).get("asking")
        check(seen_a and seen_b is False and after is False, f"source: hello says asking while the dialog is up, to that page only ({seen_a}, {seen_b}, {after})")
        g = got.get("g", {})
        check(g.get("need") == "upload" and "not allowed" in g.get("why", "") and not glob.glob(os.path.join(uploads, "*.apk")),
              f"source: not from an address its owner did not allow ({g.get('why')})")
        trust_r = subprocess.run([apksim, "beam", "trust", "allow", A], capture_output=True, text=True, env=env, timeout=60)
        check(trust_r.returncode == 0, "source: the site allowed (no dialog from here on)")

        g = start(U + "/builds/app.apk")
        check(g.get("via") == "source" and "token" in g and os.path.exists(os.path.join(uploads, sha + ".apk")),
              f"source: downloaded from an allowed address, with a store's octet-stream type ({g.get('via')}, {g.get('why')})")
        check(start(U + "/builds/app.apk").get("via") == "cache", "source: found by its hash the next time")
        clear()
        g = start(U + "/builds/redirect")
        check(g.get("via") == "source", f"source: a redirect within the allowed prefix is followed ({g.get('why')})")
        clear()
        g = start(U + "/builds/qredirect?sig=old")
        check(g.get("via") == "source", f"source: a query-only redirect keeps the path ({g.get('why')})")
        clear()
        g = start(U + "/builds/out")
        check(g.get("need") == "upload" and "redirected" in g.get("why", ""), f"source: not a redirect out of it ({g.get('why')})")
        for bad in ("/builds-other/app.apk", "/builds/../other/app.apk", "/builds/%2e%2e/other/app.apk", "/builds/x%2Fy"):
            g = start(U + bad)
            check(g.get("need") == "upload" and "not allowed" in g.get("why", ""), f"source: {bad} is not under the prefix ({g.get('why')})")
        g = start(U + "/builds/login")
        check(g.get("need") == "upload" and "not an APK" in g.get("why", ""), f"source: a sign-in page is not a build ({g.get('why')})")
        g = start(U + "/builds/app.apk", "4" * 64)
        check(g.get("need") == "upload" and "SHA-256" in g.get("why", "") and not os.path.exists(os.path.join(uploads, "4" * 64 + ".apk")),
              f"source: a download whose hash is not the one named is not kept ({g.get('why', '')[:60]})")
        r = cfg("disallow-download", U + "/builds")
        g = start(U + "/builds/app.apk")
        check(r.returncode == 0 and g.get("need") == "upload" and "not allowed" in g.get("why", ""),
              f"source: disallow-download takes effect at once ({g.get('why')})")

        # install --trust: a team's setup pre-allows its dashboard (written, not loaded: --dry-run).
        jobs = os.path.join(state, "jobs")
        r = subprocess.run([apksim, "beam", "install", "--dry-run", jobs, "--trust", "https://dashboard.example/"],
                           capture_output=True, text=True, env=env, timeout=60)
        listed = subprocess.run([apksim, "beam", "trust", "list"], capture_output=True, text=True, env=env, timeout=60).stdout
        check(r.returncode == 0 and "always\thttps://dashboard.example" in listed, f"install --trust allows the site ({r.stdout.strip()[:80]})")
        r = subprocess.run([apksim, "beam", "install", "--dry-run", jobs, "--trust", "https://dashboard.example/path"],
                           capture_output=True, text=True, env=env, timeout=60)
        check(r.returncode == 2, "install --trust takes an origin only")
    finally:
        srv.shutdown()
        for p in procs:
            p.terminate()
        for p in procs:
            try:
                p.wait(15)
            except subprocess.TimeoutExpired:
                p.kill()
                p.wait(10)
        subprocess.run([apksim, "--stop-standbys"], env=env, capture_output=True)
        shutil.rmtree(state, ignore_errors=True)


def warm_checks(apksim, apk, sha, size):
    """`apksim beam --warm on`: a build's warm-up is a beamed instance, and what it translated and extracted goes into
    that build's cache, as a session's does, so the first session starts from it. A state folder of its own."""
    state = tempfile.mkdtemp(prefix="beam-check-warm-")
    env = dict(os.environ, APKSIM_HOME=state, APKSIM_BEAM_CONSENT="allow")
    gp, origin = free_port(), "http://warm.beam-check.test"
    log = open(os.path.join(state, "agent.log"), "w")
    g = subprocess.Popen([apksim, "beam", "--port", str(gp), "--warm", "on"], stdout=subprocess.DEVNULL, stderr=log, env=env)
    classes = lambda: glob.glob(os.path.join(state, "beam", "cache", sha, "classes", "*", "*.pack"))
    entry = lambda: glob.glob(os.path.join(state, "beam", "cache", sha, "apk", sha + ".apk-*"))
    try:
        check(wait_up(gp, "/beam/hello"), "warm: an agent with --warm on")
        with open(apk, "rb") as f:
            body = f.read()
        grant, sent = begin(gp, origin, {"sha256": sha, "name": "beam-check.apk", "size": size, "options": []}, body)
        check(sent and "token" in grant, "warm: the build is sent and stored")
        warmed = until(lambda: "[serve] warmed" in open(log.name).read() or "warming" in open(log.name).read() and "failed" in open(log.name).read(), 300)
        text = open(log.name).read()
        check(warmed and "[serve] warmed" in text, "warm: the agent warms the build (" + next((l for l in text.splitlines() if "warm" in l and "fail" in l), "warmed") + ")")
        with zipfile.ZipFile(apk) as z:
            conflict = "lib/arm64-v8a/libpagefixturenorelro.so" in z.namelist()
        check(("launching with --move-shared-data" in text) == conflict,
              "warm: the warm-up adds --move-shared-data as a session does" if conflict else "warm: no --move-shared-data")
        check(until(lambda: bool(classes()) and bool(entry()), 10), f"warm: its translated classes and APK cache entry are the build's ({len(classes())} packs, {len(entry())} entries)")
        out = os.path.join(state, "beam", "out")
        check(until(lambda: not os.listdir(out) if os.path.isdir(out) else True, 10), "warm: its out folder is gone")
    finally:
        g.terminate()
        try:
            g.wait(15)
        except subprocess.TimeoutExpired:
            g.kill()
            g.wait(10)
        subprocess.run([apksim, "--stop-standbys"], env=env, capture_output=True)
        shutil.rmtree(state, ignore_errors=True)


def data_checks(apksim, apk, sha, size):
    """Phase 5 (docs/beam.md): a session's data moved from the server to this Mac, in a state folder, server and agent
    of their own. On the test app's slot screen the server's session writes a setting and an /sdcard file; its socket's
    `handoff` packs them (the app quit first) and answers a data ticket; the agent's start with it runs the app in a slot
    it unpacked them into, where the app sees them at its start. Behind a stand-in for single sign-on (issue 173) the
    agent can download neither the APK nor the data: the tab sends both."""
    import http.server
    import threading
    state = tempfile.mkdtemp(prefix="beam-check-data-")
    env = dict(os.environ, APKSIM_HOME=state, APKSIM_BEAM_CONSENT="allow")
    sp, gp = free_port(), free_port()
    S = f"http://127.0.0.1:{sp}"
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
    P = f"http://127.0.0.1:{proxy.server_address[1]}"

    def ws_session(port, path, origin):
        w = WS(f"http://127.0.0.1:{port}", path, {"Origin": origin})
        sess = sc.Session.__new__(sc.Session)
        sess.ws, sess.frames, sess.codec, sess.acking, sess.offset, sess.best_rtt = w, [], "jpeg", True, 0.0, float("inf")
        sess.texts, sess.stats, sess.ack_delay, sess.acks = [], {}, 0, []
        sess.pump(120, until=lambda: len(sess.frames) > 0)
        return sess

    def nodes(st, pid):
        f = os.path.join(st, "tmp", str(pid), "control.port")
        if not os.path.exists(f):
            return ""
        c = socket.create_connection(("127.0.0.1", int(open(f).read().strip())), timeout=30)
        io = c.makefile("rw")
        io.write("nodes\n")
        io.flush()
        out = io.readline()
        c.close()
        return out
    try:
        procs.append(subprocess.Popen([apksim, "serve", "--port", str(sp), "--allow-beam", "--beam-port", str(gp), "--apps", apk],
                                      stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env))
        procs.append(subprocess.Popen([apksim, "beam", "--port", str(gp)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env))
        check(wait_up(sp, "/api/health") and wait_up(gp, "/beam/hello"), "data: a server and an agent of its own")
        app = json.loads(req(sp, "GET", "/api/apps")[2])[0]["id"]
        slot_link = "&open=" + urllib.parse.quote("apksim://slot")
        srv = ws_session(sp, f"/ws?app={app}{slot_link}", S)
        check(len(srv.frames) > 0, "data: a session on the server, on the slot screen")
        srv.pump(3, until=lambda: srv.stats.get("pid", 0) > 0)
        srv.pump(2)
        after = srv.after([f"touch down {WRITE[0]} {WRITE[1]}", f"touch up {WRITE[0]} {WRITE[1]}"], 2)
        check(len(after) > 0, "data: Write on the server's slot screen")
        srv.pump(1.5)
        srv.ws.send("handoff")
        try:
            srv.pump(40, until=lambda: any(t.startswith("h ") for t in srv.texts))
        except (EOFError, OSError):
            pass
        h = next((json.loads(t[2:]) for t in srv.texts if t.startswith("h ")), {})
        check(h.get("ticket", "").startswith("v1.d") and h.get("size", 0) > 0, f"data: handoff answers a data ticket ({h.get('size')} bytes)")
        check(until(lambda: json.loads(req(sp, "GET", "/api/status")[2]).get("sessions") == [], 20), "data: and the server's session ends")
        st, _, body = req(sp, "GET", f"/api/apps/{app}/beam")
        info = json.loads(body)
        start = {"sha256": sha, "name": "beam-check.apk", "size": size, "options": [], "ticket": info.get("ticket"),
                 "url": S + "/api/beam/apk"}
        bad = dict(start, data={"ticket": h.get("ticket", "")[:-2] + "AA", "url": S + "/api/beam/data"})
        check(req(gp, "POST", "/beam/start", S, bad)[0] == 502, "data: an altered data ticket brings nothing")
        st, _, body = req(gp, "POST", "/beam/start", S, dict(start, data={"ticket": h.get("ticket"), "url": S + "/api/beam/data"}))
        g = json.loads(body) if st == 200 else {}
        check(g.get("data") is True and "token" in g, f"data: the agent takes the session with its data ({st})")
        mac = ws_session(gp, f"/ws?app={g.get('app')}&token={g.get('token')}{slot_link}", S)
        mac.pump(3, until=lambda: mac.stats.get("pid", 0) > 0)
        seen = ""
        for _ in range(20):
            seen = nodes(state, mac.stats.get("pid", 0))
            if "at app start" in seen:
                break
            time.sleep(1)
        ok_data = "at app start: setting=written" in seen and "sdcard=written" in seen
        check(ok_data, f"data: on this Mac the app starts with what it wrote on the server ({[x for x in seen.split(';') if 'app start' in x][:1]})")
        if not ok_data:
            for f in sorted(glob.glob(os.path.join(state, "beam", "logs", "*.log")), key=os.path.getmtime)[-1:]:
                print("     --", f, *[l for l in open(f).read().splitlines() if "control" not in l][-25:], sep="\n     ")
        seeded = glob.glob(os.path.join(state, "cache", "data", "*", "bm*-0"))
        check(len(seeded) == 1, f"data: in a slot of its own ({[os.path.basename(x) for x in seeded]})")
        pid = mac.stats.get("pid", 0)
        mac.ws.s.close()
        until(lambda: not alive(pid), 20)
        # A seed slot (bm) goes whole once erased; an empty one with its .lock was the old way.
        check(until(lambda: all(not os.path.isdir(d) or os.listdir(d) in ([], [".lock"]) for d in seeded), 15), "data: which is erased when its session ends")

        # Behind a sign-in the agent cannot pass (issue 173): a protocol 1 page still gets the 502 it understands; in
        # protocol 2 the agent asks the tab for the APK, then for the data, which the tab fetched with its own
        # credentials (here: straight from the server), and the app starts with it.
        for f in glob.glob(os.path.join(state, "beam", "uploads", "*")):
            os.remove(f)  # the agent has no build again
        sso = dict(start, url=P + "/api/beam/apk", data={"ticket": h.get("ticket"), "url": P + "/api/beam/data"})
        st, _, body = req(gp, "POST", "/beam/start", P, sso)
        g = json.loads(body) if st == 200 else {}
        st, _, body = req(gp, "POST", f"/beam/upload?upload={g.get('upload')}", P, open(apk, "rb").read())
        check(g.get("need") == "upload" and st == 502 and b"did not reach" in body,
              f"sign-in: in protocol 1 the data the agent cannot download still refuses the start ({st}, {body[:60]!r})")
        for f in glob.glob(os.path.join(state, "beam", "uploads", "*")):
            os.remove(f)
        st, _, body = req(gp, "POST", "/beam/start", P, dict(sso, protocol=2))
        g = json.loads(body) if st == 200 else {}
        check(g.get("need") == "upload" and "401" in g.get("why", ""), f"sign-in: in protocol 2 the agent asks the tab for the APK ({g.get('why')})")
        st, _, body = req(gp, "POST", f"/beam/upload?upload={g.get('upload')}", P, open(apk, "rb").read())
        g = json.loads(body) if st == 200 else {}
        check(g.get("need") == "data" and "401" in g.get("dataWhy", "") and "token" not in g,
              f"sign-in: then for the data ({st}, {g.get('dataWhy')})")
        up = g.get("dataUpload", "")
        st, _, pack = req(sp, "GET", f"/api/beam/data?ticket={urllib.parse.quote(h.get('ticket', ''))}")
        check(st == 200 and pack[:2] == b"PK", f"sign-in: the tab fetches the pack ({st}, {len(pack)} bytes)")
        check(req(gp, "POST", f"/beam/upload-data?upload={up}", S, pack)[0] == 403, "sign-in: a data upload grant does not work for another site")
        # That refusal used the grant up (as for the APK's): start again, here with the build already on this Mac.
        st, _, body = req(gp, "POST", "/beam/start", P, dict(sso, protocol=2))
        g = json.loads(body) if st == 200 else {}
        check(g.get("need") == "data" and g.get("via") == "cache", f"sign-in: a start with the build here asks only for the data ({g})")
        st, _, body = req(gp, "POST", f"/beam/upload-data?upload={g.get('dataUpload')}", P, b"not a zip")
        check(st == 400 and b"refused" in body, f"sign-in: a pack that is not one is refused ({body[:70]!r})")
        st, _, body = req(gp, "POST", "/beam/start", P, dict(sso, protocol=2))
        up = json.loads(body).get("dataUpload", "") if st == 200 else ""
        st, _, body = req(gp, "POST", f"/beam/upload-data?upload={up}", P, pack)
        g = json.loads(body) if st == 200 else {}
        check(g.get("data") is True and "token" in g, f"sign-in: the tab's pack starts the session with its data ({st}, {body[:60]!r})")
        check(req(gp, "POST", f"/beam/upload-data?upload={up}", P, pack)[0] == 403, "sign-in: a data upload grant is used once")
        mac = ws_session(gp, f"/ws?app={g.get('app')}&token={g.get('token')}{slot_link}", P)
        mac.pump(3, until=lambda: mac.stats.get("pid", 0) > 0)
        seen = ""
        for _ in range(20):
            seen = nodes(state, mac.stats.get("pid", 0))
            if "at app start" in seen:
                break
            time.sleep(1)
        check("at app start: setting=written" in seen and "sdcard=written" in seen,
              f"sign-in: on this Mac the app starts with the data the tab sent ({[x for x in seen.split(';') if 'app start' in x][:1]})")
        pid = mac.stats.get("pid", 0)
        mac.ws.s.close()
        until(lambda: not alive(pid), 20)

        # A move the Mac cannot take after the hand-off: the tab opens the server's session again with the hand-off's
        # ticket, and the app starts there with what it had saved; the pack is used once.
        srv = ws_session(sp, f"/ws?app={app}{slot_link}", S)
        srv.pump(3, until=lambda: srv.stats.get("pid", 0) > 0)
        srv.pump(2)
        srv.after([f"touch down {WRITE[0]} {WRITE[1]}", f"touch up {WRITE[0]} {WRITE[1]}"], 2)
        srv.pump(1.5)
        srv.ws.send("handoff")
        try:
            srv.pump(40, until=lambda: any(t.startswith("h ") for t in srv.texts))
        except (EOFError, OSError):
            pass
        h2 = next((json.loads(t[2:]) for t in srv.texts if t.startswith("h ")), {})
        check("ticket" in h2, "restore: a second session hands off its data")
        until(lambda: json.loads(req(sp, "GET", "/api/status")[2]).get("sessions") == [], 20)
        back = ws_session(sp, f"/ws?app={app}{slot_link}&restore={urllib.parse.quote(h2.get('ticket', ''))}", S)
        back.pump(3, until=lambda: back.stats.get("pid", 0) > 0)
        said = [t for t in back.texts if t.startswith("r ")]
        check(any("back on the server" in t for t in said), f"restore: the server says its data is back ({said[:1]})")
        seen = ""
        for _ in range(20):
            seen = nodes(state, back.stats.get("pid", 0))
            if "at app start" in seen:
                break
            time.sleep(1)
        check("at app start: setting=written" in seen and "sdcard=written" in seen,
              f"restore: on the server again the app starts with what it had saved ({[x for x in seen.split(';') if 'app start' in x][:1]})")
        back.ws.s.close()
        until(lambda: json.loads(req(sp, "GET", "/api/status")[2]).get("sessions") == [], 20)
        again = ws_session(sp, f"/ws?app={app}{slot_link}&restore={urllib.parse.quote(h2.get('ticket', ''))}", S)
        said = [t for t in again.texts if t.startswith("r ")]
        check(any("could not restore" in t and "gone" in t for t in said), f"restore: a pack is used once ({said[:1]})")
        again.ws.s.close()
    finally:
        proxy.shutdown()
        for p in procs:
            p.terminate()
        for p in procs:
            try:
                p.wait(15)
            except subprocess.TimeoutExpired:
                p.kill()
                p.wait(10)
        subprocess.run([apksim, "--stop-standbys"], env=env, capture_output=True)
        shutil.rmtree(state, ignore_errors=True)


def slot_nodes(pid):
    """The test app's slot screen as `nodes` reads it, from an instance of the main state folder."""
    f = os.path.join(STATE, "tmp", str(pid), "control.port")
    for _ in range(20):
        if os.path.exists(f):
            try:
                c = socket.create_connection(("127.0.0.1", int(open(f).read().strip())), timeout=30)
                io = c.makefile("rw")
                io.write("nodes\n")
                io.flush()
                out = io.readline()
                c.close()
                if "at app start" in out:
                    return out
            except (OSError, ValueError):
                pass
        time.sleep(1)
    return ""


def control(pid, cmd):
    """One command to an instance of the main state folder over its control port; its answer."""
    f = os.path.join(STATE, "tmp", str(pid), "control.port")
    try:
        c = socket.create_connection(("127.0.0.1", int(open(f).read().strip())), timeout=30)
        io = c.makefile("rw")
        io.write(cmd + "\n")
        io.flush()
        out = io.readline()
        c.close()
        return out
    except (OSError, ValueError) as e:
        return f"error: {e}"


def at_start(nodes):
    return [x for x in nodes.split(";") if "app start" in x][:1]


def with_comment(apk_bytes):
    """The APK with a ZIP comment added after it was signed: another SHA-256, the same certificate in its signing block,
    but contents that are not what was signed (v2 and v3 cover the end record)."""
    import struct
    eocd = apk_bytes.rfind(b"PK\x05\x06")
    assert eocd >= 0 and len(apk_bytes) == eocd + 22, "the APK has a ZIP comment already"
    c = b"beam-check: not what was signed"
    return apk_bytes[:eocd + 20] + struct.pack("<H", len(c)) + c


class Signer:
    """Builds of the test app signed with keys of beam-check's own (apksigner, keytool and zipalign from the Android SDK's
    build tools); `ok` is False when they are not here."""

    def __init__(self):
        sdk = os.environ.get("ANDROID_HOME") or os.path.expanduser("~/Library/Android/sdk")
        tools = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", "apksigner")))
        self.apksigner = tools[-1] if tools else None
        self.zipalign = os.path.join(os.path.dirname(tools[-1]), "zipalign") if tools else None
        self.keytool = shutil.which("keytool")
        self.dir = tempfile.mkdtemp(prefix="beam-check-sign-")
        self.ok = bool(self.apksigner and self.keytool)

    def key(self, name):
        ks = os.path.join(self.dir, name + ".jks")
        if not os.path.exists(ks):
            subprocess.run([self.keytool, "-genkeypair", "-keystore", ks, "-storepass", "beamcheck", "-keypass", "beamcheck",
                            "-alias", "k", "-keyalg", "RSA", "-keysize", "2048", "-validity", "100", "-dname", "CN=beam-check " + name],
                           capture_output=True)
        return ks

    def build(self, apk_path, key, extra=None):
        """The test APK (plus an asset `extra` names, as a newer build would differ) signed with `key`; its bytes."""
        src = apk_path
        if extra:
            src = os.path.join(self.dir, f"{extra}-unsigned.apk")
            with zipfile.ZipFile(apk_path) as zin, zipfile.ZipFile(src, "w") as zout:
                for info in zin.infolist():
                    zout.writestr(info, zin.read(info.filename), compress_type=info.compress_type)
                zout.writestr("assets/beam-check-" + extra + ".txt", extra)
            if self.zipalign and os.path.exists(self.zipalign):
                aligned = src + ".aligned"
                subprocess.run([self.zipalign, "-f", "-P", "16", "4", src, aligned], capture_output=True)
                if os.path.exists(aligned):
                    src = aligned
        out = os.path.join(self.dir, f"{extra or 'base'}-{os.path.basename(key)}.apk")
        r = subprocess.run([self.apksigner, "sign", "--ks", self.key(key), "--ks-pass", "pass:beamcheck", "--out", out, src],
                           capture_output=True, text=True)
        if not os.path.exists(out):
            raise RuntimeError("apksigner: " + r.stderr[-500:])
        with open(out, "rb") as f:
            return f.read()

    def close(self):
        shutil.rmtree(self.dir, ignore_errors=True)


def saved_tab_checks(apksim, gp, apk, apk_path, sha, size, sid, slot_start):
    """Issue 181: a saved session reopened from its own site's tab (on a copy of its data, written back when it ends),
    listed to that site only, and a newer build of the same app started on a copy of it, which a build signed by another
    key may not."""
    g = agent(apksim, gp, "allow")
    try:
        st, _, body = req(gp, "GET", "/beam/saved", B)
        listed = json.loads(body) if st == 200 else []
        mine = [x for x in listed if x.get("id") == sid]
        check(len(mine) == 1 and mine[0].get("package") == PKG and mine[0].get("build") is True and mine[0].get("open") is False
              and mine[0].get("sha256") == sha, f"saved: GET /beam/saved lists it to its site ({listed})")
        st, _, body = req(gp, "GET", "/beam/saved", A)
        check(st == 200 and sid not in body.decode(), "saved: and not to another site")
        st, _, body = req(gp, "POST", "/beam/start", A, {"protocol": 2, "saved": sid})
        check(st == 404, f"saved: another site cannot reopen it ({st})")
        check(req(gp, "POST", "/beam/start", B, {"protocol": 2, "saved": "zz"})[0] == 400, "saved: an id that is not one is refused")

        # Its /sdcard file removed, to see the reopened session's Write come back into the saved slot.
        sd_file = os.path.join(STATE, "cache", "data", PKG, f"k{sid}-0.device", "sdcard", "apksim-slot-check.txt")
        check(os.path.exists(sd_file), "saved: its /sdcard file is in the saved slot's .device folder")
        if os.path.exists(sd_file):
            os.remove(sd_file)
        st, _, body = req(gp, "POST", "/beam/start", B, {"protocol": 2, "saved": sid})
        gr = json.loads(body) if st == 200 else {}
        check(gr.get("saved") == sid and gr.get("data") is True and gr.get("via") == "cache" and "token" in gr,
              f"saved: its site reopens it with no build named (its own) ({st}, {body[:100]!r})")
        st, _, body = req(gp, "POST", "/beam/start", B, {"protocol": 2, "saved": sid})
        check(st == 409 and b"open" in body, f"saved: not twice at once ({st}, {body[:80]!r})")
        st, _, body = req(gp, "GET", "/beam/saved", B)
        check(any(x.get("id") == sid and x.get("open") for x in (json.loads(body) if st == 200 else [])), "saved: listed as open meanwhile")
        s = open_session(gp, gr, B)
        check(len(s.frames) > 0 and session_info(s).get("keep") is True, "saved: the reopened session runs, and says it is kept")
        seen = slot_nodes(s.stats.get("pid", 0))
        check("at app start: setting=written" in seen and "sdcard=missing" in seen,
              f"saved: the app starts with the saved data, on its saved screen ({at_start(seen)})")
        copies = glob.glob(os.path.join(STATE, "cache", "data", PKG, "bm*-0"))
        check(len(copies) == 1, "saved: it runs on a copy (the beam sandbox reaches no saved slot)")
        s.ws.send("keep off")
        s.pump(3, until=lambda: "k on" in s.texts)
        check("k on" in s.texts, "saved: a page cannot turn keeping off for a saved session")
        # By its text: with saved values the screen's lines wrap, so Write is lower than on a fresh one.
        r = control(s.stats.get("pid", 0), "tap-text Write")
        s.pump(1.5)
        check("tapped" in r, f"saved: Write on the slot screen ({r.strip()[:60]})")
        s.pump(1.5)
        check(close_session(s), "saved: the reopened session ends")
        check(until(lambda: os.path.exists(sd_file) and not glob.glob(os.path.join(STATE, "cache", "data", PKG, "bm*")), 15),
              "saved: what it wrote is back in the saved slot, and the copy is gone")
        check(saved_ids(apksim) == [sid], f"saved: still one saved session, the same ({saved_ids(apksim)})")

        # `apksim beam run` opening it while a tab has it open: the tab's copy, made before, is not put back over what
        # the run may have written, but kept as a new saved session.
        st, _, body = req(gp, "POST", "/beam/start", B, {"protocol": 2, "saved": sid})
        gr = json.loads(body) if st == 200 else {}
        s = open_session(gp, gr, B)
        cp = gp + 5
        rp = subprocess.Popen([apksim, "beam", "run", sid, "--headless", "--control", str(cp)], env=ENV,
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        ran = False
        for _ in range(60):
            r = subprocess.run([sys.executable, os.path.join(HERE, "drive.py"), str(cp), "wait 1000", "nodes"], capture_output=True, text=True, timeout=60)
            if "at app start" in r.stdout:
                ran = True
                break
            time.sleep(1)
        subprocess.run([sys.executable, os.path.join(HERE, "drive.py"), str(cp), "quit"], capture_output=True, text=True, timeout=30)
        try:
            rp.wait(30)
        except subprocess.TimeoutExpired:
            rp.kill()
        check(ran and rp.returncode == 0, "saved: apksim beam run opens it while the tab has it open")
        check(close_session(s), "saved: then the tab's session ends")
        check(until(lambda: len(saved_ids(apksim)) == 2, 10),
              f"saved: its copy is kept as a new saved session, not put over what the run may have written ({saved_ids(apksim)})")
        for i in saved_ids(apksim):
            if i != sid:
                beam(apksim, "forget", i)
        check(saved_ids(apksim) == [sid] and os.path.exists(sd_file), "saved: the saved session itself is as the run left it")

        # A newer build of the same app on a copy of saved data: builds signed here with a key of beam-check's own.
        sg = Signer()
        if not sg.ok:
            print("  -- dataFrom: no apksigner and keytool here: newer builds not checked")
            return
        try:
            dataFrom_checks(apksim, gp, apk_path, sid, slot_start, sd_file, sg)
        finally:
            sg.close()
    finally:
        stop(g)


def dataFrom_checks(apksim, gp, apk_path, sid, slot_start, sd_file, sg):
    """`dataFrom` (issue 181): build 1 signed with key K is saved after a Write; build 2 (one asset more, K) starts on a
    copy of it; build 2 altered after signing, build 2 signed with another key, and build 2 on the debug-signed saved
    session `sid` are refused; the saved sessions stay as they were."""
    b1, b2 = sg.build(apk_path, "k"), sg.build(apk_path, "k", "next")
    other, tampered = sg.build(apk_path, "other", "next"), with_comment(b2)

    def start_with(b, **kw):
        """The grant for a start of build `b` (sending it when asked): (status, answer)."""
        st, _, body = req(gp, "POST", "/beam/start", B, dict(slot_start, sha256=hashlib.sha256(b).hexdigest(), size=len(b),
                                                             ticket=None, url=None, protocol=2, **kw))
        g = json.loads(body) if st == 200 else {}
        if g.get("need") == "upload":
            st, _, body = req(gp, "POST", f"/beam/upload?upload={g.get('upload')}", B, b)
        return st, (json.loads(body) if st == 200 else {"error": body[:110]})

    st, g1 = start_with(b1)
    s = open_session(gp, g1, B)
    s.ws.send("keep on")
    s.pump(3, until=lambda: "k on" in s.texts)
    r = control(s.stats.get("pid", 0), "tap-text Write")
    s.pump(1.5)
    check("tapped" in r and close_session(s), "dataFrom: build 1, signed here, is saved after a Write")
    check(until(lambda: len(saved_ids(apksim)) == 2, 10), "dataFrom: two saved sessions now")
    s1 = next((i for i in saved_ids(apksim) if i != sid), "none")

    # An upload grant for a dataFrom start made before a tab reopens the same saved session: its upload then is refused,
    # and the reopened session holds its saved session (kept, not a copy nothing puts back).
    st, _, body = req(gp, "POST", "/beam/start", B, dict(slot_start, sha256=hashlib.sha256(b2).hexdigest(), size=len(b2),
                                                         ticket=None, url=None, protocol=2, dataFrom=s1))
    pending = json.loads(body).get("upload") if st == 200 else None
    st, _, body = req(gp, "POST", "/beam/start", B, {"protocol": 2, "saved": s1})
    gr = json.loads(body) if st == 200 else {}
    s = open_session(gp, gr, B)
    check(len(s.frames) > 0 and session_info(s).get("keep") is True, "dataFrom: build 1's saved session reopened in a tab")
    st, _, body = req(gp, "POST", f"/beam/upload?upload={pending}", B, b2)
    check(st == 409 and b"open in another tab" in body, f"dataFrom: an upload granted before that reopen is refused meanwhile ({st}, {body[:80]!r})")
    check(close_session(s), "dataFrom: the reopened session ends")
    # Until /beam/saved no longer says it is open (its end puts the copy back after its instance has gone).
    def closed():
        st, _, body = req(gp, "GET", "/beam/saved", B)
        return st == 200 and any(x.get("id") == s1 and x.get("open") is False for x in json.loads(body))
    check(until(closed, 20) and sorted(saved_ids(apksim)) == sorted([sid, s1]), "dataFrom: and is put back, not kept as a new one")

    check(req(gp, "POST", "/beam/start", A, dict(slot_start, sha256=hashlib.sha256(b2).hexdigest(), protocol=2, dataFrom=s1))[0] == 404,
          "dataFrom: not from another site")
    st, _, body = req(gp, "POST", "/beam/start", B, dict(slot_start, sha256=hashlib.sha256(b2).hexdigest(), protocol=2, dataFrom=s1, saved=s1))
    check(st == 400, "dataFrom: not with saved too")
    st, _, body = req(gp, "POST", "/beam/start", B, dict(slot_start, sha256=hashlib.sha256(b2).hexdigest(), protocol=2, saved=s1))
    check(st == 400 and b"dataFrom" in body, f"saved: a saved session is not reopened with another build ({body[:90]!r})")
    st, gn = start_with(b2, dataFrom=s1)
    check(gn.get("dataFrom") == s1 and gn.get("data") is True and "token" in gn,
          f"dataFrom: build 2 (sent by the tab, same package and key) starts on a copy of it ({st}, {gn})")
    s = open_session(gp, gn, B)
    seen = slot_nodes(s.stats.get("pid", 0))
    check("at app start: setting=written" in seen and "sdcard=written" in seen,
          f"dataFrom: build 2 starts with the saved data ({at_start(seen)})")
    check(session_info(s).get("keep") is False, "dataFrom: the session itself is not kept unless asked")
    check(close_session(s), "dataFrom: its session ends")
    # Erased as any session's slot is: emptied but for its .lock, the folder itself left to the sweep.
    check(until(lambda: all(not os.path.isdir(d) or os.listdir(d) in ([], [".lock"])
                            for d in glob.glob(os.path.join(STATE, "cache", "data", PKG, "bm*"))), 15)
          and sorted(saved_ids(apksim)) == sorted([sid, s1]), "dataFrom: its copy is erased, the saved sessions left as they were")
    st, g = start_with(tampered, dataFrom=s1)
    check(st == 409 and "signed" in str(g.get("error")), f"dataFrom: build 2 altered after signing (its certificate kept) is refused ({st}, {g})")
    st, g = start_with(other, dataFrom=s1)
    check(st == 409 and b"another key" in g.get("error", b""), f"dataFrom: build 2 signed by another key is refused ({st}, {g})")
    st, g = start_with(b2, dataFrom=sid)
    check(st == 409 and b"another key" in g.get("error", b""), f"dataFrom: and so is build 2 on the debug-signed saved session ({st}, {g})")
    check(os.path.exists(sd_file), "dataFrom: the saved session is left as it was")
    beam(apksim, "forget", s1)


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
        p.wait(10)  # ended before anything removes its state folder


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
    source_checks(apksim, a.apk, sha, size)
    warm_checks(apksim, a.apk, sha, size)
    data_checks(apksim, a.apk, sha, size)
    r = beam(apksim, "status")
    check(r.returncode == 0 and "agent: not running" in r.stdout, f"status: no agent yet ({r.stdout.splitlines()[:1]})")
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
        check(st == 200 and hello.get("protocol") == 3 and hello.get("trusted") is False, f"agent: hello ({body!r})")
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
        check(hello.get("minProtocol") == 1, f"agent: hello says the range of protocols it speaks (1-{hello.get('protocol')})")
        check(isinstance(hello.get("apksim"), str) and "--broadcast" in hello.get("options", []) and "--skin" in hello.get("options", [])
              and "--jvm" not in hello.get("options", []), f"agent: hello lists the launch options it accepts ({hello.get('options')})")
        # `apksim beam stop` (issue 309): the agent ends by itself (a local tool only: a page's POST is refused).
        st, _, _ = req(gp, "POST", "/beam/stop", A, {})
        check(st != 200, f"agent: a page cannot stop it ({st})")
        out = beam(apksim, "stop")
        check(out.returncode == 0 and "stopping" in out.stdout and until(lambda: g.poll() is not None, 5),
              f"agent: apksim beam stop ends it ({out.stdout.strip()!r} {out.stderr.strip()[-200:]!r})")
        g = agent(apksim, gp, "deny")

        # Versions (docs/beam.md, "Versions"): a page names the protocol it uses; one the agent does not speak is
        # refused with which side to update.
        st, _, body = req(gp, "POST", "/beam/start", A, dict(start, protocol=99))
        check(st == 409 and b"brew upgrade apksim" in body, f"agent: a newer page's protocol: update this Mac's apksim ({body[:90]!r})")
        st, _, body = req(gp, "POST", "/beam/start", A, dict(start, protocol=0))
        check(st == 409 and b"older" in body, f"agent: an older page's protocol: the site's apksim is older ({body[:90]!r})")

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
        w = WS(f"http://127.0.0.1:{gp}", f"/ws?app={grant['app']}&token={grant['token']}&protocol=99", {"Origin": A})
        check(w.status == 409, "agent: a session in a protocol it does not speak is refused")
        w = WS(f"http://127.0.0.1:{gp}", f"/ws?app={grant['app']}&token={grant['token']}&protocol=1", {"Origin": A})
        check(w.status == 101, "agent: the session opens with its grant (in protocol 1; that refusal left the grant usable)")
        s = sc.Session.__new__(sc.Session)
        s.ws, s.frames, s.codec, s.acking, s.offset, s.best_rtt = w, [], "jpeg", True, 0.0, float("inf")
        s.texts, s.stats, s.ack_delay, s.acks = [], {}, 0, []
        t0 = time.time()
        s.pump(120, until=lambda: len(s.frames) > 0)
        check(len(s.frames) > 0, f"agent: frames arrive ({time.time() - t0:.2f} s to the first)")
        # A build with a 4 KB-aligned library macOS cannot map as it is (the test app's libpagefixturenorelro.so) is
        # launched with --move-shared-data by the agent itself (issue 171); one without is not.
        with zipfile.ZipFile(a.apk) as z:
            conflict = "lib/arm64-v8a/libpagefixturenorelro.so" in z.namelist()
        said = "launching with --move-shared-data" in open(g.logfile).read()
        check(said == conflict, "agent: --move-shared-data added for a build with a 4 KB-aligned library"
              if conflict else "agent: no --move-shared-data for a build without a 4 KB-aligned library")
        s.pump(1.5)
        after = s.after(["touch down 733 1533", "touch up 733 1533"], 2)
        check(len(after) > 0, "agent: a tap changes the screen")
        s.pump(3, until=lambda: s.stats.get("pid", 0) > 0)
        pid = s.stats.get("pid", 0)
        live_sandbox_checks(pid)
        r = beam(apksim, "status")
        check(r.returncode == 0 and f"running on 127.0.0.1:{gp}, 1 of" in r.stdout and "beam-check.apk" in r.stdout and A in r.stdout,
              "status: the agent's session, its app and site " + repr(r.stdout.splitlines()[:2]))
        check(req(gp, "GET", "/beam/status", A)[0] != 200, "status: a page cannot read it")
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
        i1 = session_info(s1)
        check(i1.get("rec") is True and i1.get("recChannels") == "input" and i1.get("inspect") is False,
              f"report: a session says its recorder is on with the owner's channels, and the inspector off ({i1})")
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

        # Issue 311: the network inspector is the owner's to turn on; the recorder's channels are the owner's too.
        r = beam(apksim, "config", "inspect-network", "on", "rec", "input,logcat")
        check(r.returncode == 0 and "inspect-network on" in r.stdout and "rec input,logcat" in r.stdout, f"inspect: config inspect-network on ({r.stdout.strip()!r})")
        g = agent(apksim, gp, "allow")
        gi, _ = begin(gp, B, start, apk)
        si = open_session(gp, gi, B)
        ii = session_info(si)
        check(ii.get("inspect") is True and ii.get("rec") is True and ii.get("recChannels") == "input,logcat",
              f"inspect: with it on the session says so, and the owner's recorder channels ({ii})")
        si.ws.send("tap 411 800")
        si.pump(5, until=lambda: any(t.startswith("n ") for t in si.texts))
        check(not any(t.startswith("r refused: inspect") for t in si.texts), "inspect: no refusal")
        si.ws.send("inspect rule list")
        si.pump(5, until=lambda: any(t.startswith("n r ") for t in si.texts))
        check(any(t.startswith("n r rule ") for t in si.texts), f"inspect: the tab's inspect rule list is answered ({[t[:60] for t in si.texts if t.startswith('n ')]})")
        check(close_session(si), "inspect: that session ends")
        stop(g)
        r = beam(apksim, "config", "inspect-network", "off", "rec", "input")
        check("inspect-network off" in r.stdout, "inspect: and off again")
        g = agent(apksim, gp, "allow", "--keep-days", "1")
        g3, sent = begin(gp, B, start, apk)
        check(sent and "token" in g3, "keep: a deleted build is asked for again")
        s3 = open_session(gp, g3, B)
        check(close_session(s3), "keep: a session with keep-days 1 ends")
        time.sleep(2)
        check(os.path.exists(build_file(sha)), "keep: with a nonzero hold the build stays after its session")
        g4, sent = begin(gp, B, start, apk)
        check(not sent and "token" in g4, "keep: and a session within the hold finds it by its hash")
        # By now the agent's beam standby is up (it starts one a second after each first frame): a warm launch, in the
        # beam sandbox, whose standby could bind its socket there.
        time.sleep(4)
        s4 = open_session(gp, g4, B)
        log4 = os.path.join(STATE, "beam", "logs", session_info(s4).get("id", "") + ".log")
        check(len(s4.frames) > 0 and os.path.exists(log4) and "[apksim] handoff" in open(log4).read(),
              "keep: a beamed session is a warm launch (the agent's standby, in the beam sandbox)")
        # A JVM crash: its report is written in its scratch (the only place it may write) and summarised for the tab.
        s4.pump(3, until=lambda: s4.stats.get("pid", 0) > 0)
        os.kill(int(s4.stats["pid"]), signal.SIGSEGV)
        try:
            s4.pump(20, until=lambda: any(t.startswith("x ") for t in s4.texts))
        except (EOFError, OSError):
            pass
        ended = [t for t in s4.texts if t.startswith("x ")]
        check(ended and "SIGSEGV" in ended[0], f"keep: a beamed JVM's crash report reaches the tab ({ended[:1]})")
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
        s5.ws.send("appearance dark")  # a setting changed in the tab: the record keeps what the session ended with
        s5.pump(1.5)
        check(close_session(s5), "keep: the kept session ends")
        check(until(lambda: len(saved_ids(apksim)) == 1, 10), "keep: apksim beam saved lists it")
        ids = saved_ids(apksim)
        sid = ids[0] if ids else "none"
        check(os.path.isdir(os.path.join(STATE, "cache", "data", PKG, f"k{sid}-0")), f"keep: its slot is renamed to k{sid}-0")
        rec = json.load(open(os.path.join(STATE, "beam", "saved", sid + ".json"))) if ids else {}
        check(rec.get("appearance") == "dark", f"keep: the record has the appearance the tab last set ({rec.get('appearance')})")
        time.sleep(2)
        check(os.path.exists(build_file(sha)), "keep: a saved session's build survives the sweep (keep-days 0)")
        stop(g)
        g = None
        r = beam(apksim, "sweep")
        check(r.returncode == 0 and os.path.exists(build_file(sha)), "keep: and apksim beam sweep keeps it too")
        check(len(rec.get("signers", [])) == 1, f"keep: the record says who signed its build ({rec.get('signers')})")
        saved_tab_checks(apksim, gp, apk, a.apk, sha, size, sid, slot_start)

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
        err = rp.stderr.read() if code != -1 else ""
        check(("launching with --move-shared-data" in err) == conflict,
              "keep: run adds --move-shared-data as the tab's session did" if conflict else "keep: run adds no --move-shared-data")

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

        # ---- the tab relay (docs/beam.md "The tab relay", issue 276)
        stop(g)
        g = agent(apksim, gp, "allow")
        st, _, body = req(gp, "GET", "/beam/hello", B)
        check(json.loads(body).get("relay") is True, "relay: hello says this agent has it")
        st, _, body = req(gp, "POST", "/beam/start", B, dict(start, protocol=2, relay=True))
        check(st == 409 and b"protocol 3" in body, f"relay: a start in protocol 2 cannot ask for it ({st} {body[:60]!r})")
        st, _, body = req(gp, "POST", "/beam/start", B, dict(start, protocol=3, relay="yes"))
        check(st == 400, "relay: it takes true or false")
        st, _, body = req(gp, "POST", "/beam/start", B, dict(start, protocol=3, options=["--tab-relay"]))
        check(st in (400, 403), "relay: a page cannot name the launch flag itself")
        # Without it the host stays unresolved: the Relay button's request fails in the app, nothing reaches the tab.
        gr, _ = begin(gp, B, dict(start, protocol=3), apk)
        sr = open_session(gp, gr, B)
        pid = sr.stats.get("pid", 0)
        control(pid, "open apksim://network")
        time.sleep(2)
        control(pid, "tap-text Relay URL")
        sr.pump(4)
        nd = ""
        for _ in range(20):
            nd = control(pid, "nodes")
            if "relay urlconnection: UnknownHostException" in nd or "relay urlconnection: ConnectException" in nd:
                break
            time.sleep(0.5)
        check(not any(t.startswith("y ") for t in sr.texts)
              and ("relay urlconnection: UnknownHostException" in nd or "relay urlconnection: ConnectException" in nd),
              "relay: not asked for, the app's request fails and the tab hears nothing")
        close_session(sr)
        # Asked for: the tab gets `y` messages and answers them.
        gr, _ = begin(gp, B, dict(start, protocol=3, relay=True), apk)
        sr = open_session(gp, gr, B)
        pid = sr.stats.get("pid", 0)
        control(pid, "open apksim://network")
        time.sleep(2)
        seen = []

        def serve_tab(until_n, delay=0.0):
            """Answers the app's requests as the tab does, until it has answered until_n."""
            end = time.time() + 30
            while len(seen) < until_n and time.time() < end:
                sr.pump(0.3)
                for t in list(sr.texts):
                    if t.startswith("y ") and t not in seen:
                        seen.append(t)
                        _, i, rest = t.split(" ", 2)
                        r = json.loads(rest)
                        body = json.dumps({"path": r["path"], "method": r["method"],
                                           "got": base64.b64decode(r["body"]).decode(),
                                           "from": r["headers"].get("X-From")}).encode()
                        time.sleep(delay)
                        sr.ws.send("y %s %s" % (i, json.dumps({"status": 200, "headers": {"X-Relay": "tab"},
                                                                 "body": base64.b64encode(body).decode()})))

        control(pid, "tap-text Relay URL")
        serve_tab(1)
        nd = ""
        for _ in range(20):
            sr.pump(0.3)
            nd = control(pid, "nodes")
            if "relay urlconnection: 200" in nd:
                break
        check("relay urlconnection: 200" in nd and "/relay/url" in nd and "[tab]" in nd,
              "relay: HttpURLConnection's GET reaches the tab and its answer comes back")
        control(pid, "tap-text Relay")
        serve_tab(2)
        for _ in range(20):
            sr.pump(0.3)
            nd = control(pid, "nodes")
            if "relay okhttp: 200" in nd:
                break
        check("relay okhttp: 200" in nd and "POST" in nd and "[tab]" in nd, "relay: OkHttp's POST (a body each way) reaches the tab")
        first = json.loads(seen[0].split(" ", 2)[2])
        check(first["method"] == "GET" and first["path"] == "/relay/url" and first["headers"].get("X-From") == "urlconnection"
              and "Host" not in first["headers"], f"relay: the request the tab gets ({first['method']} {first['path']})")
        # A `y` the tab invents is not passed to the app: no pending request, nothing to answer.
        sr.ws.send('y 999999 {"status":200,"headers":{},"body":""}')
        sr.ws.send("y 1 not json")
        sr.pump(1)
        check(alive(pid), "relay: answers to requests nobody asked, or that are not JSON, change nothing")
        check(close_session(sr), "relay: that session ends")
    finally:
        if g:
            stop(g)
        stop(server)
        subprocess.run([apksim, "--stop-standbys"], env=ENV, capture_output=True)
        if sc.FAILED or sys.exc_info()[0] is not None:
            print(f"state folder kept for a look (logs in beam/logs): {STATE}")
        else:
            shutil.rmtree(STATE, ignore_errors=True)
    if sc.FAILED:
        print(f"{len(sc.FAILED)} check(s) failed")
        sys.exit(1)
    print("beam check passed")


if __name__ == "__main__":
    main()
