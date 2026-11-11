#!/usr/bin/env python3
"""Checks scripts/bisect-rec.py (docs/replay.md, "Bisect") against the test app.

Usage: bisect-rec-check.py APP.apk [OUT] [--image IMAGE] [--bundle BUNDLE] [--changed APK]

Records the Trips rename with `--rec all` (or takes --bundle), then bisects it over six builds, oldest first:

  1, 2  the app                                  good
  3     an unsigned APK                          skipped: not the recorded build's signer (exit 2)
  4     the app                                  good: the last good build
  5, 6  the app with --feature dev.apksim.testapp.rename_v2 (the rename writes "Roman holiday", as a changed build
        would)                                   bad: 5 is the first bad build

The skipped build sits where the search's first midpoint lands, so the search must go around it. Checks the verdict,
bisect.json, that the oldest and newest builds were replayed, that at most five were replayed in full, and that
index.html shows the recorded frame, the last good and first bad builds' frames and the difference map. With
--changed APK (a build of the test app with a source change, the same debug key), also bisects [app, app, changed]:
the changed build must be the first bad one. --image replays in the Linux image.
"""
import argparse, json, os, re, shutil, socket, subprocess, sys, time, zipfile

ROOT = os.path.abspath(os.path.dirname(os.path.abspath(sys.argv[0])) + "/..")
PORT = int(os.environ.get("BISECTCHECK_PORT", "7612"))
problems = []


def check(ok, what):
    print(("  ok    " if ok else "  FAIL  ") + what)
    if not ok:
        problems.append(what)
    return ok


def drive(port):
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


def record(a):
    """The Trips rename, recorded: a checkpoint on the Trips tab, a tap on Italian vacation."""
    log = os.path.join(a.out, "record.log")
    bundle = os.path.join(a.out, "rec.apkrec")
    if a.image:
        subprocess.run(["docker", "rm", "-f", "bisectcheck-rec"], capture_output=True)
        p = subprocess.Popen(["docker", "run", "--name", "bisectcheck-rec", "--platform", a.platform, "-v", f"{a.out}:/work",
                              "-p", f"127.0.0.1:{PORT}:{PORT}", a.image, "--headless", "--fresh", "--rec", "all", "--rec-checkpoint", "0",
                              "--control", str(PORT), "--control-bind", "0.0.0.0", "/work/app.apk"],
                             stdout=open(log, "w"), stderr=subprocess.STDOUT)
        port, target = PORT, "/work/rec.apkrec"
    else:
        p = subprocess.Popen([os.path.join(ROOT, "apksim"), "--headless", "--fresh", "--rec", "all", "--rec-checkpoint", "0",
                              "--control", "auto", a.app], stdout=open(log, "w"), stderr=subprocess.STDOUT,
                             env=dict(os.environ, APKSIM_STANDBY="0"))
        port = None  # read from its log below, inside the cleanup
        target = bundle
    try:
        return record_session(a, p, port, target, bundle)
    finally:
        # Whatever happened above, the recording's process or container does not outlive this check.
        if p.poll() is None:
            p.kill()
        if a.image:
            subprocess.run(["docker", "rm", "-f", "bisectcheck-rec"], capture_output=True)


def record_session(a, p, port, target, bundle):
    log = os.path.join(a.out, "record.log")
    for _ in range(1200 if port is None else 0):
        with open(log) as f:
            m = re.search(r"\[apksim\] control port (\d+)", f.read())
        if m:
            port = int(m.group(1))
            break
        time.sleep(0.1)
    if port is None:
        with open(log) as f:
            raise SystemExit("no control port: " + f.read()[-2000:])
    d = drive(port)
    d("settle 20000")
    d("tap 300 1690")
    d("settle 5000")
    for _ in range(20):
        if d("rec checkpoint now").startswith("checkpoint #"):
            break
        time.sleep(0.5)
    time.sleep(1)
    m = re.search(r'\[(\d+),(\d+),(\d+),(\d+)\] text="Italian vacation"', d("nodes"))
    if not m:
        raise SystemExit("no Italian vacation on the Trips tab")
    x1, y1, x2, y2 = map(int, m.groups())
    d(f"tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
    time.sleep(2)
    d(f"rec save 60 {target}")
    for _ in range(100):
        if "KB in" in d("rec"):
            break
        time.sleep(0.2)
    d("quit")
    p.wait(timeout=60)
    if a.image:
        subprocess.run(["docker", "rm", "-f", "bisectcheck-rec"], capture_output=True)
    check(zipfile.is_zipfile(bundle), "recorded the Trips rename")
    return bundle


def bisect(a, bundle, out, builds_file=None, builds=()):
    cmd = [sys.executable, os.path.join(ROOT, "scripts/bisect-rec.py"), bundle, *builds, "--out", out]
    if builds_file:
        cmd += ["--builds", builds_file]
    if a.image:
        cmd += ["--image", a.image]
    t0 = time.time()
    r = subprocess.run(cmd, capture_output=True, text=True)
    print("\n".join("        " + l for l in r.stdout.strip().splitlines()))
    took = time.time() - t0
    data = json.load(open(os.path.join(out, "bisect.json"))) if os.path.exists(os.path.join(out, "bisect.json")) else {}
    return r.returncode, data, took


def main():
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("app")
    ap.add_argument("out", nargs="?", default=os.path.join(ROOT, "work/bisect-check"))
    ap.add_argument("--image")
    ap.add_argument("--bundle")
    ap.add_argument("--changed")
    a = ap.parse_args()
    a.out = os.path.abspath(a.out)
    for f in [a.app, a.bundle, a.changed]:
        if f and os.path.realpath(f).startswith(os.path.realpath(a.out) + os.sep):
            sys.exit(f"bisect-rec-check: {f} is inside OUT ({a.out}), which is emptied first; pass another OUT")
    shutil.rmtree(a.out, ignore_errors=True)
    os.makedirs(a.out)
    shutil.copy(a.app, os.path.join(a.out, "app.apk"))
    a.app = os.path.join(a.out, "app.apk")
    if a.image:
        a.platform = "linux/" + subprocess.run(["docker", "image", "inspect", "-f", "{{.Architecture}}", a.image],
                                               capture_output=True, text=True).stdout.strip()
    print("== record")
    if a.bundle:
        bundle = os.path.join(a.out, "rec.apkrec")
        shutil.copy(a.bundle, bundle)
    else:
        bundle = record(a)
    # An APK no one signed: `apksim replay --apk` refuses it (not the recorded build's signer), so the bisect skips it.
    with zipfile.ZipFile(os.path.join(a.out, "unsigned.apk"), "w") as z:
        z.writestr("AndroidManifest.xml", b"\0" * 64)
    with open(os.path.join(a.out, "builds.txt"), "w") as f:
        f.write("# oldest first\napp.apk\napp.apk\nunsigned.apk\napp.apk\n"
                "app.apk --feature dev.apksim.testapp.rename_v2\napp.apk --feature dev.apksim.testapp.rename_v2\n")
    print("== bisect over six builds")
    out = os.path.join(a.out, "bisect")
    code, data, took = bisect(a, bundle, out, builds_file=os.path.join(a.out, "builds.txt"))
    b = data.get("builds", [])
    check(code == 0, f"exit {code}: {data.get('verdict')}")
    check(data.get("firstBad") == 5 and data.get("lastGood") == 4, f"first bad 5, last good 4 (got {data.get('firstBad')}, {data.get('lastGood')})")
    res = [x.get("result", "-") for x in b]
    check(res[0] == "good" and res[5] == "bad", f"the oldest replayed good and the newest bad: {res}")
    check(res[2] == "skipped" and "another key" in b[2].get("why", ""), f"the unsigned build skipped: {b[2].get('why') if len(b) > 2 else None}")
    replayed = sum(1 for r in res if r in ("good", "bad"))
    check(replayed <= 5, f"{replayed} of 6 builds replayed in full, in {took:.0f} s")
    page = open(os.path.join(out, "index.html")).read() if os.path.exists(os.path.join(out, "index.html")) else ""
    imgs = re.findall(r'<img src="data:image/[a-z]+;base64,[A-Za-z0-9+/=]{100,}" data-src="([^"]+)"', page)
    check(len(imgs) == 4 and all(os.path.exists(os.path.join(out, i)) for i in imgs),
          f"index.html shows the recorded, last good, first bad frames and the difference map: {imgs}")
    if a.changed:
        print("== bisect against a changed build")
        shutil.copy(a.changed, os.path.join(a.out, "changed.apk"))
        code, data, took = bisect(a, bundle, os.path.join(a.out, "bisect-changed"),
                                  builds=[a.app, a.app, os.path.join(a.out, "changed.apk")])
        check(code == 0 and data.get("firstBad") == 3, f"the changed build is the first bad one ({took:.0f} s): {data.get('verdict')}")
    print()
    if problems:
        print(f"bisect-rec-check: {len(problems)} problem(s):")
        for p in problems:
            print("  - " + p)
        sys.exit(1)
    print("bisect-rec-check: all good")


if __name__ == "__main__":
    main()
