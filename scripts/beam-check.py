#!/usr/bin/env python3
"""Checks `apksim beam` (docs/beam.md) against a server that offers it, both started here on loopback.

    scripts/beam-check.py APK [--apksim ./apksim] [--server-port 47480] [--agent-port 47481]

Acts as the page does (each request with its page's Origin): the server's /api/beam and an app's SHA-256, size and
bytes; the agent's /beam/hello, a site that is not allowed refused, `apksim beam trust allow` letting it in, an
upload whose hash is not the one named refused, the APK sent once and found by its hash the second time, grants used
once and only by their own origin, a session that streams frames, takes a tap and ends with its tab, and `revoke`
refusing the site again. A second agent answers the prompt with "Always Allow" (`APKSIM_BEAM_CONSENT=allow`, for
checks only) and keeps that, and a site saved as refused stays refused. Uses its own made-up origins and forgets them
at the end; the agent's other trusted sites are left as they were.
"""
import argparse
import hashlib
import http.client
import importlib.util
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("servecheck", os.path.join(HERE, "serve-check.py"))
sc = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sc)
check, WS = sc.check, sc.WS

A, B = "https://beam-check-a.example", "https://beam-check-b.example"


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
    return subprocess.run([apksim, "beam", "trust", *args], capture_output=True, text=True)


def agent(apksim, port, consent):
    env = dict(os.environ, APKSIM_BEAM_CONSENT=consent)
    p = subprocess.Popen([apksim, "beam", "--port", str(port)], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
    if not wait_up(port, "/beam/hello"):
        p.kill()
        sys.exit("the agent did not come up: " + (p.stderr.read() if p.stderr else ""))
    return p


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
    for o in (A, B):
        trust(apksim, "revoke", o)

    # An agent refuses anything but loopback.
    r = subprocess.run([apksim, "beam", "--bind", "0.0.0.0"], capture_output=True, text=True, timeout=30)
    check(r.returncode == 2, "apksim beam refuses --bind (loopback only)")

    server = subprocess.Popen([apksim, "serve", "--port", str(sp), "--allow-beam", "--beam-port", str(gp), "--apps", a.apk],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
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
    finally:
        if g:
            stop(g)
        stop(server)
        for o in (A, B):
            trust(apksim, "revoke", o)
    if sc.FAILED:
        print(f"{len(sc.FAILED)} check(s) failed")
        sys.exit(1)
    print("beam check passed")


if __name__ == "__main__":
    main()
