#!/usr/bin/env python3
"""Checks shared sessions of a running `apksim serve --allow-share` (docs/serve.md "Shared sessions"), with many viewers.

    scripts/share-check.py URL [--token FILE] [--app ID] [--viewers 200] [--procs N] [--seconds 10]
                           [--codec h264|jpeg] [--slow 4] [--server-max N] [--stall] [--json FILE]
    scripts/share-check.py URL [--token FILE] --off        the server does not share: no key, joins refused

URL is the server (http://localhost:8080), running the test app. One session is started as its tab would (the
"owner"), then:

  - the share link's rules: a wrong key, an ended session and another Origin are refused; the link's page and socket
    need no server token, and open nothing else;
  - one viewer: it gets the screen as it is at once (a still screen too), the same frame bytes as the owner, hears how
    many watch, and everything it sends but acknowledgements is refused (a tap changes nothing);
  - a hidden owner tab (`maxfps 1`) does not freeze a viewer's picture, and counts again once nobody watches;
  - scale: --viewers viewers join at once from --procs processes while the owner's screen animates (the test app's
    Shader tab), --slow of them acknowledging late. Every viewer must get a first frame quickly, a frame rate near the
    owner's, and (H.264) an unbroken chain: after any frame it missed, a keyframe. The owner's own frame rate must not
    fall, and the slow viewers must not cost the others theirs;
  - --server-max N (the server's --max-viewers): the place after the last is refused;
  - --stall: a viewer that acknowledges nothing is dropped after 30 s (adds ~35 s);
  - the session ending tells every viewer, and its link stops working.

Viewers here do not decode: they count and acknowledge frames, as fast as a tab that keeps up. To load a server from
other machines, run this with the same URL from each (each starts its own session), or see docs/serve.md.
Exits 1 on any failure.
"""
import argparse
import importlib.util
import json
import multiprocessing
import os
import selectors
import socket
import struct
import sys
import time
import base64

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("serve_check", os.path.join(HERE, "serve-check.py"))
sc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sc)
check = sc.check


class Viewer(sc.Session):
    """A viewer driven in this process, with serve-check's message pump."""

    def __init__(self, url, sid, key, origin=None, token=None):
        hdr = {"Origin": origin or sc.parts(url)[2]}
        if token:
            hdr["Cookie"] = "apksim_token=" + token
        self.ws = sc.WS(url, f"/ws?join={sid}&key={key}", hdr)
        self.frames, self.codec, self.acking = [], "jpeg", True
        self.offset, self.best_rtt = 0.0, float("inf")
        self.texts, self.stats, self.ack_delay, self.acks = [], {}, 0, []


def said(session, prefix):
    return [t for t in session.texts if t.startswith(prefix)]


def viewers_said(session):
    """The last viewer count a session's socket was told, or None."""
    told = said(session, 'd {"viewers"')
    return json.loads(told[-1][2:])["viewers"] if told else None


def share_said(session):
    """The last share key a session's socket was told by a switch ("" for none), or None."""
    told = said(session, 'd {"share"')
    return json.loads(told[-1][2:])["share"] if told else None


def switch(url, token, owner, sid, key):
    """The server's switch (POST /api/share?allow=on|off) while a session runs: a full turn from the state it is in,
    back to that state. `key` is the session's share key, None where the server does not share. Returns the key now."""
    def post(allow, tok=token):
        status, body = sc.http(url, f"/api/share?allow={allow}", tok, data=b"")
        return status, (json.loads(body) if status == 200 else {})

    def turn_on(old):
        status, state = post("on")
        check(status == 200 and state.get("allow") is True, f"sharing is turned on while the server runs ({status})")
        owner.pump(3, lambda: share_said(owner))
        new = share_said(owner) or ""
        check(len(new) == 32 and new != old, "the running session's tab is told its link's key" + (", a new one" if old else ""))
        if old:
            v = Viewer(url, sid, old)
            check(v.ws.status == 403, f"the link from before sharing was turned off stays dead ({v.ws.status})")
        return new

    def turn_off(k):
        v = Viewer(url, sid, k)
        check(v.ws.status == 101, "a viewer joins the running session")
        v.pump(5, lambda: len(v.frames) > 0)
        check(len(v.frames) > 0, "and gets its screen")
        status, state = post("off")
        check(status == 200 and state.get("allow") is False, f"sharing is turned off while the server runs ({status})")
        v.pump(4, lambda: said(v, "x "))
        check(said(v, "x ") == ["x sharing was turned off"], f"the viewer is told why and dropped ({said(v, 'x ')})")
        owner.pump(3, lambda: share_said(owner) == "")
        check(share_said(owner) == "", "the tab is told it has no link now")
        again = Viewer(url, sid, k)
        check(again.ws.status == 403, f"the link is refused ({again.ws.status})")
        status, _ = sc.http(url, f"/?join={sid}&key={k}")
        check(status == 403, f"and so is its page ({status})")

    if token:
        status, _ = sc.http(url, "/api/share?allow=on", None, data=b"")
        check(status == 401, f"the switch needs the server's token ({status})")
    status, _ = sc.http(url, "/api/share?allow=maybe", token, data=b"")
    check(status == 400, f"and on or off ({status})")
    status, body = sc.http(url, "/api/share", token)
    check(status == 200 and json.loads(body).get("allow") is bool(key), "GET /api/share says whether the server shares")
    if key:
        turn_off(key)
        owner.texts[:] = [t for t in owner.texts if not t.startswith('d {"share"')]
        return turn_on(key)
    new = turn_on("")
    turn_off(new)
    return None


# ---------------------------------------------------------------- the swarm (worker processes)

ACK = None


def masked(text, op=1):
    data = text if isinstance(text, bytes) else text.encode()
    mask = os.urandom(4)
    return bytes([0x80 | op, 0x80 | len(data)]) + mask + bytes(c ^ mask[i & 3] for i, c in enumerate(data))


class Conn:
    __slots__ = ("s", "buf", "mode", "t0", "first", "frames", "bytes", "keys", "first_key", "ts", "iskey", "codec",
                 "due", "closed", "refused", "end_text", "told")

    def __init__(self, s, mode, t0):
        self.s, self.buf, self.mode, self.t0 = s, bytearray(), mode, t0
        self.first, self.frames, self.bytes, self.keys, self.first_key = -1.0, 0, 0, 0, None
        self.ts, self.iskey, self.codec, self.due = [], [], "", []
        self.closed, self.refused, self.end_text, self.told = False, 0, "", 0


def swarm(job):
    """One process's viewers: joins them, reads and acknowledges frames until `until`, returns what each saw."""
    host, port, path, authority, origin, modes, start_at, until, slow_ms = job
    sel = selectors.DefaultSelector()
    conns = []
    ack = masked("ack")
    time.sleep(max(0, start_at - time.time()))
    for mode in modes:
        t0 = time.time()
        try:
            s = socket.create_connection((host, port), timeout=20)
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            key = base64.b64encode(os.urandom(16)).decode()
            s.sendall((f"GET {path} HTTP/1.1\r\nHost: {authority}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                       f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nOrigin: {origin}\r\n\r\n").encode())
            head = b""
            while b"\r\n\r\n" not in head:
                b = s.recv(1)
                if not b:
                    break
                head += b
            c = Conn(s, mode, t0)
            if b" 101 " not in head.split(b"\r\n", 1)[0]:
                c.closed, c.end_text = True, "refused: " + head.split(b"\r\n", 1)[0].decode(errors="replace")
                s.close()
            else:
                s.setblocking(False)
                sel.register(s, selectors.EVENT_READ, c)
            conns.append(c)
        except OSError as e:
            c = Conn(None, mode, t0)
            c.closed, c.end_text = True, "connect: " + str(e)
            conns.append(c)
    while time.time() < until:
        now = time.time()
        for c in conns:  # late acknowledgements that are due
            while c.due and c.due[0] <= now and not c.closed:
                c.due.pop(0)
                try:
                    c.s.send(ack)
                except OSError:
                    pass
        for k, _ in sel.select(0.02):
            c = k.data
            try:
                data = c.s.recv(1 << 18)
            except BlockingIOError:
                continue
            except OSError:
                data = b""
            if not data:
                c.closed = True
                sel.unregister(c.s)
                continue
            c.buf += data
            buf = c.buf
            pos = 0
            while len(buf) - pos >= 2:
                op, n, h = buf[pos] & 0x0f, buf[pos + 1] & 0x7f, 2
                if n == 126:
                    if len(buf) - pos < 4:
                        break
                    n, h = struct.unpack_from(">H", buf, pos + 2)[0], 4
                elif n == 127:
                    if len(buf) - pos < 10:
                        break
                    n, h = struct.unpack_from(">Q", buf, pos + 2)[0], 10
                if len(buf) - pos < h + n:
                    break
                p = pos + h
                if op == 2:
                    t = time.time()
                    if c.first < 0:
                        c.first = t - c.t0
                    key = c.codec != "h264" or (n > 20 and buf[p + 20] == 1)
                    if c.first_key is None:
                        c.first_key = key
                    c.frames += 1
                    c.bytes += n
                    c.keys += key
                    c.ts.append(struct.unpack_from(">q", buf, p + 8)[0])
                    c.iskey.append(key)
                    try:
                        if c.mode == "fast":
                            c.s.send(ack)
                        elif c.mode == "slow":
                            c.due.append(t + slow_ms / 1000)
                    except OSError:
                        pass
                elif op == 1:
                    text = bytes(buf[p:p + n]).decode(errors="replace")
                    if text.startswith("c "):
                        c.codec = text[2:]
                    elif text.startswith("x "):
                        c.end_text = text[2:]
                    elif text.startswith("r refused"):
                        c.refused += 1
                    elif text.startswith('d {"viewers"'):
                        c.told = json.loads(text[2:])["viewers"]
                elif op == 9:
                    try:
                        c.s.send(masked(bytes(buf[p:p + n]), op=0xA))
                    except OSError:
                        pass
                elif op == 8:
                    c.closed = True
                pos = p + n
            del buf[:pos]
    out = []
    for c in conns:
        out.append({"mode": c.mode, "first": c.first, "frames": c.frames, "bytes": c.bytes, "keys": c.keys,
                    "first_key": c.first_key, "ts": c.ts, "iskey": c.iskey, "codec": c.codec, "closed": c.closed,
                    "end": c.end_text, "told": c.told})
        if c.s is not None:
            try:
                c.s.close()
            except OSError:
                pass
    return out


def cpu_seconds(pid):
    """CPU time a process has used so far (ps), or None."""
    if not pid or pid <= 0:
        return None
    try:
        import subprocess
        t = subprocess.run(["ps", "-o", "cputime=", "-p", str(pid)], capture_output=True, text=True).stdout.strip()
        if not t:
            return None
        days, _, rest = t.rpartition("-")
        secs = 0.0
        for part in rest.split(":"):
            secs = secs * 60 + float(part)
        return secs + (int(days) * 86400 if days else 0)
    except (OSError, ValueError):
        return None


def summarize(results, seconds):
    """The Python swarm's raw results as the Java one reports them: each viewer's rate and whether its chain broke."""
    joined = [c for c in results if c["first"] >= 0]
    union = sorted({t for c in joined for t in c["ts"]})
    # The order of frames is that of the viewer that got the most (a frame's time is not it: a picture sent again on
    # request carries the time it was sent, which may be later than the next picture's own).
    ref = max(results, key=lambda c: len(c["ts"]))
    index = {t: i for i, t in enumerate(ref["ts"])}
    lo = union[-1] - seconds * 1e6 if union else 0
    for c in results:
        last, c["broken"] = None, False
        for t, k in zip(c["ts"], c["iskey"]):
            i = index.get(t)
            if i is not None and last is not None and i not in (last, last + 1) and not k:
                c["broken"] = True
            last = i
        c["rate"] = len([t for t in c["ts"] if t >= lo]) / seconds
        del c["ts"], c["iskey"]
    return len([t for t in union if t >= lo]) / seconds


def java_bin():
    """The JDK the build uses (work/jdk.path), else `java` on PATH, else None."""
    import shutil
    for root in (os.path.dirname(HERE), os.environ.get("APKSIM_HOME", "")):
        try:
            j = os.path.join(open(os.path.join(root, "work", "jdk.path")).read().strip(), "bin", "java")
            if os.access(j, os.X_OK):
                return j
        except OSError:
            pass
    return shutil.which("java")


def pct(xs, q):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(len(xs) * q))] if xs else float("nan")


def fps_of(frames, t0, t1):
    return len([f for f in frames if t0 <= f[0] <= t1]) / max(0.001, (t1 - t0) / 1000)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("--token")
    ap.add_argument("--app")
    ap.add_argument("--viewers", type=int, default=200)
    ap.add_argument("--swarm", choices=["auto", "java", "python"], default="auto",
                    help="the viewers' program: share-swarm.java (a thread each; thousands), or this script's own processes")
    ap.add_argument("--procs", type=int, default=max(2, min(12, (os.cpu_count() or 4) // 2)), help="processes of the python swarm")
    ap.add_argument("--seconds", type=float, default=10)
    ap.add_argument("--codec", default="h264", choices=["h264", "jpeg"])
    ap.add_argument("--slow", type=int, default=4, help="viewers that acknowledge each frame --slow-ms late")
    ap.add_argument("--slow-ms", type=int, default=150)
    ap.add_argument("--server-max", type=int, default=0, help="the server's --max-viewers, to check the limit")
    ap.add_argument("--stall", action="store_true", help="also wait for a viewer that never acknowledges to be dropped")
    ap.add_argument("--off", action="store_true", help="the server runs without --allow-share")
    ap.add_argument("--no-switch", action="store_true",
                    help="leave the server's switch alone (turning sharing off drops every session's viewers)")
    ap.add_argument("--server-pid", type=int, default=0, help="the serve process, to report the CPU it used (same machine)")
    ap.add_argument("--json", help="write the measurements here")
    a = ap.parse_args()
    url = a.url.rstrip("/")
    token = open(a.token).read().strip() if a.token else None
    hostport, prefix, origin = sc.parts(url)
    host, port = hostport.rsplit(":", 1)
    authority = origin.split("//", 1)[1]

    app = a.app
    if not app:
        status, body = sc.http(url, "/api/apps", token)
        apps = json.loads(body) if status == 200 else []
        if not apps:
            sys.exit("no app listed on the server (start it with --apps), and no --app")
        app = apps[0]["id"]

    owner = sc.Session(url, token, app, "phone", a.codec)
    if owner.ws.status != 101:
        sys.exit(f"the session's socket was refused: {owner.ws.status}")
    owner.pump(60, lambda: len(owner.frames) > 0)
    check(len(owner.frames) > 0, "the session started and sent a frame")
    info = json.loads(said(owner, "i ")[0][2:])
    sid, key = info["id"], info.get("share")
    measures = {"viewers": a.viewers, "codec": owner.codec}

    if a.off:
        check(key is None, "a server without --allow-share gives its sessions no share key")
        v = Viewer(url, sid, "0" * 32)
        check(v.ws.status == 403, f"and refuses a join ({v.ws.status})")
        if not a.no_switch:
            switch(url, token, owner, sid, None)
        owner.ws.s.close()
        return finish(a, measures)

    check(bool(key) and len(key) == 32, "the session's own tab is told its share key")
    if not key:
        return finish(a, measures)
    check(owner.codec == a.codec or a.codec == "h264", f"the stream is {owner.codec}")

    # ---------------------------------------------------------------- the link's rules
    bad = Viewer(url, sid, "0" * 32)
    check(bad.ws.status == 403, f"a wrong key is refused ({bad.ws.status})")
    other = Viewer(url, "0" * 12, key)
    check(other.ws.status == 403, f"the key of another session is refused ({other.ws.status})")
    cross = Viewer(url, sid, key, origin="http://evil.example")
    check(cross.ws.status == 403, f"a join from another origin is refused ({cross.ws.status})")
    status, body = sc.http(url, f"/?join={sid}&key={key}")
    check(status == 200 and b"<canvas" in body, f"the link's page needs no server token ({status})")
    status, _ = sc.http(url, f"/?join={sid}&key={'0' * 32}")
    check(status == 403, f"the page with a wrong key is refused ({status})")
    if token:
        for path in ("/api/status", "/api/apps", f"/api/sessions/{sid}/log"):
            status, _ = sc.http(url, f"{path}?join={sid}&key={key}")
            check(status == 401, f"the link opens nothing else: {path} still needs the token ({status})")
        status, _ = sc.http(url, f"/ws?app={app}&join={sid}&key={'0' * 32}")
        check(status in (400, 403), f"and a wrong key with an app does not start a session ({status})")

    # ---------------------------------------------------------------- the switch
    if not a.no_switch:
        key = switch(url, token, owner, sid, key)
        if not key:
            return finish(a, measures)

    # ---------------------------------------------------------------- one viewer
    owner.pump(1.5)  # a still screen now
    t0 = time.time()
    one = Viewer(url, sid, key)
    check(one.ws.status == 101, "a viewer joins with the session's key, without the token")
    one.pump(5, lambda: len(one.frames) > 0)
    first = time.time() - t0
    check(len(one.frames) > 0, f"it gets the screen as it is, still or not ({first * 1000:.0f} ms)")
    check(one.codec == owner.codec, f"in the session's codec ({one.codec})")
    if one.codec == "h264" and one.frames:
        check(one.frames[0][7], "starting at a keyframe")
    vi = [json.loads(t[2:]) for t in said(one, "i ")]
    check(bool(vi) and vi[0].get("viewer") is True and "share" not in vi[0], "it is told it only watches, and not the key")
    owner.pump(0.5)
    one.pump(0.5)
    check(viewers_said(owner) == 1 and viewers_said(one) == 1, "the tab and the viewer hear that one watches")

    # What a viewer sends is refused: a tap on the test app's FAB draws nothing.
    owner.pump(1.0)
    n_owner = len(owner.frames)
    for cmd in ("tap 733 1533", "touch down 733 1533", "touch up 733 1533", "type hello", "back", "rotate left",
                "shell s1 getprop", "rec save", "stats", "handoff", "maxfps 1", "device tablet"):
        one.ws.send(cmd)
    one.pump(1.5)
    owner.pump(0.5)
    check(len(said(one, "r refused")) >= 8, f"a viewer's input and commands are refused ({len(said(one, 'r refused'))} answers)")
    check(len(owner.frames) - n_owner <= 3 and not said(one, "s "), "and reach nothing: the screen stays as it was")

    # The owner's tap is seen by both, byte for byte.
    n_one, n_owner = len(one.frames), len(owner.frames)
    owner.ws.send("touch down 733 1533")
    owner.ws.send("touch up 733 1533")
    end = time.time() + 2
    while time.time() < end:
        owner.pump(0.05)
        one.pump(0.05)
    seen_owner = {f[6] for f in owner.frames[n_owner:]}
    seen_one = {f[6] for f in one.frames[n_one:]}
    check(len(seen_one) > 0 and len(seen_one & seen_owner) >= max(1, min(len(seen_one), len(seen_owner)) // 2),
          f"the tab's tap reaches the viewer as the same frames ({len(seen_one & seen_owner)} of {len(seen_one)} shared)")

    # The Shader tab animates for good: a hidden owner tab must not freeze the viewer.
    owner.ws.send("touch down 720 1690")
    owner.ws.send("touch up 720 1690")
    end = time.time() + 1
    while time.time() < end:
        owner.pump(0.05)
        one.pump(0.05)
    t1 = time.time() * 1000
    end = time.time() + 2
    while time.time() < end:
        owner.pump(0.02)
        one.pump(0.02)
    shown_fps = fps_of(one.frames, t1, time.time() * 1000)  # what this loop reads of the animation, before the cap
    owner.ws.send("maxfps 1")
    t1 = time.time() * 1000
    end = time.time() + 3
    while time.time() < end:
        owner.pump(0.02)
        one.pump(0.02)
    hidden_fps = fps_of(one.frames, t1 + 500, time.time() * 1000)
    check(hidden_fps > 0.5 * shown_fps and hidden_fps > 1.5,
          f"a hidden tab (maxfps 1) does not freeze a viewer's picture ({hidden_fps:.0f} fps, {shown_fps:.0f} before)")
    one.ws.send("", op=8, raw=struct.pack(">H", 1000))
    one.ws.s.close()
    owner.pump(1.5)
    t1 = time.time() * 1000
    owner.pump(3)
    alone_fps = fps_of(owner.frames, t1, time.time() * 1000)
    check(viewers_said(owner) == 0, "the tab hears when the viewer has left")
    # A frame a second, each sent up to three times as a still screen sharpens: under 5 a second. Where the app draws
    # under 8 a second uncapped (a small CI runner's CPU shader) that is no lower than before, so only the bound holds.
    check(alone_fps < 5 and (hidden_fps < 8 or alone_fps < 0.6 * hidden_fps),
          f"and its own cap counts again once nobody watches ({alone_fps:.1f} fps)")
    owner.ws.send("maxfps 120")
    owner.pump(2)

    # ---------------------------------------------------------------- scale
    t1 = time.time() * 1000
    owner.pump(4)
    base_fps = fps_of(owner.frames, t1, time.time() * 1000)
    check(base_fps > 2, f"the screen animates before the viewers come ({base_fps:.0f} fps to the tab)")
    n = a.viewers
    slow = min(a.slow, n // 4)
    modes = ["slow"] * slow + ["fast"] * (n - slow)
    procs = max(1, min(a.procs, n))
    start_at = time.time() + 1.5
    until = start_at + a.seconds + 3 + n * 0.002  # joins take a moment; the measured window is the last --seconds
    path = prefix + f"/ws?join={sid}&key={key}"
    java = java_bin() if a.swarm != "python" else None
    if a.swarm == "java" and not java:
        sys.exit("--swarm java: no JDK (work/jdk.path, or java on PATH)")
    inst_pid = (owner.stats or {}).get("pid", 0)
    pool = proc = None
    if java:
        import subprocess
        print(f"     {n} viewers (share-swarm.java) for {a.seconds:.0f} s ({slow} slow)...", flush=True)
        proc = subprocess.Popen([java, "-Xmx2g", os.path.join(HERE, "share-swarm.java"), host, port, path, authority, origin, str(n),
                                 str(slow), str(a.slow_ms), str(int(start_at * 1000)), str(int(until * 1000)), str(a.seconds)],
                                stdout=subprocess.PIPE, text=True)
    else:
        print(f"     {n} viewers from {procs} processes for {a.seconds:.0f} s ({slow} slow)...", flush=True)
        jobs = [(host, int(port), path, authority, origin, modes[i::procs], start_at, until, a.slow_ms) for i in range(procs)]
        pool = multiprocessing.Pool(procs)
        res = pool.map_async(swarm, jobs)
    while time.time() < until - a.seconds:
        owner.pump(0.25)
    cpu0, tcpu0 = (cpu_seconds(a.server_pid), cpu_seconds(inst_pid)), time.time()
    while time.time() < until:
        owner.pump(0.25)
    cpu1, tcpu1 = (cpu_seconds(a.server_pid), cpu_seconds(inst_pid)), time.time()
    owner.pump(0.5)
    if proc:
        out = proc.communicate(timeout=90)[0]
        report = json.loads(out) if out.strip() else {"sentFps": 0, "viewers": []}
        results, sent_fps = report["viewers"], report["sentFps"]
    else:
        results = [c for part in res.get(60) for c in part]
        pool.close()
        sent_fps = summarize(results, a.seconds)
    cores = [None if x is None or y is None else (y - x) / (tcpu1 - tcpu0) for x, y in zip(cpu0, cpu1)]
    w0, w1 = (until - a.seconds) * 1000, until * 1000
    during_fps = fps_of(owner.frames, w0, w1)
    status, body = sc.http(url, "/api/status", token)
    served = next((s for s in json.loads(body)["sessions"] if s["id"] == sid), {}) if status == 200 else {}
    vstats = served.get("viewers", {})

    fast = [c for c in results if c["mode"] == "fast"]
    lagging = [c for c in results if c["mode"] == "slow"]
    joined = [c for c in results if c["first"] >= 0]
    check(len(joined) == n, f"all {n} viewers joined and got a frame ({len(joined)}; "
          + ", ".join(sorted({c['end'] for c in results if c['first'] < 0}))[:200] + ")")
    firsts = [c["first"] for c in joined]
    check(pct(firsts, 0.95) < 4, f"a first frame within {pct(firsts, 0.5) * 1000:.0f} ms (median), {pct(firsts, 0.95) * 1000:.0f} ms (p95), {max(firsts or [0]) * 1000:.0f} ms (worst)")
    if owner.codec == "h264":
        check(all(c["first_key"] for c in joined), "every viewer's first frame is a keyframe")
        broken = len([c for c in joined if c["broken"]])
        check(broken == 0, f"no viewer's chain is broken: after frames it missed comes a keyframe ({broken} broken of {len(joined)})")
    # The frame rate each viewer saw in the measured window, from its frames' own times (the instance's clock).
    rates = [c["rate"] for c in fast if c["first"] >= 0]
    slow_rates = [c["rate"] for c in lagging if c["first"] >= 0]
    check(bool(rates) and pct(rates, 0.05) >= 0.8 * sent_fps,
          f"viewers that keep up get the stream's frames: {pct(rates, 0.5):.0f} fps median, {pct(rates, 0.05):.0f} fps at the 5th percentile, of {sent_fps:.0f} sent")
    if lagging:
        check(all(r >= 0.3 for r in slow_rates) and pct(rates, 0.05) >= 0.8 * sent_fps,
              f"slow viewers still get a picture ({pct(slow_rates, 0.5):.1f} fps) and cost the others nothing")
    # A tab that fell behind by itself (this script's, on a machine the viewers also run on) paces the stream for all:
    # then its rate is the stream's, and what is checked is that it got what was sent.
    paced = bool(served.get("paceMs"))
    check(during_fps >= 0.8 * base_fps or paced and during_fps >= 0.9 * sent_fps,
          f"the tab's own frame rate holds: {during_fps:.0f} fps with {n} viewers, {base_fps:.0f} before"
          + (f" (the tab fell behind and paced the stream: {sent_fps:.0f} sent)" if paced and during_fps < 0.8 * base_fps else ""))
    mbit = sum(c["bytes"] for c in joined) * 8 / 1e6 / (a.seconds + 3)
    told = max((c["told"] for c in results), default=0)
    check(told >= n * 0.95, f"viewers hear how many watch ({told})")
    print(f"     sent to viewers: {mbit:.0f} Mbit/s in all; server: {json.dumps(vstats)}; instance {served.get('rssMb', '?')} MB", flush=True)
    if served.get("paceMs"):
        print(f"     the tab itself fell behind: the instance paced the stream to one frame per {served['paceMs']} ms "
              f"({served.get('keyDrops', 0)} drops to a keyframe); viewers get the tab's rate", flush=True)
    measures.update({"tabPaceMs": served.get("paceMs"), "tabKeyDrops": served.get("keyDrops")})
    if cores[0] is not None or cores[1] is not None:
        print("     CPU while they watched: serve " + ("?" if cores[0] is None else f"{cores[0]:.2f}") + " cores, the instance "
              + ("?" if cores[1] is None else f"{cores[1]:.2f}") + " cores", flush=True)
    measures.update({"serveCores": cores[0], "instanceCores": cores[1]})
    measures.update({"baseFps": base_fps, "ownerFpsDuring": during_fps, "sentFps": sent_fps, "viewerFpsMedian": pct(rates, 0.5),
                     "viewerFpsP05": pct(rates, 0.05), "slowFpsMedian": pct(slow_rates, 0.5) if slow_rates else None,
                     "firstFrameMsMedian": pct(firsts, 0.5) * 1000, "firstFrameMsP95": pct(firsts, 0.95) * 1000,
                     "mbit": mbit, "server": vstats, "swarm": "java" if java else "python", "seconds": a.seconds})
    owner.pump(1.5)
    check(viewers_said(owner) == 0, f"the tab hears they have all left ({viewers_said(owner)})")

    # ---------------------------------------------------------------- the limit
    if a.server_max:
        held = []
        for _ in range(a.server_max):
            v = Viewer(url, sid, key)
            held.append(v)
        late = Viewer(url, sid, key)
        late.pump(3, lambda: bool(said(late, "x ")))
        check(bool(said(late, "x all")), f"viewer {a.server_max + 1} is told every place is taken ({said(late, 'x ')})")
        for v in held:
            v.ws.s.close()
        owner.pump(2)

    # ---------------------------------------------------------------- a viewer that stops acknowledging
    if a.stall:
        dead = Viewer(url, sid, key)
        dead.acking = False
        live = Viewer(url, sid, key)
        print("     waiting for a viewer that acknowledges nothing to be dropped (30 s)...", flush=True)
        end = time.time() + 40
        gone = False
        t1 = time.time() * 1000
        while time.time() < end and not gone:
            owner.pump(0.02)
            live.pump(0.05)
            try:
                dead.pump(0.005, lambda: bool(said(dead, "x ")))
            except EOFError:
                pass  # dropped: its connection is closed once it has been told
            gone = bool(said(dead, "x "))
        check(gone, f"it is dropped and told why ({(said(dead, 'x ') or ['nothing'])[0][:60]}; it got {len(dead.frames)} frames)")
        check(len(dead.frames) <= 8, f"having been sent only its window of frames ({len(dead.frames)})")
        # This loop reads three sockets in turn, so the live viewer's own rate here is the loop's: it must only not stop.
        live_fps = fps_of(live.frames, t1 + 2000, time.time() * 1000)
        check(live_fps >= 0.4 * base_fps, f"while another viewer went on ({live_fps:.0f} fps read in turn with two other sockets)")
        live.ws.s.close()

    # ---------------------------------------------------------------- the end
    last = [Viewer(url, sid, key) for _ in range(3)]
    for v in last:
        v.pump(3, lambda v=v: len(v.frames) > 0)
    owner.ws.send("", op=8, raw=struct.pack(">H", 1000))
    owner.ws.s.close()
    told = 0
    for v in last:
        v.pump(15, lambda v=v: bool(said(v, "x ")))
        told += bool(said(v, "x the session has ended"))
    check(told == len(last), f"viewers are told when the session ends ({told} of {len(last)})")
    time.sleep(0.5)
    after = Viewer(url, sid, key)
    check(after.ws.status == 403, f"and its link no longer works ({after.ws.status})")
    return finish(a, measures)


def finish(a, measures):
    if a.json:
        with open(a.json, "w") as f:
            json.dump(measures, f, indent=1)
    if sc.FAILED:
        print(f"\n{len(sc.FAILED)} failed:\n  " + "\n  ".join(sc.FAILED))
        sys.exit(1)
    print("\nall passed")


if __name__ == "__main__":
    main()
