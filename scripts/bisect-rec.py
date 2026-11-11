#!/usr/bin/env python3
"""Finds the first build whose replay of a flight recording diverges or crashes (docs/replay.md, "Bisect").

Usage: bisect-rec.py BUNDLE.apkrec BUILD... [--out DIR] [--image IMAGE] [-- APKSIM OPTIONS]
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

--image IMAGE replays in the Linux image (the bundle, builds and any file named in the apksim options are copied into
OUT/in, mounted as /work). Exit 0 when a first bad build was found, 1 when none was (the oldest already bad, the newest
good, or only skipped builds between), 2 for bad arguments.
"""
import base64, html, json, os, re, shutil, subprocess, sys, time

ROOT = os.path.abspath(os.path.dirname(os.path.abspath(sys.argv[0])) + "/..")


def die(msg):
    print(msg, file=sys.stderr)
    sys.exit(2)


def natural(s):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)]


def parse(argv):
    opts = {"out": None, "image": None, "builds_file": None}
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
            line = line.split("#", 1)[0].strip()
            if not line:
                continue
            parts = line.split()
            # A file named in the build's options (--network-trust cert.pem) is relative to the builds file too.
            opts_ = [os.path.normpath(os.path.join(base, x)) if not x.startswith("-") and os.path.exists(os.path.join(base, x)) else x
                     for x in parts[1:]]
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
    return bundle, builds, apksim_opts, opts


class Bisect:
    def __init__(self, bundle, builds, apksim_opts, opts):
        self.bundle, self.builds, self.apksim_opts = bundle, builds, apksim_opts
        stem = os.path.basename(bundle).rsplit(".apkrec", 1)[0]
        self.out = os.path.abspath(opts["out"] or os.path.join(ROOT, "work", "bisect-" + stem))
        self.image = opts["image"]
        self.staged = {}
        # OUT is ours only if empty or an earlier bisect's: then only what a bisect writes there goes. Anything else in
        # it (the builds themselves, say) is never deleted.
        if os.path.isdir(self.out) and os.listdir(self.out) and not os.path.exists(os.path.join(self.out, "bisect.json")):
            die(f"bisect-rec.py: {self.out} is not empty and not an earlier bisect's output; give another --out")
        inputs = [bundle] + [b["apk"] for b in builds]
        for sub in ("runs", "in"):
            d = os.path.join(self.out, sub)
            if any(os.path.abspath(f).startswith(d + os.sep) for f in inputs):
                die(f"bisect-rec.py: an input is inside {d}, which a bisect replaces; move it or give another --out")
            shutil.rmtree(d, ignore_errors=True)
        for f in ("bisect.json", "index.html"):
            if os.path.exists(os.path.join(self.out, f)):
                os.remove(os.path.join(self.out, f))
        os.makedirs(os.path.join(self.out, "runs"))
        if self.image:
            arch = subprocess.run(["docker", "image", "inspect", "-f", "{{.Architecture}}", self.image],
                                  capture_output=True, text=True).stdout.strip()
            self.platform = "linux/" + arch
            os.makedirs(os.path.join(self.out, "in"))

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
            mapped = [self.inside(x) if os.path.isfile(x) else x for x in extra]
            cmd = ["docker", "run", "--rm", "--platform", self.platform, "-v", f"{self.out}:/work", self.image,
                   "replay", "--headless", "--apk", self.inside(b["apk"]), "--out", "/work/" + b["run"]] + mapped + [self.inside(self.bundle)]
        else:
            cmd = [os.path.join(ROOT, "apksim"), "replay", "--headless", "--apk", b["apk"], "--out", rundir] + extra + [self.bundle]
        with open(os.path.join(rundir, "replay.log"), "w") as log:
            code = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, env=dict(os.environ, APKSIM_STANDBY=os.environ.get("APKSIM_STANDBY", "0"))).returncode
        b["seconds"] = round(time.time() - t0, 1)
        b["exit"] = code
        text = open(os.path.join(rundir, "replay.log"), errors="replace").read()
        rep = os.path.join(rundir, "report.json")
        b["report"] = json.load(open(rep)) if os.path.exists(rep) else None
        if code == 0:
            b["result"] = "good"
        elif code in (3, 10):
            b["result"] = "bad"
            b["why"] = "crashed" if code == 10 else "diverged"
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
    bundle, builds, apksim_opts, opts = parse(sys.argv[1:])
    sys.exit(Bisect(bundle, builds, apksim_opts, opts).run())


if __name__ == "__main__":
    main()
