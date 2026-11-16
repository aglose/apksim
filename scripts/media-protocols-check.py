#!/usr/bin/env python3
"""The test app's protocol and media screens, run and compared with what each platform is expected to give.

    scripts/media-protocols-check.py APK [--serial SERIAL] [--only media,grpc,ws,hls] [--out DIR] [--port N]

Starts the fixture server (testapks/composeapp/fixture: gRPC-java's server, OkHttp's MockWebServer; build it once with
`./gradlew :fixture:installDist` in testapks/composeapp), then opens, in a running app:

  apksim://grpc       12 gRPC calls over TLS (ALPN h2) and in plain text (unary, server stream, bidi, a failing status,
                      a deadline the client cancels, a 300 KB answer)
  apksim://websocket  12 WebSocket exchanges over wss:// (permessage-deflate) and ws:// (text, binary, a burst, 200 KB,
                      a clean close)
  apksim://media      23 media checks: every sample in assets/media through Media3 and through MediaCodec alone, video
                      frame by frame and audio by its pitch and rate, and encode round trips
  apksim://media?only=hls  the fixture's HLS stream (H.264 and AAC in MPEG-TS over HTTP/2)

Each screen writes one line per check to logcat ("NAME: ok|unsupported|FAIL DETAIL"), and each line's status must be
the one EXPECTED below gives for the platform: a check that starts to pass where it failed is reported too, so the
table moves with the fixes. Without --serial the app runs in apksim (headless, `--audio mute`, `--rec net`), the
fixture on this machine's LAN address (loopback is never captured), and the saved flight recording must hold the
traffic: the gRPC calls as HTTP/2 exchanges with their trailers, the WebSocket messages, the HLS segments, and the
connections with their ALPN choice (docs/network.md "The tap"). With --serial the app runs on that adb device
(an emulator reaches this machine as 10.0.2.2), installed for the run and removed after unless it was there already.

Exits 1 on any difference. OUT (default work/media-protocols-check) gets the logs, the fixture's certificate and the
recording.

Under apksim the fixture listens on this machine's LAN address for the length of the run (apksim leaves loopback
alone, so a server there would not exercise the network tap): its gRPC and WebSocket echoes and the HLS files are
reachable from the local network meanwhile. They hold nothing but test data, and a burst is capped at 100 messages.
"""
import argparse
import json
import os
import re
import socket
import subprocess
import sys
import time
import zipfile

ROOT = os.path.abspath(os.path.dirname(os.path.abspath(__file__)) + "/..")
FIXTURE = os.path.join(ROOT, "testapks/composeapp/fixture/build/install/fixture/bin/fixture")
HLS = os.path.join(ROOT, "testapks/composeapp/media/hls")

# What each check gives, per platform: "ok", "unsupported", or ("FAIL", why it is known to fail). A check not listed
# for a platform must not appear there; one listed must.
SAME = {
    **{f"grpc {m} {c}": "ok" for m in ("tls", "plain") for c in ("unary", "stream", "bidi", "fail", "deadline", "big")},
    **{f"ws {m} {c}": "ok" for m in ("tls", "plain") for c in ("open", "text", "binary", "burst", "large", "close")},
    "hls stream": "ok",
    "play h264-baseline.mp4": "ok", "play h264-high-bframes.mp4": "ok", "play hevc-main.mp4": "ok", "play vp9.webm": "ok",
    "play aac-lc.m4a": "ok", "play mp3.mp3": "ok", "play aac-he.m4a": "ok", "play aac-he-v2.m4a": "ok",
    "play aac-he.adts": "ok", "play aac-he-v2.adts": "ok", "play aac-lc-22k.adts": "ok",
    "codec h264-high-bframes.h264": "ok", "codec hevc-main.h265": "ok", "codec vp9.ivf": "ok", "codec aac-lc.adts": "ok",
    "codec aac-he.adts": "ok", "codec aac-he-v2.adts": "ok",
}
EXPECTED = {
    # apksim on a Mac (VideoToolbox and AudioToolbox): measured 2026-11-15.
    "apksim": {
        **SAME,
        "play hevc-main10.mp4": "ok",
        "play vp8.webm": "unsupported", "play av1.mp4": "unsupported", "codec vp8.ivf": "unsupported", "codec av1.ivf": "unsupported",
        "play opus.ogg": "unsupported", "play flac.flac": "unsupported",
        "encode video/avc": "unsupported", "encode video/hevc": "unsupported", "encode video/x-vnd.on2.vp8": "unsupported",
        "encode video/x-vnd.on2.vp9": "unsupported", "encode audio/mp4a-latm": "unsupported",
    },
    # The API 36 emulator (ApksimCmp36, AGENTS.md "Comparing with the emulator"): measured 2026-11-15. Its HEVC
    # decoder (c2.goldfish.hevc.decoder) has no Main10.
    "device": {
        **SAME,
        "play hevc-main10.mp4": "unsupported",
        "play vp8.webm": "ok", "play av1.mp4": "ok", "codec vp8.ivf": "ok", "codec av1.ivf": "ok",
        "play opus.ogg": "ok", "play flac.flac": "ok",
        "encode video/avc": "ok", "encode video/hevc": "ok", "encode video/x-vnd.on2.vp8": "ok", "encode video/x-vnd.on2.vp9": "ok",
        "encode audio/mp4a-latm": "ok",
    },
}

problems = []


def check(ok, what):
    print(("  ok    " if ok else "  FAIL  ") + what)
    if not ok:
        problems.append(what)
    return ok


def lan_address():
    """This machine's address on its network (no packet is sent: a UDP socket only picks its route)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 9))
        a = s.getsockname()[0]
        return None if a.startswith("127.") else a
    except OSError:
        return None
    finally:
        s.close()


def start_fixture(out, bind, sans):
    if not os.path.exists(FIXTURE):
        raise SystemExit(f"no fixture server at {FIXTURE}: run ./gradlew :fixture:installDist in testapks/composeapp")
    args = [FIXTURE, "--bind", bind, "--out", out, "--hls", HLS]
    for s in sans:
        args += ["--san", s]
    log = open(os.path.join(out, "fixture.log"), "w")
    p = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT)
    for _ in range(200):
        time.sleep(0.1)
        # The line, wherever it is: a JVM may print something first (JAVA_TOOL_OPTIONS, a warning).
        ready = [l for l in open(os.path.join(out, "fixture.log")).read().splitlines() if l.startswith("ready ")]
        if ready:
            return p, json.loads(ready[0][6:]), open(os.path.join(out, "cert.b64")).read().strip()
        if p.poll() is not None:
            break
    raise SystemExit("the fixture server did not start:\n" + open(os.path.join(out, "fixture.log")).read())


def urls(host, ports, cert, only):
    out = []
    if "grpc" in only:
        out.append(("grpc", f"apksim://grpc?tls={host}:{ports['grpcTls']}&plain={host}:{ports['grpcPlain']}&cert={cert}"))
    if "ws" in only:
        out.append(("ws", f"apksim://websocket?url=wss://{host}:{ports['https']}/ws&plain=ws://{host}:{ports['http']}/ws&cert={cert}"))
    if "media" in only:
        out.append(("media", "apksim://media"))
    if "hls" in only:
        out.append(("media", f"apksim://media?only=hls&hls=https://{host}:{ports['https']}/hls/index.m3u8&cert={cert}"))
    return out


class Apksim:
    """The app in apksim, headless, its log (the app's Log lines with APKSIM_LOGCAT=1) read from its output."""

    def __init__(self, apk, out, port):
        self.log_path = os.path.join(out, "apksim.log")
        self.port = port
        # Another instance on the port (one an earlier run left) would take every command meant for this one.
        try:
            socket.create_connection(("127.0.0.1", port), timeout=1).close()
            raise SystemExit(f"something already answers on port {port}: quit it (scripts/drive.py {port} quit) or pass --port")
        except OSError:
            pass
        env = dict(os.environ, APKSIM_LOGCAT="1")
        self.p = subprocess.Popen([os.path.join(ROOT, "apksim"), "--headless", "--fresh", "--audio", "mute", "--rec", "net",
                                   "--control", str(port), apk], stdout=open(self.log_path, "w"), stderr=subprocess.STDOUT, env=env, cwd=out)
        for _ in range(600):
            if self.p.poll() is not None:
                raise SystemExit(f"apksim ended (status {self.p.returncode}) before opening its control port; see {self.log_path}")
            try:
                socket.create_connection(("127.0.0.1", port), timeout=1).close()
                return
            except OSError:
                time.sleep(0.1)
        self.p.kill()
        raise SystemExit("apksim never opened its control port; see " + self.log_path)

    def cmd(self, c, timeout=60):
        s = socket.create_connection(("127.0.0.1", self.port), timeout=timeout)
        f = s.makefile("rw")
        f.write(c + "\n")
        f.flush()
        r = f.readline().strip()
        s.close()
        return r

    def lines(self):
        out = []
        for l in open(self.log_path, errors="replace"):
            m = re.match(r"I/apksim-(?:media|protocols): (.*)", l.rstrip("\n"))
            if m:
                out.append(m.group(1))
        return out

    def run(self, url):
        start = len(self.lines())
        self.cmd("open " + url)
        return start

    def close(self):
        try:
            self.cmd("quit", timeout=10)
        except OSError:
            pass
        try:
            self.p.wait(30)
        except subprocess.TimeoutExpired:
            self.p.kill()


class Device:
    """The app on an adb device, its lines read from logcat."""

    def __init__(self, apk, serial, out):
        self.serial = serial
        self.env = dict(os.environ, ANDROID_SERIAL=serial)
        had = "package:dev.apksim.composeapp" in self.adb("shell", "pm", "list", "packages", "dev.apksim.composeapp").split()
        self.installed_here = not had
        r = self.adb("install", "-r", apk)
        if "Success" not in r:
            raise SystemExit("adb install failed: " + r)
        self.log_path = os.path.join(out, "logcat.txt")

    def adb(self, *a):
        return subprocess.run(["adb", *a], capture_output=True, text=True, env=self.env).stdout

    def lines(self):
        text = self.adb("logcat", "-d", "-v", "raw", "-s", "apksim-media:I", "apksim-protocols:I")
        open(self.log_path, "w").write(text)
        return [l for l in text.splitlines() if l and not l.startswith("-")]

    def run(self, url):
        self.adb("logcat", "-c")
        self.adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", f"'{url}'")
        return 0

    def close(self):
        self.adb("shell", "am", "force-stop", "dev.apksim.composeapp")
        if self.installed_here:
            self.adb("uninstall", "dev.apksim.composeapp")


def wait_done(runner, prefix, start, seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        got = runner.lines()[start:]
        if any(l.startswith(prefix + ": done") for l in got):
            return got
        time.sleep(1)
    return runner.lines()[start:]


def compare(platform, results):
    want = EXPECTED[platform]
    seen = {}
    for line in results:
        m = re.match(r"(.+?): (ok|unsupported|FAIL)\b ?(.*)", line)
        if not m or m.group(1) in ("grpc", "ws", "media"):
            continue
        seen[m.group(1)] = (m.group(2), m.group(3))
    for name, (status, detail) in seen.items():
        w = want.get(name)
        if w is None:
            check(False, f"{name}: not expected on {platform} ({status} {detail})")
            continue
        ws = w[0] if isinstance(w, tuple) else w
        if status == ws:
            check(True, f"{name}: {status}" + (f" (known: {w[1]})" if isinstance(w, tuple) else "") + (f" {detail}" if status == "ok" else ""))
        else:
            check(False, f"{name}: {status} {detail}, expected {ws}" + (f" (known: {w[1]}; fixed? update EXPECTED)" if isinstance(w, tuple) and status == "ok" else ""))
    return seen


def check_recording(path, host, ports):
    """The flight recording's network.har: what the tap must have read of the gRPC, WebSocket and HLS traffic."""
    if not check(os.path.exists(path) and zipfile.is_zipfile(path), f"the recording was written ({path})"):
        return
    z = zipfile.ZipFile(path)
    if not check("network.har" in z.namelist(), "the recording has network.har"):
        return
    log = json.loads(z.read("network.har"))["log"]
    entries = log["entries"]

    def find(part, scheme=None):
        return [e for e in entries if part in e["request"]["url"] and (scheme is None or e["request"]["url"].startswith(scheme + "://"))]

    def trailer(e, name):
        for h in e["response"].get("_apksimTrailers", []):
            if h["name"] == name:
                return h["value"]
        return None

    for scheme in ("https", "http"):
        label = "tls" if scheme == "https" else "plain"
        u = find("/apksim.demo.Echo/Unary", scheme)
        check(bool(u) and u[0]["request"]["httpVersion"] == "HTTP/2" and u[0]["response"]["status"] == 200 and trailer(u[0], "grpc-status") == "0",
              f"recording: gRPC {label} unary is an HTTP/2 exchange with grpc-status 0 in its trailers")
        f = find("/apksim.demo.Echo/Fail", scheme)
        check(bool(f) and (trailer(f[0], "grpc-status") == "3" or any(h["name"] == "grpc-status" and h["value"] == "3" for h in f[0]["response"]["headers"])),
              f"recording: gRPC {label} fail carries grpc-status 3 (INVALID_ARGUMENT)")
        s = find("/apksim.demo.Echo/Slow", scheme)
        check(bool(s) and "CANCEL" in str(s[0].get("_apksimError", "")), f"recording: gRPC {label} deadline: the stream reset at the deadline, with the reason ({s[0].get('_apksimError') if s else None})")
        b = find("/apksim.demo.Echo/Big", scheme)
        check(bool(b) and b[0]["response"]["bodySize"] >= 300_000, f"recording: gRPC {label} big: a 300 KB answer over many DATA frames ({b[0]['response']['bodySize'] if b else None})")
        c = find("/apksim.demo.Echo/Count", scheme)
        check(bool(c) and c[0]["response"]["bodySize"] >= 5 * 12, f"recording: gRPC {label} stream: the server's 5 messages")
        ws = [e for e in find("/ws", "wss" if scheme == "https" else "ws") + find("/ws", scheme) if e["response"]["status"] == 101]
        msgs = ws[0].get("_webSocketMessages", []) if ws else []
        texts = [(m["type"], m["data"]) for m in msgs if m["opcode"] == 1]
        check(("send", "hello") in texts and ("receive", "echo: hello") in texts, f"recording: WebSocket {label}: the text echo, both ways ({len(msgs)} messages)")
        deflated = bool(ws) and "permessage-deflate" in str([h["value"] for h in ws[0]["response"]["headers"] if h["name"].lower() == "sec-websocket-extensions"])
        check(deflated, f"recording: WebSocket {label}: permessage-deflate agreed, so the messages went compressed")
        check(any(t == "receive" and d == "echo: " + "x" * 200_000 for t, d in texts),
              f"recording: WebSocket {label}: the 200,000-character echo, whole (inflated)")
        check(sum(1 for t, d in texts if t == "receive" and d.startswith("burst ")) == 5, f"recording: WebSocket {label}: the server's burst of 5")
    seg = find("/hls/seg")
    check(len(seg) >= 2 and all(e["response"]["status"] == 200 for e in seg), f"recording: the HLS segments ({len(seg)})")
    conns = log.get("_apksimConnections", [])
    h2 = [c for c in conns if c.get("port") == ports["grpcTls"] and c.get("tls", {}).get("alpn") == "h2" and c.get("protocol") == "h2"]
    check(bool(h2), "recording: the TLS gRPC connection is listed, its ALPN choice h2")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("apk")
    ap.add_argument("--serial", help="run on this adb device instead of apksim")
    ap.add_argument("--only", default="grpc,ws,media,hls")
    ap.add_argument("--out", default=os.path.join(ROOT, "work/media-protocols-check"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("MEDIACHECK_PORT", "7497")))
    a = ap.parse_args()
    only = set(a.only.split(","))
    out = os.path.abspath(a.out)
    os.makedirs(out, exist_ok=True)
    if a.serial:
        platform, bind, host, sans = "device", "127.0.0.1", "10.0.2.2", ["10.0.2.2"]
    else:
        platform, bind = "apksim", lan_address()
        if bind is None:
            raise SystemExit("no LAN address: apksim leaves loopback alone, so the fixture must be on another address")
        host, sans = bind, []
    fixture, ports, cert = start_fixture(out, bind, sans)
    runner = None
    results = []
    try:
        runner = Device(a.apk, a.serial, out) if a.serial else Apksim(os.path.abspath(a.apk), out, a.port)
        for prefix, url in urls(host, ports, cert, only):
            print(f"== {url.split('?')[0]}" + (" (hls)" if "only=hls" in url else ""))
            start = runner.run(url)
            got = wait_done(runner, prefix, start, 240 if url == "apksim://media" else 90)
            if not any(l.startswith(prefix + ": done") for l in got):
                check(False, f"{prefix}: never finished ({len(got)} lines); see {runner.log_path}")
            results += got
            if not a.serial:
                runner.cmd("back")
                time.sleep(1)
        seen = compare(platform, results)
        # Every check the screens that ran should have given: a line that never came is a failure too.
        ran = ({"grpc "} if "grpc" in only else set()) | ({"ws "} if "ws" in only else set()) \
            | ({"play ", "codec ", "encode "} if "media" in only else set()) | ({"hls "} if "hls" in only else set())
        missing = [n for n in EXPECTED[platform] if any(n.startswith(p) for p in ran) and n not in seen]
        check(not missing, f"every expected check ran on {platform}" + (f": missing {missing}" if missing else ""))
        if not a.serial and only >= {"grpc", "ws", "hls"}:
            print("== the recording (--rec net)")
            rec = os.path.join(out, "net.apkrec")
            if os.path.exists(rec):
                os.remove(rec)
            check(runner.cmd(f"rec save 300 {rec}").startswith("saving "), "rec save")
            for _ in range(60):
                if os.path.exists(rec) and zipfile.is_zipfile(rec):
                    break
                time.sleep(0.5)
            check_recording(rec, host, ports)
    finally:
        if runner:
            runner.close()
        fixture.stdin.close()
        try:
            fixture.wait(10)
        except subprocess.TimeoutExpired:
            fixture.kill()
    print()
    if problems:
        print(f"{len(problems)} check(s) failed")
        sys.exit(1)
    print("all ok")


if __name__ == "__main__":
    main()
