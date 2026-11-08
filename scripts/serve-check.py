#!/usr/bin/env python3
"""Checks a running `apksim serve` end to end, as a browser tab uses it (docs/serve.md), with only the standard library.

    scripts/serve-check.py URL [--token FILE] [--app ID] [--upload APK] [--device phone] [--max-upload-mb N]
                           [--allow-origin ORIGIN] [--add-url URL [--add-options JSON]]

URL is the server (http://localhost:8080). It checks that the token is required (when given), that a cross-origin
WebSocket and a command outside the allowed set are refused, an upload over the size limit (when given) is refused,
then starts a session with --app (or the first listed app, or --upload's APK), waits for the first frame, taps the
test app's FAB, drags, scrolls, types and switches tabs, and checks that new frames follow each. It prints the time to
the first frame, fps while dragging, frame and input latency (this machine's clock against the instance's: the
same machine, or a container's clock) and the session's memory. Exits 1 on any failure.

It also checks that /api/health answers without the token, that an upload sent chunked is stored, and that a URL add
with a launch option outside the allowed set is refused. With --allow-origin (the server's own), that origin may open a
WebSocket and frame the page, and an embedded page's token link is served rather than redirected. With --add-url, the
session runs that APK, added with POST /api/apps (and --add-options, a JSON list of launch options), once it has
downloaded.
"""
import http.client as httpc
import argparse
import base64
import hashlib
import io
import json
import os
import socket
import struct
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile

FAILED = []


def parts(url):
    """(host:port, path prefix, origin) of a server URL, which may have a path (a proxy's prefix, --base-path)."""
    u = urllib.parse.urlsplit(url)
    if u.scheme.lower() != "http":
        # Its WebSocket and raw requests are plain sockets: an https URL would send the token unencrypted.
        sys.exit(f"serve-check.py speaks plain HTTP only (no TLS): use an http:// URL, not {url}")
    # Origin as a browser sends it: lower case, no default port.
    scheme, host = u.scheme.lower(), u.netloc.lower()
    if (scheme, u.port) in (("http", 80), ("https", 443)):
        host = host.rsplit(":", 1)[0]
    netloc = u.netloc if u.port else u.netloc + (":443" if scheme == "https" else ":80")
    return netloc, u.path.rstrip("/"), f"{scheme}://{host}"


def check(ok, what):
    print(("ok   " if ok else "FAIL ") + what, flush=True)
    if not ok:
        FAILED.append(what)


class WS:
    def __init__(self, url, path, headers):
        hostport, prefix, _ = parts(url)
        host, port = hostport.rsplit(":", 1)
        path = prefix + path
        self.s = socket.create_connection((host, int(port)), timeout=30)
        self.s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        key = base64.b64encode(os.urandom(16)).decode()
        authority = parts(url)[2].split("//", 1)[1]  # Host as a browser sends it: no default port
        req = f"GET {path} HTTP/1.1\r\nHost: {authority}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" \
              f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
        for k, v in headers.items():
            req += f"{k}: {v}\r\n"
        self.s.sendall((req + "\r\n").encode())
        head = b""
        while b"\r\n\r\n" not in head:
            b = self.s.recv(1)
            if not b:
                break
            head += b
        self.status = int(head.split(b" ", 2)[1]) if head else 0
        self.buf = b""

    def _read(self, n):
        while len(self.buf) < n:
            b = self.s.recv(1 << 16)
            if not b:
                raise EOFError
            self.buf += b
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def recv(self, timeout):
        """(opcode, payload), or None on timeout."""
        self.s.settimeout(timeout)
        try:
            b0, b1 = self._read(2)
        except socket.timeout:
            return None
        self.s.settimeout(30)
        n = b1 & 0x7f
        if n == 126:
            n = struct.unpack(">H", self._read(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", self._read(8))[0]
        return b0 & 0x0f, self._read(n)

    def send(self, text):
        data = text.encode()
        mask = os.urandom(4)
        head = bytes([0x81])
        head += bytes([0x80 | len(data)]) if len(data) < 126 else bytes([0x80 | 126]) + struct.pack(">H", len(data))
        self.s.sendall(head + mask + bytes(c ^ mask[i & 3] for i, c in enumerate(data)))


def http(url, path, token=None, data=None, headers=None):
    h = dict(headers or {})
    if token:
        h["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url + path, data=data, headers=h, method="POST" if data is not None else "GET")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


class Session:
    def __init__(self, url, token, app, device, codec=None):
        hdr = {"Origin": parts(url)[2]}
        if token:
            hdr["Cookie"] = "apksim_token=" + token
        self.ws = WS(url, f"/ws?app={app}&device={device}" + (f"&codec={codec}" if codec else ""), hdr)
        # (arrived, present_ms in this machine's time, encode_ms, size, w, h, payload digest, H.264 keyframe, payload)
        self.frames = []
        self.codec = "jpeg"  # what the server answered ("c h264" or "c jpeg"), when asked
        self.acking = True
        self.offset, self.best_rtt = 0.0, float("inf")  # the server's clock minus this one's
        self.texts = []
        self.stats = {}

    def pump(self, seconds, until=None):
        """Reads messages for this long (or until until() is true), acknowledging each frame as a tab does."""
        end = time.time() + seconds
        while time.time() < end:
            m = self.ws.recv(max(0.01, end - time.time()))
            if m is None:
                break
            op, p = m
            if op == 2:
                w, h, present_us, encode_us = struct.unpack(">iiqi", p[:20])
                key = self.codec == "h264" and len(p) > 20 and p[20] == 1
                self.frames.append((time.time() * 1000, present_us / 1000 - self.offset, encode_us / 1000, len(p), w, h,
                                    hashlib.sha1(p[20:]).hexdigest(), key, p[21:] if self.codec == "h264" else b""))
                if self.acking:
                    self.ws.send("ack")
            elif op == 1:
                t = p.decode()
                self.texts.append(t)
                if t.startswith("t "):
                    sent, server = (float(x) for x in t[2:].split())
                    rtt = time.time() * 1000 - sent
                    if rtt < self.best_rtt:
                        self.best_rtt, self.offset = rtt, server - (sent + rtt / 2)
                if t.startswith("s "):
                    self.stats = json.loads(t[2:])
                if t.startswith("c "):
                    self.codec = t[2:]
                if t.startswith("x "):
                    print("     session ended: " + t[2:])
                    return False
            elif op == 8:
                return False
            if until and until():
                return True
        return until is None or until()

    def after(self, cmds, wait=1.5):
        """Sends commands; the frames that arrived after them."""
        n = len(self.frames)
        t = time.time() * 1000
        for c in cmds:
            self.ws.send(c)
        self.pump(wait)
        return [f for f in self.frames[n:] if f[0] > t]


def median(xs):
    xs = sorted(xs)
    return xs[len(xs) // 2] if xs else float("nan")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("--token")
    ap.add_argument("--app")
    ap.add_argument("--upload")
    ap.add_argument("--device", default="phone")
    ap.add_argument("--max-upload-mb", type=int)
    ap.add_argument("--first-frame-timeout", type=int, default=180)
    ap.add_argument("--allow-origin")
    ap.add_argument("--add-url")
    ap.add_argument("--add-options", default="[]")
    ap.add_argument("--h264", choices=["required", "optional", "skip"], default="optional",
                    help="the H.264 stream's checks: required fails when the server answers JPEG (no encoder there)")
    ap.add_argument("--crash-app", help="id of an app that crashes at start (test app: -- --activity .CrashActivity)")
    a = ap.parse_args()
    url = a.url.rstrip("/")
    token = open(a.token).readline().strip() if a.token else None

    # A request head that never ends (a byte now and then would do the same): the server must drop it (10 s).
    hostport, prefix, origin = parts(url)
    slow = socket.create_connection((hostport.split(":")[0], int(hostport.split(":")[1])), timeout=30)
    slow.sendall(b"GET " + (prefix + "/").encode() + b" HTTP/1.1\r\nHost: " + hostport.encode() + b"\r\n")
    slow_since = time.time()
    if token:
        status, _ = http(url, "/api/apps")
        check(status == 401, f"no token: refused ({status})")
        status, _ = http(url, "/api/apps", token="wrong-" + token)
        check(status == 401, f"wrong token: refused ({status})")
    status, body = http(url, "/api/health")
    check(status == 200 and body.strip() == b"ok", f"/api/health answers without the token ({status} {body.strip()[:60]!r})")
    status, body = http(url, "/api/apps", token)
    check(status == 200, f"apps listed ({status})")
    apps = json.loads(body) if status == 200 else []
    page_status, page = http(url, "/", token)
    check(page_status == 200 and b"<script" in page and b"src=" not in page.lower().split(b"<script", 1)[1][:20],
          "the page is served and loads no external script")

    evil = WS(url, "/ws?app=x", {"Origin": "http://evil.example", **({"Cookie": "apksim_token=" + token} if token else {})})
    check(evil.status == 403, f"cross-origin WebSocket refused ({evil.status})")
    if a.allow_origin:
        friend = WS(url, "/ws?app=x", {"Origin": a.allow_origin, **({"Cookie": "apksim_token=" + token} if token else {})})
        check(friend.status == 101, f"a WebSocket from an --allow-origin page is accepted ({friend.status})")
        friend.s.close()
        c = httpc.HTTPConnection(hostport, timeout=30)
        c.request("GET", prefix + "/", headers={"Authorization": "Bearer " + token} if token else {})
        r = c.getresponse()
        csp, xfo = r.getheader("Content-Security-Policy", ""), r.getheader("X-Frame-Options")
        r.read()
        check(a.allow_origin in csp and "frame-ancestors" in csp and xfo is None,
              f"the page may be framed by the allowed origin (X-Frame-Options {xfo})")
        if token:
            c = httpc.HTTPConnection(hostport, timeout=30)
            c.request("GET", prefix + "/?embed=1&app=x&token=" + urllib.parse.quote(token))
            r = c.getresponse()
            r.read()
            check(r.status == 200, f"an embedded page's token link is served, not redirected ({r.status})")
    else:
        page_hdrs = urllib.request.urlopen(urllib.request.Request(
            url + "/", headers={"Authorization": "Bearer " + token} if token else {}), timeout=30).headers
        check(page_hdrs.get("X-Frame-Options") == "DENY", "without --allow-origin the page cannot be framed")
    if a.max_upload_mb:
        too_big = (a.max_upload_mb << 20) + 1
        s = socket.create_connection((hostport.split(":")[0], int(hostport.split(":")[1])), timeout=30)
        s.sendall(f"POST {prefix}/api/upload HTTP/1.1\r\nHost: {hostport}\r\nContent-Length: {too_big}\r\n".encode()
                  + (f"Authorization: Bearer {token}\r\n" if token else "").encode() + b"\r\n")
        reply = s.recv(200)
        check(b" 413 " in reply, "an upload over the limit is refused: " + reply.split(b"\r\n")[0].decode())
        s.close()
    # A name outside Latin-1 travels URL-encoded (as the page sends it) and is kept in a plain-ASCII form.
    tiny = io.BytesIO()
    with zipfile.ZipFile(tiny, "w") as z:
        z.writestr("AndroidManifest.xml", b"")
    status, body = http(url, "/api/upload", token, data=tiny.getvalue(),
                        headers={"X-Filename": urllib.parse.quote("测试 build.apk")})
    check(status == 200 and json.loads(body).get("name", "").endswith(" build.apk"),
          f"an upload named outside Latin-1 is stored ({status} {body[:60]!r})")
    status, _ = http(url, "/api/upload", token, data=b"not a zip file", headers={"X-Filename": "x.apk"})
    check(status == 400, f"an upload that is not an APK is refused ({status})")
    # A streamed body (fetch with a ReadableStream, curl -T -) comes chunked, without Content-Length.
    c = httpc.HTTPConnection(hostport, timeout=60)
    c.request("POST", prefix + "/api/upload", body=iter([tiny.getvalue()[:10], tiny.getvalue()[10:]]), encode_chunked=True,
              headers={"X-Filename": "chunked.apk", "Transfer-Encoding": "chunked",
                       **({"Authorization": "Bearer " + token} if token else {})})
    r = c.getresponse()
    body = r.read()
    check(r.status == 200 and json.loads(body).get("name") == "chunked.apk", f"a chunked upload is stored ({r.status} {body[:60]!r})")
    status, body = http(url, "/api/apps", token, data=json.dumps({"url": "https://example.invalid/a.apk",
                                                                  "options": ["--jvm", "-Dx=1"]}).encode())
    check(status == 400 and b"not allowed" in body, f"a URL add with a launch option outside the allowed set is refused ({status})")
    status, body = http(url, "/api/apps", token, data=json.dumps({"url": "file:///etc/passwd"}).encode())
    check(status == 400, f"a URL add that is not http(s) is refused ({status})")

    app = a.app
    if a.add_url:
        status, body = http(url, "/api/apps", token, data=json.dumps({"url": a.add_url, "options": json.loads(a.add_options)}).encode(),
                            headers={"Content-Type": "application/json"})
        check(status == 200, f"URL add accepted ({status} {body[:80]!r})")
        app = json.loads(body)["id"] if status == 200 else None
        state = {}
        t_add = time.time()
        while app and time.time() - t_add < 300:
            _, body = http(url, "/api/apps/" + app, token)
            state = json.loads(body)
            if state.get("state") != "downloading":
                break
            time.sleep(0.5)
        check(state.get("state") == "ready", f"the added app downloaded ({state}) in {time.time() - t_add:.1f} s")
    if a.upload:
        with open(a.upload, "rb") as f:
            status, body = http(url, "/api/upload", token, data=f.read(), headers={"X-Filename": os.path.basename(a.upload)})
        check(status == 200, f"upload stored ({status})")
        app = json.loads(body)["id"]
    if not app:
        app = apps[0]["id"] if apps else None
    if not app:
        check(False, "an app to run")
        return finish()

    t0 = time.time()
    s = Session(url, token, app, a.device)
    check(s.ws.status == 101, f"session WebSocket open ({s.ws.status})")
    got = s.pump(a.first_frame_timeout, until=lambda: len(s.frames) > 0)
    check(got, "first frame")
    if not got:
        return finish()
    first_ms = (time.time() - t0) * 1000
    w, h = s.frames[0][4], s.frames[0][5]
    print(f"     first frame after {first_ms:.0f} ms ({w}x{h}, {s.frames[0][3] // 1024} KB)")
    for _ in range(5):  # the server's clock, as the page learns it
        s.ws.send(f"t {time.time() * 1000}")
        s.pump(0.1)
    s.pump(3)  # let the app settle
    synced = len(s.frames)
    s.ws.send("shot /tmp/x.png")
    s.pump(0.5)
    check(any(t.startswith("r refused") for t in s.texts), "a command outside the allowed set is refused")

    # The test app's FAB counts taps: each tap changes the screen.
    taps = []
    for _ in range(3):
        t = time.time() * 1000
        frames = s.after(["touch down 733 1533", "touch up 733 1533"], 1.0)
        check(len(frames) > 0, "a frame after tapping the FAB")
        if frames:
            taps.append(frames[0][0] - t)
    # A smooth drag on the list: one move per 8 ms, as a browser on a 120 Hz display sends them.
    s.ws.send("touch down 400 1300")
    s.pump(0.1)
    n0 = len(s.frames)
    t_drag = time.time()
    for i in range(1, 91):
        s.ws.send(f"touch move 400 {1300 - i * 8}")
        s.pump(0.008)
    drag_s = time.time() - t_drag
    drag = s.frames[n0:]
    s.ws.send("touch up 400 580")
    s.pump(0.5)
    fps = len(drag) / drag_s
    drag_mb_s = sum(f[3] for f in drag) / drag_s / 1e6
    check(len(drag) > 10, f"frames while dragging ({len(drag)} in {drag_s:.2f} s)")
    check(len(s.after(["wheel 400 1000 -3"], 1.0)) > 0, "a frame after scrolling with the wheel")
    # Trips tab, then its note field: typing changes the screen.
    check(len(s.after(["touch down 350 1690", "touch up 350 1690"], 2.0)) > 0, "a frame after switching to Trips")
    s.pump(1)
    # Trips' note field (bottom left of the phone screen). Once focused its caret blinks, so frames keep coming; typing
    # must bring a screen that never showed before it.
    n0 = len(s.frames)
    s.after(["touch down 300 1490", "touch up 300 1490"], 1.5)
    before = {f[6] for f in s.frames[n0:]}
    typed = s.after(["type hi", "key SPACE", "type there"], 1.5)
    check(len(before) > 0 and any(f[6] not in before for f in typed), "typing in the note field changes the screen")
    # Backspace mid-text ("hi there" -> "hithere"): erasing from the end would bring back a screen seen while typing.
    erased = s.after(["key MOVE_HOME", "key DPAD_RIGHT 3", "key DEL"], 1.5)
    check(any(f[6] not in before and f[6] not in {g[6] for g in typed} for f in erased), "Backspace changes it again")
    check(len(s.after(["appearance dark"], 2.5)) > 0, "a frame after dark mode")
    check(len(s.after(["rotate right"], 3.0)) > 0 and s.frames[-1][4] > s.frames[-1][5], "landscape frames after rotating")
    s.after(["rotate left", "appearance light"], 2.5)
    # The Shader tab animates without end: the stream's own ceiling (every frame differs, and noise compresses badly).
    s.after(["touch down 720 1690", "touch up 720 1690"], 1.0)
    n0, t_sh = len(s.frames), time.time()
    s.pump(3)
    shader = s.frames[n0:]
    shader_fps = len(shader) / (time.time() - t_sh)
    shader_kb = sum(f[3] for f in shader) / max(1, len(shader)) / 1024
    shader_enc = median([f[2] for f in shader])
    # (On Linux the shader runs on the CPU, a few frames a second: the app's rate, not the stream's.)
    check(len(shader) >= 3, f"frames on the Shader tab ({len(shader)})")
    s.after(["touch down 130 1690", "touch up 130 1690"], 1.0)
    s.ws.send("stats")
    s.pump(3, until=lambda: s.stats.get("rssMb", -1) > 0)
    lat = [f[0] - f[1] for f in s.frames[synced:]]
    enc = [f[2] for f in s.frames]
    kb = sum(f[3] for f in s.frames) / len(s.frames) / 1024
    print(f"     taps: input -> frame {median(taps):.0f} ms (median of {len(taps)}); drag: {fps:.0f} fps, {len(drag)} frames")
    print(f"     present -> received {median(lat):.0f} ms median (clocks matched to {s.best_rtt:.1f} ms round trip); encode {median(enc):.1f} ms median; {kb:.0f} KB/frame")
    print(f"     shader tab: {shader_fps:.0f} fps, {shader_kb:.0f} KB/frame, encode {shader_enc:.1f} ms")
    print(f"     bandwidth (JPEG): drag {drag_mb_s:.2f} MB/s, shader tab {shader_kb * shader_fps / 1024:.2f} MB/s")
    print(f"     memory: {s.stats.get('rssMb')} MB resident, {s.stats.get('heapMb')} MB heap; frames sent {s.stats.get('framesSent')}")
    status, body = http(url, "/api/status", token)
    st = json.loads(body) if status == 200 else {}
    check(any(x.get("id") == s.stats.get("id") for x in st.get("sessions", [])), "the session is in /api/status")
    s.ws.s.close()
    slow.settimeout(max(1, 15 - (time.time() - slow_since)))
    try:
        dropped = slow.recv(100) == b""
    except (socket.timeout, ConnectionResetError):
        dropped = False
    check(dropped, "an unfinished request head is dropped")
    # The tab closed: the session ends and its instance goes.
    gone = False
    for _ in range(30):
        time.sleep(0.5)
        _, body = http(url, "/api/status", token)
        if not any(x.get("id") == s.stats.get("id") for x in json.loads(body).get("sessions", [])):
            gone = True
            break
    check(gone, "closing the tab ends the session")
    check("apps" in st, "/api/status lists the apps' cache states")
    if a.crash_app:
        crash_check(url, token, a.crash_app, a.device)
    print("RESULT " + json.dumps({"firstFrameMs": round(first_ms), "tapMs": round(median(taps)), "dragFps": round(fps),
                                  "frameLatencyMs": round(median(lat)), "encodeMs": round(median(enc), 1),
                                  "kbPerFrame": round(kb), "shaderFps": round(shader_fps), "shaderKb": round(shader_kb),
                                  "shaderEncodeMs": round(shader_enc, 1), "rssMb": s.stats.get("rssMb"), "heapMb": s.stats.get("heapMb"),
                                  "dragMBps": round(drag_mb_s, 2)}))
    if a.h264 != "skip":
        check_h264(url, token, app, a)
    return finish()


def nal_types(unit):
    """The NAL unit types of an Annex B access unit."""
    types, i = [], 0
    while True:
        i = unit.find(b"\x00\x00\x01", i)
        if i < 0 or i + 3 >= len(unit):
            return types
        types.append(unit[i + 3] & 0x1F)
        i += 3


def check_h264(url, token, app, a):
    """A second tab that asks for H.264 (as the page does when WebCodecs decodes it): keyframes, deltas, flow control."""
    print("  -- H.264 stream (codec=h264)")
    s = Session(url, token, app, a.device, codec="h264")
    got = s.pump(a.first_frame_timeout, until=lambda: len(s.frames) > 0)
    check(got and any(t.startswith("c ") for t in s.texts), "the server names the stream's codec")
    if not got:
        return
    if s.codec != "h264":
        check(a.h264 != "required", f"an H.264 stream (the server answered {s.codec}: no encoder there)")
        s.ws.s.close()
        return
    first = s.frames[0]
    types = nal_types(first[8])
    check(first[7] and {7, 8, 5} <= set(types), f"the first frame is a keyframe with SPS, PPS and IDR ({types})")
    s.pump(3)  # let the app settle
    # A drag, as in the JPEG session: delta frames, every one in order.
    s.ws.send("touch down 400 1300")
    s.pump(0.1)
    n0, t_drag = len(s.frames), time.time()
    for i in range(1, 91):
        s.ws.send(f"touch move 400 {1300 - i * 8}")
        s.pump(0.008)
    drag_s = time.time() - t_drag
    drag = s.frames[n0:]
    s.ws.send("touch up 400 580")
    s.pump(0.5)
    check(len(drag) > 10 and not any(f[7] for f in drag), f"delta frames while dragging ({len(drag)}, no keyframes)")
    drag_mb_s = sum(f[3] for f in drag) / drag_s / 1e6
    # A still screen still answers a keyframe request (a tab whose decoder failed asks for one).
    s.pump(1)
    check(any(f[7] for f in s.after(["key"], 1.0)), "a keyframe after the tab asks for one, on a still screen")
    # The Shader tab: every frame differs.
    s.after(["touch down 720 1690", "touch up 720 1690"], 1.0)
    n0, t_sh = len(s.frames), time.time()
    s.pump(3)
    shader = s.frames[n0:]
    shader_s = time.time() - t_sh
    shader_mb_s = sum(f[3] for f in shader) / shader_s / 1e6
    check(len(shader) >= 3, f"H.264 frames on the Shader tab ({len(shader)})")
    # A tab that stops acknowledging gets no more than two frames in flight; once it acknowledges again the stream
    # starts over at a keyframe, not with frames it could no longer decode.
    s.acking = False
    n0 = len(s.frames)
    s.pump(1.0)
    held = s.frames[n0:]
    s.acking = True
    for _ in held:
        s.ws.send("ack")
    n1 = len(s.frames)
    s.pump(2.0)
    resumed = s.frames[n1:]
    check(len(held) <= 2, f"at most two frames in flight to a tab that does not acknowledge ({len(held)})")
    if len(shader) >= 30:  # only where the app animates fast enough to fall behind (not the Linux image's CPU shader)
        check(bool(resumed) and resumed[0][7], "after falling behind, the stream resumes at a keyframe")
    s.after(["touch down 130 1690", "touch up 130 1690"], 1.0)
    enc = median([f[2] for f in s.frames])
    kb = sum(f[3] for f in s.frames) / len(s.frames) / 1024
    print(f"     H.264: encode {enc:.1f} ms median, {kb:.1f} KB/frame; drag {len(drag) / drag_s:.0f} fps {drag_mb_s:.2f} MB/s; "
          f"shader tab {len(shader) / shader_s:.0f} fps {shader_mb_s:.2f} MB/s")
    print("RESULT-H264 " + json.dumps({"encodeMs": round(enc, 1), "kbPerFrame": round(kb, 1), "dragMBps": round(drag_mb_s, 2),
                                       "shaderFps": round(len(shader) / shader_s), "shaderMBps": round(shader_mb_s, 2)}))
    s.ws.s.close()


def crash_check(url, token, app, device, expect=("uncaught exception", "crashed:")):
    """A session whose app crashes: the tab gets the reason and the log's last lines, and the log endpoint the tail."""
    s = Session(url, token, app, device)
    check(s.ws.status == 101, "crash app: session WebSocket open")
    s.pump(120, until=lambda: any(t.startswith("x ") for t in s.texts))
    ended = [t for t in s.texts if t.startswith("x ")]
    check(bool(ended) and any(e in ended[0] for e in expect), f"the end message says why: {ended[:1]}")
    logs = [t for t in s.texts if t.startswith("r log: ")]
    check(0 < len(logs) <= 50 and any("FATAL EXCEPTION" in t for t in logs), f"the log's last lines reach the tab ({len(logs)})")
    i = next((json.loads(t[2:]) for t in s.texts if t.startswith("i ")), {})
    status, body = http(url, "/api/sessions/" + str(i.get("id")) + "/log", token)
    check(status == 200 and b"FATAL EXCEPTION" in body and len(body) <= 70000, f"GET /api/sessions/ID/log has the tail ({status}, {len(body)} bytes)")
    if token:
        status, _ = http(url, "/api/sessions/" + str(i.get("id")) + "/log")
        check(status == 401, f"the log needs the token ({status})")
    status, _ = http(url, "/api/sessions/../../etc/passwd/log", token)
    check(status == 404, f"the log path takes only a session id ({status})")
    left = [f for f in os.listdir(os.environ.get("APKSIM_SERVE_LOGS", ".")) if f.startswith(("core", "hs_err"))] \
        if os.environ.get("APKSIM_SERVE_LOGS") else []
    check(not left, f"no core files or crash reports left in the logs folder ({left})")
    s.ws.s.close()


def finish():
    if FAILED:
        print(f"{len(FAILED)} check(s) failed")
        sys.exit(1)
    print("serve check passed")


if __name__ == "__main__":
    main()
