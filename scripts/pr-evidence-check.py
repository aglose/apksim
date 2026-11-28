#!/usr/bin/env python3
"""Checks scripts/pr-evidence.py (docs/bug-to-pr.md) on the test app.

Usage: pr-evidence-check.py APP.apk [OUT] [--image IMAGE]

The "bug" is a line of the test app's Bluetooth screen (`apksim://bluetooth`: "features: bluetooth yes, ble yes"), "ble
yes" in the base build's launches and "ble no" in the new build's, planted with a launch option as the other checks
plant a changed build (`--base-option`/`--new-option "--feature android.hardware.bluetooth_le[=false]"`): the same APK
twice.

1. Records the bug with `--rec all` against scripts/network-fixture.py, reached through `--network-host` (the tap
   never reads loopback): a checkpoint, the network screen's fetch, Back, then the Bluetooth screen showing "ble yes".
2. `pr-evidence.py run` with the fixture stopped, on a repository made here whose last commit changes
   BluetoothActivity.kt and a README (with git; `--changed` without, as in the Linux image):
   - baseline: two starts explored (--depth 1), what they lead to found and not explored, said to have no dependencies;
   - verify: exit 0, the base matched at every point, the new build first differing at the end, its region named,
     one request recorded and one asked on each;
   - diff: exactly the Bluetooth screen selected by BluetoothActivity.kt, the README ignored, the unexplored screens
     left out and counted, the screen reported changed with its text going from "ble yes" to "ble no";
   - comment: the verdict, that screen's table with before, after and difference images copied under stable names.
3. `verify --expect same` of the base build against itself exits 0; without `--expect same` it exits 1.
4. The verdict for a recorded crash, for both builds crashing and for a replay that compared nothing, and the options
   a replay is not given (`--data-slots`, `--fresh`), on made-up results.
5. `comment --image-base URL` names the images under that address.

--image IMAGE runs all of it inside the Linux image (this script mounted, pr-evidence.py the image's own). OUT
(default work/pr-evidence-check) is emptied first.
"""
import argparse, json, os, re, shutil, socket, subprocess, sys, time, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
problems = []
BUG, FIXED = "ble yes", "ble no"
FEATURE = "--feature android.hardware.bluetooth_le"


def read(path):
    try:
        with open(path, errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def check(ok, what, detail=""):
    print(("  ok    " if ok else "  FAIL  ") + what + (f": {detail}" if detail and not ok else ""), flush=True)
    if not ok:
        problems.append(what)
    return ok


class Drive:
    def __init__(self, port, log):
        for _ in range(900):
            try:
                self.s = socket.create_connection(("127.0.0.1", port), timeout=1)
                self.s.settimeout(60)
                self.f = self.s.makefile("rw")
                if self("ping") == "pong":
                    return
            except OSError:
                pass
            time.sleep(0.2)
        raise SystemExit("the app never answered on its control port: " + read(log)[-3000:])

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


def apksim():
    beside = os.path.join(ROOT, "apksim")
    return beside if os.path.isfile(beside) else "apksim"


def evidence():
    """pr-evidence.py: the one beside this script when the launcher is there too, else the installed one."""
    beside = os.path.join(HERE, "pr-evidence.py")
    if os.path.isfile(os.path.join(ROOT, "apksim")) and os.path.isfile(beside):
        return beside
    return shutil.which("pr-evidence.py") or beside


def record(out, apk, fport, cert):
    print("== record the bug")
    log = os.path.join(out, "record.log")
    p = subprocess.Popen([apksim(), "--control", "auto", "--headless", "--fresh", "--data-slots", "prevcheck", "--rec", "all",
                          "--rec-checkpoint", "0", "--feature", "dev.apksim.testapp.no_timings",
                          "--network-host", f"api.apksim.test=127.0.0.1:{fport}", "--network-trust", cert,
                          apk],  # without FEATURE: a replay applies the flags a recording was made with over its own,
                         stdout=open(log, "w"), stderr=subprocess.STDOUT, cwd=out, env=dict(os.environ, APKSIM_STANDBY="0"))
    try:
        port = None
        for _ in range(1200):
            m = re.search(r"\[apksim\] control port (\d+)", read(log))
            if m:
                port = int(m.group(1))
                break
            if p.poll() is not None:
                break
            time.sleep(0.1)
        if port is None:
            raise SystemExit("record: no control port: " + read(log)[-3000:])
        d = Drive(port, log)
        d("settle 20000")
        cp = ""
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
        d("open apksim://bluetooth")
        d("settle 10000")
        shown = d.wait_text(lambda t: BUG in t, 20)
        check(shown is not None, f"the Bluetooth screen shows the bug ({BUG})", str(d.texts()[:12]))
        time.sleep(1.5)
        bundle = os.path.join(out, "bug.apkrec")
        s = d("rec save 60 " + bundle)
        check(s.startswith("saving"), f"saved: {s}")
        for _ in range(100):
            if "KB in" in d("rec"):
                break
            time.sleep(0.2)
        d("quit")
        try:
            p.wait(timeout=60)
        except subprocess.TimeoutExpired:
            pass
    finally:
        if p.poll() is None:
            p.kill()
    har = json.loads(zipfile.ZipFile(bundle).read("network.har"))["log"]["entries"]
    check(len(har) == 1, f"the fetch through --network-host is in the recording ({len(har)} entries)")
    return bundle


def make_repo(out):
    """A repository whose last commit changes the Bluetooth screen's source file and a README. Returns (repo, select args)."""
    repo = os.path.join(out, "repo")
    src = os.path.join(repo, "app/src/main/kotlin/dev/apksim/composeapp/BluetoothActivity.kt")
    os.makedirs(os.path.dirname(src))
    with open(src, "w") as f:
        f.write("package dev.apksim.composeapp\n\nclass BluetoothActivity {\n  fun seed() = \"off\"\n}\n")
    with open(os.path.join(repo, "README.md"), "w") as f:
        f.write("# app\n")

    def git(*args):
        return subprocess.run(["git", "-C", repo, "-c", "user.name=check", "-c", "user.email=check@example.invalid",
                               "-c", "commit.gpgsign=false", *args], capture_output=True, text=True)

    use_git = shutil.which("git") is not None and git("init", "-q").returncode == 0
    if use_git:
        git("add", "-A")
        use_git = git("commit", "-q", "-m", "base").returncode == 0
    with open(src, "w") as f:
        f.write("package dev.apksim.composeapp\n\nclass BluetoothActivity {\n  fun seed() = \"on\"\n}\n")
    with open(os.path.join(repo, "README.md"), "a") as f:
        f.write("\nFixed the seed.\n")
    if use_git:
        git("add", "-A")
        use_git = git("commit", "-q", "-m", "fix").returncode == 0
    if use_git:
        return repo, ["--diff", "HEAD~1"]
    print("  ..    no git here: naming the changed files with --changed")
    return repo, ["--changed", "app/src/main/kotlin/dev/apksim/composeapp/BluetoothActivity.kt", "README.md"]


def run_evidence(out, name, args, want):
    log = os.path.join(out, name + ".log")
    t0 = time.time()
    p = subprocess.run([sys.executable, evidence()] + args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, cwd=out)
    with open(log, "w") as f:
        f.write(p.stdout)
    check(p.returncode == want, f"pr-evidence.py {name}: exit {p.returncode} (want {want}) in {time.time() - t0:.0f} s", p.stdout[-2500:])
    return p.stdout


def inside(apk, out):
    cert = os.path.join(out, "fixture/cert.pem")
    fixture_py = os.path.join(out, "network-fixture.py")
    flog = os.path.join(out, "fixture.log")
    fixture = subprocess.Popen([sys.executable, fixture_py, os.path.join(out, "fixture")], stdout=open(flog, "w"), stderr=subprocess.STDOUT)
    try:
        fport = None
        for _ in range(100):
            m = re.search(r"^ready (\d+)", read(flog), re.M)
            if m:
                fport = m.group(1)
                break
            time.sleep(0.1)
        if not fport:
            raise SystemExit("the fixture server did not start: " + read(flog))
        bundle = record(out, apk, fport, cert)
    finally:
        fixture.terminate()
        fixture.wait()
    repo, change = make_repo(out)

    print("== pr-evidence.py run (fixture stopped)")
    work = os.path.join(out, "work")
    text = run_evidence(out, "run", ["run", bundle, "--base", apk, "--new", apk, "--work", work, "--repo", repo, *change,
                                    "--depth", "1", "--base-option", FEATURE, "--new-option", FEATURE + "=false",
                                    "--start", "apksim://bluetooth", "--start", "apksim://stack", "-j", "2",
                                    "--", "--network-trust", cert], 0)

    base = json.load(open(os.path.join(work, "baseline/crawl.json"))) if os.path.exists(os.path.join(work, "baseline/crawl.json")) else {}
    how = json.load(open(os.path.join(work, "baseline/pr-evidence.json"))) if os.path.exists(os.path.join(work, "baseline/pr-evidence.json")) else {}
    with_deps = [s for s in base.get("screens", []) if s.get("deps") is not None]
    without = [s for s in base.get("screens", []) if s.get("deps") is None]
    slot = [s for s in with_deps if "BluetoothActivity" in s.get("activity", "")]
    check(len(with_deps) == 2 and len(slot) == 1, "baseline: the two starts explored with dependencies, one of them the Bluetooth screen",
          str([(s["id"], s.get("depth")) for s in base.get("screens", [])]))
    check(bool(without) and how.get("summary", {}).get("beyond_depth") == [s["id"] for s in without]
          and f"{len(without)} screen" in text and "found at the depth limit (1 from a start) and not explored" in text,
          f"baseline: says the {len(without)} screen(s) at the depth limit have no dependencies", str(how.get("summary")))
    sid = slot[0]["id"] if slot else "?"

    v = json.load(open(os.path.join(work, "verify/pr-evidence.json"))) if os.path.exists(os.path.join(work, "verify/pr-evidence.json")) else {}
    b, n = v.get("base", {}), v.get("new", {})
    check(v.get("ok") is True and "**PASS**" in text, "verify: PASS", str(v.get("lines")))
    check(b.get("result") == "matched" and b.get("points", 0) >= 3, f"verify: the base matched at every point ({b.get('points')})", str(b))
    first = n.get("first") or {}
    check(n.get("result") == "diverged" and first.get("label") == "end" and first.get("n") == n.get("points"),
          f"verify: the new build first differs at the end ({first.get('pixels')} pixels)", str(n))
    check(bool(first.get("regions")) and "the largest" in "\n".join(v.get("lines", [])), "verify: the difference's region is named", str(first))
    for r in (b, n):
        q = r.get("requests", {})
        check(q.get("answered") == 1 and q.get("unanswered") == 0 and "1 recorded, 1 asked" in text,
              f"verify: {r.get('name')}: one request recorded, one asked", str(q))
    check(not v.get("warnings"), "verify: no warnings", str(v.get("warnings")))

    sel = json.load(open(os.path.join(work, "diff/select.json"))) if os.path.exists(os.path.join(work, "diff/select.json")) else {}
    check(sel.get("selected") == [sid], f"diff: exactly the Bluetooth screen is selected ({sid})", str(sel.get("selected")))
    why = (sel.get("screens") or {}).get(sid, [])
    check(any(w.get("file", "").endswith("BluetoothActivity.kt") and w.get("via") == "source" for w in why)
          and "BluetoothActivity.kt (source)" in text, "diff: BluetoothActivity.kt is named as what selected it", str(why))
    check(sel.get("ignored") == ["README.md"] and "ignored (1): README.md" in text, "diff: the README is ignored and said so", str(sel.get("ignored")))
    check(sel.get("left_out_unexplored") == len(without) and "found and never explored (no dependencies) left out" in text,
          f"diff: the {len(without)} unexplored screens are left out and counted", str(sel.get("left_out_unexplored")))
    walk = json.load(open(os.path.join(work, "diff/crawl.json"))) if os.path.exists(os.path.join(work, "diff/crawl.json")) else {}
    pairs = {p.get("id"): p for p in walk.get("compare", [])}
    changed = [p for p in walk.get("compare", []) if p["status"] == "changed"]
    replaced = (((pairs.get(sid) or {}).get("ui_diff") or {}).get("texts") or {}).get("replaced") or []
    check([p["id"] for p in changed] == [sid], "diff: only the Bluetooth screen is reported changed", str([(p.get("id"), p["status"]) for p in walk.get("compare", [])]))
    check(any(a.endswith(BUG) and b_.endswith(FIXED) for a, b_ in replaced), f"diff: its text changed from '{BUG}' to '{FIXED}'", str(replaced))
    origins = {s["id"]: s.get("origin") for s in walk.get("screens", [])}
    check(origins.get(sid) == "walked" and all(o == "carried" for i, o in origins.items() if i != sid),
          "diff: that screen alone is walked, the rest carried from the baseline", str(origins))

    md = read(os.path.join(work, "comment.md"))
    imgs = os.path.join(work, "comment-images")
    names = sorted(os.listdir(imgs)) if os.path.isdir(imgs) else []
    check("**PASS**" in md and "### The recorded bug, replayed" in md and "### What else the change touches" in md, "comment: the verdict and both parts", md[:600])
    check("#### Changed:" in md and f"(`{sid}`)" in md and FIXED in md and "→" in md, "comment: the changed screen with its text change", md[-1500:])
    check({"replay-recorded.jpg", "replay-new.jpg", "replay-diff.png"} <= set(names), "comment: the replay's three pictures copied", str(names))
    stems = [x for x in names if x.startswith("screen-")]
    check(len(stems) == 3 and all(any(x.endswith(e) for x in stems) for e in ("-before.png", "-after.png", "-diff.png")),
          "comment: the screen's before, after and difference pictures copied under stable names", str(names))
    check(all(f'src="comment-images/{x}"' in md for x in names) and "| Before | After | Difference |" in md, "comment: the Markdown shows them in a table", md[-1500:])
    check("carried from the baseline" in md, "comment: says what was carried unchanged")

    print("== verify --expect same")
    same = os.path.join(out, "same")
    run_evidence(out, "verify-same", ["verify", bundle, "--base", apk, "--new", apk, "--out", same, "--expect", "same",
                                     "--", "--network-trust", cert], 0)
    t = run_evidence(out, "verify-notfixed", ["verify", bundle, "--base", apk, "--new", apk, "--out", os.path.join(out, "notfixed"),
                                             "--", "--network-trust", cert], 1)
    check("**FAIL**" in t and "nothing visible changed" in t, "an unchanged build is not a fix: FAIL, and it says why", t[-600:])

    print("== verdicts and options without a replay")
    import importlib.util
    spec = importlib.util.spec_from_file_location("pr_evidence", evidence())
    pe = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(pe)
    ok_ = {"result": "matched", "points": 5}
    crash = {"result": "crashed", "points": 2, "crash": "crashed after open x"}
    check(pe.verdict_of(crash, ok_, "fix")[0] is True, "a recorded crash the new build no longer has is a fix")
    check(pe.verdict_of(crash, crash, "fix")[0] is False and pe.verdict_of(crash, ok_, "same")[0] is False,
          "both builds crashing is not, nor is a crash recording with --expect same")
    check(pe.verdict_of({"result": "matched", "points": 0}, ok_, "same")[0] is None, "a replay that compared no screen gives no verdict")
    check(pe.replay_options(["--data-slots", "map", "--fresh", "--network-trust", "c.pem"]) == ["--network-trust", "c.pem"],
          "the walks' --data-slots and --fresh do not reach a replay")

    print("== comment --image-base")
    hosted = os.path.join(out, "hosted.md")
    run_evidence(out, "comment-hosted", ["comment", "--out", hosted, "--verify", os.path.join(work, "verify"), "--walk", os.path.join(work, "diff"),
                                        "--images", os.path.join(out, "hosted-images"), "--image-base", "https://example.invalid/pr/7"], 0)
    check('src="https://example.invalid/pr/7/replay-diff.png"' in read(hosted)
          and os.path.isfile(os.path.join(out, "hosted-images/replay-diff.png")), "the images are named under --image-base and copied to --images")

    print()
    if problems:
        print(f"pr-evidence-check: {len(problems)} problem(s):")
        for p in problems:
            print("  - " + p)
        return 1
    print("pr-evidence-check: all good")
    return 0


def main():
    ap = argparse.ArgumentParser(usage=__doc__)
    ap.add_argument("apk")
    ap.add_argument("out", nargs="?", default=os.path.join(ROOT, "work/pr-evidence-check"))
    ap.add_argument("--image")
    ap.add_argument("--inside", action="store_true", help=argparse.SUPPRESS)  # in the image: OUT is prepared
    a = ap.parse_args()
    out = os.path.abspath(a.out)
    if a.inside:
        return inside(os.path.abspath(a.apk), out)
    if os.path.abspath(a.apk).startswith(out + os.sep):
        sys.exit(f"pr-evidence-check: {a.apk} is inside OUT ({out}), which is emptied first; pass another OUT")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(os.path.join(out, "fixture"))
    shutil.copy(a.apk, os.path.join(out, "app.apk"))
    fixture_py = os.path.join(HERE, "network-fixture.py")
    shutil.copy(fixture_py, os.path.join(out, "network-fixture.py"))
    subprocess.run([sys.executable, fixture_py, os.path.join(out, "fixture"), "--cert-only"], check=True, capture_output=True)  # openssl: not in the image
    if not a.image:
        return inside(os.path.join(out, "app.apk"), out)
    arch = subprocess.run(["docker", "image", "inspect", "-f", "{{.Architecture}}", a.image], capture_output=True, text=True).stdout.strip()
    shutil.copy(os.path.abspath(__file__), os.path.join(out, "pr-evidence-check.py"))
    return subprocess.run(["docker", "run", "--rm", "--init", "--platform", "linux/" + arch, "-v", f"{out}:/work", "--entrypoint", "python3", a.image,
                           "/work/pr-evidence-check.py", "/work/app.apk", "/work", "--inside"]).returncode


if __name__ == "__main__":
    sys.exit(main())
