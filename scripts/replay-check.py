#!/usr/bin/env python3
"""Checks `apksim replay` (docs/replay.md) against the test app and the network fixture.

Usage: replay-check.py APP.apk [OUT] [--image IMAGE] [--changed APK]

Records a session with `--rec all`: the Trips tab, a checkpoint, the network screen opened with `open` and its OkHttp
fetch against scripts/network-fixture.py, Back, then the Trips rename ("Italian vacation" -> "Roman vacation"). Then,
with the fixture server stopped, so every answer must come from the bundle's HAR:

  1. replays it with --control: the replay must match at every compared point, and once it is done the app must be
     on the Trips tab with the renamed trip, and still answer (a person can take over);
  2. replays it without --control: exit 0;
  3. replays it as a changed build (`--feature dev.apksim.testapp.rename_v2`: the rename writes "Roman holiday"):
     exit 3, the first divergence at the end (after the rename) and none before it, with the difference map written;
     with --changed APK, also that build (`--apk`, same signer) must diverge;
  4. records a crash (apksim://crash?kind=post, which saves a crash bundle by itself) and replays it: exit 10, and
     the report says the app crashed.

--image IMAGE runs every launch in the Linux image (the fixture inside the recording's container). OUT (default
work/replay-check) is emptied first. Prints each replay's wall time and its points' pixel counts.
"""
import argparse, json, os, re, shutil, socket, subprocess, sys, time, zipfile

ROOT = os.path.abspath(os.path.dirname(os.path.abspath(sys.argv[0])) + "/..")
PORT = int(os.environ.get("REPLAYCHECK_PORT", "7611"))  # the image's control port; the Mac uses --control auto
problems = []
a = None
procs = []


def check(ok, what):
    print(("  ok    " if ok else "  FAIL  ") + what)
    if not ok:
        problems.append(what)
    return ok


def wpath(p):
    """A path under OUT as the launched apksim sees it (/work inside the image)."""
    p = os.path.abspath(p)
    return "/work" + p[len(a.out):] if a.image else p


class Drive:
    def __init__(self, port, log=None, tries=900):
        for _ in range(tries):
            try:
                self.s = socket.create_connection(("127.0.0.1", port), timeout=1)
                self.s.settimeout(60)
                self.f = self.s.makefile("rw")
                if self("ping") == "pong":
                    return
            except OSError:
                pass
            time.sleep(0.2)
        raise SystemExit("the app never answered on its control port" + (": " + open(log).read()[-3000:] if log else ""))

    def __call__(self, cmd):
        self.f.write(cmd + "\n")
        self.f.flush()
        return self.f.readline().strip()

    def texts(self):
        return [m.group(1) for m in re.finditer(r'text="((?:[^"\\]|\\.)*)"', self("nodes"))]

    def tap_text(self, text, seconds=20):
        for _ in range(seconds * 4):
            for m in re.finditer(r'\[(\d+),(\d+),(\d+),(\d+)\] text="((?:[^"\\]|\\.)*)"', self("nodes")):
                if m.group(5) == text:
                    x1, y1, x2, y2 = map(int, m.groups()[:4])
                    return self(f"tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
            time.sleep(0.25)
        raise SystemExit(f'no "{text}" on screen: {self.texts()}')

    def wait_text(self, pred, seconds=30):
        for _ in range(seconds * 4):
            for t in self.texts():
                if pred(t):
                    return t
            time.sleep(0.25)
        return None


def launch(name, args, log, port=None, pre="", detach=True):
    """apksim ARGS on this Mac or in the image; returns (Popen, control port or None)."""
    logp = os.path.join(a.out, log)
    if not a.image:
        at = 1 if args[:1] == ["replay"] else 0  # options after the subcommand
        cmd = [os.path.join(ROOT, "apksim")] + args[:at] + (["--control", "auto"] if port is not None else []) + args[at:]
        p = subprocess.Popen(cmd, stdout=open(logp, "w"), stderr=subprocess.STDOUT, cwd=a.out,
                             env=dict(os.environ, APKSIM_STANDBY="0"))
        procs.append(p)
        if port is None:
            return p, None
        for _ in range(1200):
            m = re.search(r"\[apksim\] control port (\d+)", open(logp).read())
            if m:
                return p, int(m.group(1))
            if p.poll() is not None:
                break
            time.sleep(0.1)
        raise SystemExit(f"{name}: no control port: " + open(logp).read()[-3000:])
    subprocess.run(["docker", "rm", "-f", name], capture_output=True)
    at = 1 if args[:1] == ["replay"] else 0
    if port is not None:
        args = args[:at] + ["--control", str(PORT), "--control-bind", "0.0.0.0"] + args[at:]
    quoted = " ".join("'" + x.replace("'", "'\\''") + "'" for x in args)
    script = f"{pre}exec apksim {quoted} > /work/{log} 2>&1"
    cmd = ["docker", "run", "--name", name, "--platform", a.platform, "-v", f"{a.out}:/work"]
    if port is not None:
        cmd += ["-p", f"127.0.0.1:{PORT}:{PORT}"]
    cmd += ["--entrypoint", "bash", a.image, "-c", script]
    p = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    procs.append(p)
    return p, PORT if port is not None else None


def finish(p, seconds=240):
    try:
        return p.wait(timeout=seconds)
    except subprocess.TimeoutExpired:
        p.kill()
        return None


def report(name):
    path = os.path.join(a.out, name, "report.json")
    return json.load(open(path)) if os.path.exists(path) else {}


def show(r):
    pts = r.get("points", [])
    return ", ".join(f"{p['n']}:{p['pixels']}{'!' if p['diverged'] else ''}" for p in pts) + f" (threshold {r.get('threshold')})"


def record(fport):
    print("== record")
    pre = ""
    if a.image:
        pre = ("python3 /work/network-fixture.py /work/fixture 8443 > /work/fixture.log 2>&1 & "
               "until grep -q ready /work/fixture.log 2>/dev/null; do sleep 0.2; done; ")
        fport = 8443
    # no_timings: the network screen leaves out how long a fetch took, real time a replay cannot repeat (the
    # recording keeps --feature flags, so the replays get it too).
    p, port = launch("replaycheck-rec", ["--headless", "--fresh", "--rec", "all", "--rec-checkpoint", "0",
                                         "--feature", "dev.apksim.testapp.no_timings",
                                         "--network-host", f"api.apksim.test=127.0.0.1:{fport}",
                                         "--network-trust", wpath(os.path.join(a.out, "fixture/cert.pem")), wpath(a.apk)],
                     "record.log", port=True, pre=pre)
    d = Drive(port, os.path.join(a.out, "record.log"))
    d("settle 20000")
    d("tap 300 1690")  # Trips
    check(d.wait_text(lambda t: t == "Italian vacation") is not None, "Trips shows Italian vacation")
    d("settle 5000")
    for _ in range(20):
        cp = d("rec checkpoint now")
        if cp.startswith("checkpoint #"):
            break
        time.sleep(0.5)
    check(cp.startswith("checkpoint #"), f"checkpoint: {cp}")
    time.sleep(1)
    d("open apksim://network")
    d("settle 10000")
    d.tap_text("Fetch")
    r = d.wait_text(lambda t: t.startswith("okhttp:") and not t.endswith(("idle", "fetching")))
    check(r is not None and r.startswith("okhttp: 200"), f"fetched from the fixture: {r}")
    time.sleep(1)
    d("back")
    d("settle 10000")
    d.tap_text("Italian vacation")
    check(d.wait_text(lambda t: t == "Roman vacation") is not None, "renamed to Roman vacation")
    time.sleep(1.5)
    end = d.texts()
    bundle = os.path.join(a.out, "rec.apkrec")
    s = d("rec save 60 " + wpath(bundle))
    check(s.startswith("saving"), f"saved: {s}")
    for _ in range(100):
        if "KB in" in d("rec"):
            break
        time.sleep(0.2)
    d("quit")
    finish(p, 60)
    z = zipfile.ZipFile(bundle)
    man = json.loads(z.read("manifest.json"))
    har = json.loads(z.read("network.har"))["log"]["entries"]
    events = z.read("input.jsonl").decode().splitlines()
    check(man.get("checkpoint", {}).get("device", {}).get("name") == "phone", "the checkpoint names its device")
    check(any("greeting" in e["request"]["url"] or "api.apksim.test" in e["request"]["url"] for e in har), f"the fetch is in the HAR ({len(har)} entries)")
    print(f"  ..    {len(events)} input lines, {man['counts'].get('screenFrames')} frames, end screen {end[:8]}")
    return bundle, end


def replay_control(bundle):
    print("== replay with --control (fixture stopped)")
    t0 = time.time()
    p, port = launch("replaycheck-a", ["replay", "--out", wpath(os.path.join(a.out, "replay-a")), "--headless",
                                       "--network-trust", wpath(os.path.join(a.out, "fixture/cert.pem")), wpath(bundle)],
                     "replay-a.log", port=True)
    d = Drive(port, os.path.join(a.out, "replay-a.log"))
    status = ""
    for _ in range(600):
        status = d("replay")
        if re.match(r"replay: (matched|diverged|crashed)", status):
            break
        time.sleep(0.2)
    took = time.time() - t0
    check(status.startswith("replay: matched"), f"replay matched: {status[:160]}")
    texts = d.texts()
    check("Roman vacation" in texts and "Italian vacation" not in texts, f"the app is on Trips with the renamed trip: {texts[:6]}")
    check(d("ping") == "pong", "the app answers after the replay (taken over)")
    r = report("replay-a")
    print(f"  ..    {took:.1f} s from launch to the result; points {show(r)}; network {r.get('network')}")
    d("quit")
    finish(p, 60)
    return r


def replay_exit(name, bundle, extra, want, what):
    print(f"== {what}")
    t0 = time.time()
    p, _ = launch(f"replaycheck-{name}", ["replay", "--out", wpath(os.path.join(a.out, name)), "--headless",
                                          "--network-trust", wpath(os.path.join(a.out, "fixture/cert.pem"))] + extra + [wpath(bundle)],
                  f"{name}.log", detach=False)
    code = finish(p)
    if a.image and code is not None:
        subprocess.run(["docker", "rm", "-f", f"replaycheck-{name}"], capture_output=True)
    took = time.time() - t0
    log = open(os.path.join(a.out, f"{name}.log")).read()
    check(code == want, f"exit {code} (want {want}) in {took:.1f} s" + ("" if code == want else ": " + log[-1500:]))
    r = report(name)
    print(f"  ..    points {show(r)}")
    return r


def main():
    global a
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("apk")
    ap.add_argument("out", nargs="?", default=os.path.join(ROOT, "work/replay-check"))
    ap.add_argument("--image")
    ap.add_argument("--changed")
    a = ap.parse_args()
    a.out = os.path.abspath(a.out)
    for f in [a.apk] + ([a.changed] if a.changed else []):
        if os.path.abspath(f).startswith(a.out + os.sep):
            sys.exit(f"replay-check: {f} is inside OUT ({a.out}), which is emptied first; pass another OUT")
    shutil.rmtree(a.out, ignore_errors=True)
    os.makedirs(os.path.join(a.out, "fixture"))
    shutil.copy(a.apk, os.path.join(a.out, "app.apk"))
    a.apk = os.path.join(a.out, "app.apk")
    if a.changed:
        shutil.copy(a.changed, os.path.join(a.out, "changed.apk"))
    if a.image:
        arch = subprocess.run(["docker", "image", "inspect", "-f", "{{.Architecture}}", a.image], capture_output=True, text=True).stdout.strip()
        a.platform = "linux/" + arch
    fixture_py = os.path.join(ROOT, "scripts/network-fixture.py")
    shutil.copy(fixture_py, os.path.join(a.out, "network-fixture.py"))
    subprocess.run([sys.executable, fixture_py, os.path.join(a.out, "fixture"), "--cert-only"], check=True, capture_output=True)
    fixture = None
    try:
        fport = None
        if not a.image:
            fixture = subprocess.Popen([sys.executable, fixture_py, os.path.join(a.out, "fixture")],
                                       stdout=open(os.path.join(a.out, "fixture.log"), "w"), stderr=subprocess.STDOUT)
            for _ in range(100):
                m = re.search(r"^ready (\d+)", open(os.path.join(a.out, "fixture.log")).read(), re.M)
                if m:
                    fport = m.group(1)
                    break
                time.sleep(0.1)
            if not fport:
                raise SystemExit("the fixture server did not start")
        bundle, end = record(fport)
        if fixture:
            fixture.terminate()
            fixture.wait()
            fixture = None
        ra = replay_control(bundle)
        check(any(p["label"].startswith("before back") for p in ra.get("points", [])),
              "a point on the network screen (its fetch answered from the HAR) was compared")
        replay_exit("replay-b", bundle, [], 0, "replay without --control")
        rc = replay_exit("replay-c", bundle, ["--feature", "dev.apksim.testapp.rename_v2"], 3, "replay as a changed build (rename_v2)")
        pts = rc.get("points", [])
        first = next((p for p in pts if p["diverged"]), None)
        check(first is not None and first["label"] == "end" and not any(p["diverged"] for p in pts[:-1]),
              f"first divergence at the end, after the rename: {first and first['label']}")
        check(first is not None and "diff.png" in first["files"]
              and os.path.exists(os.path.join(a.out, "replay-c", first["files"].split()[-1])), "difference map written")
        if a.changed:
            replay_exit("replay-d", bundle, ["--apk", wpath(os.path.join(a.out, "changed.apk"))], 3, "replay against --changed (another build)")
        print("== a crash bundle replayed")
        p, port = launch("replaycheck-crash", ["--headless", "--fresh", "--rec", "all", wpath(a.apk)], "crash-rec.log", port=True)
        d = Drive(port, os.path.join(a.out, "crash-rec.log"))
        d("settle 20000")
        for _ in range(40):
            if d("rec").find("checkpoints: #") >= 0:
                break
            time.sleep(0.25)
        try:
            d("open apksim://crash?kind=post")
        except OSError:
            pass
        code = finish(p, 60)
        crash = re.search(r"saved (\S+-crash\.apkrec)", open(os.path.join(a.out, "crash-rec.log")).read())
        if check(code == 10 and crash is not None, f"the recording crashed (exit {code}) and saved {crash and crash.group(1)}"):
            src = crash.group(1)
            local = src if not a.image else None
            if a.image:
                subprocess.run(["docker", "cp", f"replaycheck-crash:{src}", os.path.join(a.out, "crash.apkrec")], check=True)
            else:
                shutil.copy(local, os.path.join(a.out, "crash.apkrec"))
            if a.image:
                subprocess.run(["docker", "rm", "-f", "replaycheck-crash"], capture_output=True)
            rd = replay_exit("replay-crash", os.path.join(a.out, "crash.apkrec"), [], 10, "the crash replayed")
            check(rd.get("result") == "crashed" and any("crashed after open" in n for n in rd.get("notes", [])),
                  f"the report says it crashed after the open command: {rd.get('notes')}")
    finally:
        if fixture:
            fixture.terminate()
        for p in procs:
            if p.poll() is None:
                p.kill()
        if a.image:
            for n in ["rec", "a", "replay-b", "replay-c", "replay-d", "crash", "replay-crash"]:
                subprocess.run(["docker", "rm", "-f", f"replaycheck-{n}"], capture_output=True)
    print()
    if problems:
        print(f"replay-check: {len(problems)} problem(s):")
        for p in problems:
            print("  - " + p)
        sys.exit(1)
    print("replay-check: all good")


if __name__ == "__main__":
    main()
