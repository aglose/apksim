#!/usr/bin/env python3
"""Screenshot (golden) tests for an APK: every screen in every combination of a device matrix, against goldens.

Usage: snapshot.py SPEC.json [--apk APK] [--update] [-j N] [--out DIR] [--screens a,b] [--goldens DIR]
                   [-- APKSIM_OPTION...]

SPEC.json names the screens (a deep link and/or control commands each), the matrix (device, appearance, locale,
fontscale) and the golden folder; paths in it are relative to the spec's own folder. docs/snapshots.md has the format
and what makes captures deterministic. Starts N headless instances (`apksim --headless --snapshot --fresh`), each
taking every Nth combination: it applies the combination with configuration changes, then for each screen sets the
clock, restarts the app's activity (`restart URL`: no state from the screen before), runs the steps, waits for the UI
to settle (`settle`) and takes `shot`. Each capture is compared with GOLDENS/<screen>/<combination>.png (the
instance's `diff`, so no imaging library is needed here).

Writes OUT/report.html (golden, capture and a difference map side by side), OUT/results.json, and OUT/actual,
OUT/golden, OUT/diff, OUT/logs. --update writes the captures as the new goldens. Exit status: 1 if a capture failed
or differed from its golden beyond the screen's tolerance (or had none), 2 for a bad spec or an instance that never
started, else 0.
"""
import html, itertools, json, os, re, shutil, socket, subprocess, sys, tempfile, threading, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AXES = ("device", "appearance", "locale", "fontscale")
DEFAULTS = {"device": "phone", "appearance": "light", "locale": "en-US", "fontscale": 1.0}


def fail(msg, code=2):
    print(f"snapshot: {msg}", file=sys.stderr)
    sys.exit(code)


def parse_args(argv):
    opts = {"update": False, "jobs": None, "out": None, "screens": None, "apk": None, "goldens": None, "apksim": []}
    if "--" in argv:
        i = argv.index("--")
        argv, opts["apksim"] = argv[:i], argv[i + 1:]
    rest = []
    it = iter(argv)
    for a in it:
        if a == "--update":
            opts["update"] = True
        elif a == "-j":
            opts["jobs"] = int(next(it))
        elif a in ("--out", "--screens", "--apk", "--goldens"):
            opts[a[2:]] = next(it)
        elif a in ("-h", "--help"):
            print(__doc__)
            sys.exit(0)
        else:
            rest.append(a)
    if len(rest) != 1:
        sys.exit(__doc__)
    opts["spec"] = rest[0]
    return opts


def fmt(value):
    """A matrix value as it appears in file names: 1.0 -> 1, 1.3 -> 1.3."""
    return format(value, "g") if isinstance(value, float) else str(value)


def combo_name(c):
    return "-".join(fmt(c[a]) for a in AXES)


def tolerance(t):
    t = t or {}
    return {"pixels": float(t.get("pixels", 0)), "delta": int(t.get("delta", 0))}


def load_spec(opts):
    path = os.path.abspath(opts["spec"])
    try:
        with open(path) as f:
            spec = json.load(f)
    except (OSError, ValueError) as e:
        fail(f"cannot read {path}: {e}")
    here = os.path.dirname(path)
    apk = opts["apk"] or (spec.get("apk") and os.path.join(here, spec["apk"]))
    goldens = opts["goldens"] or (spec.get("goldens") and os.path.join(here, spec["goldens"]))
    if not apk or not os.path.isfile(apk):
        fail(f"no APK: {apk!r} (spec 'apk' or --apk)")
    if not goldens:
        fail("no golden folder (spec 'goldens' or --goldens)")
    matrix = spec.get("matrix", {})
    for k in matrix:
        if k not in AXES:
            fail(f"unknown matrix axis {k!r} (axes: {', '.join(AXES)})")
    values = [[(float(v) if a == "fontscale" else str(v)) for v in matrix.get(a, [DEFAULTS[a]])] for a in AXES]
    combos = [dict(zip(AXES, vs)) for vs in itertools.product(*values)]

    def matches(c, rule):
        return all(fmt(c[k]) in [fmt(float(x) if k == "fontscale" else x) for x in (v if isinstance(v, list) else [v])]
                   for k, v in rule.items())

    combos = [c for c in combos if not any(matches(c, e) for e in spec.get("exclude", []))]
    screens = spec.get("screens", [])
    names = [s.get("name") for s in screens]
    if not screens or any(not n or not re.fullmatch(r"[A-Za-z0-9._-]+", n) for n in names) or len(set(names)) != len(names):
        fail("screens need unique names of letters, digits, '.', '_' and '-'")
    if opts["screens"]:
        wanted = opts["screens"].split(",")
        missing = set(wanted) - set(names)
        if missing:
            fail(f"no such screen: {', '.join(sorted(missing))}")
        screens = [s for s in screens if s["name"] in wanted]
    for s in screens:
        if not isinstance(s.get("open", ""), str) or not isinstance(s.get("steps", []), list) \
                or not all(isinstance(x, str) and x.strip() for x in s.get("steps", [])) \
                or not isinstance(s.get("only", {}), dict):
            fail(f"screen {s['name']}: 'open' is a URL, 'steps' a list of commands, 'only' an object of axis values")
    try:
        default_tol = tolerance(spec.get("tolerance"))
        for s in screens:
            if "tolerance" in s:
                tolerance(s["tolerance"])
    except (TypeError, ValueError, AttributeError) as e:
        fail(f"bad tolerance: {e}")
    for s in screens:
        s["_tol"] = tolerance(s["tolerance"]) if "tolerance" in s else default_tol
        s["_only"] = s.get("only", {})
    return spec, os.path.abspath(apk), os.path.abspath(goldens), combos, screens


class Closed(Exception):
    pass


class Worker:
    """One headless instance and the combinations it captures."""

    def __init__(self, i, combos, ctx):
        self.i, self.combos, self.ctx = i, combos, ctx
        self.log = os.path.join(ctx["out"], "logs", f"instance-{i}.log")
        self.proc = self.file = self.sock = None
        self.error = None
        # Captures and difference maps are written here by the instance, then moved into OUT: the control protocol
        # splits on whitespace, and the Mac launcher's sandbox lets the app write under the home folder only in
        # apksim's own state folder. Created in main().
        self.stage = None

    def start(self):
        launcher = os.path.join(ROOT, "apksim")
        if not os.path.exists(launcher):
            launcher = shutil.which("apksim") or launcher
        spec = self.ctx["spec"]
        cmd = [launcher, "--headless", "--snapshot", "--fresh", "--control", "auto",
               "--timezone", spec.get("timezone", "UTC")]
        if spec.get("clock"):
            cmd += ["--clock", spec["clock"]]
        cmd += ["--device", self.combos[0]["device"], *self.ctx["apksim"], self.ctx["apk"]]
        with open(self.log, "w") as log:
            # Cold launches: a standby has read the GPU switches --snapshot sets before the launch.
            self.proc = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                         env=dict(os.environ, APKSIM_STANDBY="0"))
        deadline = time.time() + 180
        port = None
        while time.time() < deadline and self.proc.poll() is None and port is None:
            try:
                with open(self.log, errors="replace") as f:
                    m = re.search(r"\[apksim\] control port (\d+)", f.read())
                port = m and int(m[1])
            except OSError:
                pass
            time.sleep(0.1)
        while port and time.time() < deadline and self.proc.poll() is None:
            try:
                self.sock = socket.create_connection(("127.0.0.1", port), timeout=2)
                self.sock.settimeout(120)
                self.file = self.sock.makefile("rw")
                if self.send("ping") == "pong":
                    break
            except (OSError, Closed):
                self.close()
                time.sleep(0.2)
        if self.file is None:
            code = self.proc.poll()
            raise Closed(f"instance {self.i} " + (f"exited with status {code}" if code is not None else "did not answer")
                         + f" (see {self.log})")
        self.expect("animscale 0")
        # Wait for the launch screen's first frame (a cold start can take seconds), but not for it to settle: it may
        # animate for ever while every screen asked for is static. Each capture restarts and settles its own screen.
        while not re.search(r"frames=[1-9]", self.send("stats")):
            if time.time() > deadline:
                raise Closed(f"instance {self.i} drew no frame within 180 s (see {self.log})")
            time.sleep(0.1)

    def send(self, cmd):
        self.file.write(cmd + "\n")
        self.file.flush()
        reply = self.file.readline()
        if not reply:
            raise Closed(f"instance {self.i} closed its control connection after {cmd!r} (see {self.log})")
        return reply.strip()

    def expect(self, cmd):
        """Sends a command whose reply must not be an error."""
        reply = self.send(cmd)
        if reply.startswith("error") or reply.startswith("unknown command") or reply.startswith("not found") \
                or reply.startswith("usage") or reply.startswith("no activity"):
            raise StepFailed(f"{cmd!r}: {reply}")
        return reply

    def run(self):
        todo = [(c, s) for c in self.combos for s in applicable(self.ctx["screens"], c)]
        try:
            self.start()
        except Exception as e:  # anything: the captures this instance had fail, and the run with them
            self.error = str(e)
            for c, s in todo:
                self.ctx["record"](capture_result(s, c, "error", str(e)))
            self.quit()
            return
        try:
            self.captures(todo)
        except Exception as e:  # a bug here, not the instance: still every capture gets a result
            self.error = f"{type(e).__name__}: {e}"
            for c, s in todo:
                self.ctx["record"](capture_result(s, c, "error", self.error))
        finally:
            self.quit()

    def captures(self, todo):
        """Runs the captures in [todo], removing each once it has a result."""
        applied = None
        while todo:
            c, s = todo[0]
            try:
                if applied is not c:
                    applied = c
                    try:
                        for axis in AXES:
                            self.expect(f"{axis} {fmt(c[axis])}")
                    except StepFailed as e:
                        while todo and todo[0][0] is c:
                            self.ctx["record"](capture_result(todo.pop(0)[1], c, "error", f"applying the combination: {e}"))
                        continue
                self.ctx["record"](self.capture(s, c))
                todo.pop(0)
            except (Closed, OSError) as e:
                # The instance is gone: what it had left fails with the reason.
                code = self.proc.poll()
                why = f"{e}" + (f" (exit status {code})" if code is not None else "")
                while todo:
                    c2, s2 = todo.pop(0)
                    self.ctx["record"](capture_result(s2, c2, "error", why))
                return

    def capture(self, screen, combo):
        ctx = self.ctx
        rel = os.path.join(screen["name"], combo_name(combo) + ".png")
        actual = os.path.join(ctx["out"], "actual", rel)
        os.makedirs(os.path.dirname(actual), exist_ok=True)
        staged = os.path.join(self.stage, "capture.png")
        t0 = time.time()
        try:
            if ctx["spec"].get("clock"):
                self.expect(f"clock {ctx['spec']['clock']}")
            self.expect("restart" + (f" {screen['open']}" if screen.get("open") else ""))
            settled = self.expect("settle")
            for step in screen.get("steps", []):
                settled = self.expect(step)
                if not step.startswith("settle"):
                    settled = self.expect("settle")
            self.expect(f"shot {staged}")
            shutil.move(staged, actual)
        except StepFailed as e:
            return capture_result(screen, combo, "error", str(e), actual=None)
        r = compare(screen, combo, rel, actual, ctx, self)
        r["seconds"] = round(time.time() - t0, 2)
        r["settle"] = settled
        return r

    def close(self):
        for f in (self.file, self.sock):
            try:
                if f:
                    f.close()
            except OSError:
                pass
        self.file = self.sock = None

    def quit(self):
        """Ends the instance whatever state it is in: `quit` if it still answers, else SIGTERM, then SIGKILL."""
        if self.proc is None:
            return
        if self.file is not None and self.proc.poll() is None:
            try:
                self.send("quit")
            except (Closed, OSError, ValueError):
                pass
        self.close()
        for stop in (None, self.proc.terminate, self.proc.kill):
            if stop:
                stop()
            try:
                self.proc.wait(timeout=30 if stop is None else 10)
                return
            except subprocess.TimeoutExpired:
                pass


class StepFailed(Exception):
    pass


def applicable(screens, combo):
    out = []
    for s in screens:
        only = s["_only"]
        if all(fmt(combo[k]) in [fmt(float(x) if k == "fontscale" else x) for x in (v if isinstance(v, list) else [v])]
               for k, v in only.items()):
            out.append(s)
    return out


def capture_result(screen, combo, status, detail, actual=None):
    return {"screen": screen["name"], "combo": combo_name(combo), "status": status, "detail": detail,
            "actual": actual}


def compare(screen, combo, rel, actual, ctx, worker):
    golden = os.path.join(ctx["goldens"], rel)
    gcopy = os.path.join(ctx["out"], "golden", rel)
    dmap = os.path.join(ctx["out"], "diff", rel)
    r = capture_result(screen, combo, "match", "", actual=rel)
    tol = screen["_tol"]
    have = os.path.isfile(golden)
    same = False
    if have:
        os.makedirs(os.path.dirname(gcopy), exist_ok=True)
        shutil.copyfile(golden, gcopy)
        r["golden"] = rel
        with open(golden, "rb") as a, open(actual, "rb") as b:
            same = a.read() == b.read()
        if same:
            r.update(pixels=0, detail="identical")
        else:
            os.makedirs(os.path.dirname(dmap), exist_ok=True)
            sg, sa, sm = (os.path.join(worker.stage, n) for n in ("golden.png", "actual.png", "diff.png"))
            shutil.copyfile(golden, sg)
            shutil.copyfile(actual, sa)
            reply = worker.send(f"diff {sg} {sa} {sm} {tol['delta']}")
            if os.path.exists(sm):
                shutil.move(sm, dmap)
            m = re.search(r"size (\d+x\d+)(?: (\d+x\d+))? pixels=(\d+) total=(\d+) maxDelta=(\d+)", reply)
            if not m:
                r.update(status="error", detail=f"diff: {reply}")
                return r
            pixels, total = int(m[3]), int(m[4])
            allowed = tol["pixels"] * total if tol["pixels"] < 1 else tol["pixels"]
            r["diff"] = rel
            r.update(pixels=pixels, total=total, maxDelta=int(m[5]))
            if m[2]:
                r.update(status="mismatch", detail=f"size changed: golden {m[1]}, capture {m[2]}")
            elif pixels > allowed:
                r.update(status="mismatch", detail=f"{pixels} pixels differ ({100 * pixels / total:.3f}%, max delta {m[5]}),"
                         f" tolerance {fmt(tol['pixels'])} beyond delta {tol['delta']}")
            else:
                r.update(detail=f"{pixels} pixels differ beyond delta {tol['delta']}, within tolerance")
    if ctx["update"]:
        # Rewritten whenever the bytes differ, also when the difference is within the tolerance.
        if not have or not same:
            os.makedirs(os.path.dirname(golden), exist_ok=True)
            shutil.copyfile(actual, golden)
            r["updated"] = True
            r["detail"] = ("new golden" if not have else "golden rewritten: " + r["detail"])
            r["status"] = "updated" if have else "new"
    elif not have:
        r.update(status="missing", detail=f"no golden {golden}")
    return r


def report(out, results, spec_path, update, seconds):
    order = {"error": 0, "mismatch": 1, "missing": 2, "updated": 3, "new": 4, "match": 5}
    results = sorted(results, key=lambda r: (order.get(r["status"], 9), r["screen"], r["combo"]))
    counts = {}
    for r in results:
        counts[r["status"]] = counts.get(r["status"], 0) + 1

    def img(kind, rel, label):
        if not rel:
            return f"<figure><div class=none>no {label}</div><figcaption>{label}</figcaption></figure>"
        p = html.escape(f"{kind}/{rel}")
        return f'<figure><a href="{p}"><img loading=lazy src="{p}"></a><figcaption>{label}</figcaption></figure>'

    rows = []
    for r in results:
        bad = r["status"] in ("error", "mismatch", "missing", "updated")
        figs = img("golden", r.get("golden"), "golden") + img("actual", r.get("actual"), "capture") \
            + (img("diff", r.get("diff"), "difference") if r.get("diff") else "")
        if not bad and r.get("actual"):
            figs = img("actual", r.get("actual"), "capture")
        rows.append(f'<section class="{r["status"]}"><h3><span class=badge>{r["status"]}</span> {html.escape(r["screen"])}'
                    f' <small>{html.escape(r["combo"])}</small></h3><p>{html.escape(r.get("detail") or "")}</p>'
                    f'<div class=figs>{figs}</div></section>')
    summary = ", ".join(f"{n} {s}" for s, n in sorted(counts.items(), key=lambda kv: order.get(kv[0], 9)))
    page = f"""<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>Snapshot report</title>
<style>
:root{{--bg:#fff;--fg:#1d1d1f;--muted:#6e6e73;--line:#e5e5ea;--bad:#d70015;--warn:#b25000;--ok:#248a3d}}
@media (prefers-color-scheme:dark){{:root{{--bg:#1c1c1e;--fg:#f2f2f7;--muted:#98989d;--line:#38383a;--bad:#ff6961;--warn:#ffb340;--ok:#30d158}}}}
body{{background:var(--bg);color:var(--fg);font:14px/1.4 -apple-system,system-ui,sans-serif;margin:0 auto;padding:16px;max-width:1200px}}
h1{{font-size:20px;margin:0 0 4px}} .sum{{color:var(--muted);margin:0 0 16px}}
section{{border-top:1px solid var(--line);padding:12px 0}} h3{{margin:0;font-size:15px}} small{{color:var(--muted);font-weight:normal}}
p{{margin:4px 0 8px;color:var(--muted)}} .figs{{display:flex;gap:12px;flex-wrap:wrap}}
figure{{margin:0}} img{{max-width:260px;max-height:520px;border:1px solid var(--line);display:block}}
figcaption{{color:var(--muted);font-size:12px}} .none{{width:120px;height:80px;border:1px dashed var(--line);display:grid;place-items:center;color:var(--muted)}}
.badge{{font-size:12px;padding:1px 6px;border-radius:4px;border:1px solid currentColor;margin-right:4px}}
.error .badge,.mismatch .badge,.missing .badge{{color:var(--bad)}} .updated .badge,.new .badge{{color:var(--warn)}} .match .badge{{color:var(--ok)}}
</style>
<h1>Snapshot report</h1>
<p class=sum>{html.escape(os.path.basename(spec_path))}: {len(results)} captures in {seconds:.1f} s{" (update)" if update else ""}: {html.escape(summary)}.
Red pixels differ beyond the screen's delta, yellow ones within it.</p>
{"".join(rows)}
"""
    with open(os.path.join(out, "report.html"), "w") as f:
        f.write(page)
    with open(os.path.join(out, "results.json"), "w") as f:
        json.dump({"spec": spec_path, "update": update, "seconds": round(seconds, 1), "counts": counts,
                   "results": results}, f, indent=1)
    return counts


def main():
    opts = parse_args(sys.argv[1:])
    spec, apk, goldens, combos, screens = load_spec(opts)
    combos = [c for c in combos if applicable(screens, c)]
    if not combos:
        fail("no combination has a screen to capture")
    # State as the launcher keeps it: APKSIM_HOME, else ~/.apksim for a release (its folder may be read-only), else work/.
    state = os.environ.get("APKSIM_HOME") or (os.path.expanduser("~/.apksim") if os.path.exists(
        os.path.join(ROOT, "work", "release")) else os.path.join(ROOT, "work"))
    out = os.path.abspath(opts["out"] or os.path.join(state, "snapshots", time.strftime("%Y%m%d-%H%M%S")))
    for d in ("actual", "golden", "diff"):
        shutil.rmtree(os.path.join(out, d), ignore_errors=True)
    os.makedirs(os.path.join(out, "logs"), exist_ok=True)
    if opts["jobs"] is not None and opts["jobs"] < 1:
        fail(f"-j takes a number of instances, 1 or more, not {opts['jobs']}")
    jobs = opts["jobs"] or max(1, min(4, (os.cpu_count() or 2) // 2))
    jobs = min(jobs, len(combos))
    results, lock = [], threading.Lock()

    def record(r):
        with lock:
            results.append(r)
            mark = {"match": "ok", "new": "new", "updated": "UPDATED"}.get(r["status"], r["status"].upper())
            print(f"{mark:>8}  {r['screen']:<16} {r['combo']:<32} {r.get('detail', '')}", flush=True)

    ctx = {"spec": spec, "apk": apk, "goldens": goldens, "screens": screens, "out": out, "update": opts["update"],
           "apksim": opts["apksim"], "record": record}
    # Each instance takes a run of combinations in matrix order (device outermost), so it changes devices seldom.
    workers = [Worker(i, combos[len(combos) * i // jobs:len(combos) * (i + 1) // jobs], ctx) for i in range(jobs)]
    total = sum(len(applicable(screens, c)) for c in combos)
    print(f"{total} captures: {len(screens)} screens x {len(combos)} combinations on {jobs} instance(s)", flush=True)
    stage = tempfile.mkdtemp(prefix="apksim-snapshot-")
    if re.search(r"\s", stage):
        fail(f"the temporary folder {stage!r} has whitespace in its path, which the control protocol cannot carry: set TMPDIR")
    for w in workers:
        w.stage = os.path.join(stage, str(w.i))
        os.makedirs(w.stage)
    t0 = time.time()
    threads = [threading.Thread(target=w.run) for w in workers]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    seconds = time.time() - t0
    shutil.rmtree(stage, ignore_errors=True)
    counts = report(out, results, os.path.abspath(opts["spec"]), opts["update"], seconds)
    print(f"{len(results)} captures in {seconds:.1f} s: " + ", ".join(f"{n} {s}" for s, n in sorted(counts.items())))
    print(f"report: {os.path.join(out, 'report.html')}")
    if len(results) != total:
        print(f"snapshot: {total - len(results)} of {total} captures have no result", file=sys.stderr)
        sys.exit(2)
    if any(w.error for w in workers):
        sys.exit(2)
    bad = sum(counts.get(s, 0) for s in ("error", "mismatch") + (() if opts["update"] else ("missing",)))
    sys.exit(1 if bad else 0)


main()
