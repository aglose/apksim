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
     the report says the app crashed;
  5. first records a password typed, a checkpoint, the sign-in sent and a 5 s window saved once the typing is older
     than it (the password must not be in the HAR); then records a session with a typed sign-in (apksim://network?signin=1: a user and a password typed into fields, a
     POST with both in its body, a GET with an id made at run time in its query, and a connect to a mapped host
     nothing listens on). The bundle must not hold the password anywhere, its sign-in request must hold the
     placeholder a replay types and be marked, and the failed connect must be an entry. Replayed as it is: the sign-in
     and the failed connect come out as recorded, the GET is unmatched, and the report and stderr name
     `--network-ignore-query device`; with `--loose`, and with that option, the replay matches at every point and
     every recorded request is asked again. A copy of the bundle whose sign-in entry is not marked and holds another
     body is unmatched there, with a note naming `--network-match method,url`.

Each replay's report.json `requests` and the summary line on stderr ("N of M recorded requests asked again, K
unmatched") are checked too.

--image IMAGE runs every launch in the Linux image (the fixture inside the recording's container). OUT (default
work/replay-check) is emptied first. Prints each replay's wall time and its points' pixel counts.
"""
import argparse, json, os, re, shutil, socket, subprocess, sys, time, zipfile

ROOT = os.path.abspath(os.path.dirname(os.path.abspath(sys.argv[0])) + "/..")
PORT = int(os.environ.get("REPLAYCHECK_PORT", "7611"))  # the image's control port; the Mac uses --control auto
# The sign-in replays' threshold: a text field's caret in the other blink phase (44 pixels) and a frame the recorder kept
# a few characters into the typing (10 a password dot) are not divergences; a changed line of text is (868 above).
CARET = ["--threshold", "300"]
PASSWORD = "correct horse"  # what the fixture's /v1/signin takes; typed into a password field, so left out of recordings
problems = []
a = None
procs = []


def read(path):
    with open(path, errors="replace") as f:
        return f.read()


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
        raise SystemExit("the app never answered on its control port" + (": " + read(log)[-3000:] if log else ""))

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
            m = re.search(r"\[apksim\] control port (\d+)", read(logp))
            if m:
                return p, int(m.group(1))
            if p.poll() is not None:
                break
            time.sleep(0.1)
        raise SystemExit(f"{name}: no control port: " + read(logp)[-3000:])
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
    log = read(os.path.join(a.out, f"{name}.log"))
    check(code == want, f"exit {code} (want {want}) in {took:.1f} s" + ("" if code == want else ": " + log[-1500:]))
    r = report(name)
    print(f"  ..    points {show(r)}")
    return r


def requests_line(name):
    m = re.search(r"(\d+) of (\d+) recorded requests asked again, (\d+) unmatched", read(os.path.join(a.out, f"{name}.log")))
    return tuple(map(int, m.groups())) if m else None


def record_signin(fport):
    print("== record a session with a typed sign-in")
    pre = ""
    if a.image:
        pre = ("python3 /work/network-fixture.py /work/fixture 8443 > /work/fixture2.log 2>&1 & "
               "until grep -q ready /work/fixture2.log 2>/dev/null; do sleep 0.2; done; ")
        fport = 8443
    p, port = launch("replaycheck-rec2", ["--headless", "--fresh", "--rec", "all", "--rec-checkpoint", "0",
                                          "--feature", "dev.apksim.testapp.no_timings",
                                          "--network-host", f"api.apksim.test=127.0.0.1:{fport}",
                                          "--network-host", "down.apksim.test=127.0.0.1:1",  # nothing listens there
                                          "--network-trust", wpath(os.path.join(a.out, "fixture/cert.pem")), wpath(a.apk)],
                     "record-signin.log", port=True, pre=pre)
    d = Drive(port, os.path.join(a.out, "record-signin.log"))
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
    d("open apksim://network?signin=1")
    d("settle 10000")
    # Each step 0.3 s after the last: long enough for the recorder (5 frames a second at most) to have kept the screen
    # the step left, and short of the 500 ms at which a focused field's caret turns, where a compared point can catch
    # the other phase (44 pixels of the 442x960 frame; CARET above allows for it).
    d.tap_text("User")
    time.sleep(0.3)
    d("type ada")
    time.sleep(0.3)
    d.tap_text("Password")
    time.sleep(0.3)
    d("type " + PASSWORD)
    time.sleep(0.3)
    d.tap_text("Sign in")
    r = d.wait_text(lambda t: t.startswith("signin:") and not t.endswith(("idle", "fetching")))
    check(r == "signin: 200 Welcome, ada", f"signed in at the fixture: {r}")
    r = d.wait_text(lambda t: t.startswith("profile:") and not t.endswith(("idle", "fetching")))
    check(r == "profile: 200 Profile (en)", f"the profile, asked for with an id made at run time: {r}")
    time.sleep(0.5)
    d.tap_text("Ping down")
    r = d.wait_text(lambda t: t.startswith("down:") and not t.endswith(("idle", "fetching")))
    check(r == "down: ConnectException", f"the connect to a port nothing listens on failed: {r}")
    time.sleep(1.5)
    bundle = os.path.join(a.out, "signin.apkrec")
    s = d("rec save 60 " + wpath(bundle))
    check(s.startswith("saving"), f"saved: {s}")
    for _ in range(100):
        if "KB in" in d("rec"):
            break
        time.sleep(0.2)
    d("quit")
    finish(p, 60)
    if a.image:
        subprocess.run(["docker", "rm", "-f", "replaycheck-rec2"], capture_output=True)
    z = zipfile.ZipFile(bundle)
    man = json.loads(z.read("manifest.json"))
    har = json.loads(z.read("network.har"))["log"]["entries"]
    held = [n for n in z.namelist() if PASSWORD.encode() in z.read(n)]
    check(not held, f"the password is nowhere in the bundle: {held}")
    check(man["counts"].get("redactedInput") == len(PASSWORD) and man["counts"].get("harRedactedInput") == 1,
          f"the manifest counts the characters left out and the request that carried them: {man['counts']}")
    si = [e for e in har if e["request"]["url"].endswith("/v1/signin")]
    check(len(si) == 1 and si[0].get("_apksimRedactedInput") is True
          and json.loads(si[0]["request"]["postData"]["text"]) == {"user": "ada", "password": "x" * len(PASSWORD)},
          f"the sign-in request holds the placeholder and is marked: {si and si[0]['request'].get('postData')}")
    down = [e for e in har if e["request"]["method"] == "CONNECT" and e["request"]["url"] == "down.apksim.test:9"]
    check(bool(down) and all(str(e.get("_apksimError", "")).startswith("connect failed: ") for e in down),
          f"the failed connect is in the HAR ({len(down)} entries): {down and down[0].get('_apksimError')}")
    return bundle, len(har)


def late_checkpoint(fport):
    """A checkpoint between typing a password and sending it, saved once the typing is older than the window."""
    print("== a password typed before the saved window, sent inside it")
    pre = ""
    if a.image:
        pre = ("python3 /work/network-fixture.py /work/fixture 8443 > /work/fixture3.log 2>&1 & "
               "until grep -q ready /work/fixture3.log 2>/dev/null; do sleep 0.2; done; ")
        fport = 8443
    p, port = launch("replaycheck-rec3", ["--headless", "--fresh", "--rec", "all", "--rec-checkpoint", "0",
                                          "--feature", "dev.apksim.testapp.no_timings",
                                          "--network-host", f"api.apksim.test=127.0.0.1:{fport}",
                                          "--network-trust", wpath(os.path.join(a.out, "fixture/cert.pem")), wpath(a.apk)],
                     "record-late.log", port=True, pre=pre)
    d = Drive(port, os.path.join(a.out, "record-late.log"))
    d("settle 20000")
    d("open apksim://network?signin=1")
    d("settle 10000")
    d.tap_text("User")
    time.sleep(0.3)
    d("type ada")
    time.sleep(0.3)
    d.tap_text("Password")
    time.sleep(0.3)
    d("type " + PASSWORD)
    d("key A 1 ctrl")  # a shortcut in the password field: not part of the text
    time.sleep(1)
    for _ in range(20):
        cp = d("rec checkpoint now")
        if cp.startswith("checkpoint #"):
            break
        time.sleep(0.5)
    check(cp.startswith("checkpoint #"), f"checkpoint after typing: {cp}")
    d.tap_text("Sign in")
    r = d.wait_text(lambda t: t.startswith("signin:") and not t.endswith(("idle", "fetching")))
    check(r == "signin: 200 Welcome, ada", f"signed in at the fixture: {r}")
    d("rec window 5")
    time.sleep(8)  # the window's start passes the checkpoint: nothing from before it is kept
    bundle = os.path.join(a.out, "late.apkrec")
    s = d("rec save 5 " + wpath(bundle))
    check(s.startswith("saving"), f"saved: {s}")
    for _ in range(100):
        if "KB in" in d("rec"):
            break
        time.sleep(0.2)
    d("quit")
    finish(p, 60)
    if a.image:
        subprocess.run(["docker", "rm", "-f", "replaycheck-rec3"], capture_output=True)
    z = zipfile.ZipFile(bundle)
    har = z.read("network.har")
    si = [e for e in json.loads(har)["log"]["entries"] if e["request"]["url"].endswith("/v1/signin")]
    check(len(si) == 1 and si[0].get("_apksimRedactedInput") is True, f"the sign-in is in the HAR and marked ({len(si)})")
    check(PASSWORD.encode() not in har and PASSWORD.replace(" ", "+").encode() not in har
          and PASSWORD.replace(" ", "%20").encode() not in har, "the password is nowhere in the HAR")


def by_url(r, part):
    return [e for e in r.get("requests", {}).get("entries", []) if part in e["url"]]


def signin_checks(fport, stop):
    late_checkpoint(fport)
    bundle, entries = record_signin(fport)
    stop()  # the fixture: from here every answer is the bundle's
    r = replay_exit("signin-a", bundle, CARET, 3, "the sign-in replayed as it is (an id made at run time is unmatched)")
    q = r.get("requests", {})
    log = read(os.path.join(a.out, "signin-a.log"))
    si = by_url(r, "/v1/signin")
    check(len(si) == 1 and si[0]["asked"] == 1 and si[0]["matched"] == ["exact"] and si[0]["status"] == 200,
          f"the sign-in was asked once and answered as recorded, by its placeholder body: {si}")
    down = by_url(r, "down.apksim.test:9")
    check(bool(down) and sum(e["asked"] for e in down) >= 1 and all(e.get("matched", ["connect-failed"]) == ["connect-failed"] for e in down),
          f"the failed connect failed again: {down}")
    un = q.get("unmatchedRequests", [])
    check(q.get("unmatched") == 1 and len(un) == 1 and "/v1/profile" in un[0]["url"] and un[0].get("ignoreQuery") == ["device"]
          and by_url(r, "/v1/profile")[0]["entry"] == un[0]["closest"]["entry"],
          f"the profile request is the one unmatched, its closest entry differing in device's value alone: {un}")
    line = requests_line("signin-a")
    check(line is not None and line == (q.get("asked"), q.get("recorded"), 1) and line[0] == line[1] - 1,
          f"stderr says how many recorded requests were asked again: {line}")
    check("--network-ignore-query device" in log and any("--network-ignore-query device" in n and "--loose" in n for n in r.get("notes", [])),
          "stderr and the report's notes name --network-ignore-query device and --loose")
    check(r.get("redactedInput") == len(PASSWORD), f"the report counts the characters typed as x: {r.get('redactedInput')}")

    r = replay_exit("signin-b", bundle, CARET + ["--loose"], 0, "the sign-in replayed with --loose")
    q = r.get("requests", {})
    pr = by_url(r, "/v1/profile")
    check(q.get("unmatched") == 0 and q.get("loose") is True and len(pr) == 1 and pr[0]["matched"] == ["loose-query: device"],
          f"the profile was answered by the entry that differs in device alone, and marked: {pr}")
    check(q.get("asked") == q.get("recorded") == entries and requests_line("signin-b") == (entries, entries, 0),
          f"every recorded request was asked again: {q.get('asked')} of {q.get('recorded')} ({entries} in the HAR), {requests_line('signin-b')}")

    r = replay_exit("signin-c", bundle, CARET + ["--network-ignore-query", "device"], 0, "the sign-in replayed with --network-ignore-query device")
    pr = by_url(r, "/v1/profile")
    check(r.get("requests", {}).get("unmatched") == 0 and len(pr) == 1 and pr[0]["matched"] == ["exact"], f"the profile matched without its device: {pr}")

    # An older bundle, or a body that carries the typed text in a form the recorder did not find (hashed): the entry is
    # not marked and its body is not what a replay sends.
    old = os.path.join(a.out, "signin-old.apkrec")
    with zipfile.ZipFile(bundle) as zin, zipfile.ZipFile(old, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == "network.har":
                h = json.loads(data)
                for e in h["log"]["entries"]:
                    if e["request"]["url"].endswith("/v1/signin"):
                        e.pop("_apksimRedactedInput", None)
                        e["request"]["postData"]["text"] = json.dumps({"user": "ada", "password": "sha256:0f3a"})
                        e["request"].pop("_bodySha256", None)
                data = json.dumps(h).encode()
            zout.writestr(item, data)
    r = replay_exit("signin-d", old, CARET + ["--loose"], 3, "a sign-in whose recorded body is not what a replay types")
    un = r.get("requests", {}).get("unmatchedRequests", [])
    check(len(un) == 1 and "/v1/signin" in un[0]["url"] and un[0].get("bodyOnly") is True, f"the sign-in is unmatched for its body alone: {un}")
    check(any("--network-match method,url" in n for n in r.get("notes", [])) and "--network-match method,url" in read(os.path.join(a.out, "signin-d.log")),
          f"a note names --network-match method,url: {r.get('notes')}")


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
                m = re.search(r"^ready (\d+)", read(os.path.join(a.out, "fixture.log")), re.M)
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
        qa = ra.get("requests", {})
        check(qa.get("unmatched") == 0 and qa.get("recorded", 0) >= 1 and qa.get("asked") == qa.get("recorded")
              and all(e["matched"] == ["exact"] for e in qa.get("entries", []) if e["asked"]),
              f"the report lists the recorded requests, each asked again: {qa.get('asked')} of {qa.get('recorded')}, {qa.get('unmatched')} unmatched")
        replay_exit("replay-b", bundle, [], 0, "replay without --control")
        line = requests_line("replay-b")
        check(line is not None and line[0] == line[1] >= 1 and line[2] == 0, f"stderr's summary counts them: {line}")
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
        crash = re.search(r"saved (\S+-crash\.apkrec)", read(os.path.join(a.out, "crash-rec.log")))
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
        if a.image:
            subprocess.run(["docker", "rm", "-f", "replaycheck-rec"], capture_output=True)  # its port, for the next recording
        fixture2 = None
        try:
            if not a.image:
                fixture2 = subprocess.Popen([sys.executable, fixture_py, os.path.join(a.out, "fixture")],
                                            stdout=open(os.path.join(a.out, "fixture2.log"), "w"), stderr=subprocess.STDOUT)
                fport = None
                for _ in range(100):
                    m = re.search(r"^ready (\d+)", read(os.path.join(a.out, "fixture2.log")), re.M)
                    if m:
                        fport = m.group(1)
                        break
                    time.sleep(0.1)
                if not fport:
                    raise SystemExit("the fixture server did not start again")
            signin_checks(fport, lambda: fixture2 and (fixture2.terminate(), fixture2.wait()))
        finally:
            if fixture2 and fixture2.poll() is None:
                fixture2.terminate()
    finally:
        if fixture:
            fixture.terminate()
        for p in procs:
            if p.poll() is None:
                p.kill()
        if a.image:
            for n in ["rec", "a", "replay-b", "replay-c", "replay-d", "crash", "replay-crash", "rec2", "signin-a", "signin-b", "signin-c", "signin-d"]:
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
