#!/usr/bin/env python3
"""Finds the first build whose replay of a flight recording diverges or crashes (docs/replay.md, "Bisect").

Usage: bisect-rec.py BUNDLE.apkrec BUILD... [--out DIR] [--image IMAGE] [--timeout SEC] [-- APKSIM OPTIONS]
       bisect-rec.py BUNDLE.apkrec DIR ...
       bisect-rec.py BUNDLE.apkrec --builds FILE ...

The builds are in order, oldest first: APK files, or a folder whose .apk files are taken in natural name order
(build-9 before build-10), or --builds FILE with one build a line, `APK [apksim options...]` (a build that differs
by a launch option; `#` starts a comment, paths are relative to the file). Each replay is `apksim replay --headless
--apk BUILD` with the options after `--` (for example `--network-trust CERT`), and counts as:

  good      exit 0: every compared point matched;
  bad       exit 3 (diverged) or 10 (crashed);
  skipped   exit 2 (cannot be replayed against it: another signer, another package, an activity it lacks) or any
            other exit: the search goes around it, as `git bisect skip` does.

The oldest build must replay good and the newest bad; between them a binary search finds the last good and the first
bad build, replaying about log2(N) of them. OUT (default work/bisect-<bundle>) gets runs/NN-<build>/ (each replay's
report and frames), bisect.json and index.html: every build with its result, and the last good and first bad builds'
frames at the first bad one's first divergence side by side with the recorded frame and the difference map, embedded
so the page can be sent on its own.

--timeout SEC (default 600) ends a replay that runs longer (a build that hangs): it is skipped as "timed out", and the
search goes on. --image IMAGE replays in the Linux image (the bundle, builds and any file named in the apksim options are copied into
OUT/in, mounted as /work). Exit 0 when a first bad build was found, 1 when none was (the oldest already bad, the newest
good, or only skipped builds between), 2 for bad arguments.
"""
import base64, hashlib, html, json, os, re, shlex, shutil, signal, subprocess, sys, time, zipfile

ROOT = os.path.abspath(os.path.dirname(os.path.abspath(sys.argv[0])) + "/..")


def die(msg):
    print(msg, file=sys.stderr)
    sys.exit(2)


def file_part(x):
    """The file an option names, whole (cert.pem) or after its last '=' (fake=/path/photo.png), or None."""
    if x.startswith("-"):
        return None
    if os.path.isfile(x):
        return x
    k, _, v = x.rpartition("=")
    if k and v and os.path.isfile(v):
        return v
    return None


def natural(s):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)]


def parse(argv):
    opts = {"out": None, "image": None, "builds_file": None, "timeout": 600.0}
    rest, apksim_opts = [], []
    if "--" in argv:
        i = argv.index("--")
        argv, apksim_opts = argv[:i], argv[i + 1:]
    it = iter(argv)
    for x in it:
        if x in ("-h", "--help"):
            print(__doc__)
            sys.exit(0)
        elif x == "--out":
            opts["out"] = next(it)
        elif x == "--timeout":
            try:
                opts["timeout"] = float(next(it))
            except (StopIteration, ValueError):
                die("bisect-rec.py: --timeout takes seconds")
        elif x == "--image":
            opts["image"] = next(it)
        elif x == "--builds":
            opts["builds_file"] = next(it)
        else:
            rest.append(x)
    if not rest:
        die("bisect-rec.py: no bundle; see --help")
    bundle = os.path.abspath(rest[0])
    builds = []
    for b in rest[1:]:
        if os.path.isdir(b):
            for n in sorted((n for n in os.listdir(b) if n.endswith(".apk")), key=natural):
                builds.append({"apk": os.path.abspath(os.path.join(b, n)), "opts": []})
        else:
            builds.append({"apk": os.path.abspath(b), "opts": []})
    if opts["builds_file"]:
        base = os.path.dirname(os.path.abspath(opts["builds_file"]))
        for line in open(opts["builds_file"]):
            # Shell words: a quoted value keeps its spaces and a '#' inside it ('MODEL=Pixel 9'); '#' starts a comment.
            parts = shlex.split(line, comments=True)
            if not parts:
                continue

            def rel(x):
                # A file named in the build's options (--network-trust cert.pem, fake=photo.png) is relative to the
                # builds file too.
                if x.startswith("-"):
                    return x
                # Files only, and only a non-empty name after '=': "YQ==" or "secure:key=" end in '=' and an empty name
                # would join to the builds folder itself.
                if os.path.isfile(os.path.join(base, x)):
                    return os.path.normpath(os.path.join(base, x))
                k, _, v = x.rpartition("=")
                if k and v and os.path.isfile(os.path.join(base, v)):
                    return k + "=" + os.path.normpath(os.path.join(base, v))
                return x
            opts_ = [rel(x) for x in parts[1:]]
            builds.append({"apk": os.path.normpath(os.path.join(base, parts[0])), "opts": opts_, "shown": parts[1:]})
    if len(builds) < 2:
        die("bisect-rec.py: give at least two builds, oldest first")
    for b in builds:
        if not os.path.isfile(b["apk"]):
            die(f"bisect-rec.py: no build at {b['apk']}")
        shown = b.get("shown", b["opts"])
        b["label"] = os.path.basename(b["apk"]) + ("" if not shown else " " + " ".join(shown))
    if not os.path.isfile(bundle):
        die(f"bisect-rec.py: no bundle at {bundle}")
    # A replay that keeps its app up for a person (--control) never ends, and a bisect waits for each to end.
    for x in apksim_opts + [o for b in builds for o in b["opts"]]:
        if x == "--control" or x.startswith("--control="):
            die("bisect-rec.py: --control would keep each replay running; leave it out")
    opts["inputs"] = [bundle] + [b["apk"] for b in builds] + ([opts["builds_file"]] if opts["builds_file"] else []) \
        + [f for f in (file_part(x) for x in apksim_opts + [o for b in builds for o in b["opts"]]) if f]
    return bundle, builds, apksim_opts, opts


class Bisect:
    def __init__(self, bundle, builds, apksim_opts, opts):
        self.bundle, self.builds, self.apksim_opts = bundle, builds, apksim_opts
        stem = os.path.basename(bundle).rsplit(".apkrec", 1)[0]
        self.out = os.path.abspath(opts["out"] or os.path.join(ROOT, "work", "bisect-" + stem))
        self.image = opts["image"]
        self.timeout = opts["timeout"]
        self.staged = {}
        # OUT is ours only if empty or an earlier bisect's: then only what a bisect writes there goes. Anything else in
        # it (the builds themselves, say) is never deleted.
        if os.path.isdir(self.out) and os.listdir(self.out) and not os.path.exists(os.path.join(self.out, "bisect.json")):
            die(f"bisect-rec.py: {self.out} is not empty and not an earlier bisect's output; give another --out")
        # Every input (the bundle, the builds, a builds file, files named in options), through links, checked before
        # anything is deleted: none may be in what a bisect replaces.
        real_out = os.path.realpath(self.out)
        replaced = [os.path.join(real_out, "runs"), os.path.join(real_out, "in"),
                    os.path.join(real_out, "bisect.json"), os.path.join(real_out, "index.html")]
        for f in opts.get("inputs", []):
            r = os.path.realpath(f)
            if any(r == d or r.startswith(d + os.sep) for d in replaced):
                die(f"bisect-rec.py: {f} is inside {self.out}'s runs/ or in/ (or is its report), which a bisect replaces;"
                    " move it or give another --out")
        for sub in ("runs", "in"):
            shutil.rmtree(os.path.join(self.out, sub), ignore_errors=True)
        for f in ("bisect.json", "index.html"):
            if os.path.exists(os.path.join(self.out, f)):
                os.remove(os.path.join(self.out, f))
        os.makedirs(os.path.join(self.out, "runs"))
        if self.image:
            arch = subprocess.run(["docker", "image", "inspect", "-f", "{{.Architecture}}", self.image],
                                  capture_output=True, text=True).stdout.strip()
            self.platform = "linux/" + arch
            os.makedirs(os.path.join(self.out, "in"))
            self.stage_recorded_build()

    def stage_recorded_build(self):
        """--image: an older bundle names no signer, and `apksim replay --apk` then finds the recorded build by its
        file name beside the bundle to check another build's signer. When it is among the builds, a copy of it goes
        there (OUT/in/NAME, beside the staged bundle)."""
        try:
            with zipfile.ZipFile(self.bundle) as z:
                m = json.loads(z.read("manifest.json"))
        except (OSError, KeyError, ValueError, zipfile.BadZipFile):
            return
        name, sha = m.get("apk"), m.get("apkSha256")
        if not name or not sha or "/" in name or m.get("apkSigners"):
            return
        for b in self.builds:
            h = hashlib.sha256()
            with open(b["apk"], "rb") as f:
                for chunk in iter(lambda: f.read(1 << 20), b""):
                    h.update(chunk)
            if h.hexdigest() == sha:
                shutil.copy(b["apk"], os.path.join(self.out, "in", name))
                return

    def stop(self, p, container, run):
        """Ends a replay: its process group, its container (a killed docker client leaves it running), and, in the
        image, the permissions its root container would have fixed on the way out, so the host can empty --out."""
        try:
            os.killpg(p.pid, 9)
        except OSError:
            p.kill()
        code = p.wait()
        if container:
            subprocess.run(["docker", "rm", "-f", container], capture_output=True)
            subprocess.run(["docker", "run", "--rm", "--platform", self.platform, "-v", f"{self.out}:/work", "--entrypoint",
                            "chmod", self.image, "-R", "a+rwX", "/work/" + run, "/work/in"], capture_output=True)
        return code

    def mapped(self, x):
        """An option as the image sees it: a file it names staged into OUT/in, whole or after its '='."""
        f = file_part(x)
        if f is None:
            return x
        return self.inside(x) if f == x else x[: len(x) - len(f)] + self.inside(f)

    def inside(self, path):
        """A file as the image sees it: copied into OUT/in once per source path, under a name of its own (two builds
        both called app.apk in different folders are two files), as /work/in/NAME."""
        src = os.path.abspath(path)
        if src not in self.staged:
            name = f"{len(self.staged) + 1:02d}-" + re.sub(r"[^A-Za-z0-9._-]", "_", os.path.basename(src))
            shutil.copy(src, os.path.join(self.out, "in", name))
            self.staged[src] = name
        return "/work/in/" + self.staged[src]

    def replay(self, i):
        b = self.builds[i]
        if "result" in b:
            return b["result"]
        run = f"{i + 1:02d}-" + re.sub(r"[^A-Za-z0-9._-]", "_", os.path.basename(b["apk"]).rsplit(".apk", 1)[0])
        b["run"] = "runs/" + run
        rundir = os.path.join(self.out, "runs", run)
        os.makedirs(rundir, exist_ok=True)
        extra = b["opts"] + self.apksim_opts
        t0 = time.time()
        if self.image:
            mapped = [self.mapped(x) for x in extra]
            args = ["replay", "--headless", "--apk", self.inside(b["apk"]), "--out", "/work/" + b["run"]] + mapped + [self.inside(self.bundle)]
            # The container runs as root: what it writes into the mount is left writable by everyone, so the host user
            # can empty this --out next time (as instrument.py's bundles are).
            script = ("apksim " + " ".join(shlex.quote(x) for x in args) + "; s=$?; chmod -R a+rwX /work/" + shlex.quote(b["run"])
                      + " /work/in 2>/dev/null; exit $s")
            container = f"bisect-rec-{os.getpid()}-{i + 1:02d}"
            cmd = ["docker", "run", "--rm", "--name", container, "--platform", self.platform, "-v", f"{self.out}:/work",
                   "--entrypoint", "bash", self.image, "-c", script]
        else:
            cmd = [os.path.join(ROOT, "apksim"), "replay", "--headless", "--apk", b["apk"], "--out", rundir] + extra + [self.bundle]
        timed_out = False
        with open(os.path.join(rundir, "replay.log"), "w") as log:
            p = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, start_new_session=True,
                                 env=dict(os.environ, APKSIM_STANDBY=os.environ.get("APKSIM_STANDBY", "0")))
            try:
                code = p.wait(timeout=self.timeout)
            except subprocess.TimeoutExpired:
                # A build that hangs must not stall the bisect: it is stopped, and counts as skipped.
                timed_out = True
                code = self.stop(p, container if self.image else None, b["run"])
            except BaseException:
                # Ctrl-C or any other interruption: the replay runs in a session of its own and would go on, so it is
                # stopped first.
                self.stop(p, container if self.image else None, b["run"])
                raise
        b["seconds"] = round(time.time() - t0, 1)
        b["exit"] = code
        text = open(os.path.join(rundir, "replay.log"), errors="replace").read()
        rep = os.path.join(rundir, "report.json")
        b["report"] = json.load(open(rep)) if os.path.exists(rep) else None
        rep_state = (b["report"] or {}).get("result")
        if timed_out:
            b["result"] = "skipped"
            b["why"] = f"timed out after {self.timeout:g} s"
        elif code == 0 and rep_state == "matched":
            b["result"] = "good"
        elif code == 0:
            # Ended before its result (the app exited by itself, the last activity finished): not evidence either way.
            b["result"] = "skipped"
            b["why"] = f"the replay ended before its result (report: {rep_state or 'none'})"
        elif code == 3 and rep_state == "diverged":
            b["result"] = "bad"
            b["why"] = "diverged"
        elif code == 10:
            # A crash, in the replay or before it (the app's start): bad either way.
            b["result"] = "bad"
            b["why"] = "crashed"
        elif code == 3:
            # Exit 3 without a diverged report: the app's own System.exit(3), not apksim's verdict.
            b["result"] = "skipped"
            b["why"] = f"exit 3 without a diverged report (report: {rep_state or 'none'})"
        else:
            b["result"] = "skipped"
            m = re.findall(r"^apksim replay: (.*)$|^\[apksim\] replay: cannot start: (.*)$", text, re.M)
            b["why"] = next((x or y for x, y in reversed(m)), f"exit {code}")
        print(f"  {b['result']:8s} {b['label']}  ({b['seconds']} s{', ' + b['why'] if b.get('why') else ''})", flush=True)
        return b["result"]

    def first_tested(self, lo, hi, step):
        """From lo towards hi (inclusive), the first build that is not skipped, or None."""
        i = lo
        while (i <= hi) if step > 0 else (i >= hi):
            if self.replay(i) != "skipped":
                return i
            i += step
        return None

    def run(self):
        n = len(self.builds)
        print(f"bisect-rec: {n} builds, {os.path.basename(self.bundle)}")
        lo = self.first_tested(0, n - 1, 1)
        hi = self.first_tested(n - 1, (lo or 0) + 1, -1) if lo is not None else None
        verdict = None
        if lo is None:
            verdict = "no build could replay the recording"
        elif self.builds[lo]["result"] == "bad":
            verdict = f"the oldest build that replays, {self.builds[lo]['label']}, already diverges"
            hi = None  # no good build before it: nothing to call the first bad one
        elif hi is None or self.builds[hi]["result"] == "good":
            verdict = "no build diverges"
        else:
            while hi - lo > 1:
                mid = (lo + hi) // 2
                # A skipped build: the nearest tested one between lo and hi, alternating outwards from mid.
                found = None
                for d in range(0, hi - lo):
                    for j in (mid + d, mid - d - 1):
                        if lo < j < hi and self.replay(j) != "skipped":
                            found = j
                            break
                    if found is not None:
                        break
                if found is None:
                    break
                if self.builds[found]["result"] == "good":
                    lo = found
                else:
                    hi = found
            skipped_between = [self.builds[j]["label"] for j in range(lo + 1, hi) if self.builds[j].get("result") == "skipped"]
            if skipped_between:
                verdict = (f"the first bad build is {self.builds[hi]['label']} or one of the skipped builds before it: "
                           + ", ".join(skipped_between))
        self.lo, self.hi = lo, hi
        found = verdict is None or verdict.startswith("the first bad build is")
        if verdict is None:
            verdict = f"first bad build: {self.builds[hi]['label']} (last good: {self.builds[lo]['label']})"
        self.verdict = verdict
        self.write()
        print("bisect-rec: " + verdict)
        print("bisect-rec: report " + os.path.join(self.out, "index.html"))
        return 0 if found else 1

    def write(self):
        data = {"bundle": self.bundle, "verdict": self.verdict,
                "lastGood": self.lo if self.lo is not None and self.builds[self.lo].get("result") == "good" else None,
                "firstBad": self.hi if self.hi is not None and self.builds[self.hi].get("result") == "bad" else None,
                "builds": [{k: v for k, v in b.items() if k != "report"} | {"points": [
                    {"n": p["n"], "label": p["label"], "pixels": p["pixels"], "diverged": p["diverged"]}
                    for p in (b.get("report") or {}).get("points", [])]} for b in self.builds]}
        if data["firstBad"] is not None:
            data["firstBad"] += 1
        if data["lastGood"] is not None:
            data["lastGood"] += 1
        json.dump(data, open(os.path.join(self.out, "bisect.json"), "w"), indent=1)
        open(os.path.join(self.out, "index.html"), "w").write(self.page())

    def page(self):
        e = html.escape
        rows = []
        for i, b in enumerate(self.builds):
            r = b.get("result", "not replayed")
            mark = " (last good)" if i == self.lo and r == "good" else " (first bad)" if i == self.hi and r == "bad" else ""
            pts = (b.get("report") or {}).get("points", [])
            first = next((p for p in pts if p["diverged"]), None)
            detail = e(b.get("why", ""))
            if first:
                detail += f": point {first['n']} ({e(first['label'])}), {first['pixels']} pixels"
            elif r == "bad" and b.get("report"):
                detail += ": " + e("; ".join(b["report"].get("notes", [])[-1:]))
            link = f' <a href="{e(b["run"])}/replay.log">log</a>' if b.get("run") else ""
            rows.append(f'<tr class="{r.split()[0]}"><td>{i + 1}</td><td>{e(b["label"])}</td><td>{r}{mark}</td>'
                        f'<td>{detail}</td><td>{b.get("seconds", "")}</td><td>{link}</td></tr>')
        compare = ""
        if self.hi is not None and self.builds[self.hi].get("result") == "bad" and self.builds[self.hi].get("report"):
            bad = self.builds[self.hi]
            pts = bad["report"].get("points", [])
            p = next((x for x in pts if x["diverged"]), pts[-1] if pts else None)
            if p:
                nn = f"{p['n']:02d}"
                imgs = [("recorded", f"{bad['run']}/points/{nn}-recorded.jpg")]
                good = self.builds[self.lo] if self.lo is not None and self.builds[self.lo].get("result") == "good" else None
                if good and os.path.exists(os.path.join(self.out, good["run"], "points", nn + "-replayed.jpg")):
                    imgs.append(("last good: " + good["label"], f"{good['run']}/points/{nn}-replayed.jpg"))
                imgs.append(("first bad: " + bad["label"], f"{bad['run']}/points/{nn}-replayed.jpg"))
                if os.path.exists(os.path.join(self.out, bad["run"], "points", nn + "-diff.png")):
                    imgs.append(("difference (first bad against the recording)", f"{bad['run']}/points/{nn}-diff.png"))
                # Embedded, so index.html can be sent on its own; data-src keeps where each came from.
                def data(src):
                    kind = "image/png" if src.endswith(".png") else "image/jpeg"
                    return f"data:{kind};base64," + base64.b64encode(open(os.path.join(self.out, src), "rb").read()).decode()
                figs = "".join(f'<figure><img src="{data(src)}" data-src="{e(src)}" alt="{e(cap)}"><figcaption>{e(cap)}</figcaption></figure>'
                               for cap, src in imgs if os.path.exists(os.path.join(self.out, src)))
                how = "crashed" if bad.get("why") == "crashed" else f"{p['pixels']} pixels differ"
                compare = (f"<h2>Point {p['n']}: {e(p['label'])}, +{p.get('offsetMs', '?')} ms ({how})</h2>"
                           f'<div class="frames">{figs}</div>')
                if bad.get("why") == "crashed":
                    compare += "<pre>" + e("\n".join(bad["report"].get("notes", []))) + "</pre>"
        return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Recording bisect</title>
<style>
:root {{ --bg: #fff; --fg: #1d1d1f; --muted: #6e6e73; --line: #d2d2d7; --good: #1a7f37; --bad: #c62828; --skip: #9a6700; }}
@media (prefers-color-scheme: dark) {{ :root {{ --bg: #161618; --fg: #f2f2f2; --muted: #a1a1a6; --line: #3a3a3c;
  --good: #4ac26b; --bad: #ff6b6b; --skip: #e3b341; }} }}
body {{ background: var(--bg); color: var(--fg); font: 15px/1.45 -apple-system, system-ui, sans-serif; margin: 0 auto;
  max-width: 1100px; padding: 16px; }}
h1 {{ font-size: 22px; margin: 8px 0 4px; }} h2 {{ font-size: 17px; margin-top: 28px; }}
.verdict {{ font-size: 17px; margin: 8px 0 20px; }} .muted {{ color: var(--muted); }}
table {{ border-collapse: collapse; width: 100%; }} td, th {{ border-bottom: 1px solid var(--line); padding: 6px 8px;
  text-align: left; vertical-align: top; }} th {{ color: var(--muted); font-weight: 500; }}
tr.good td:nth-child(3) {{ color: var(--good); }} tr.bad td:nth-child(3) {{ color: var(--bad); }}
tr.skipped td:nth-child(3) {{ color: var(--skip); }}
.frames {{ display: flex; flex-wrap: wrap; gap: 12px; }} figure {{ margin: 0; flex: 1 1 200px; max-width: 260px; }}
figure img {{ width: 100%; border: 1px solid var(--line); }} figcaption {{ color: var(--muted); font-size: 13px; }}
.wrap {{ overflow-x: auto; }} pre {{ white-space: pre-wrap; }}
</style></head><body>
<h1>Recording bisect</h1>
<div class="muted">{e(os.path.basename(self.bundle))}, {len(self.builds)} builds, oldest first</div>
<p class="verdict">{e(self.verdict)}</p>
{compare}
<h2>Builds</h2>
<div class="wrap"><table><tr><th>#</th><th>Build</th><th>Result</th><th>Where</th><th>s</th><th></th></tr>
{''.join(rows)}</table></div>
</body></html>
"""


def main():
    # SIGTERM (a CI job cancelled) goes the way Ctrl-C does: the replay running is stopped before the bisect ends.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(143))
    bundle, builds, apksim_opts, opts = parse(sys.argv[1:])
    sys.exit(Bisect(bundle, builds, apksim_opts, opts).run())


if __name__ == "__main__":
    main()
