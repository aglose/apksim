#!/usr/bin/env python3
"""Checks `apksim mcp` (docs/mcp.md): speaks the protocol to the server over stdio (both eras), runs a journey on the
test app, and measures an action's answer against a full nodes dump.

  mcp-check.py [--apk APK] [--keep DIR]

Journey: tap the FAB and read the new count from the answer, rename a trip on Trips, dark appearance, save a recording,
and the refusals (an APK outside the allowed folders, a launch option off the allow-list, a path in a record name).
"""
import argparse, json, os, subprocess, sys, tempfile, time, zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.realpath(__file__)))
ap = argparse.ArgumentParser()
ap.add_argument("--apk", default=os.path.join(ROOT, "testapks/composeapp/app/build/outputs/apk/debug/app-debug.apk"))
ap.add_argument("--keep", help="output folder to keep (default: a temporary one)")
args = ap.parse_args()
apk = os.path.realpath(args.apk)
out = os.path.realpath(args.keep or tempfile.mkdtemp(prefix="mcp-check-"))
os.makedirs(out, exist_ok=True)
fails = []


def check(ok, what):
    print(("ok   " if ok else "FAIL ") + what, flush=True)
    if not ok:
        fails.append(what)


def start():
    return subprocess.Popen([os.path.join(ROOT, "apksim"), "mcp", "--apps", os.path.dirname(apk), "--out", out],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)


class Client:
    def __init__(self, proc, modern):
        self.p, self.n, self.modern = proc, 0, modern

    def rpc(self, method, params=None):
        self.n += 1
        params = dict(params or {})
        if self.modern and "_meta" not in params:
            params["_meta"] = {"io.modelcontextprotocol/protocolVersion": "2026-07-28",
                               "io.modelcontextprotocol/clientInfo": {"name": "mcp-check", "version": "1"},
                               "io.modelcontextprotocol/clientCapabilities": {}}
        self.p.stdin.write(json.dumps({"jsonrpc": "2.0", "id": self.n, "method": method, "params": params}) + "\n")
        self.p.stdin.flush()
        line = self.p.stdout.readline()
        if not line:
            raise SystemExit("server closed stdout")
        r = json.loads(line)
        assert r["id"] == self.n, r
        return r

    def tool(self, _tool, **a):
        r = self.rpc("tools/call", {"name": _tool, "arguments": a})
        if "error" in r:
            return json.dumps(r["error"]), {"isError": True, "content": [], "protocolError": r["error"]}
        res = r["result"]
        return "".join(x.get("text", "") for x in res["content"] if x["type"] == "text"), res

    def ok(self, _tool, **a):
        t, res = self.tool(_tool, **a)
        if res.get("isError"):
            raise SystemExit("%s failed: %s" % (_tool, t))
        return t


# --- legacy era: initialize handshake
p = start()
c = Client(p, False)
r = c.rpc("initialize", {"protocolVersion": "2025-11-25", "capabilities": {}, "clientInfo": {"name": "x", "version": "1"}})
check(r["result"]["protocolVersion"] == "2025-11-25" and "tools" in r["result"]["capabilities"], "legacy initialize")
p.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n")
check(len(c.rpc("tools/list")["result"]["tools"]) > 10, "legacy tools/list")
p.stdin.close()
p.wait(20)

# --- modern era
p = start()
c = Client(p, True)
r = c.rpc("server/discover")["result"]
check("2026-07-28" in r["supportedVersions"] and r["resultType"] == "complete", "server/discover")
bad = c.rpc("tools/list", {"_meta": {"io.modelcontextprotocol/protocolVersion": "1900-01-01"}})
check(bad.get("error", {}).get("code") == -32022 and "supported" in bad["error"]["data"], "unsupported version error")
tl = c.rpc("tools/list")["result"]
size = len(json.dumps(tl["tools"], separators=(",", ":")))
print("tools/list: %d tools, %d bytes (~%d tokens)" % (len(tl["tools"]), size, size // 4))
check(c.rpc("tools/call", {"name": "nope", "arguments": {}}).get("error", {}).get("code") == -32602,
      "unknown tool is a protocol error")

# refusals
t, res = c.tool("launch", apk="/etc/hosts")
check(res.get("isError"), "apk outside the allowed folders refused")
t, res = c.tool("launch", apk=apk, options=["--jvm", "-Dx=y"])
check(res.get("isError") and "not allowed" in t, "launch option off the allow-list refused")
t, res = c.tool("nodes")
check(res.get("isError"), "action with no instance is an error")
t, res = c.tool("launch", apk=apk, options=["--camera", "fake=/etc/hosts"])
check(res.get("isError"), "--camera with a file path refused")
p.stdin.write('{"jsonrpc":"2.0","id":99,"method":"tools/list","params":[1]}\n')
p.stdin.flush()
check(json.loads(p.stdout.readline()).get("error", {}).get("code") == -32602, "params that are not an object: an error, server lives")

t = c.ok("launch", apk=apk)
print("launch answer:", len(t), "bytes")
check("MainActivity" in t and "FAB clicks: 0" in t, "launch answers with the screen")
fab = [l.split()[0] for l in t.splitlines() if l.endswith(" click") and '"' not in l]
check(len(fab) == 1, "the FAB is the one unlabelled clickable")
c.ok("record", action="start")
c.ok("tap", text="Trips")
t = c.ok("type", tap_text="Trip note", text="a  b ")
check("a  b" in t or "typed 5 chars" in t, "type keeps its spaces: " + t.splitlines()[0])
c.ok("tap", text="Home")

t = c.ok("tap", ref=fab[0])
print("tap answer:\n" + t)
check('"FAB clicks: 0" -> "FAB clicks: 1"' in t, "tap the FAB, read the new count from the change")
t2 = c.ok("tap", ref=fab[0])
check('"FAB clicks: 1" -> "FAB clicks: 2"' in t2, "the reference stays valid: second tap")
action_bytes = len(t2)

t = c.ok("tap", text="Trips")
check("Italian vacation" in t and "Tokyo weekend" in t, "tap by text switches tab")
t = c.ok("tap", text="Italian vacation")
check('"Roman vacation"' in t and "Italian" in t, "rename a trip: the answer shows the new name")
full = c.ok("nodes", bounds=True)
print("action answer %d bytes, nodes dump %d bytes (%.0f%%)" % (action_bytes, len(full), 100.0 * action_bytes / len(full)))
print("rename answer %d bytes" % len(t))
check(action_bytes < len(full) / 2, "a typical answer is under half a full dump")

t = c.ok("device", setting="appearance", value="dark")
check("appearance dark" in t, "dark appearance")
r = c.tool("screenshot")[1]
img = [x for x in r["content"] if x["type"] == "image"]
check(len(img) == 1 and len(img[0]["data"]) > 100, "screenshot returns an image")
print("screenshot (640 px): %d bytes base64" % len(img[0]["data"]))
check(c.tool("device", setting="appearance", value="; rm -rf")[1].get("isError"), "device value validated")
check(c.tool("record", action="save", name="../evil")[1].get("isError"), "record name cannot hold a path")
t = c.ok("record", action="save", name="journey")
path = os.path.join(out, "journey.apkrec")
check(os.path.exists(path) and zipfile.is_zipfile(path), "recording saved to the output folder: " + t)
check(c.ok("activity").endswith("MainActivity"), "activity")
check("[apksim]" not in c.ok("logcat", lines=5), "logcat tail has app lines only")
check("not on screen" in c.tool("tap", ref="e999")[0], "stale ref reports itself")
check(c.tool("open_url", url="file:///etc/hosts")[1].get("isError"), "file: links refused")
c.ok("quit")
p.stdin.close()
p.wait(30)
check(p.returncode == 0, "server exits cleanly")
# --- no call blocks for ever: a stand-in launcher whose instance goes quiet (needs the timeouts to pass)
import signal, textwrap
FAKE = textwrap.dedent("""\
    #!/usr/bin/env python3
    import os, socket, sys, threading, time
    mode = os.environ["FAKE_MODE"]
    srv = socket.socket(); srv.bind(("127.0.0.1", 0)); srv.listen(8)
    print("[apksim] control port %d" % srv.getsockname()[1], file=sys.stderr, flush=True)
    def serve(c):
        f = c.makefile("rw")
        for line in f:
            w = line.split()[0]
            if mode == "silent" or (mode == "hangtap" and w == "tap"):
                time.sleep(3600)
            if mode == "err" and w not in ("nodes", "activity", "config", "settle", "quit"):
                f.write("error: nope\\n"); f.flush(); continue
            if w == "quit":
                f.write("ok\\n"); f.flush(); os._exit(0)
            f.write({"nodes": '[0,0,10,10] text="Hello" ; ' + ('[0,20,10,30] text="Hello" ; ' if mode == "dup" else ''), "activity": "x.Main", "config": "mBounds=Rect(0, 0 - 822, 1782)"}.get(w, "ok") + "\\n"); f.flush()
    while True:
        c, _ = srv.accept(); threading.Thread(target=serve, args=(c,), daemon=True).start()
""")
fake = os.path.join(out, "fake-apksim")
open(fake, "w").write(FAKE)
os.chmod(fake, 0o755)


def stuck(mode, call, launch=4):
    os.environ["FAKE_MODE"] = mode
    q = subprocess.Popen([sys.executable, os.path.join(ROOT, "scripts/mcp.py"), "--apps", os.path.dirname(apk), "--out", out,
                          "--apksim", fake, "--call-timeout", str(call), "--launch-timeout", str(launch)],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
    signal.alarm(60)  # without the bound these would wait for ever: the check fails
    return q, Client(q, True)


t0 = time.time()
q, qc = stuck("silent", 3)
t, res = qc.tool("launch", apk=apk)
check(res.get("isError") and time.time() - t0 < 20, "launch whose instance never answers ends in an error (%.0f s)" % (time.time() - t0))
q.stdin.close(); q.wait(30)
t0 = time.time()
q, qc = stuck("hangtap", 3)
qc.ok("launch", apk=apk)
t, res = qc.tool("tap", x=5, y=5)
check(res.get("isError") and "did not answer" in t and time.time() - t0 < 20, "a tap the app never answers ends in an error")
check("Hello" in qc.ok("nodes"), "the next call works on a new connection")
q.stdin.close(); q.wait(30)
# --- an instance's `error:` answer is the tool's error; a targeted swipe names one node; a fractional density passes
q, qc = stuck("err", 5)
qc.ok("launch", apk=apk)
for tool, kw in (("tap", dict(x=5, y=5)), ("key", dict(key="ENTER")), ("back", {}), ("swipe", dict(direction="up")),
                 ("device", dict(setting="appearance", value="dark")), ("open_url", dict(url="apksim://x"))):
    t, res = qc.tool(tool, **kw)
    check(res.get("isError") and "error: nope" in t, "%s: the instance's error is the tool's error" % tool)
q.stdin.close(); q.wait(30)
q, qc = stuck("dup", 5)
qc.ok("launch", apk=apk)
t, res = qc.tool("swipe", text="Hello", direction="up")
check(res.get("isError") and "2 nodes match" in t, "a swipe at a text two nodes share is refused, not sent to the first")
t, res = qc.tool("device", setting="device", value="1080x2400@2.625")
check(not res.get("isError"), "a fractional density is accepted: " + t.splitlines()[0])
q.stdin.close(); q.wait(30)
signal.alarm(0)
print("output folder:", sorted(os.listdir(out)))
print("FAILED: %d" % len(fails) if fails else "all checks passed")
sys.exit(1 if fails else 0)
