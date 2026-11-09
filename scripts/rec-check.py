#!/usr/bin/env python3
"""Checks the flight recorder (docs/flight-recorder.md) against the test app and the network fixture.

Usage: rec-check.py APP.apk [OUT]

Launches the app headless with `--rec all` and the fixture server mapped in, then: switches to the Trips tab (SQLite),
opens the network screen and fetches over OkHttp and HttpURLConnection, sets the clipboard (left out of the
recording), turns the screen channel off and on again, takes a checkpoint and reads it back (`rec checkpoint
verify`), saves the last 60 s and checks the bundle: manifest (format, package, APK hash, checkpoint and its device),
input (the taps as down/up events, the `open` commands, the clipboard text left out), logcat lines, the HAR (both
fetches, Authorization redacted), db calls (the trips table, no values), a well-formed Motion-JPEG AVI, and the
checkpoint (state.bin, data.zip in SlotPack's format with the trips database). Then a second launch crashes on
purpose (apksim://crash?kind=post) and must leave a `-crash.apkrec` whose manifest has the exception. OUT (default
work/rec-check) is emptied first.

Also: pasted text is left out, the checkpoint holds the databases in a read transaction (no "cloned as a file" note,
no -shm in data.zip), a rotation keeps the movie's length (one frame per slot, fitted), two saves at once write two
files, `rec clear` and `rec off logcat` keep what came before out of the next save, and a save followed at once by
`quit` is still written.
"""
import argparse, json, os, re, shutil, socket, struct, subprocess, sys, time, zipfile, io

ROOT = os.path.abspath(os.path.dirname(os.path.abspath(sys.argv[0])) + "/..")
PORT = int(os.environ.get("RECCHECK_PORT", "7493"))
problems = []


def check(ok, what):
    print(("  ok    " if ok else "  FAIL  ") + what)
    if not ok:
        problems.append(what)
    return ok


class Drive:
    def __init__(self, port):
        for _ in range(600):
            try:
                self.s = socket.create_connection(("127.0.0.1", port), timeout=1)
                break
            except OSError:
                time.sleep(0.1)
        else:
            raise SystemExit("the app never opened its control port")
        self.s.settimeout(60)
        self.f = self.s.makefile("rw")

    def __call__(self, cmd):
        self.f.write(cmd + "\n")
        self.f.flush()
        return self.f.readline().strip()

    def nodes(self):
        return self("nodes")

    def tap_text(self, text):
        for m in re.finditer(r'\[(\d+),(\d+),(\d+),(\d+)\] text="((?:[^"\\]|\\.)*)"', self.nodes()):
            if m.group(5) == text:
                x1, y1, x2, y2 = map(int, m.groups()[:4])
                return self(f"tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
        raise SystemExit(f'no "{text}" on screen')

    def wait_text(self, prefix, bad=("idle", "fetching"), seconds=30):
        t = ""
        for _ in range(seconds * 2):
            time.sleep(0.5)
            for m in re.finditer(r'text="((?:[^"\\]|\\.)*)"', self.nodes()):
                if m.group(1).startswith(prefix):
                    t = m.group(1)
            if t and not any(t.endswith(b) for b in bad):
                return t
        return t


def avi(data):
    if data[:4] != b"RIFF" or data[8:12] != b"AVI " or struct.unpack("<I", data[4:8])[0] != len(data) - 8:
        return "bad RIFF header or size"
    i = data.index(b"avih")
    usec, _, _, _, frames, _, _, _, w, h = struct.unpack("<10I", data[i + 8:i + 48])
    if b"MJPG" not in data[:300]:
        return "not MJPG"
    i = data.index(b"idx1")
    n = struct.unpack("<I", data[i + 4:i + 8])[0] // 16
    first = struct.unpack("<I", data[i + 16:i + 20])[0] if n else 0
    movi = data.index(b"movi")
    jpeg_ok = n == 0 or data[movi + first + 8:movi + first + 10] == b"\xff\xd8"
    return w, h, round(1_000_000 / usec), frames, n, jpeg_ok


def launch(out, log, *extra):
    p = subprocess.Popen([os.path.join(ROOT, "apksim"), "--headless", "--fresh", "--control", str(PORT), *extra, a.app],
                         stdout=open(os.path.join(out, log), "w"), stderr=subprocess.STDOUT, cwd=out)
    return p


def main():
    global a
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("app")
    ap.add_argument("out", nargs="?", default=os.path.join(ROOT, "work/rec-check"))
    a = ap.parse_args()
    a.app = os.path.abspath(a.app)
    out = os.path.abspath(a.out)
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(os.path.join(out, "fixture"))
    try:
        socket.create_connection(("127.0.0.1", PORT), timeout=0.5).close()
        raise SystemExit(f"port {PORT} already has something on it; set RECCHECK_PORT")
    except OSError:
        pass
    fixture_py = os.path.join(ROOT, "scripts/network-fixture.py")
    subprocess.run([sys.executable, fixture_py, os.path.join(out, "fixture"), "--cert-only"], check=True)
    fixture = subprocess.Popen([sys.executable, fixture_py, os.path.join(out, "fixture")],
                               stdout=open(os.path.join(out, "fixture.log"), "w"), stderr=subprocess.STDOUT)
    app = None
    try:
        fport = None
        for _ in range(100):
            m = re.search(r"^ready (\d+)", open(os.path.join(out, "fixture.log")).read(), re.M)
            if m:
                fport = m.group(1)
                break
            time.sleep(0.1)
        if not fport:
            raise SystemExit("the fixture server did not start")
        bundle_dir = os.path.join(out, "bundles")
        app = launch(out, "rec.log", "--rec", "all", "--rec-checkpoint", "5",
                     "--network-host", f"api.apksim.test=127.0.0.1:{fport}",
                     "--network-trust", os.path.join(out, "fixture/cert.pem"))
        d = Drive(PORT)
        time.sleep(2.5)
        print("== channels")
        status = d("rec")
        check(status.startswith("rec: input logcat net db screen"), f"all channels on at launch: {status[:80]}")
        d("tap 300 1690")  # Trips
        time.sleep(1.5)
        d("open apksim://network")
        time.sleep(2.5)
        d.tap_text("Fetch")
        r1 = d.wait_text("okhttp:")
        check(r1.startswith("okhttp: 200"), f"OkHttp fetch through the capture: {r1}")
        d.tap_text("URL")
        r2 = d.wait_text("urlconnection:")
        check(r2.startswith("urlconnection: 200"), f"HttpURLConnection fetch through the capture: {r2}")
        check(d("clipboard set a-secret-clipboard-value").startswith("ok"), "clipboard set")
        pasted = d("paste")
        print("  ..    " + pasted)
        check(d("rec off screen") == "screen: off", "screen off on the fly")
        time.sleep(0.5)
        check(d("rec on screen") == "screen: on", "screen on again")
        d("back")
        time.sleep(1.5)
        print("== checkpoint")
        cp = d("rec checkpoint now")
        check(cp.startswith("checkpoint #"), f"checkpoint now: {cp}")
        check("cloned as a file" not in cp and "cloned as files" not in cp, "databases held in a read transaction while cloned")
        v = d("rec checkpoint verify")
        check(" ok: " in v and "MainActivity" in v and "data " in v, f"checkpoint reads back: {v}")
        d("rotate landscape")
        time.sleep(2)
        d("rotate portrait")
        time.sleep(2)
        d("tap 733 1533")
        time.sleep(1)
        print("== save")
        s = d("rec save 60 " + os.path.join(bundle_dir, "window.apkrec"))
        check(s.startswith("saving "), f"save: {s}")
        status = ""
        for _ in range(60):
            time.sleep(0.5)
            status = d("rec")
            if "last save:" in status and "writing" not in status.split("last save:")[1]:
                break
        check("KB in" in status.split("last save:")[-1], "saved: " + status.split("last save:")[-1].strip())

        print("== two saves at once")
        s1, s2 = d("rec save 20"), d("rec save 20")
        f1, f2 = (re.match(r"saving (\S+)", x) for x in (s1, s2))
        if check(bool(f1 and f2) and f1.group(1) != f2.group(1), f"two files: {s1[:90]} / {s2[:90]}"):
            for _ in range(60):
                # Each name is reserved as an empty file at once and replaced when the bundle is written.
                if all(os.path.isfile(f) and os.path.getsize(f) > 0 for f in (f1.group(1), f2.group(1))):
                    break
                time.sleep(0.5)
            for f in (f1.group(1), f2.group(1)):
                ok = os.path.isfile(f) and zipfile.is_zipfile(f) and "manifest.json" in zipfile.ZipFile(f).namelist()
                check(ok, "a whole bundle: " + os.path.basename(f))
                if os.path.isfile(f):
                    shutil.move(f, os.path.join(bundle_dir, os.path.basename(f)))
            leftovers = [f for f in os.listdir(os.path.dirname(f1.group(1))) if f.endswith(".part") or f.endswith(".tmp")]
            check(not leftovers, f"no part files left: {leftovers}")

        print("== clear, logcat off")
        check(d("rec off logcat") == "logcat: off", "logcat off")
        check(d("rec clear") == "cleared", "clear")
        d("tap 130 1690")
        time.sleep(1.5)
        s = d("rec save 60 " + os.path.join(bundle_dir, "cleared.apkrec"))
        check(s.startswith("saving "), f"save after clear: {s}")
        print("== save, then quit at once")
        s = d("rec save 60 " + os.path.join(bundle_dir, "quit.apkrec"))
        d("quit")
        app.wait(60)
        app = None
        check(os.path.isfile(os.path.join(bundle_dir, "quit.apkrec")), "the save before quit was written")
        check(not [f for f in os.listdir(bundle_dir) if f.endswith(".part") or f.endswith(".tmp")], "no part file left behind")
        check_bundle(os.path.join(bundle_dir, "window.apkrec"))
        print("== bundle cleared.apkrec")
        z = zipfile.ZipFile(os.path.join(bundle_dir, "cleared.apkrec"))
        check(z.read("logcat.txt") == b"", "no logcat from before the clear or while it was off")
        har = json.loads(z.read("network.har"))["log"]["entries"] if "network.har" in z.namelist() else []
        check(har == [], f"no network exchanges from before the clear ({len(har)})")
        cmds = [json.loads(l).get("cmd") for l in z.read("input.jsonl").decode().splitlines()]
        check(["open", "apksim://network"] not in cmds and "db.jsonl" not in z.namelist(), "no input or db calls from before the clear")

        print("== crash")
        app = launch(out, "crash.log", "--rec", "input,logcat,screen")
        d = Drive(PORT)
        time.sleep(2.5)
        d("tap 300 1690")
        time.sleep(1.5)
        try:
            d("open apksim://crash?kind=post")
        except OSError:
            pass
        code = app.wait(60)
        app = None
        check(code == 10, f"the crash ended the app with exit 10 (got {code})")
        rec_dir = os.path.join(ROOT, "work/screenshots")
        crashes = sorted((f for f in os.listdir(rec_dir) if f.endswith("-crash.apkrec")),
                         key=lambda f: os.path.getmtime(os.path.join(rec_dir, f))) if os.path.isdir(rec_dir) else []
        if check(bool(crashes), "a crash bundle was saved"):
            path = os.path.join(rec_dir, crashes[-1])
            check(time.time() - os.path.getmtime(path) < 120, f"it is this run's: {crashes[-1]}")
            z = zipfile.ZipFile(path)
            man = json.loads(z.read("manifest.json"))
            check("RuntimeException" in man.get("crash", "") or "Exception" in man.get("crash", ""),
                  "its manifest has the exception: " + man.get("crash", "").splitlines()[0][:100] if man.get("crash") else "no crash field")
            check("checkpoint" in man and "checkpoint/data.zip" in z.namelist(), "and a checkpoint to replay from")
            shutil.move(path, os.path.join(bundle_dir, crashes[-1]))
    finally:
        if app is not None:
            try:
                Drive(PORT)("quit")
            except Exception:
                app.kill()
        fixture.kill()
    print()
    if problems:
        print(f"{len(problems)} problem(s):")
        for p in problems:
            print("  " + p)
        sys.exit(1)
    print("all ok")


def check_bundle(path):
    print("== bundle " + os.path.basename(path))
    if not check(os.path.isfile(path), "the bundle exists"):
        return
    z = zipfile.ZipFile(path)
    names = set(z.namelist())
    for n in ["manifest.json", "input.jsonl", "logcat.txt", "network.har", "db.jsonl", "screen.avi",
              "checkpoint/state.bin", "checkpoint/stack.json", "checkpoint/data.zip"]:
        check(n in names, "has " + n)
    man = json.loads(z.read("manifest.json"))
    check(man.get("format") == "apksim-flight-recording/1", "format")
    check(man.get("package") == "dev.apksim.composeapp", "package")
    check(re.fullmatch(r"[0-9a-f]{64}", man.get("apkSha256", "")) is not None, "APK hash")
    cp = man.get("checkpoint", {})
    check(cp.get("activities") == ["dev.apksim.composeapp.MainActivity"], f"checkpoint stack {cp.get('activities')}")
    check("qualifiers" in cp.get("device", {}), "checkpoint has the device's qualifiers")
    events = [json.loads(l) for l in z.read("input.jsonl").decode().splitlines()]
    evs = [e.get("ev") for e in events]
    check(evs.count("down") >= 3 and evs.count("up") >= 3, f"taps as down/up events ({evs.count('down')} down)")
    check(all(e["t"] >= cp.get("uptime", 0) for e in events), "input starts at the checkpoint")
    cmds = [e["cmd"] for e in events if "cmd" in e]
    check(any(c[:2] == ["open", "apksim://network"] for c in cmds) or cp.get("uptime", 0) > 0, f"device commands kept: {cmds}")
    raw = z.read("input.jsonl").decode()
    check("a-secret-clipboard-value" not in raw, "clipboard text left out of the input")
    chars = [e for e in events if e.get("ev") == "char"]
    pasted = [e for e in chars if e.get("pasted")]
    check(all(e.get("redacted") and "v" not in e for e in pasted) and not [e for e in chars if "v" in e and e["v"] in map(ord, "a-secret")],
          f"pasted characters left out ({len(pasted)} pasted)")
    log = z.read("logcat.txt").decode()
    check(len(log.splitlines()) > 5, f"logcat lines ({len(log.splitlines())})")
    har = json.loads(z.read("network.har"))["log"]["entries"]
    urls = sorted(e["request"]["url"] for e in har)
    check("https://api.apksim.test/v1/greeting" in urls and "https://api.apksim.test/v1/plain" in urls, f"HAR has both fetches: {urls}")
    for e in har:
        auth = [h["value"] for h in e["request"]["headers"] if h["name"].lower() == "authorization"]
        check(auth in ([], ["REDACTED"]), f"Authorization redacted in {e['request']['url']}")
    check(b"test-token-123" not in z.read("network.har"), "no token in the HAR")
    db = [json.loads(l) for l in z.read("db.jsonl").decode().splitlines()]
    check(any(r.get("db") == "trips.db" and "trips" in r.get("sql", "") for r in db), f"db calls on trips.db ({len(db)})")
    check(not any("Italian vacation" in json.dumps(r) or "Roman vacation" in json.dumps(r) for r in db), "no values in db calls")
    v = avi(z.read("screen.avi"))
    if check(isinstance(v, tuple), f"screen.avi is a Motion-JPEG AVI: {v}"):
        w, h, fps, frames, n, jpeg_ok = v
        check(frames == n and frames > 10 and jpeg_ok, f"{w}x{h} at {fps} fps, {frames} frames, index {n}")
        check(max(w, h) <= 960, "scaled down")
        times = [int(t) for t in z.read("screen-times.txt").decode().split()]
        t0 = max(man["window"]["from"], times[0])
        slots = (man["window"]["to"] - t0) // (1000 // fps) + 1
        check(frames == slots, f"one movie frame per slot through the rotation ({frames} of {slots})")
    data = zipfile.ZipFile(io.BytesIO(z.read("checkpoint/data.zip")))
    dn = data.namelist()
    check("package" in dn and data.read("package") == b"dev.apksim.composeapp", "data.zip names the package")
    check(any(n.startswith("slot/") and n.endswith("trips.db") for n in dn), "data.zip has the trips database")
    check(not any(n.endswith("-shm") or n.endswith("-journal") for n in dn), "and no -shm or -journal files")
    st = z.read("checkpoint/state.bin")
    check(struct.unpack(">i", st[:4])[0] == 1, "state.bin has one activity")


if __name__ == "__main__":
    main()
