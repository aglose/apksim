#!/usr/bin/env python3
"""From a recording of a bug to what a pull request should show (docs/bug-to-pr.md).

    pr-evidence.py baseline BASE.apk --out DIR [--depth N] [crawl.py options] [-- apksim options]
    pr-evidence.py verify BUNDLE.apkrec --base BASE.apk --new NEW.apk [--out DIR] [--expect fix|same]
                          [--base-option "OPTS"] [--new-option "OPTS"] [-- apksim options]
    pr-evidence.py diff NEW.apk --baseline DIR [--repo REPO] (--diff REV | --changed PATH...) [--out DIR]
                        [--cautious] [--new-option "OPTS"] [crawl.py options] [-- apksim options]
    pr-evidence.py comment --out FILE.md [--verify DIR] [--walk DIR] [--images DIR] [--image-base URL]
    pr-evidence.py run BUNDLE.apkrec --base BASE.apk --new NEW.apk --work DIR [--repo REPO] (--diff REV | --changed PATH...)
                       [--baseline DIR] [--depth N] [--expect fix|same] [--base-option "OPTS"] [--new-option "OPTS"]
                       [crawl.py options for the baseline] [-- apksim options]

baseline  walks the base build with `crawl.py --record-deps` to --depth (crawl.py's --max-depth, default 3: screens
          fewer than 3 actions from a start are explored), goes on from the screens a budget left unexplored, and
          says how many screens have no recorded dependencies and why.
verify    replays the recording on both builds (`apksim replay --apk`) and prints a verdict to paste: where the base
          matched, where the new build first differs (label, pixels, region), and the requests each replay asked for
          against those the recording holds. Exit 0 when the base matches and the new build differs (a fix), or with
          `--expect same` when both match (a refactor); 1 otherwise; 2 when a replay could not run.
diff      names the screens the change can reach (`crawl-select.py`: which file selected which screen, what was
          ignored, what no screen placed), then walks only those on the new build against the baseline
          (`crawl.py --seed --only-file --diff-seed`). Screens the baseline found and never explored are left out and
          counted; `--cautious` walks them too.
comment   writes Markdown for the pull request from verify's and diff's results, and copies the images it shows into
          one folder under stable names for the caller to host (`--image-base URL` is put in front of their names).
run       baseline (unless --baseline DIR has one), verify, diff, comment, in WORK.

Options after `--` go to every apksim launch (`--network-host`, `--network-trust`, `--feature`...), except that
`--data-slots` and `--fresh` go to the walks only: a replay runs in its own slot, restored from the recording;
`--base-option`/`--new-option` to one build's only. diff reuses the baseline's crawl.py and apksim options unless it
is given its own. Name the APK or the recording first: an option this script does not know goes to crawl.py with what
follows it. Standard library only; needs crawl.py, crawl-select.py and the apksim launcher beside it (an
installed release has them), or `APKSIM=/path/to/apksim`.
"""
import argparse, hashlib, json, os, re, shlex, shutil, subprocess, sys, threading, time, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
STATE_FILE = "pr-evidence.json"  # in a baseline folder: how it was walked; in verify's: the verdict


def say(msg=""):
    print(msg, flush=True)


def die(msg, code=2):
    print("pr-evidence: " + msg, file=sys.stderr, flush=True)
    sys.exit(code)


def launcher():
    """The apksim launcher: $APKSIM, the one beside scripts/, else `apksim` on the PATH."""
    if os.environ.get("APKSIM"):
        return shlex.split(os.environ["APKSIM"])
    beside = os.path.join(ROOT, "apksim")
    return [beside] if os.path.isfile(beside) else ["apksim"]


def sibling(name):
    p = os.path.join(HERE, name)
    if not os.path.isfile(p):
        die(f"{name} is not beside {os.path.basename(__file__)} ({HERE})")
    return p


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def load(path, default=None):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def dump(path, obj):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(obj, f, indent=1)
        f.write("\n")
    os.replace(tmp, path)


def plural(n, one, many=None):
    return f"{n} {one if n == 1 else (many or one + 's')}"


def strip_queries(s):
    """Cuts the query and fragment off every URL or path in s: they can hold tokens."""
    return re.sub(r"((?:[A-Za-z][A-Za-z0-9+.-]*:|/)[^\s?#`]*)[?#][^\s`]*", r"\1", str(s))


def code(s, limit=300):
    """Text the app, a recording or a launch chose, for Markdown: one code span (so an @name mentions nobody) without
    query strings."""
    t = " ".join(strip_queries(s).replace("`", "'").split())[:limit]
    return f"`{t}`" if t else "(none)"


def cell_code(s):
    return code(s).replace("|", "\\|")


def forget(folder, *names):
    """Removes an earlier run's results, so a step that fails leaves none to be read as its own."""
    for n in names:
        try:
            os.remove(os.path.join(folder, n))
        except OSError:
            pass


def run_stream(cmd, log):
    """Runs cmd with its output shown and kept in `log`; returns the exit status."""
    os.makedirs(os.path.dirname(log), exist_ok=True)
    with open(log, "a") as lf:
        lf.write("$ " + " ".join(shlex.quote(c) for c in cmd) + "\n")
        lf.flush()
        p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
        for line in p.stdout:
            lf.write(line)
            if not line.startswith("{"):  # crawl.py --events lines are for tools
                sys.stdout.write("  | " + line)
                sys.stdout.flush()
        return p.wait()


# ------------------------------------------------------------------------------------------------ baseline

def has_option(opts, name):
    return any(o == name or o.startswith(name + "=") for o in opts)


def option_value(opts, name, default=None):
    for i, o in enumerate(opts):
        if o == name and i + 1 < len(opts):
            return opts[i + 1]
        if o.startswith(name + "="):
            return o.split("=", 1)[1]
    return default


def without_option(opts, name, takes_value=True):
    out, skip = [], False
    for o in opts:
        if skip:
            skip = False
            continue
        if o == name:
            skip = takes_value
            continue
        if o.startswith(name + "="):
            continue
        out.append(o)
    return out


def baseline_summary(doc, depth):
    """Which screens have recorded dependencies, and for the rest, why not."""
    screens = doc.get("screens", [])
    unexplored = set(doc.get("unexplored", []))
    partly = set(doc.get("partly_explored", []))
    with_deps = [s for s in screens if s.get("deps") is not None]
    beyond, budget, other = [], [], []
    for s in screens:
        if s.get("deps") is not None:
            continue
        if s["id"] in unexplored and s["id"] not in partly and depth is not None and s.get("depth", 0) >= depth:
            beyond.append(s["id"])
        elif s["id"] in unexplored:
            budget.append(s["id"])
        else:
            other.append(s["id"])
    return {"screens": len(screens), "with_deps": len(with_deps), "beyond_depth": beyond, "cut_by_budget": budget,
            "no_deps_other": other, "partly_explored": sorted(partly), "cut_by": doc.get("cut_by", []),
            "deps_unreliable": doc.get("deps_unreliable"), "crashes": sum(1 for f in doc.get("findings", []) if f.get("kind") == "crash"),
            "incomplete": doc.get("incomplete", [])}


def print_baseline_summary(s, depth, out):
    say(f"baseline: {plural(s['screens'], 'screen')} in {out}, {s['with_deps']} with recorded dependencies")
    if s["beyond_depth"]:
        say(f"  {plural(len(s['beyond_depth']), 'screen')} found at the depth limit ({depth} from a start) and not explored: no "
            f"dependencies. A change reaches them only through `diff --cautious`; walk with --depth {depth + 1} to record them.")
    if s["cut_by_budget"]:
        cut = ", ".join(c for c in s["cut_by"] if c != "max-depth") or "a budget"
        say(f"  {plural(len(s['cut_by_budget']), 'screen')} left unexplored by {cut}: no dependencies "
            f"({', '.join(s['cut_by_budget'][:6])}{'...' if len(s['cut_by_budget']) > 6 else ''}). Raise the budget and run baseline again.")
    if s["no_deps_other"]:
        say(f"  {plural(len(s['no_deps_other']), 'screen')} explored without dependencies (a path that did not replay, or a "
            f"crash on the way): {', '.join(s['no_deps_other'][:6])}")
    if s["deps_unreliable"]:
        say(f"  source files cannot be trusted for this build ({s['deps_unreliable']}): `diff` will walk every screen. "
            "Walk a debug build for the map.")
    if s["crashes"]:
        say(f"  {plural(s['crashes'], 'crash', 'crashes')} found on the base build: see report.html")
    if s["screens"] <= 2:
        say("  Very few screens. If that is a sign-in screen: an app behind a sign-in is walked signed in only from a prepared "
            "data slot (crawl.py --keep-data; the crawler never signs in: docs/bug-to-pr.md \"Before you start\").")


def cmd_baseline(a, crawl_opts, sim):
    if not os.path.isfile(a.apk):
        die(f"no such APK: {a.apk}")
    out = os.path.abspath(a.out)
    log = os.path.join(out, "pr-evidence.log")
    forget(out, STATE_FILE)
    crawl = sibling("crawl.py")
    depth = a.depth
    if has_option(crawl_opts, "--max-depth"):
        depth = int(option_value(crawl_opts, "--max-depth"))
        crawl_opts = without_option(crawl_opts, "--max-depth")
    for refused in ("--seed", "--only", "--only-file", "--diff-seed", "--base", "--base-option", "--out"):
        if has_option(crawl_opts, refused):
            die(f"baseline does not take {refused}")
    walk_opts = without_option(crawl_opts, "--record-deps", takes_value=False)
    base_cmd = [sys.executable, crawl, a.apk, "--out", out, "--record-deps", "--max-depth", str(depth)]
    base_only = shlex.split(getattr(a, "base_option", "") or "")
    tail = (["--"] + sim + base_only) if sim or base_only else []
    t0 = time.time()
    forget(out, "crawl.json")  # never read an earlier walk's result as this one's
    say(f"baseline: walking {os.path.basename(a.apk)} to depth {depth}")
    code = run_stream(base_cmd + walk_opts + tail, log)
    doc = load(os.path.join(out, "crawl.json"))
    if code not in (0, 1) or doc is None:
        die(f"crawl.py exited {code}" + ("" if doc is not None else " and wrote no crawl.json") + f": see {log}")
    cont_opts = without_option(walk_opts, "--start")
    rounds = 0
    while rounds < a.rounds:
        depth_of = {s["id"]: s.get("depth", 0) for s in doc.get("screens", [])}
        todo = [i for i in doc.get("unexplored", []) if depth_of.get(i, 0) < depth]
        if not todo:
            break
        if "max-screens" in doc.get("cut_by", []):
            say(f"baseline: --max-screens cut the walk with {plural(len(todo), 'screen')} unexplored; not going on (raise it)")
            break
        rounds += 1
        ids = os.path.join(out, "unexplored.ids")
        with open(ids, "w") as f:
            f.write("\n".join(todo) + "\n")
        say(f"baseline: going on from {plural(len(todo), 'unexplored screen')} (round {rounds} of {a.rounds})")
        code = run_stream(base_cmd + cont_opts + ["--seed", out, "--only-file", ids] + tail, log)
        nxt = load(os.path.join(out, "crawl.json"))
        if code not in (0, 1) or nxt is None:
            die(f"crawl.py exited {code} while going on from the unexplored screens: see {log}")
        doc = nxt
        left = [i for i in doc.get("unexplored", []) if i in set(todo)]
        if len(left) >= len(todo):
            say("baseline: that round explored none of them; stopping")
            break
    summary = baseline_summary(doc, depth)
    dump(os.path.join(out, STATE_FILE), {"kind": "baseline", "apk": os.path.abspath(a.apk), "apk_sha256": sha256(a.apk),
                                         "depth": depth, "crawl_options": without_option(walk_opts, "--start"),
                                         "apksim_options": sim, "base_options": base_only, "rounds": rounds, "seconds": round(time.time() - t0, 1),
                                         "summary": summary})
    print_baseline_summary(summary, depth, out)
    say(f"baseline: {time.time() - t0:.0f} s; map {os.path.join(out, 'report.html')}")
    return 0


# ------------------------------------------------------------------------------------------------ verify

def bundle_info(bundle):
    """What the recording holds: its requests (None without a HAR), events and checkpoint."""
    info = {"requests": None, "events": None, "checkpoint": None, "package": None, "crash": False}
    try:
        with zipfile.ZipFile(bundle) as z:
            names = set(z.namelist())
            if "manifest.json" in names:
                man = json.loads(z.read("manifest.json"))
                info["package"] = man.get("package")
                info["checkpoint"] = (man.get("checkpoint") or {}).get("id")
                info["crash"] = bool(man.get("crash"))  # FlightRecorder: the recording was saved by a crash
            if "network.har" in names:
                info["requests"] = len(json.loads(z.read("network.har")).get("log", {}).get("entries", []))
            if "input.jsonl" in names:
                info["events"] = len(z.read("input.jsonl").decode(errors="replace").splitlines())
    except (OSError, ValueError, zipfile.BadZipFile) as e:
        die(f"{bundle} is not a readable recording: {e}")
    return info


def regions_of(map_path):
    """Boxes of what differs in a difference map, through crawl.py's reader; None when it cannot be read."""
    try:
        sys.path.insert(0, HERE)
        import crawl  # noqa: E402  (scripts/crawl.py: diff_regions needs no imaging library)
        return crawl.diff_regions(map_path)
    except Exception:  # noqa: BLE001  a region is a nicety, never a failed verdict
        return None


def replay_options(sim):
    """The shared launch options a replay may have: it runs in a data slot of its own, restored from the checkpoint,
    so the walks' --data-slots (a prepared sign-in slot) and --fresh must not reach it."""
    return without_option(without_option(sim, "--data-slots"), "--fresh", takes_value=False)


def unanswered_of(log_text):
    """The requests a replay's log says the recording could not answer, one entry each, without query strings. Read from
    the line Net logs per request (NetEngine), not from the report's note, whose entries carry "closest:" parts."""
    out = []
    for m in re.finditer(r"^\[apksim\] network replay: (.*)$", log_text, re.M):
        line = m.group(1).strip()
        req = re.match(r"(no recorded response for|the recorded response for) ([A-Z][A-Z-]*) (\S+)", line)
        if req:
            too_big = " (too big to store)" if req.group(1).startswith("the") else ""
            out.append(f"{req.group(2)} {strip_queries(req.group(3).rstrip(','))}{too_big}")
        elif line.startswith("no recorded response for") or line.endswith("cannot be replayed"):
            out.append(strip_queries(line))
    return out


def replay_one(name, bundle, apk, opts, out, timeout, result):
    d = os.path.join(out, name)
    shutil.rmtree(d, ignore_errors=True)
    os.makedirs(d)
    log = os.path.join(out, name + ".log")
    cmd = launcher() + ["replay", "--headless", "--out", d, "--apk", apk, "--jvm", "-Dapksim.networkTrace=true"] + opts + [bundle]
    t0 = time.time()
    with open(log, "w") as lf:
        lf.write("$ " + " ".join(shlex.quote(c) for c in cmd) + "\n")
        lf.flush()
        try:
            code = subprocess.run(cmd, stdout=lf, stderr=subprocess.STDOUT, timeout=timeout).returncode
        except subprocess.TimeoutExpired:
            code = None
        except OSError as e:
            lf.write(f"could not start: {e}\n")
            code = 127
    try:
        with open(log, errors="replace") as f:
            text = f.read()
    except OSError:
        text = ""
    rep = load(os.path.join(d, "report.json"), {})
    pts = rep.get("points", [])
    first = next((p for p in pts if p.get("diverged")), None)
    r = {"name": name, "apk": os.path.abspath(apk), "options": opts, "exit": code, "seconds": round(time.time() - t0, 1),
         "result": {0: "matched", 3: "diverged", 10: "crashed", 2: "refused", None: "timed out"}.get(code, f"exit {code}"),
         "points": len(pts), "max_pixels": max([p.get("pixels", 0) for p in pts], default=0),
         "threshold": rep.get("threshold"), "frame": rep.get("frameSize"), "events": rep.get("events"),
         "notes": rep.get("notes", []), "dir": d, "log": log}
    # -Dapksim.networkTrace=true: one line per request answered from the recording; one it has no answer for is
    # always logged.
    answered = re.findall(r"network replay: (\d+) (\S+) (\S+)", text)
    unmatched = unanswered_of(text)
    r["requests"] = {"answered": len(answered), "unanswered": len(unmatched), "unanswered_list": unmatched[:20]}
    if first:
        files = first.get("files", "").split()
        r["first"] = {"n": first["n"], "label": first["label"], "offset_ms": first.get("offsetMs"), "pixels": first.get("pixels"),
                      "files": files}
        m = next((f for f in files if f.endswith("-diff.png")), None)
        found = regions_of(os.path.join(d, m)) if m else None
        if found:
            r["first"]["regions"] = found["regions"]
            r["first"]["regions_more"] = found["more"]
            r["first"]["size"] = found["size"]
    if code == 3 and not first:
        r["result"] = "exit 3 with no diverged point in its report"
    # A build that ended before the replay did (it exited by itself, or never got that far) compared nothing: its
    # exit status, 0 included, is no result.
    if r["result"] in ("matched", "diverged", "crashed") and rep.get("result") not in ("matched", "diverged", "crashed"):
        r["result"] = f"exit {code} with no finished report.json"
    if r["result"] == "crashed":
        r["crash"] = next((n for n in r["notes"] if n.startswith("crashed after")), "crashed")
    if r["result"] not in ("matched", "diverged", "crashed"):
        r["error"] = (re.findall(r"\[apksim\][^\n]*", text) or text.strip().splitlines() or ["no output"])[-1][:400]
    result[name] = r


def requests_line(r, recorded):
    q = r["requests"]
    if recorded is None:
        return "live network (the recording has no network channel)"
    asked = q["answered"] + q["unanswered"]
    s = f"{recorded} recorded, {asked} asked"
    if q["unanswered"]:
        s += f", {q['unanswered']} without a recorded answer"
    return s


def box_text(b):
    return f"{b[0]},{b[1]} to {b[2]},{b[3]}"


def build_line(r, recorded):
    apk = os.path.basename(r["apk"])
    if r["result"] == "matched":
        s = (f"matched the recording at all {plural(r['points'], 'point')} "
             f"(at most {plural(r['max_pixels'], 'pixel')} differing, threshold {r['threshold']})")
    elif r["result"] == "diverged":
        f = r["first"]
        s = (f"first differs at point {f['n']} of {r['points']} ({code(f['label'])}, +{f['offset_ms']} ms): "
             f"{plural(f['pixels'], 'pixel')} (threshold {r['threshold']})")
        if f.get("regions"):
            n = len(f["regions"]) + f.get("regions_more", 0)
            s += f" in {plural(n, 'region')}, the largest {box_text(f['regions'][0])} of a {f['size'][0]}x{f['size'][1]} frame"
    elif r["result"] == "crashed":
        s = code(r.get("crash", "crashed"))
    else:
        s = f"could not be replayed ({r['result']}): {code(r.get('error', ''))}"
    return f"{apk}: {s}; requests: {requests_line(r, recorded)}"


def verdict_of(base, new, expect, recorded_crash=False):
    """(ok, sentence). A fix: the base build still shows what was recorded and the new build no longer does.
    recorded_crash: the recording was saved by a crash (its manifest's `crash`), so the crash is the bug."""
    for r, which in ((base, "base"), (new, "new")):
        if r["result"] not in ("matched", "diverged", "crashed"):
            return None, f"the replay on the {which} build could not run ({r['result']}): {code(r.get('error', ''))}"
    if recorded_crash:
        if base["result"] != "crashed":
            return False, ("the recording ends in a crash and the base build did not crash on the replay, so it does not show "
                           "the bug and nothing can be said about the change.")
        if expect == "same":
            return False, f"the base build crashes on the recorded steps ({code(base.get('crash'))}): not a recording to expect no change from."
        if new["result"] == "crashed":
            return False, f"both builds crash on the recorded steps (base: {code(base.get('crash'))}; new: {code(new.get('crash'))})."
        return True, (f"the base build crashes on the recorded steps ({code(base.get('crash'))}) and the new build plays them to the end "
                      "without crashing. Look at the new build's last pictures: the replay shows it no longer crashes, not what it shows instead.")
    if base["result"] == "crashed":
        return False, (f"the base build crashes on the recorded steps ({code(base.get('crash'))}) and the recording does not end in a "
                       "crash, so it does not show this build's behaviour and nothing can be said about the change.")
    for r, which in ((base, "base"), (new, "new")):
        if r["result"] == "matched" and not r["points"]:
            return None, (f"the replay on the {which} build compared no screen (a recording without the screen channel, e.g. "
                          "`--rec input`): there is nothing to tell the builds apart by. Record with `--rec all`.")
    if base["result"] != "matched":
        return False, (f"the base build differs from the recording at {code(base['first']['label'])}, so the recording does not show "
                       "this build's behaviour and nothing can be said about the change. Was it recorded on this build, with the same launch options?")
    if expect == "same":
        if new["result"] == "matched":
            return True, "both builds replay the recording to the same screens: no visible change along the recorded steps."
        how = "crashes" if new["result"] == "crashed" else f"first differs at {code(new['first']['label'])}"
        return False, f"expected no visible change, but the new build {how}."
    if new["result"] == "diverged":
        return True, (f"the base build shows what was recorded and the new build does not, first at {code(new['first']['label'])}. "
                      "Look at that point's pictures: a replay shows that the screen changed there, not that the change is the one wanted.")
    if new["result"] == "crashed":
        return False, f"the new build crashes on the recorded steps ({code(new.get('crash'))})."
    return False, ("the new build replays the recording to the same screens as the base build: along the recorded steps "
                   "nothing visible changed. (`--expect same` if that is the point of the change.)")


def cmd_verify(a, sim):
    out = os.path.abspath(a.out)
    forget(out, STATE_FILE, "verdict.md")
    for f in (a.bundle, a.base, a.new):
        if not os.path.isfile(f):
            die(f"no such file: {f}")
    os.makedirs(out, exist_ok=True)
    info = bundle_info(a.bundle)
    say(f"verify: replaying {os.path.basename(a.bundle)} on both builds ({info['events']} input lines, "
        + (f"{info['requests']} recorded requests" if info["requests"] is not None else "no network channel") + ")")
    res, threads = {}, []
    for name, apk, extra in (("base", a.base, a.base_option), ("new", a.new, a.new_option)):
        t = threading.Thread(target=replay_one, args=(name, os.path.abspath(a.bundle), os.path.abspath(apk),
                                                      replay_options(sim) + shlex.split(extra or ""), out, a.timeout, res))
        threads.append(t)
        t.start()
        if a.serial:
            t.join()
    for t in threads:
        t.join()
    base, new = res["base"], res["new"]
    ok, sentence = verdict_of(base, new, a.expect, info["crash"])
    warnings = []
    if info["requests"] == 0:
        warnings.append("the recording holds no requests. If the app talked to a server on this machine, record it through "
                        "`--network-host NAME=127.0.0.1:PORT` with the app pointed at NAME: the network tap never reads loopback "
                        "connections, so a replay goes out to the live server.")
    for r in (base, new):
        if r["requests"]["unanswered"]:
            warnings.append(f"{r['name']}: {plural(r['requests']['unanswered'], 'request')} had no recorded answer (answered 599): "
                            + "; ".join(code(u) for u in r["requests"]["unanswered_list"][:3]))
    lines = [f"Replay of `{os.path.basename(a.bundle)}`" + (f" ({info['package']})" if info["package"] else "") + ":",
             "- base " + build_line(base, info["requests"]),
             "- new " + build_line(new, info["requests"]),
             ("**" + {True: "PASS", False: "FAIL", None: "NOT RUN"}[ok] + "**: ") + sentence]
    verdict = {"kind": "verify", "ok": ok, "expect": a.expect, "sentence": sentence, "lines": lines, "warnings": warnings,
               "bundle": os.path.abspath(a.bundle), "recording": info, "base": base, "new": new}
    dump(os.path.join(out, STATE_FILE), verdict)
    with open(os.path.join(out, "verdict.md"), "w") as f:
        f.write("\n".join(lines + [""] + [f"> Note: {w}" for w in warnings]) + "\n")
    say()
    for line in lines:
        say(line)
    for w in warnings:
        say("note: " + w)
    say(f"verify: reports and frames in {out} (base/, new/), the verdict in {os.path.join(out, 'verdict.md')}")
    return 0 if ok else 2 if ok is None else 1


# ------------------------------------------------------------------------------------------------ diff

def print_selection(sel, doc, left_out, cautious):
    by_id = {s["id"]: s for s in doc.get("screens", [])}
    total = len(by_id)
    if sel["mode"] == "full":
        full = sel.get("full") or {}
        say(f"diff: every screen is walked again ({total}): {sel.get('fallback_reason')}"
            + (f" [rule {full.get('rule')}]" if full.get("rule") else ""))
    elif sel["mode"] == "none":
        say(f"diff: no screen of {total} is selected by this change")
    else:
        say(f"diff: {len(sel['selected'])} of {plural(total, 'screen')} selected")
        files = {}
        for sid, why in (sel.get("screens") or {}).items():
            for w in why:
                key = (w.get("file") or "(unexplored in the baseline)", w.get("via"), w.get("name"))
                files.setdefault(key, []).append(sid)
        for (f, via, name), ids in files.items():
            titles = ", ".join((by_id.get(i, {}).get("title") or i) + f" [{i}]" for i in ids[:5]) + ("..." if len(ids) > 5 else "")
            say(f"  {f} ({via}{': ' + name if name else ''}) -> {titles}")
    if sel.get("ignored"):
        say(f"  ignored ({len(sel['ignored'])}): {', '.join(sel['ignored'][:8])}{'...' if len(sel['ignored']) > 8 else ''}")
    if sel.get("unplaced"):
        say(f"  unplaced ({len(sel['unplaced'])}): {', '.join(sel['unplaced'][:8])}: changed sources no explored screen ran. "
            "That does not show they are invisible, only that the map cannot say where they show.")
    if left_out and not cautious and sel["mode"] != "full":
        say(f"  {plural(left_out, 'screen')} the baseline found and never explored (no dependencies) left out; "
            "`--cautious` walks them too")


def walk_summary(doc):
    pairs = doc.get("compare") or []
    counts = {}
    for p in pairs:
        counts[p["status"]] = counts.get(p["status"], 0) + 1
    return counts


def cmd_diff(a, crawl_opts, sim):
    base_dir = os.path.abspath(a.baseline)
    seed = load(os.path.join(base_dir, "crawl.json"))
    if seed is None:
        die(f"{base_dir} has no crawl.json: run `pr-evidence.py baseline BASE.apk --out {a.baseline}` first")
    if not os.path.isfile(a.apk):
        die(f"no such APK: {a.apk}")
    if not a.diff and not a.changed:
        die("give --diff REV (with --repo) or --changed PATH...")
    out = os.path.abspath(a.out)
    if out == base_dir:
        die("--out must not be the baseline's folder: the baseline is kept for the next change")
    os.makedirs(out, exist_ok=True)
    forget(out, STATE_FILE, "select.json", "crawl.json")  # an earlier change's results are not this one's
    log = os.path.join(out, "pr-evidence.log")
    how = load(os.path.join(base_dir, STATE_FILE), {})
    if "start_deps" not in seed:
        say("diff: the baseline was walked without --record-deps, so nothing maps a file to a screen: every screen is walked")

    ids = os.path.join(out, "affected.ids")
    sel_cmd = [sys.executable, sibling("crawl-select.py"), base_dir, "--repo", a.repo, "--out-ids", ids, "--json"]
    if a.diff:
        sel_cmd += ["--diff", a.diff]
    if a.changed:
        sel_cmd += ["--changed"] + a.changed
    if not a.cautious:
        sel_cmd += ["--explored-only"]
    for g in a.ignore or []:
        sel_cmd += ["--ignore", g]
    p = subprocess.run(sel_cmd, capture_output=True, text=True)
    try:
        sel = json.loads(p.stdout)
    except ValueError:
        die(f"crawl-select.py failed (exit {p.returncode}): {(p.stderr or p.stdout).strip()[-600:]}")
    left_out = sum(1 for s in seed.get("screens", []) if s.get("deps") is None)
    # A full walk takes every screen, the unexplored ones too: none is left out.
    sel["left_out_unexplored"] = 0 if a.cautious or sel["mode"] == "full" else left_out
    sel["cautious"] = bool(a.cautious)
    dump(os.path.join(out, "select.json"), sel)
    print_selection(sel, seed, left_out, a.cautious)

    result = {"kind": "diff", "baseline": base_dir, "apk": os.path.abspath(a.apk), "selection": os.path.join(out, "select.json"),
              "walked": False}
    if sel["mode"] == "none":
        dump(os.path.join(out, STATE_FILE), result)
        if sel.get("unplaced"):
            tail = " The unplaced files above are not covered by this."
        elif sel["left_out_unexplored"]:
            tail = (f" No screen with recorded dependencies is reached; nothing is known about the "
                    f"{plural(sel['left_out_unexplored'], 'screen')} without them.")
        else:
            tail = " Every screen of the baseline is carried unchanged."
        say("diff: nothing to walk." + tail)
        return 0

    opts = crawl_opts or how.get("crawl_options") or []
    for refused in ("--seed", "--only", "--only-file", "--base", "--base-option", "--out", "--start"):
        if has_option(opts, refused):
            die(f"diff does not take {refused}")
    opts = without_option(without_option(without_option(opts, "--record-deps", takes_value=False), "--deps-packages"), "--max-depth")
    if not has_option(opts, "--diff-controls"):
        opts += ["--diff-controls", sel.get("diff_controls") or "changed"]
    depth = how.get("depth")
    if depth is not None:  # --cautious names screens at the baseline's limit, which only a walk one deeper explores
        opts += ["--max-depth", str(depth + 1 if a.cautious else depth)]
    sim_all = (sim if sim else how.get("apksim_options") or []) + shlex.split(a.new_option or "")
    cmd = [sys.executable, sibling("crawl.py"), a.apk, "--out", out, "--seed", base_dir, "--only-file", ids, "--diff-seed"] + opts
    if sim_all:
        cmd += ["--"] + sim_all
    t0 = time.time()
    say(f"diff: walking {plural(len(sel['selected']), 'screen')} on {os.path.basename(a.apk)} against the baseline")
    code = run_stream(cmd, log)
    doc = load(os.path.join(out, "crawl.json"))
    if code not in (0, 1) or doc is None:
        die(f"crawl.py exited {code}" + ("" if doc is not None else " and wrote no crawl.json") + f": see {log}")
    counts = walk_summary(doc)
    result.update(walked=True, seconds=round(time.time() - t0, 1), counts=counts, crawl_exit=code)
    dump(os.path.join(out, STATE_FILE), result)
    say(f"diff: {time.time() - t0:.0f} s; " + (", ".join(f"{v} {k}" for k, v in sorted(counts.items())) or "nothing compared"))
    for p_ in doc.get("compare") or []:
        if p_["status"] in ("same",):
            continue
        d = (doc.get("diffs") or {}).get(p_.get("id") or "", {})
        px = "; ".join(f"{c}: {x.get('pixels')} pixels in {plural(len(x.get('regions', [])) + x.get('regions_more', 0), 'region')}"
                       for c, x in d.items() if x.get("differs"))
        say(f"  {p_['status']}: {p_.get('id') or p_.get('base')}" + (f": {p_['why']}" if p_.get("why") else "") + (f" ({px})" if px else ""))
    for line in doc.get("incomplete", []):
        if not line.startswith("seed: "):
            say("  incomplete: " + line)
    say(f"diff: report {os.path.join(out, 'report.html')}")
    return 0


# ------------------------------------------------------------------------------------------------ comment

def safe_name(s):
    return re.sub(r"[^A-Za-z0-9._-]+", "-", s).strip("-")


class Images:
    """Copies the pictures a comment shows into one folder under stable names."""

    def __init__(self, folder, md_dir, base_url):
        self.folder, self.md_dir, self.base_url, self.copied = folder, md_dir, base_url, []

    def add(self, src, name):
        if not src or not os.path.isfile(src):
            return None
        os.makedirs(self.folder, exist_ok=True)
        dst = os.path.join(self.folder, name)
        shutil.copyfile(src, dst)
        self.copied.append(name)
        if self.base_url:
            return self.base_url.rstrip("/") + "/" + name
        return os.path.relpath(dst, self.md_dir).replace(os.sep, "/")

    def cell(self, src, name, alt):
        ref = self.add(src, name)
        return f'<img src="{ref}" width="240" alt="{alt}">' if ref else "(none)"


def ui_diff_lines(ud):
    out = []
    if ud.get("title"):
        out.append(f"title {code(ud['title'].get('from'))} → {code(ud['title'].get('to'))}")
    for key, label in (("texts", "text"), ("controls", "control")):
        d = ud.get(key) or {}
        for pair in d.get("replaced") or []:
            out.append(f"{label} {code(pair[0])} → {code(pair[1])}")
        for x in d.get("added") or []:
            out.append(f"{label} added: {code(x)}")
        for x in d.get("removed") or []:
            out.append(f"{label} removed: {code(x)}")
    return out


def comment_verify(v, images, md):
    md.append("### The recorded bug, replayed")
    md.append("")
    md += v.get("lines", [])
    for w in v.get("warnings", []):
        md.append("")
        md.append(f"> Note: {w}")
    new = v.get("new", {})
    first = new.get("first")
    if first:
        files = {("recorded" if f.endswith("-recorded.jpg") else "replayed" if f.endswith("-replayed.jpg") else "diff"): os.path.join(new["dir"], f)
                 for f in first.get("files", [])}
        md.append("")
        md.append(f"| Recorded (the bug) | New build at {cell_code(first['label'])} | Difference |")
        md.append("|---|---|---|")
        md.append("| " + " | ".join([images.cell(files.get("recorded"), "replay-recorded.jpg", "recorded"),
                                     images.cell(files.get("replayed"), "replay-new.jpg", "new build"),
                                     images.cell(files.get("diff"), "replay-diff.png", "difference")]) + " |")
    md.append("")


def comment_walk(walk_dir, images, md, max_screens):
    result = load(os.path.join(walk_dir, STATE_FILE), {})
    sel = load(os.path.join(walk_dir, "select.json"), {})
    md.append("### What else the change touches")
    md.append("")
    base_doc = load(os.path.join(result.get("baseline", ""), "crawl.json"), {}) if result.get("baseline") else {}
    total = len(base_doc.get("screens", []))
    if sel:
        if sel.get("mode") == "full":
            md.append(f"Every screen of the baseline map ({total}) was walked again: {code(sel.get('fallback_reason'))}.")
        elif sel.get("mode") == "none":
            unknown = sel.get("left_out_unexplored", 0)
            md.append(f"No screen of the baseline map ({total}) depends on the changed files." if not unknown else
                      f"No screen with recorded dependencies ({total - unknown} of {total}) depends on the changed files.")
        else:
            md.append(f"{len(sel.get('selected', []))} of {plural(total, 'screen')} in the baseline map can be reached by the changed files:")
            md.append("")
            for sid, why in (sel.get("screens") or {}).items():
                files = sorted({(w.get("file") or "unexplored in the baseline") + (f" ({w['name']})" if w.get("name") else "") for w in why})
                md.append(f"- `{sid}` ← " + ", ".join(code(f) for f in files))
        if sel.get("unplaced"):
            md.append("")
            md.append("Changed sources no explored screen ran (the map cannot say where they show): "
                      + ", ".join(code(f) for f in sel["unplaced"][:12]) + ".")
        if sel.get("ignored"):
            md.append("")
            md.append(f"Ignored as not part of the app's screens: {plural(len(sel['ignored']), 'file')}.")
        if sel.get("left_out_unexplored"):
            md.append("")
            md.append(f"Not selected: {plural(sel['left_out_unexplored'], 'screen')} without recorded dependencies (found and never "
                      "explored). Nothing is known about whether the change reaches them.")
        md.append("")
    doc = load(os.path.join(walk_dir, "crawl.json"))
    if doc is None:
        if not sel:
            md.append("No walk was run.")
        md.append("")
        return
    pairs = doc.get("compare") or []
    by_id = {s["id"]: s for s in doc.get("screens", [])}
    base_by_id = {s["id"]: s for s in doc.get("base_screens", [])}
    diffs = doc.get("diffs") or {}
    combos = doc.get("combos") or []
    changed = [p for p in pairs if p["status"] == "changed"]
    shown = 0
    for p in changed:
        sid, bid = p["id"], p.get("base") or p["id"]
        ns, bs = by_id.get(sid, {}), base_by_id.get(bid, {})
        title = ns.get("title") or bs.get("title") or sid
        md.append(f"#### Changed: {code(title)} (`{sid}`)")
        md.append("")
        for line in ui_diff_lines(p.get("ui_diff") or {}):
            md.append("- " + line)
        if p.get("unstable"):
            md.append(f"- animates for ever in {', '.join(code(u) for u in p['unstable'])}: those captures are not compared")
        if shown >= max_screens:
            md.append("- (pictures left out: see report.html)")
            md.append("")
            continue
        shown += 1
        rows = []
        for c in combos:
            d = diffs.get(sid, {}).get(c)
            if not d or not d.get("differs"):
                continue
            stem = f"screen-{safe_name(sid)}-{safe_name(c)}"
            n = len(d.get("regions", [])) + d.get("regions_more", 0)
            where = f"{d.get('pixels')} pixels in {plural(n, 'region')}" + (f", the largest {box_text(d['regions'][0])}" if d.get("regions") else "")
            rows.append("| " + " | ".join([cell_code(c),
                                           images.cell(os.path.join(walk_dir, (bs.get("captures") or {}).get(c, "-")), stem + "-before.png", "before"),
                                           images.cell(os.path.join(walk_dir, (ns.get("captures") or {}).get(c, "-")), stem + "-after.png", "after"),
                                           images.cell(os.path.join(walk_dir, d.get("map") or "-"), stem + "-diff.png", "difference"),
                                           where]) + " |")
        if rows:
            md.append("")
            md.append("| | Before | After | Difference | |")
            md.append("|---|---|---|---|---|")
            md += rows
        elif not (p.get("ui_diff") or p.get("unstable")):
            md.append(f"- {code(p.get('why') or 'changed')}")
        md.append("")
    for status, head in (("new", "New screens"), ("gone", "Screens no longer reached"), ("unknown", "Screens that could not be checked"),
                         ("unstable", "Left out as unstable (content that changed between two reads, or requests that "
                          "failed, in the baseline or this walk: docs/crawl.md \"Unstable screens\")")):
        some = [p for p in pairs if p["status"] == status]
        if some:
            md.append(f"**{head}:** " + "; ".join(f"`{p.get('id') or p.get('base')}`" + (f" ({code(p['why'])})" if p.get("why") else "") for p in some[:12]))
            md.append("")
    same = [p for p in pairs if p["status"] == "same"]
    walked_same = [p for p in same if by_id.get(p.get("id"), {}).get("origin") == "walked"]
    # Carried says something only of a screen whose dependencies the baseline recorded.
    no_deps = {s["id"] for s in base_doc.get("screens", []) if s.get("deps") is None}
    unknown = [p for p in same if p not in walked_same and p.get("id") in no_deps]
    carried = len(same) - len(walked_same) - len(unknown)
    if not changed:
        md.append("No walked screen changed.")
        md.append("")
    md.append(f"Unchanged: {plural(len(walked_same), 'screen')} walked again and found the same, {carried} carried from the baseline without a walk (nothing they depend on changed).")
    if unknown:
        md.append("")
        md.append(f"Not walked and not counted as unchanged: {plural(len(unknown), 'screen')} without recorded dependencies.")
    if walked_same:
        md.append("")
        md.append("<details><summary>Walked again, same</summary>\n\n" + "\n".join(f"- `{p['id']}` {code(by_id.get(p['id'], {}).get('title') or '')}" for p in walked_same[:60]) + "\n\n</details>")
    crashes = [f for f in doc.get("findings", []) if f.get("kind") == "crash" and f.get("build", "new") == "new"]
    if crashes:
        md.append("")
        md.append(f"**{plural(len(crashes), 'crash', 'crashes')} on the new build:** " + "; ".join(code(f.get("detail", ""), 160) for f in crashes[:5]))
    cut = [x for x in doc.get("incomplete", []) if not x.startswith("seed: ")]
    if cut:
        md.append("")
        md.append("The walk was cut short: " + "; ".join(code(x) for x in cut[:4]) + ".")
    md.append("")


def cmd_comment(a):
    out = os.path.abspath(a.out)
    md_dir = os.path.dirname(out)
    folder = os.path.abspath(a.images) if a.images else os.path.splitext(out)[0] + "-images"
    forget(md_dir, os.path.basename(out))
    if os.path.isdir(folder):  # only the names this script writes: --images may hold other files
        forget(folder, *[n for n in os.listdir(folder)
                         if n in ("replay-recorded.jpg", "replay-new.jpg", "replay-diff.png")
                         or re.fullmatch(r"screen-.+-(before|after|diff)\.png", n)])
    images = Images(folder, md_dir, a.image_base)
    md = ["## Evidence from apksim", ""]
    v = load(os.path.join(a.verify, STATE_FILE)) if a.verify else None
    have_walk = bool(a.walk) and (os.path.isfile(os.path.join(a.walk, STATE_FILE)) or os.path.isfile(os.path.join(a.walk, "crawl.json")))
    if v is None and not have_walk:
        die("nothing to write about: neither verify's results (--verify DIR) nor diff's (--walk DIR) were found")
    if v is not None:
        comment_verify(v, images, md)
    if have_walk:
        comment_walk(os.path.abspath(a.walk), images, md, a.pictures)
    os.makedirs(md_dir, exist_ok=True)
    with open(out, "w") as f:
        f.write("\n".join(md).rstrip() + "\n")
    say(f"comment: {out}")
    if images.copied:
        say(f"comment: {plural(len(images.copied), 'image')} in {folder}: " + ", ".join(images.copied))
        if not a.image_base:
            say("comment: the Markdown names them by relative path. A pull request shows only hosted images: upload the "
                "folder somewhere the reviewers can read and run comment again with --image-base URL.")
    return 0


# ------------------------------------------------------------------------------------------------ run

def cmd_run(a, crawl_opts, sim):
    work = os.path.abspath(a.work)
    os.makedirs(work, exist_ok=True)
    base_dir = os.path.abspath(a.baseline) if a.baseline else os.path.join(work, "baseline")
    how = load(os.path.join(base_dir, STATE_FILE), {})
    if os.path.isfile(os.path.join(base_dir, "crawl.json")) and how.get("apk_sha256") == sha256(a.base):
        say(f"run: using the baseline in {base_dir} (same base build)")
    else:
        if a.baseline and os.path.isfile(os.path.join(base_dir, "crawl.json")):
            say(f"run: the baseline in {base_dir} is of another build; it is left as it is, and this build is walked into {os.path.join(work, 'baseline')}")
            base_dir = os.path.join(work, "baseline")
        ns = argparse.Namespace(apk=a.base, out=base_dir, depth=a.depth, rounds=a.rounds, base_option=a.base_option)
        cmd_baseline(ns, crawl_opts, sim)
    say()
    v = cmd_verify(argparse.Namespace(bundle=a.bundle, base=a.base, new=a.new, out=os.path.join(work, "verify"), expect=a.expect,
                                      base_option=a.base_option, new_option=a.new_option, timeout=a.timeout, serial=a.serial), sim)
    say()
    failed = None
    try:
        cmd_diff(argparse.Namespace(apk=a.new, baseline=base_dir, repo=a.repo, diff=a.diff, changed=a.changed, out=os.path.join(work, "diff"),
                                    cautious=a.cautious, new_option=a.new_option, ignore=a.ignore), [], sim)
    except SystemExit as e:  # the verdict is still worth its comment
        failed = e.code or 2
        forget(os.path.join(work, "diff"), STATE_FILE, "select.json", "crawl.json")  # not the log its message names
    say()
    cmd_comment(argparse.Namespace(out=os.path.join(work, "comment.md"), verify=os.path.join(work, "verify"), walk=os.path.join(work, "diff"),
                                   images=a.images, image_base=a.image_base, pictures=a.pictures))
    if failed:
        say("run: the diff step failed (above); the comment has the replay's verdict only")
    return failed or v


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    sim = []
    if "--" in argv:
        i = argv.index("--")
        argv, sim = argv[:i], argv[i + 1:]
    ap = argparse.ArgumentParser(prog="pr-evidence.py", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter, allow_abbrev=False)
    sub = ap.add_subparsers(dest="cmd", required=True)

    b = sub.add_parser("baseline", allow_abbrev=False, help="walk the base build and record what each screen depends on")
    b.add_argument("apk")
    b.add_argument("--out", required=True)
    b.add_argument("--depth", type=int, default=3, help="explore the screens fewer than N actions from a start; those N away are found and not explored (crawl.py --max-depth; default 3)")
    b.add_argument("--rounds", type=int, default=3, help="how often to go on from screens a budget left unexplored (default 3)")
    b.add_argument("--base-option", default="", metavar="OPTS", help="apksim options for this build's launches that `diff` must not reuse")

    def builds(p):
        p.add_argument("--base-option", default="", metavar="OPTS", help="apksim options for the base build's launches only")
        p.add_argument("--new-option", default="", metavar="OPTS", help="apksim options for the new build's launches only")

    def replay_opts(p):
        p.add_argument("--expect", choices=("fix", "same"), default="fix",
                       help="fix: the base matches and the new build differs (default); same: both match (a refactor)")
        p.add_argument("--timeout", type=float, default=600, help="seconds a replay may take (default 600)")
        p.add_argument("--serial", action="store_true", help="replay the builds one after the other, not side by side")

    def select_opts(p):
        p.add_argument("--repo", default=".", help="the app's repository (for --diff and the changed files)")
        p.add_argument("--diff", metavar="REV", help="git revision spec of the change, e.g. origin/main...HEAD")
        p.add_argument("--changed", nargs="+", default=[], metavar="PATH", help="changed files, instead of or besides --diff")
        p.add_argument("--cautious", action="store_true", help="also walk every screen the baseline found and never explored")
        p.add_argument("--ignore", action="append", default=[], metavar="GLOB", help="changed files to leave out (repeatable)")

    def comment_opts(p):
        p.add_argument("--images", metavar="DIR", help="where to copy the pictures (default FILE-images beside the Markdown)")
        p.add_argument("--image-base", metavar="URL", help="where the pictures will be hosted: put in front of their names")
        p.add_argument("--pictures", type=int, default=12, metavar="N", help="changed screens shown with pictures (default 12)")

    v = sub.add_parser("verify", allow_abbrev=False, help="replay the recording on both builds and print a verdict")
    v.add_argument("bundle")
    v.add_argument("--base", required=True)
    v.add_argument("--new", required=True)
    v.add_argument("--out", default="pr-evidence/verify")
    builds(v)
    replay_opts(v)

    d = sub.add_parser("diff", allow_abbrev=False, help="walk the screens the change can reach on the new build, against the baseline")
    d.add_argument("apk")
    d.add_argument("--baseline", required=True, metavar="DIR")
    d.add_argument("--out", default="pr-evidence/diff")
    d.add_argument("--new-option", default="", metavar="OPTS", help="apksim options added for the new build's launches")
    select_opts(d)

    c = sub.add_parser("comment", allow_abbrev=False, help="Markdown for the pull request")
    c.add_argument("--out", required=True, metavar="FILE.md")
    c.add_argument("--verify", default="pr-evidence/verify", metavar="DIR", help="verify's --out (default pr-evidence/verify)")
    c.add_argument("--walk", default="pr-evidence/diff", metavar="DIR", help="diff's --out (default pr-evidence/diff)")
    comment_opts(c)

    r = sub.add_parser("run", allow_abbrev=False, help="baseline, verify, diff and comment in one folder")
    r.add_argument("bundle")
    r.add_argument("--base", required=True)
    r.add_argument("--new", required=True)
    r.add_argument("--work", required=True, metavar="DIR")
    r.add_argument("--baseline", metavar="DIR", help="a baseline to reuse (default WORK/baseline)")
    r.add_argument("--depth", type=int, default=3)
    r.add_argument("--rounds", type=int, default=3)
    builds(r)
    replay_opts(r)
    select_opts(r)
    comment_opts(r)

    a, extra = ap.parse_known_args(argv)
    if extra and a.cmd not in ("baseline", "diff", "run"):
        ap.error("unrecognized arguments: " + " ".join(extra))
    if a.cmd == "baseline":
        return cmd_baseline(a, extra, sim)
    if a.cmd == "verify":
        return cmd_verify(a, sim)
    if a.cmd == "diff":
        return cmd_diff(a, extra, sim)
    if a.cmd == "comment":
        return cmd_comment(a)
    if not a.diff and not a.changed:
        ap.error("run needs --diff REV or --changed PATH...")
    return cmd_run(a, extra, sim)


if __name__ == "__main__":
    sys.exit(main())
