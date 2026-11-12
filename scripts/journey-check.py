#!/usr/bin/env python3
"""Checks recording-to-journey (`apksim replay --journey`) and scripts/journey.py (docs/replay.md) on the test app.

Usage: journey-check.py APP.apk [OUT] [--image IMAGE]

Records with `--rec all`: the Trips tab, the rename ("Italian vacation" -> "Roman vacation"), the Home tab, two
FAB taps and a quick double tap on it. Then:

  1. `apksim replay --journey` writes journey.json: the taps target nodes (text "Trips", "Italian vacation", "Home";
     the FAB, tapped below its icon, by its resource id "fab", a Compose testTag), the double tap is one step with its
     timing (`times`), and the steps expect "Roman vacation", "FAB clicks: 1", "FAB clicks: 2" and "FAB clicks: 4";
  2. journey.py runs it from the recording's checkpoint: exit 0, JUnit XML with a test case per step and no failure;
  3. on a tablet (`-- --device tablet`: a navigation rail instead of the bottom bar, other coordinates) it still passes,
     and the same journey by the recorded coordinates (--by-coordinates) fails there;
  4. as a changed build (`-- --feature dev.apksim.testapp.rename_v2`) it fails at the rename, expecting "Roman
     vacation", exit 1, the later steps skipped in the XML;
  5. a recording that ends in a crash (the Trips tab, then apksim://crash?kind=post): its replay crashes too (exit 10)
     and still writes journey.json, with the steps up to the crash and a note that it crashed.

--image runs the replay and journey.py inside the Linux image (journey.py is in it). OUT defaults to
work/journey-check and is emptied first.
"""
import argparse, json, os, re, shutil, socket, subprocess, sys, time
import xml.etree.ElementTree as ET

ROOT = os.path.abspath(os.path.dirname(os.path.abspath(sys.argv[0])) + "/..")
PORT = int(os.environ.get("JOURNEYCHECK_PORT", "7613"))
problems = []
a = None


def check(ok, what):
    print(("  ok    " if ok else "  FAIL  ") + what)
    if not ok:
        problems.append(what)
    return ok


def w(path):
    p = os.path.abspath(path)
    return "/work" + p[len(a.out):] if a.image else p


def docker(name, args, entry=None, port=None, detach=False):
    subprocess.run(["docker", "rm", "-f", name], capture_output=True)
    cmd = ["docker", "run", "--name", name, "--platform", a.platform, "-v", f"{a.out}:/work"]
    if port:
        cmd += ["-p", f"127.0.0.1:{port}:{port}"]
    if entry:
        cmd += ["--entrypoint", entry]
    return cmd + [a.image] + args


def control(port):
    for _ in range(900):
        try:
            s = socket.create_connection(("127.0.0.1", port), timeout=1)
            s.settimeout(60)
            f = s.makefile("rw")

            def cmd(c):
                f.write(c + "\n")
                f.flush()
                return f.readline().strip()
            if cmd("ping") == "pong":
                return cmd
        except OSError:
            pass
        time.sleep(0.2)
    raise SystemExit("the app never answered on its control port")


def tap_text(d, text):
    for _ in range(80):
        m = re.search(r'\[(\d+),(\d+),(\d+),(\d+)\] [^;]*text="' + re.escape(text) + '"', d("nodes"))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            return d(f"tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
        time.sleep(0.25)
    raise SystemExit(f"no {text!r} on screen")


def record(name="rec", session=None):
    log = os.path.join(a.out, "record.log" if name == "rec" else name + ".log")
    args = ["--headless", "--fresh", "--rec", "all", "--rec-checkpoint", "0"]
    if a.image:
        p = subprocess.Popen(docker("journeycheck-" + name, args + ["--control", str(PORT), "--control-bind", "0.0.0.0", "/work/app.apk"], port=PORT),
                             stdout=open(log, "w"), stderr=subprocess.STDOUT)
        port = PORT
    else:
        p = subprocess.Popen([os.path.join(ROOT, "apksim")] + args + ["--control", "auto", a.app], stdout=open(log, "w"),
                             stderr=subprocess.STDOUT, env=dict(os.environ, APKSIM_STANDBY="0"))
        port = None
    try:
        for _ in range(1200 if port is None else 0):
            with open(log) as f:
                m = re.search(r"\[apksim\] control port (\d+)", f.read())
            if m:
                port = int(m.group(1))
                break
            time.sleep(0.1)
        return (session or record_session)(port, p, log)
    finally:
        # Whatever happened, the recording's JVM (or container) does not outlive the check holding its data slot.
        if p.poll() is None:
            p.kill()
        if a.image:
            subprocess.run(["docker", "rm", "-f", "journeycheck-" + name], capture_output=True)


def record_session(port, p, log):
    d = control(port)
    d("settle 20000")
    for _ in range(40):  # the first checkpoint: the replay and the journey start from it
        if "checkpoints: #" in d("rec"):
            break
        time.sleep(0.25)
    tap_text(d, "Trips")
    d("settle 5000")
    time.sleep(0.8)
    tap_text(d, "Italian vacation")
    time.sleep(1)
    tap_text(d, "Home")
    d("settle 5000")
    time.sleep(0.8)
    d("tap 733 1533")  # the FAB
    time.sleep(0.8)
    d("tap 733 1533")
    time.sleep(0.8)
    d("tap 733 1533")  # a double tap: the second ~100 ms after the first lifts
    time.sleep(0.1)
    d("tap 733 1533")
    time.sleep(1.5)
    d("rec save 60 " + w(os.path.join(a.out, "rec.apkrec")))
    for _ in range(100):
        if "KB in" in d("rec"):
            break
        time.sleep(0.2)
    d("quit")
    p.wait(timeout=60)
    if a.image:
        subprocess.run(["docker", "rm", "-f", "journeycheck-rec"], capture_output=True)
    return os.path.join(a.out, "rec.apkrec")


def crash_session(port, p, log):
    """The Trips tab, then a screen that crashes 300 ms after it opens: the flight recorder saves a crash bundle."""
    d = control(port)
    d("settle 20000")
    for _ in range(40):
        if "checkpoints: #" in d("rec"):
            break
        time.sleep(0.25)
    tap_text(d, "Trips")
    d("settle 5000")
    time.sleep(0.8)
    try:
        d("open apksim://crash?kind=post")
    except OSError:
        pass
    code = p.wait(timeout=60)
    check(code == 10, f"the recorded app crashed (exit {code})")
    m = re.search(r"\[apksim\] rec: the app crashed: (\S+\.apkrec)", open(log, errors="replace").read())
    if not check(m is not None, "and saved a crash bundle"):
        return None
    bundle = os.path.join(a.out, "crash.apkrec")
    if a.image:
        subprocess.run(["docker", "cp", "journeycheck-crash:" + m.group(1), bundle], capture_output=True)
    else:
        shutil.move(m.group(1), bundle)
    return bundle


def generate(bundle, name="gen", want=0):
    out = os.path.join(a.out, name)
    args = ["replay", "--journey", "--headless", "--out", w(out), w(bundle)]
    t0 = time.time()
    if a.image:
        code = subprocess.run(docker("journeycheck-" + name, args), stdout=open(out + ".log", "w"), stderr=subprocess.STDOUT).returncode
        subprocess.run(["docker", "rm", "-f", "journeycheck-" + name], capture_output=True)
    else:
        code = subprocess.run([os.path.join(ROOT, "apksim")] + args, stdout=open(out + ".log", "w"), stderr=subprocess.STDOUT,
                              env=dict(os.environ, APKSIM_STANDBY="0")).returncode
    check(code == want, f"the replay that writes the journey {'matched' if want == 0 else 'crashed'} (exit {code}, {time.time() - t0:.0f} s)")
    return os.path.join(out, "journey.json")


def run_journey(name, journey, extra=(), apksim=()):
    xml = os.path.join(a.out, name + ".xml")
    args = [w(journey), "--xml", w(xml), *extra]
    if apksim:
        args += ["--", *apksim]
    t0 = time.time()
    if a.image:
        r = subprocess.run(docker("journeycheck-" + name, args, entry="journey.py"), capture_output=True, text=True)
        subprocess.run(["docker", "rm", "-f", "journeycheck-" + name], capture_output=True)
    else:
        r = subprocess.run([sys.executable, os.path.join(ROOT, "scripts/journey.py")] + args, capture_output=True, text=True)
    open(os.path.join(a.out, name + ".log"), "w").write(r.stdout + r.stderr)
    print("\n".join("        " + l for l in r.stdout.strip().splitlines()))
    cases = []
    if os.path.exists(xml):
        for tc in ET.parse(xml).getroot().iter("testcase"):
            cases.append((tc.get("name"), tc.find("failure") is not None, tc.find("skipped") is not None,
                          (tc.find("failure").get("message") if tc.find("failure") is not None else "")))
    return r.returncode, cases, time.time() - t0


def main():
    global a
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("app")
    ap.add_argument("out", nargs="?", default=os.path.join(ROOT, "work/journey-check"))
    ap.add_argument("--image")
    a = ap.parse_args()
    a.out = os.path.abspath(a.out)
    if os.path.realpath(a.app).startswith(os.path.realpath(a.out) + os.sep):
        sys.exit(f"journey-check: {a.app} is inside OUT ({a.out}), which is emptied first; pass another OUT")
    shutil.rmtree(a.out, ignore_errors=True)
    os.makedirs(a.out)
    shutil.copy(a.app, os.path.join(a.out, "app.apk"))
    a.app = os.path.join(a.out, "app.apk")
    if a.image:
        a.platform = "linux/" + subprocess.run(["docker", "image", "inspect", "-f", "{{.Architecture}}", a.image],
                                               capture_output=True, text=True).stdout.strip()
    try:
        print("== record")
        bundle = record()
        print("== replay --journey")
        jpath = generate(bundle)
        j = json.load(open(jpath)) if os.path.exists(jpath) else {"steps": []}
        steps = j["steps"]
        print("        " + "\n        ".join(json.dumps(s) for s in steps))
        targets = [s.get("target", {}) for s in steps if s["do"] == "tap"]
        check({"text": "Trips"} in targets and {"text": "Italian vacation"} in targets and {"text": "Home"} in targets
              and targets.count({"id": "fab"}) == 3, f"taps target nodes: {targets}")
        double = [s for s in steps if s["do"] == "tap" and len(s.get("times", [])) == 2]
        check(len(double) == 1 and double[0].get("target") == {"id": "fab"} and double[0]["times"][1][0] < 400,
              f"the double tap is one step with its timing: {double}")
        expects = [e for s in steps for e in s.get("expect", [])]
        check(all(t in expects for t in ("Roman vacation", "FAB clicks: 1", "FAB clicks: 2", "FAB clicks: 4")), f"expectations: {expects}")
        print("== journey.py")
        code, cases, took = run_journey("pass", jpath)
        check(code == 0 and cases and not any(f for _, f, _, _ in cases), f"passes: exit {code}, {len(cases)} test cases, {took:.0f} s")
        print("== journey.py on a tablet")
        code, cases, took = run_journey("tablet", jpath, apksim=["--device", "tablet"])
        check(code == 0 and cases and not any(f for _, f, _, _ in cases), f"passes on a tablet by nodes: exit {code}, {took:.0f} s")
        code, cases, took = run_journey("tablet-coordinates", jpath, extra=["--by-coordinates"], apksim=["--device", "tablet"])
        check(code == 1, f"fails on a tablet by the recorded coordinates: exit {code}: {next((m for _, f, _, m in cases if f), '')[:100]}")
        print("== journey.py as a changed build")
        code, cases, took = run_journey("changed", jpath, apksim=["--feature", "dev.apksim.testapp.rename_v2"])
        failed = [(n, m) for n, f, _, m in cases if f]
        skipped = [n for n, _, s, _ in cases if s]
        check(code == 1 and len(failed) == 1 and "Italian vacation" in failed[0][0] and "Roman vacation" in failed[0][1],
              f"fails at the rename: exit {code}, {failed[:1]}")
        check(len(skipped) >= 3, f"the steps after it are skipped in the XML: {len(skipped)}")
        print("== a recording that ends in a crash")
        crash = record("crash", crash_session)
        if crash:
            jpath = generate(crash, "gen-crash", want=10)
            j = json.load(open(jpath)) if os.path.exists(jpath) else None
            if check(j is not None, "the replay that crashed wrote journey.json"):
                print("        " + "\n        ".join(json.dumps(s) for s in j["steps"]) + "\n        notes: " + json.dumps(j.get("notes")))
                check(any(s.get("target") == {"text": "Trips"} for s in j["steps"])
                      and any(s.get("command", [])[:1] == ["open"] and "crash" in s["command"][-1] for s in j["steps"]),
                      "with the steps up to the crash (Trips, the screen that crashes)")
                check(any("crashed" in n for n in j.get("notes", [])), "and a note that the app crashed")
    finally:
        if a.image:
            for n in ["rec", "gen", "pass", "tablet", "tablet-coordinates", "changed", "crash", "gen-crash"]:
                subprocess.run(["docker", "rm", "-f", "journeycheck-" + n], capture_output=True)
    print()
    if problems:
        print(f"journey-check: {len(problems)} problem(s):")
        for p in problems:
            print("  - " + p)
        sys.exit(1)
    print("journey-check: all good")


if __name__ == "__main__":
    main()
