#!/usr/bin/env python3
"""Explores an APK's screens breadth-first over the control channel and maps them (docs/crawl.md).

Usage: crawl.py APK --out DIR [--base APK2] [--start URL]... [--max-screens N] [--max-seconds S] [--max-depth N]
                [--skip-text REGEX]... [--tap-unlabeled] [--fill TEXT] [--matrix AXIS=V,V]... [--matrix-file SPEC.json]
                [-j N] [--setting NS:KEY=VALUE]... [--sdcard-file PATH=text:TEXT]... [--keep-data]
                [--base-option "APKSIM OPTIONS"] [--sweep LIST ...] [-- APKSIM_OPTION...]

Starts N headless instances (`apksim --headless --snapshot`, as scripts/snapshot.py does) and walks the app level by
level: each screen reached is replayed from a fresh start, every control on it is tried in turn, and where it leads
is a screen (activity + structural hash of `nodes tree`, see docs/crawl.md for what that does and does not tell
apart) or a crash or the end of the app. Then every screen is captured in every combination of the matrix
(device, appearance, locale, fontscale: snapshot.py's axes). With --base the other build is walked the same way and
the two are compared: screens new, gone and changed, with a difference image.

Writes OUT/report.html (a clickable map and a card per screen), OUT/crawl.json (graph, paths, captures, findings),
OUT/screens/ (captures) and OUT/logs/. Exit status: 0 when the walk finished, 1 when it found crashes (or, with --base,
differences), 2 for bad arguments or an instance that never started.
"""
import argparse, base64, concurrent.futures, hashlib, html, itertools, json, os, re, shlex, shutil, subprocess, sys
import tempfile, threading, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import snapshot  # noqa: E402  (scripts/snapshot.py: instances, matrix axes, captures)

ROOT = snapshot.ROOT
AXES = snapshot.AXES
DEFAULT_COMBO = dict(snapshot.DEFAULTS)
CLOCK = "2026-01-01T09:41:00Z"
# Labels the crawler will not tap (case-insensitive, searched in the control's own text/description and in the
# text inside it): a walk must not delete, buy, send or sign out of anything.
DEFAULT_SKIP = (r"\b(delete|remove|erase|clear( all| data| history)?|reset|sign[ -]?out|log[ -]?out|logout|"
                r"purchase|buy|pay|checkout|order|subscribe|unsubscribe|send|submit|post|publish|share|"
                r"uninstall|deactivate|close account|cancel (subscription|order)|confirm|transfer|withdraw|donate)\b")
SETTLE = "settle 1500 100"
PSEUDO = ("EXIT", "CRASH", "SKIPPED", "FAILED")  # where an edge leads when it does not lead to a screen


def fail(msg, code=2):
    print(f"crawl: {msg}", file=sys.stderr)
    sys.exit(code)


def log(msg):
    print(msg, flush=True)


# ----------------------------------------------------------------------------------------------------------- tree

class Rec:
    __slots__ = ("depth", "cls", "rid", "flags", "box", "text", "desc", "hint")

    def __init__(self, depth, cls, rid, flags, box, text, desc, hint):
        self.depth, self.cls, self.rid, self.flags, self.box = depth, cls, rid, flags, box
        self.text, self.desc, self.hint = text, desc, hint

    @property
    def label(self):
        return self.text or self.desc

    def centre(self):
        l, t, r, b = self.box
        return (l + r) // 2, (t + b) // 2


def parse_tree(reply):
    """The records of `nodes tree` (Nodes.tree): depth, class, id, flags, bounds, text, description, hint."""
    recs = []
    for part in reply.split("\x1e"):
        f = part.strip("\n").split("\t")
        if len(f) < 5 or not f[0].strip().isdigit():
            continue
        f += [""] * (8 - len(f))
        try:
            box = tuple(int(x) for x in f[4].split(","))
        except ValueError:
            continue
        if len(box) == 4:
            recs.append(Rec(int(f[0]), f[1], f[2], f[3], box, f[5], f[6], f[7]))
    return recs


STRUCT_FLAGS = "clekps"


def build(recs):
    """Children by index: kids[i] are the visible records directly under record i; kids[-1] the roots."""
    kids = {-1: []}
    stack = []
    hidden = None  # depth of an invisible record whose subtree is skipped
    for i, r in enumerate(recs):
        if hidden is not None:
            if r.depth > hidden:
                continue
            hidden = None
        if "v" not in r.flags:
            hidden = r.depth
            continue
        while stack and recs[stack[-1]].depth >= r.depth:
            stack.pop()
        kids.setdefault(i, [])
        kids[stack[-1] if stack else -1].append(i)
        stack.append(i)
    return kids


def screen_size(recs):
    w = max([r.box[2] for r in recs if r.depth == 0] or [1])
    h = max([r.box[3] for r in recs if r.depth == 0] or [1])
    return max(w, 1), max(h, 1)


def canon(recs, kids, i, w, h, scrolling):
    """A record's structure as a string: class, id, the flags that say what it is for (not its state), where it sits
    coarsely (a third of the width; top, middle or bottom of the height, not inside a scroller, whose content moves),
    and its children, with runs of identical consecutive siblings (the rows of a list) written once."""
    r = recs[i]
    cx, cy = r.centre()
    col = min(2, max(0, cx * 3 // w))
    row = "-" if scrolling else ("T" if cy < h * 0.15 else "B" if cy > h * 0.85 else "M")
    flags = "".join(c for c in STRUCT_FLAGS if c in r.flags)
    inner = scrolling or "s" in r.flags
    parts = []
    for k in kids.get(i, []):
        s = canon(recs, kids, k, w, h, inner)
        if parts and parts[-1][0] == s:
            parts[-1][1] += 1
        else:
            parts.append([s, 1])
    body = ",".join(s + ("*" if n > 1 else "") for s, n in parts)
    return f"{r.cls.rsplit('.', 1)[-1]}:{r.rid}:{flags}:{col}{row}({body})"


def structure(activity, recs):
    kids = build(recs)
    w, h = screen_size(recs)
    c = "|".join(canon(recs, kids, i, w, h, False) for i in kids[-1])
    digest = hashlib.sha1((activity + "#" + c).encode()).hexdigest()[:8]
    return f"{activity.rsplit('.', 1)[-1]}-{digest}", len(recs)


# --------------------------------------------------------------------------------------------------- instances

class ReplayFailed(Exception):
    pass


class Gone(Exception):
    """The instance ended: [crash] says how."""

    def __init__(self, crash):
        super().__init__(crash.get("detail", "the instance ended"))
        self.crash = crash


class Instance(snapshot.Worker):
    """A headless instance and what a walk does with it: replaying paths, reading the screen, capturing."""

    def __init__(self, i, ctx):
        super().__init__(i, [dict(DEFAULT_COMBO)], ctx)
        self.stage = os.path.join(ctx["stage"], str(i))
        os.makedirs(self.stage, exist_ok=True)
        self.generation = 0
        self.applied = {a: None for a in AXES}  # unknown: a launch option may have set any of them
        self.crashes = ctx["crashes"]

    def launch(self):
        self.generation += 1
        self.log = os.path.join(self.ctx["out"], "logs", f"{self.ctx['name']}-instance-{self.i}.{self.generation}.log")
        self.proc = self.file = self.sock = None
        self.applied = {a: None for a in AXES}  # unknown: a launch option may have set any of them
        self.start()

    def ensure(self):
        if self.file is None or self.proc is None or self.proc.poll() is not None:
            self.close()
            self.launch()

    def say(self, cmd):
        """Sends a command; an instance that ends meanwhile raises Gone with what the log says."""
        try:
            return self.send(cmd)
        except (snapshot.Closed, OSError, ValueError):
            raise Gone(self.ended(cmd))

    def ended(self, cmd):
        try:
            self.proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        self.close()
        try:
            with open(self.log, errors="replace") as f:
                text = f.read()
        except OSError:
            text = ""
        code = self.proc.returncode
        m = re.findall(r"\[apksim\] exit (\d+): ([^\n]*)", text)
        crash = {"exit": code, "after": cmd}
        if "FATAL EXCEPTION" in text:
            i = text.index("FATAL EXCEPTION")
            lines = text[max(0, text.rfind("\n", 0, i)):].splitlines()
            crash.update(kind="crash", detail="FATAL EXCEPTION " + "\n".join(lines[1:12]).strip()[:1500])
        elif m:
            crash.update(kind="exit", detail=m[-1][1])
        else:
            crash.update(kind="died", detail=f"the instance ended with status {code}")
        crash["log"] = os.path.relpath(self.log, self.ctx["out"])
        return crash

    def expect_ok(self, cmd):
        reply = self.say(cmd)
        if reply.startswith("error: app crashed"):  # the command ran app code that threw: the process is ending
            raise Gone(self.ended(cmd))
        if reply.startswith(snapshot.FAILED_REPLIES):
            raise ReplayFailed(f"{cmd!r}: {reply}")
        return reply

    def settle(self):
        """Waits for the screen to be still, at most 2.5 s; a screen that animates for ever is read as it is."""
        return not self.say(SETTLE).startswith("error")

    # --- reading
    def observe(self):
        """The screen as it is: read until two reads in a row agree (a list still composing, a transition ending)."""
        prev = None
        for attempt in range(4):
            act = self.say("activity")
            if act == "none":
                return {"id": "EXIT", "activity": "none", "nodes": 0, "recs": []}
            recs = parse_tree(self.say("nodes tree"))
            sid, n = structure(act, recs)
            if sid == prev:
                break
            prev = sid
            if attempt < 3:
                # `settle` does not wait for a coroutine's delay() or a posted Runnable: a screen that fills in a
                # moment after it is still is read again until two reads --stable-ms apart agree.
                time.sleep(self.ctx.get("stable_ms", 200) / 1000)
        return {"id": sid, "activity": act, "nodes": n, "recs": recs}

    # --- moving
    def configure(self, combo):
        for axis in AXES:
            if fmt(self.applied[axis]) != fmt(combo[axis]):
                self.expect_ok(f"{axis} {fmt(combo[axis])}")
                self.applied[axis] = combo[axis]

    def reach(self, path, extra=()):
        """Back to a screen: the default configuration, a fresh start (at the path's deep link), the path's steps."""
        self.ensure()
        self.configure(DEFAULT_COMBO)
        self.say(f"clock {CLOCK}")
        self.expect_ok("restart" + (f" {path['start']}" if path.get("start") else ""))
        self.settle()
        for step in list(path["steps"]) + list(extra):
            self.expect_ok(step)
            self.settle()
        self.say("pick cancel")

    def reach_screen(self, screen, extra=()):
        """Replays the screen's path and checks where it ended; [extra] steps (scrolls, a fill) follow the check."""
        self.reach(screen["path"])
        got = self.observe()
        if got["id"] != screen["id"]:
            raise ReplayFailed(f"replay reached {got['id']}, not {screen['id']}")
        for step in extra:
            self.expect_ok(step)
            self.settle()
        return got


def fmt(v):
    return snapshot.fmt(v)


# ----------------------------------------------------------------------------------------------- the explorer

class Budget:
    def __init__(self, opts):
        self.deadline = time.time() + opts.max_seconds if opts.max_seconds else None
        self.max_screens = opts.max_screens
        self.max_depth = opts.max_depth

    def out_of_time(self):
        return self.deadline is not None and time.time() > self.deadline


def candidates(recs, skip_re, tap_unlabeled, scrolls=0):
    """The controls on the screen as actions: clickable, visible, enabled records, with the label they show."""
    out = []
    labels = {}
    for r in recs:
        for t in (r.text, r.desc):
            if t:
                labels[t.strip().lower()] = labels.get(t.strip().lower(), 0) + 1
    kids = build(recs)
    for i, r in enumerate(recs):
        if "v" not in r.flags or "d" in r.flags:
            continue
        editable = "e" in r.flags
        if not ("c" in r.flags or "k" in r.flags or editable):
            continue
        inside, inside_skip = [], []
        stack = [i]
        while stack:
            j = stack.pop()
            for k in reversed(kids.get(j, [])):
                stack.append(k)
                if recs[k].label:
                    inside.append(recs[k].label)
                inside_skip += [recs[k].text, recs[k].desc]  # both, whichever the label prefers
        own = r.label
        label = own or (inside[0] if len(inside) == 1 else " ".join(inside))
        x, y = r.centre()
        cmd = f"tap {x} {y}"
        if own and not scrolls and labels.get(own.strip().lower(), 0) == 1 and len(own) <= 40 and "\n" not in own:
            cmd = f"tap-text {own}"
        elif len(inside) == 1 and not scrolls and labels.get(inside[0].strip().lower(), 0) == 1 and len(inside[0]) <= 40:
            cmd = f"tap-text {inside[0]}"
        text_for_skip = " ".join([r.text, r.desc] + inside_skip)
        why = None
        if editable:
            kind = "field"
        else:
            kind = "control"
            if skip_re.search(text_for_skip):
                why = "skip list: " + skip_re.search(text_for_skip).group(0)
            elif not label and not tap_unlabeled:
                why = "unlabeled control (--tap-unlabeled taps these)"
        out.append({"label": label or f"<{r.cls.rsplit('.', 1)[-1]} at {x},{y}>", "cmd": cmd, "kind": kind,
                    "box": list(r.box), "scrolls": scrolls, "skipped": why, "x": x, "y": y})
    return out


def scroll_cmd(recs):
    """A swipe up across the biggest scrollable record, or None."""
    best = None
    for r in recs:
        if "s" in r.flags and "v" in r.flags:
            area = (r.box[2] - r.box[0]) * (r.box[3] - r.box[1])
            if best is None or area > best[0]:
                best = (area, r)
    if not best:
        return None
    l, t, rr, b = best[1].box
    x = (l + rr) // 2
    return f"swipe {x} {t + (b - t) * 3 // 4} {x} {t + (b - t) // 4} 300"


class Explorer:
    """One walk of one APK: the graph, the findings and the captures."""

    def __init__(self, name, apk, args, out, extra_options, shots="screens"):
        self.name, self.apk, self.args, self.out, self.shots = name, apk, args, out, shots
        self.options = list(args.apksim) + list(extra_options)
        # Never the owner's saved sign-ins, and a slot namespace of the crawler's own: --fresh would empty an ordinary
        # launch's `default` slot (--keep-data takes the slot as it is, and an option of the caller's own wins).
        self.options += ["--no-autofill"]
        # One device identity for every instance (the serial and ANDROID_ID come from the slot's name otherwise, and a
        # screen that shows one would differ between instances, and between builds walked on other slots).
        # Not with --keep-data: the slot's own identity is what its sign-in was made under (a seed would be written into it).
        if not args.keep_data and "--serial" not in self.options:
            self.options += ["--serial", "CRAWL0000000000"]
        if not args.keep_data and not any(o.startswith("secure:android_id=") for o in self.options):
            self.options += ["--setting", "secure:android_id=00c0ffee00c0ffee"]
        if not args.keep_data and "--data-slots" not in self.options:
            self.options += ["--data-slots", "crawl"]
        self.screens = {}      # id -> screen
        self.order = []        # ids in discovery order
        self.edges = []        # {from, action, to, ...}
        self.findings = []     # crashes and the like
        self.lock = threading.Lock()
        self.incomplete = []
        self.skip_re = re.compile("|".join(f"(?:{p})" for p in (args.skip_text or [DEFAULT_SKIP])), re.I)
        self.budget = Budget(args)
        self.t_explore = self.t_capture = 0.0
        os.makedirs(os.path.join(out, "logs"), exist_ok=True)
        self.stage = tempfile.mkdtemp(prefix="apksim-crawl-")
        if re.search(r"\s", self.stage):
            fail(f"the temporary folder {self.stage!r} has whitespace in its path: set TMPDIR")
        self.ctx = {"spec": {"timezone": "UTC", "clock": CLOCK}, "apk": apk, "apksim": self.options, "out": out,
                    "stage": self.stage, "snapshot": True, "fresh": not args.keep_data, "screens": [],
                    "record": lambda r: None, "crashes": self.findings, "stable_ms": args.stable_ms, "name": name}
        self.instances = []

    # --- instances
    def pool(self, n):
        self.instances = [Instance(i, self.ctx) for i in range(n)]
        errors = []

        def up(inst):
            try:
                inst.launch()
            except Exception as e:  # noqa: BLE001  (any launch failure ends the run with the reason)
                errors.append(str(e))

        threads = [threading.Thread(target=up, args=(inst,)) for inst in self.instances]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        if errors:
            self.close()
            fail("; ".join(errors))

    def close(self):
        for inst in self.instances:
            inst.quit()
        shutil.rmtree(self.stage, ignore_errors=True)

    def run_on_pool(self, tasks, fn):
        """fn(instance, task) for every task, on the pool's instances; results in the tasks' order."""
        if not tasks:
            return []
        q = list(enumerate(tasks))
        results = [None] * len(tasks)
        lock = threading.Lock()

        def worker(inst):
            while True:
                with lock:
                    if not q:
                        return
                    i, t = q.pop(0)
                results[i] = fn(inst, t)

        with concurrent.futures.ThreadPoolExecutor(len(self.instances)) as ex:
            list(ex.map(worker, self.instances))
        return results

    # --- the walk
    def finding(self, kind, detail, path, **more):
        f = {"kind": kind, "detail": detail, "path": path, "build": self.name}
        f.update(more)
        with self.lock:
            self.findings.append(f)
        return f

    def full_path(self, screen, extra=()):
        return {"start": screen["path"].get("start"), "steps": list(screen["path"]["steps"]) + list(extra)}

    def explore(self):
        t0 = time.time()
        args = self.args
        starts = args.start or [None]
        # Level 0: the roots.
        roots = self.run_on_pool(starts, self.observe_root)
        level = []
        for start, obs in zip(starts, roots):
            if obs is None:
                continue
            if obs["id"] not in self.screens:
                if self.budget.max_screens is not None and len(self.screens) >= self.budget.max_screens:
                    self.incomplete.append(f"--max-screens {self.budget.max_screens}: start {start or 'the launcher activity'} not added")
                    continue
                level.append(self.add_screen(obs, {"start": start, "steps": []}, None, 0))
        depth = 0
        cut = False  # the time ran out inside a level: some screens or controls were left
        while level and not self.budget.out_of_time():
            log(f"[{self.name}] depth {depth}: {len(level)} screen(s) to explore, {len(self.screens)} known")
            if self.budget.max_depth is not None and depth >= self.budget.max_depth:
                self.incomplete.append(f"--max-depth {self.budget.max_depth}: {', '.join(s['id'] for s in level)} not explored")
                break
            level.sort(key=lambda s: s["id"])
            results = self.run_on_pool(level, self.explore_screen)
            nxt = []
            # Merged in screen order, then action order: the same graph whatever the instances' timing.
            for screen, res in zip(level, results):
                if res is None or res.get("truncated"):
                    cut = True
                if res is None:
                    continue
                screen["actions"] = res["actions"]
                screen["flaky"] = res.get("flaky")
                for e in res["edges"]:
                    to = e["to"]
                    edge = {"from": screen["id"], "action": e["label"], "cmd": e["cmd"], "kind": e["kind"], "to": to["id"],
                            "steps": list(e.get("steps") or [])}  # fills and scrolls before the tap, and the tap
                    if e.get("note"):
                        edge["note"] = e["note"]
                    if to["id"] not in PSEUDO and to["id"] not in self.screens:
                        steps = self.full_path(screen, e["steps"])["steps"]
                        if repeated(steps, args.max_repeat):
                            edge["note"] = f"not followed: the same step {args.max_repeat} times in a row"
                            edge["to"] = None
                        elif self.budget.max_screens is not None and len(self.screens) >= self.budget.max_screens:
                            self.incomplete.append(f"--max-screens {self.budget.max_screens}")
                            edge["note"] = "not followed: --max-screens"
                            edge["to"] = None
                        else:
                            nxt.append(self.add_screen(to, self.full_path(screen, e["steps"]), screen["id"], depth + 1))
                    self.edges.append(edge)
                for f in res["findings"]:
                    self.findings.append(f)
            level = nxt
            depth += 1
        if (level or cut) and self.budget.out_of_time():
            self.incomplete.append(f"--max-seconds {self.args.max_seconds}: {len(level)} screen(s) not explored"
                                   + (", and some controls of the last level not tried" if cut else ""))
        self.t_explore = time.time() - t0

    def observe_root(self, inst, start):
        for attempt in range(2):
            try:
                inst.reach({"start": start, "steps": []})
                return inst.observe()
            except ReplayFailed as e:
                self.finding("replay", f"start {start}: {e}", {"start": start, "steps": []})
            except Gone as e:
                self.findings.append(self.crash_finding(e.crash, {"start": start, "steps": []}))
        return None

    def add_screen(self, obs, path, parent, depth):
        s = {"id": obs["id"], "activity": obs["activity"], "nodes": obs["nodes"], "path": path, "parent": parent,
             "depth": depth, "actions": [], "captures": {}, "title": title_of(obs["recs"]),
             "texts": [r.text[:80] for r in obs["recs"] if r.text and "v" in r.flags][:40]}
        self.screens[s["id"]] = s
        self.order.append(s["id"])
        return s

    def explore_screen(self, inst, screen):
        """Tries every control of a screen. Returns its actions, edges (action -> observed screen) and findings."""
        if self.budget.out_of_time():
            return None
        args = self.args
        findings, edges = [], []
        truncated = False
        here = None
        for attempt in range(2):
            try:
                here = inst.reach_screen(screen)
                break
            except ReplayFailed as e:
                if attempt:
                    findings.append({"kind": "flaky", "detail": f"{screen['id']}: {e}", "path": screen["path"], "build": self.name})
                    return {"actions": [], "edges": [], "findings": findings, "flaky": str(e)}
            except Gone as e:
                findings.append(self.crash_finding(e.crash, screen["path"]))
                return {"actions": [], "edges": [], "findings": findings, "flaky": "ended on arrival"}
        prep = []
        filled = None
        if args.fill is not None:
            for a in candidates(here["recs"], self.skip_re, args.tap_unlabeled):
                if a["kind"] == "field":
                    prep += [f"tap {a['x']} {a['y']}", f"type {args.fill}"]
            filled = None
            try:
                for step in prep:
                    inst.expect_ok(step)
                inst.settle()
                filled = parse_tree(inst.say("nodes tree"))
            except ReplayFailed:
                prep = []
            except Gone as e:
                findings.append(self.crash_finding(e.crash, self.full_path(screen, prep)))
                return {"actions": [], "edges": [], "findings": findings, "flaky": "ended while filling fields"}
        recs = filled if prep and filled is not None else here["recs"]
        acts = [a for a in candidates(recs, self.skip_re, args.tap_unlabeled) if a["kind"] == "control"]
        # Controls below the fold: scroll and read again, until nothing new shows.
        scrolls = []
        aliases = set()  # the screen scrolled is still this screen
        seen = {a["label"] for a in acts}
        for k in range(1, args.max_scrolls + 1):
            sc = scroll_cmd(recs)
            if not sc:
                break
            try:
                inst.expect_ok(sc)
                inst.settle()
                scrolled = parse_tree(inst.say("nodes tree"))
            except ReplayFailed:
                break
            except Gone as e:
                findings.append(self.crash_finding(e.crash, self.full_path(screen, prep + scrolls + [sc])))
                return {"actions": [], "edges": [], "findings": findings, "flaky": "ended while scrolling"}
            scrolls.append(sc)
            recs = scrolled
            aliases.add(structure(here["activity"], recs)[0])
            fresh = [a for a in candidates(recs, self.skip_re, args.tap_unlabeled, scrolls=k) if a["kind"] == "control"
                     and a["label"] not in seen]
            if not fresh:
                break
            for a in fresh:
                seen.add(a["label"])
                a["prefix"] = list(scrolls)
            acts += fresh
        for a in acts:
            a.setdefault("prefix", [])
        for a in acts[:args.max_actions]:
            if a["skipped"]:
                edges.append({"label": a["label"], "cmd": a["cmd"], "kind": "skipped", "to": {"id": "SKIPPED"},
                              "steps": [], "note": a["skipped"]})
                continue
            if self.budget.out_of_time():
                truncated = True
                break
            steps = prep + a["prefix"] + [a["cmd"]]
            try:
                inst.reach_screen(screen, prep + a["prefix"])
                try:
                    inst.expect_ok(a["cmd"])
                except ReplayFailed as e:
                    # tap-text compares the label as the app has it; `nodes tree` flattens tabs and line breaks
                    if not a["cmd"].startswith("tap-text") or "not found" not in str(e):
                        raise
                    a["cmd"] = f"tap {a['x']} {a['y']}"
                    steps = prep + a["prefix"] + [a["cmd"]]
                    inst.expect_ok(a["cmd"])
                inst.settle()
                inst.say("pick cancel")
                to = inst.observe()
                if to["id"] == "EXIT":
                    edges.append({"label": a["label"], "cmd": a["cmd"], "kind": "control", "to": {"id": "EXIT"}, "steps": steps,
                                  "note": "the app has no activity left"})
                    continue
                edges.append({"label": a["label"], "cmd": a["cmd"], "kind": "control", "to": to, "steps": steps})
            except ReplayFailed as e:
                edges.append({"label": a["label"], "cmd": a["cmd"], "kind": "control", "to": {"id": "FAILED"}, "steps": steps,
                              "note": str(e)})
            except Gone as e:
                f = self.crash_finding(e.crash, self.full_path(screen, steps))
                findings.append(f)
                edges.append({"label": a["label"], "cmd": a["cmd"], "kind": "control",
                              "to": {"id": "CRASH" if f["kind"] == "crash" else "EXIT"}, "steps": steps,
                              "note": f["detail"].splitlines()[0][:200]})
        if len(acts) > args.max_actions:
            findings.append({"kind": "note", "detail": f"{screen['id']}: {len(acts)} controls, the first {args.max_actions} tried",
                             "path": screen["path"], "build": self.name})
        for e in edges:
            if e["to"]["id"] in aliases:
                e["to"] = {"id": screen["id"]}
        return {"actions": [{k: a[k] for k in ("label", "cmd", "kind", "box", "skipped", "scrolls")} for a in acts],
                "edges": edges, "findings": findings, "truncated": truncated}

    def crash_finding(self, crash, path):
        kind = "crash" if crash["kind"] == "crash" else crash["kind"]
        return {"kind": kind, "detail": crash["detail"], "path": path, "build": self.name, "exit": crash.get("exit"),
                "log": crash.get("log"), "after": crash.get("after")}

    # --- captures
    def capture(self, combos):
        t0 = time.time()
        ids = sorted(self.screens)
        jobs = len(self.instances)
        chunks = max(1, min(len(combos), -(-jobs // max(1, len(ids)))))
        tasks = []
        for sid in ids:
            for c in range(chunks):
                part = combos[c::chunks]
                if part:
                    tasks.append((self.screens[sid], part))
        self.run_on_pool(tasks, self.capture_task)
        self.t_capture = time.time() - t0

    def capture_task(self, inst, task):
        screen, combos = task
        base_dir = os.path.join(self.out, self.shots, screen["id"])
        os.makedirs(base_dir, exist_ok=True)
        for combo in combos:
            name = snapshot.combo_name(combo)
            staged = os.path.join(inst.stage, "capture.png")
            try:
                # Replayed for every combination: the one before may have closed a dialog or moved a scroll position.
                try:
                    inst.reach_screen(screen)
                except ReplayFailed:
                    inst.reach_screen(screen)
                inst.configure(combo)
                settled = inst.settle()
                screen["settled"] = settled if "settled" not in screen else settled and screen["settled"]
                if not settled:
                    screen.setdefault("unsettled", []).append(name)
                inst.expect_ok(f"shot {staged}")
                shutil.move(staged, os.path.join(base_dir, name + ".png"))
                screen["captures"][name] = f"{self.shots}/{screen['id']}/{name}.png"
            except Gone as e:
                with self.lock:
                    self.findings.append(self.crash_finding(e.crash, screen["path"]))
            except ReplayFailed as e:
                self.finding("capture", f"{screen['id']} {name}: {e}", screen["path"])

    def doc(self):
        return {"build": self.name, "apk": os.path.basename(self.apk), "screens": [self.screens[i] for i in self.order],
                "edges": self.edges, "findings": self.findings, "incomplete": self.incomplete,
                "seconds": {"explore": round(self.t_explore, 1), "capture": round(self.t_capture, 1)}}


def repeated(steps, n):
    """The path ends in the same step n times (a counter, a stack of dialogs: a chain that never ends)."""
    return len(steps) >= n and len(set(steps[-n:])) == 1


def title_of(recs):
    """A name for the screen's card: its first text that is not a number."""
    for r in recs:
        if r.text and "v" in r.flags and not r.text.strip().isdigit():
            return r.text[:40]
    return ""


# -------------------------------------------------------------------------------------------- resilience sweep

PERTURBATIONS = ("process-death", "recreate", "rotate", "offline", "permission-deny", "fontscale", "rtl", "dark")
TIME_RE = re.compile(r"\b\d{1,2}:\d{2}\b")


def parse_sweep(spec):
    """'recreate,fontscale=1.5,rtl' (or 'all') -> [(name, parameter)]."""
    plan = []
    for item in [x for x in (spec or "").split(",") if x]:
        if item == "all":
            plan += [(n, None) for n in PERTURBATIONS if (n, None) not in plan]
            continue
        name, _, param = item.partition("=")
        if name not in PERTURBATIONS:
            fail(f"--sweep: unknown perturbation {name!r} (known: {', '.join(PERTURBATIONS)}, all)")
        plan.append((name, param or None))
    return plan


def read_allow(path):
    """Accepted findings: lines of 'RULE SCREEN [DETAIL TEXT]' (RULE and SCREEN are shell patterns, `*` for any; the detail
    is text the finding must contain; # starts a comment)."""
    rules = []
    if not path:
        return rules
    try:
        with open(path) as f:
            for n, line in enumerate(f, 1):
                line = line.split("#", 1)[0].strip()
                if not line:
                    continue
                parts = line.split(None, 2)
                if len(parts) < 2:
                    fail(f"{path}:{n}: expected 'RULE SCREEN [DETAIL]'")
                rules.append({"rule": parts[0], "screen": parts[1], "detail": parts[2] if len(parts) > 2 else "", "used": 0})
    except OSError as e:
        fail(f"cannot read {path}: {e}")
    return rules


def allowed_by(finding, rules):
    import fnmatch
    for r in rules:
        if fnmatch.fnmatchcase(finding["rule"], r["rule"]) and fnmatch.fnmatchcase(finding["screen"], r["screen"]) \
                and r["detail"] in finding["detail"]:
            r["used"] += 1
            return r
    return None


def texts_of(recs):
    return [r.text for r in recs if "v" in r.flags and r.text and not TIME_RE.search(r.text)]


def cut_of(recs):
    return sorted({r.text for r in recs if "t" in r.flags and "v" in r.flags and r.text})


def row_order(recs):
    """Pairs of texts side by side on one row (centres within 24 px, no overlap, each under 60% of the width) -> whether
    the first is left of the second: what a mirrored layout flips."""
    w, _ = screen_size(recs)
    items = [r for r in recs if "v" in r.flags and r.text and r.box[2] - r.box[0] < w * 0.6 and r.box[2] > r.box[0]]
    order = {}
    for i, a in enumerate(items):
        for b in items[i + 1:]:
            if abs(a.centre()[1] - b.centre()[1]) <= 24 and (a.box[2] <= b.box[0] or b.box[2] <= a.box[0]) and a.text != b.text:
                order[(a.text, b.text)] = a.box[0] < b.box[0]
    return order


def offscreen(recs):
    w, h = screen_size(recs)
    return sorted({r.text for r in recs if "v" in r.flags and r.text and (r.box[0] < -2 or r.box[2] > w + 2)})


class Sweep:
    """The perturbations, run on each screen of an Explorer's graph."""

    def __init__(self, ex, plan, args, allow):
        self.ex, self.plan, self.args, self.allow = ex, plan, args, allow
        self.dir = os.path.join(ex.out, "sweep")
        self.checked = []   # {"perturbation", "screen", "case"}
        self.omitted = []   # cases the deadline kept from running
        self.found = []
        self.lock = threading.Lock()

    def run(self):
        shutil.rmtree(self.dir, ignore_errors=True)
        ids = sorted(self.ex.screens)
        t0 = time.time()
        self.ex.run_on_pool([self.ex.screens[i] for i in ids], self.screen)
        self.seconds = time.time() - t0

    def states_of(self, screen):
        """The screen as reached, then with each of its first controls that stay on it tapped once (a counter, a toggle):
        state worth keeping across a perturbation."""
        scrolled = {a["label"] for a in screen["actions"] if a.get("scrolls")}
        out = [[]]
        for e in self.ex.edges:
            if e["from"] == screen["id"] and e["to"] == screen["id"] and e["kind"] == "control" and e["action"] not in scrolled:
                if len(out) <= self.args.sweep_actions and [e["cmd"]] not in out:
                    out.append([e["cmd"]])
        return out

    def screen(self, inst, screen):
        for kind, param in self.plan:
            if self.ex.budget.out_of_time():
                # The time is up: what is left is said, not dropped (a sweep that ran nothing must not look green).
                self.omit(kind, screen)
                continue
            fn = getattr(self, "sw_" + kind.replace("-", "_"))
            inst.cmds = []
            try:
                fn(inst, screen, param)
            except ReplayFailed as e:
                self.note(kind, screen, "replay", f"could not set up the case: {e}", [])
            except Gone as e:
                c = e.crash
                self.note(kind, screen, "crash" if c["kind"] == "crash" else "exit", c["detail"], getattr(inst, "case", []),
                          log=c.get("log"), repro_cmds=list(getattr(inst, "cmds", [])))
            except Exception as e:  # noqa: BLE001  (a bug of the sweep's own is a finding, not a dead worker)
                self.note(kind, screen, "sweep-error", f"{type(e).__name__}: {e}", [])
            finally:
                for undo in ("network online", "permission reset"):
                    try:
                        inst.send(undo)
                    except (snapshot.Closed, OSError, ValueError, AttributeError):
                        break

    def omit(self, kind, screen):
        with self.lock:
            self.omitted.append({"perturbation": kind, "screen": screen["id"]})

    # --- records
    def case(self, kind, screen, label):
        with self.lock:
            self.checked.append({"perturbation": kind, "screen": screen["id"], "case": label})

    def note(self, kind, screen, rule, detail, steps, pre=(), **more):
        path = {"start": screen["path"].get("start"), "steps": list(screen["path"]["steps"]) + list(steps)}
        f = {"kind": "sweep", "perturbation": kind, "rule": rule, "screen": screen["id"], "detail": detail, "path": path,
             "repro": list(pre) + ["restart" + (f" {path['start']}" if path["start"] else "")] + path["steps"],
             "build": self.ex.name}
        f.update(more)
        with self.lock:
            self.found.append(f)

    def read(self, inst, screen, tag):
        settled = inst.settle()
        obs = inst.observe()
        path = os.path.join(self.dir, screen["id"], tag + ".png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        staged = os.path.join(inst.stage, "sweep.png")
        inst.expect_ok(f"shot {staged}")
        shutil.move(staged, path)
        return {"id": obs["id"], "recs": obs["recs"], "texts": texts_of(obs["recs"]), "settled": settled,
                "shot": os.path.relpath(path, self.ex.out)}

    def differ(self, inst, a, b, tag):
        """Why two reads of a screen are not the same state, or None."""
        if a["id"] != b["id"]:
            return f"the screen is {b['id']}, not {a['id']}", None
        if a["texts"] != b["texts"]:
            lost = [t for t in a["texts"] if t not in b["texts"]]
            gained = [t for t in b["texts"] if t not in a["texts"]]
            return "text changed: " + "; ".join([f"'{t}' gone" for t in lost[:4]] + [f"'{t}' appeared" for t in gained[:4]]), None
        if a["settled"] and b["settled"]:
            d = diff_images(inst, os.path.join(self.ex.out, a["shot"]), os.path.join(self.ex.out, b["shot"]), tag,
                            os.path.join(self.dir, a["shot"].split("/")[1], "diff"), self.args.diff_delta, self.args.sweep_pixels,
                            self.ex.out)
            if d.get("differs"):
                return f"it looks different: {d['pixels']} pixels differ", d.get("map")
        return None, None

    # --- state kept across a configuration change, a rebuild or a rotation
    def roundtrip(self, inst, screen, kind, forward, back):
        for steps in self.states_of(screen):
            label = (" + ".join(steps) or "as reached")
            inst.case = steps
            inst.reach_screen(screen, steps)
            tag = f"{kind}-{len(self.checked)}"
            before = self.read(inst, screen, tag + "-before")
            for c in forward + back:
                inst.cmds.append(c)
                inst.expect_ok(c)
                inst.settle()
            after = self.read(inst, screen, tag + "-after")
            self.case(kind, screen, label)
            why, dmap = self.differ(inst, before, after, tag)
            if why:
                self.note(kind, screen, "state-lost", f"after {' and '.join(forward + back)}: {why}", steps,
                          repro_cmds=forward + back, before=before["shot"], after=after["shot"], diff=dmap)

    def sw_process_death(self, inst, screen, param):
        self.roundtrip(inst, screen, "process-death", ["process-death"], [])

    def sw_recreate(self, inst, screen, param):
        self.roundtrip(inst, screen, "recreate", ["recreate"], [])

    def sw_rotate(self, inst, screen, param):
        self.roundtrip(inst, screen, "rotate", ["rotate landscape"], ["rotate portrait"])

    # --- offline: no crash, no wait for ever
    def spinner(self, inst):
        deadline = time.time() + self.args.spinner_seconds
        while True:
            inst.settle()
            recs = inst.observe()["recs"]
            if not any("v" in r.flags and r.cls.endswith("ProgressBar") for r in recs):
                return False
            if time.time() > deadline:
                return True
            time.sleep(0.5)

    def sw_offline(self, inst, screen, param):
        inst.case = []
        self.case("offline", screen, "as reached")
        inst.say("network offline")
        inst.cmds.append("network offline")
        inst.reach(screen["path"])  # the path with no network, from a fresh start
        if self.spinner(inst):
            inst.say("network online")
            inst.reach(screen["path"])
            if not self.spinner(inst):  # a spinner that only offline leaves up
                self.note("offline", screen, "endless-spinner",
                          f"still a progress indicator {self.args.spinner_seconds:g} s after the screen opened with no network "
                          "(online it goes)", [], pre=["network offline"])

    # --- permissions denied
    def sw_permission_deny(self, inst, screen, param):
        """`permission deny` answers the requests pending when it runs, so it follows each state: the controls
        tapped there (a camera button) ask, and are told no. Requests a replay raises before that are not answered."""
        for steps in self.states_of(screen):
            inst.case = steps
            inst.reach_screen(screen, steps)
            inst.cmds.append("permission deny")
            inst.say("permission deny")
            inst.settle()
            inst.observe()
            self.case("permission-deny", screen, " + ".join(steps) or "as reached")

    # --- large text
    def sw_fontscale(self, inst, screen, param):
        scale = float(param or 2.0)
        inst.case = []
        inst.reach_screen(screen)
        inst.settle()
        before = cut_of(inst.observe()["recs"])
        inst.cmds.append(f"fontscale {fmt(scale)}")
        inst.configure(dict(DEFAULT_COMBO, fontscale=scale))
        inst.settle()
        after = inst.observe()
        self.case("fontscale", screen, f"font scale {fmt(scale)}")
        for t in cut_of(after["recs"]):
            if t not in before:
                tag = f"fontscale-{len(self.checked)}"
                shot = self.read(inst, screen, tag)["shot"]
                self.note("fontscale", screen, "text-cut-off", f"'{t}' is cut off at font scale {fmt(scale)}", [],
                          repro_cmds=[f"fontscale {fmt(scale)}"], after=shot)

    # --- right to left
    def sw_rtl(self, inst, screen, param):
        locale = param or "ar-EG"
        inst.case = []
        inst.reach_screen(screen)
        inst.settle()
        ltr = inst.observe()["recs"]
        inst.cmds.append(f"locale {locale}")
        inst.configure(dict(DEFAULT_COMBO, locale=locale))
        inst.settle()
        rtl = inst.observe()
        self.case("rtl", screen, locale)
        shot = None

        def report(rule, detail):
            nonlocal shot
            if shot is None:
                shot = self.read(inst, screen, f"rtl-{len(self.checked)}")["shot"]
            self.note("rtl", screen, rule, detail, [], repro_cmds=[f"locale {locale}"], after=shot)

        before_cut = cut_of(ltr)
        for t in cut_of(rtl["recs"]):
            if t not in before_cut:
                report("text-cut-off", f"'{t}' is cut off with locale {locale}")
        were = offscreen(ltr)
        for t in offscreen(rtl["recs"]):
            if t not in were:
                report("offscreen", f"'{t}' extends past the screen edge with locale {locale}")
        a, b = row_order(ltr), row_order(rtl["recs"])
        same = [k for k in a if k in b and a[k] == b[k]]
        for left, right in same[:3]:
            report("rtl-not-mirrored", f"'{left}' and '{right}' keep their order with locale {locale}: a mirrored layout swaps them")

    # --- dark appearance and the accessibility audit
    def sw_dark(self, inst, screen, param):
        inst.case = []
        inst.reach_screen(screen)
        inst.cmds.append("appearance dark")
        inst.configure(dict(DEFAULT_COMBO, appearance="dark"))
        inst.settle()
        self.case("dark", screen, "dark appearance, a11y audit")
        reply = inst.say("a11y")
        m = re.match(r"\d+ issue\(s\) in \d+ nodes: (.*)", reply)
        if not m:
            return
        shot = None
        for issue in m[1].split(" ; "):
            rule = ("a11y-unlabeled" if "no label" in issue else "a11y-target-size" if "touch target" in issue
                    else "text-cut-off" if "text cut off" in issue else "a11y-image" if "image" in issue else "a11y")
            if shot is None:
                shot = self.read(inst, screen, f"dark-{len(self.checked)}")["shot"]
            self.note("dark", screen, rule, issue.strip(), [], repro_cmds=["appearance dark", "a11y"], after=shot)

    # --- the outcome
    def finish(self):
        if self.omitted:
            by = sorted({o["perturbation"] for o in self.omitted})
            self.note("sweep", {"id": "*", "path": {"start": None, "steps": []}}, "sweep-incomplete",
                      f"the time ran out: {len(self.omitted)} case(s) not run ({', '.join(by)}); raise --max-seconds", [])
        for f in self.found:
            r = allowed_by(f, self.allow)
            f["allowed"] = r is not None
            if r is not None:
                f["allowed_by"] = f"{r['rule']} {r['screen']} {r['detail']}".strip()
        self.found.sort(key=lambda f: (f["allowed"], f["rule"], f["screen"], f["perturbation"], f["detail"]))
        # The same finding found in two states of a screen is one.
        seen, unique = set(), []
        for f in self.found:
            k = (f["rule"], f["screen"], f["perturbation"], f["detail"])
            if k not in seen:
                seen.add(k)
                unique.append(f)
        self.found = unique


def junit(path, sweep):
    """One test case per perturbation and screen checked, passing; one failing case per finding (skipped when allowed)."""
    from xml.sax.saxutils import escape, quoteattr
    cases, failures, skipped = [], 0, 0
    bad = {(f["perturbation"], f["screen"]) for f in sweep.found if not f["allowed"]}
    done = set()
    for c in sorted(sweep.checked, key=lambda c: (c["perturbation"], c["screen"], c["case"])):
        key = (c["perturbation"], c["screen"])
        if key in done or key in {(f["perturbation"], f["screen"]) for f in sweep.found}:
            continue
        done.add(key)
        cases.append(f'<testcase classname={quoteattr("crawl." + c["perturbation"])} name={quoteattr(c["screen"])}/>')
    for o in sweep.omitted:
        cases.append(f'<testcase classname={quoteattr("crawl." + o["perturbation"])} name={quoteattr(o["screen"])}>'
                     f'<skipped message="not run: the time ran out"/></testcase>')
    for f in sweep.found:
        name = f'{f["screen"]}: {f["rule"]}: {f["detail"]}'[:300]
        body = "path to reproduce (drive.py commands):\n" + "\n".join(f["repro"] + f.get("repro_cmds", [])) + \
            f"\n\nfound on {f['build']} build, perturbation {f['perturbation']}"
        if f["allowed"]:
            skipped += 1
            cases.append(f'<testcase classname={quoteattr("crawl." + f["perturbation"])} name={quoteattr(name)}>'
                         f'<skipped message={quoteattr("allowed by: " + f.get("allowed_by", ""))}/></testcase>')
        else:
            failures += 1
            cases.append(f'<testcase classname={quoteattr("crawl." + f["perturbation"])} name={quoteattr(name)}>'
                         f'<failure message={quoteattr(f["detail"][:500])} type={quoteattr(f["rule"])}>{escape(body)}</failure></testcase>')
    xml = (f'<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="crawl-sweep" tests="{len(cases)}" failures="{failures}" '
           f'skipped="{skipped + len(sweep.omitted)}" time="{sweep.seconds:.1f}">\n' + "\n".join(cases) + "\n</testsuite>\n")
    with open(path, "w") as fh:
        fh.write(xml)


# ------------------------------------------------------------------------------------------ comparing builds

def pair_builds(new, base, args):
    """Matches the new build's screens with the base's: by id, else by replaying the new path on the base. Returns
    [{"id", "status": same|changed|new|gone, "base": id|None, "why"}]."""
    pairs = {}
    used = set()
    for sid in new.order:
        if sid in base.screens:
            pairs[sid] = base_id = sid
            used.add(base_id)
    rest = [sid for sid in new.order if sid not in pairs]

    def replay(inst, sid):
        try:
            inst.reach(new.screens[sid]["path"])
            return inst.observe()["id"]
        except ReplayFailed:
            return None
        except Gone as e:
            with base.lock:
                base.findings.append(base.crash_finding(e.crash, new.screens[sid]["path"]))
            return None

    # The new build's paths, replayed on the base build's instances.
    results = base.run_on_pool(rest, replay)
    for sid, got in zip(rest, results):
        if got is not None and got in base.screens and got not in used:
            pairs[sid] = got
            used.add(got)
        else:
            pairs[sid] = None
    out = []
    for sid in new.order:
        b = pairs[sid]
        out.append({"id": sid, "base": b, "status": "new" if b is None else ("same" if b == sid else "changed"),
                    "why": None if b == sid or b is None else f"structure differs: {b} in the base build"})
    for sid in base.order:
        if sid not in used:
            out.append({"id": None, "base": sid, "status": "gone", "why": "no screen of the new build reaches it"})
    return out


def diff_images(inst, a_path, b_path, name, outdir, delta, pixels, root):
    """The control channel's `diff` of two PNGs: (differing pixels, total, map file or None, size changed)."""
    inst.ensure()  # a Gone earlier may have ended this instance
    sa = os.path.join(inst.stage, "a.png")
    sb = os.path.join(inst.stage, "b.png")
    sm = os.path.join(inst.stage, "diff.png")
    shutil.copyfile(a_path, sa)
    shutil.copyfile(b_path, sb)
    if os.path.exists(sm):
        os.remove(sm)
    reply = inst.say(f"diff {sa} {sb} {sm} {delta}")
    m = re.search(r"size (\d+x\d+)(?: (\d+x\d+))? pixels=(\d+) total=(\d+) maxDelta=(\d+)", reply)
    if not m:
        return {"error": reply}
    res = {"pixels": int(m[3]), "total": int(m[4]), "maxDelta": int(m[5]), "sizeChanged": bool(m[2])}
    res["differs"] = bool(m[2]) or res["pixels"] > (pixels * res["total"] if pixels < 1 else pixels)
    if os.path.exists(sm) and res["pixels"]:
        os.makedirs(outdir, exist_ok=True)
        shutil.move(sm, os.path.join(outdir, name + ".png"))
        res["map"] = os.path.relpath(os.path.join(outdir, name + ".png"), root)
    return res


# --------------------------------------------------------------------------------------------------- report

def data_uri(path):
    with open(path, "rb") as f:
        return "data:image/png;base64," + base64.b64encode(f.read()).decode()


def html_report(out, doc):
    """One HTML file: the map (click a screen), a card per screen with its captures, findings, differences."""
    # The first combination's capture of each screen (the map's thumbnail) and difference images are always inlined; the
    # rest of the matrix is inlined while the page stays under 12 MB, else linked (keep the folder with the report).
    total = sum(os.path.getsize(os.path.join(r, f)) for r, _, fs in os.walk(out) for f in fs
                if f.endswith(".png") and ("screens" in r or "diff" in r))
    embed = total <= 12 * 1024 * 1024
    first = set(doc["combos"][:1])
    images = {}

    def img(rel, always=False):
        if not rel:
            return ""
        if embed or always:
            p = os.path.join(out, rel)
            if rel not in images and os.path.exists(p):
                images[rel] = data_uri(p)
            return images.get(rel, "")
        return html.escape(rel)

    shots = {}
    for key in ("screens", "base_screens"):
        for s in doc.get(key, []):
            shots.setdefault(key, {})[s["id"]] = {c: img(p, c in first) for c, p in s["captures"].items()}
    diffs = {k: {c: {**d, "map": img(d.get("map"), True)} for c, d in v.items()} for k, v in doc.get("diffs", {}).items()}
    sweep_images = {}
    for f in doc["findings"]:
        for k in ("before", "after", "diff"):
            if f.get(k):
                sweep_images[f[k]] = img(f[k], True)
    data = {"screens": [{k: v for k, v in s.items() if k != "captures"} for s in doc["screens"]],
            "edges": doc["edges"], "findings": doc["findings"], "shots": shots.get("screens", {}),
            "baseShots": shots.get("base_screens", {}), "compare": doc.get("compare"), "diffs": diffs,
            "incomplete": doc.get("incomplete", []), "sweep": doc.get("sweep"), "sweepImages": sweep_images, "seconds": doc.get("seconds"), "combos": doc["combos"],
            "baseScreens": [{k: v for k, v in s.items() if k != "captures"} for s in doc.get("base_screens", [])]}
    page = PAGE.replace("__TITLE__", html.escape(doc["apk"])).replace("__DATA__", json.dumps(data).replace("</", "<\\/"))
    with open(os.path.join(out, "report.html"), "w") as f:
        f.write(page)


PAGE = r"""<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>Screen map</title>
<style>
:root{--bg:#fff;--fg:#1d1d1f;--muted:#6e6e73;--line:#d2d2d7;--card:#f5f5f7;--bad:#d70015;--warn:#b25000;--ok:#248a3d;--acc:#0071e3}
@media (prefers-color-scheme:dark){:root{--bg:#1c1c1e;--fg:#f2f2f7;--muted:#98989d;--line:#48484a;--card:#2c2c2e;--bad:#ff6961;--warn:#ffb340;--ok:#30d158;--acc:#0a84ff}}
body{background:var(--bg);color:var(--fg);font:14px/1.4 -apple-system,system-ui,sans-serif;margin:0;padding:16px}
h1{font-size:20px;margin:0 0 4px}h2{font-size:16px;margin:24px 0 8px}.sum{color:var(--muted);margin:0 0 12px}
#map{overflow:auto;border:1px solid var(--line);border-radius:8px;background:var(--card)}
svg text{fill:var(--fg);font-size:11px}svg .node{cursor:pointer}svg .node rect{fill:var(--bg);stroke:var(--line);stroke-width:1.5}
svg .node:hover rect,svg .node.sel rect{stroke:var(--acc);stroke-width:2.5}svg .node.crash rect{stroke:var(--bad)}
svg .node.new rect{stroke:var(--ok);stroke-width:2.5}svg .node.changed rect{stroke:var(--warn);stroke-width:2.5}
svg path.edge{fill:none;stroke:var(--line);stroke-width:1.3;opacity:.6}svg path.edge.hot{stroke:var(--acc);stroke-width:2.2}
svg .elabel{font-size:10px;fill:var(--muted)}svg .special rect{fill:var(--card);stroke-dasharray:4 3}
.card{border:1px solid var(--line);border-radius:8px;padding:12px;margin:12px 0;background:var(--bg)}
.card.sel{outline:2px solid var(--acc)}.card h3{margin:0 0 4px;font-size:15px}code,pre{font:12px ui-monospace,Menlo,monospace}
pre{background:var(--card);padding:6px 8px;border-radius:6px;overflow:auto;margin:4px 0}small,.muted{color:var(--muted)}
.shots{display:flex;gap:10px;flex-wrap:wrap;margin-top:8px}.shots figure{margin:0}
.shots img{max-width:150px;max-height:300px;border:1px solid var(--line);display:block}.shots figcaption{font-size:11px;color:var(--muted);max-width:150px}
.badge{font-size:12px;padding:1px 6px;border-radius:4px;border:1px solid currentColor}.bad{color:var(--bad)}.warn{color:var(--warn)}.ok{color:var(--ok)}
a{color:var(--acc);cursor:pointer}table{border-collapse:collapse}td,th{padding:2px 10px 2px 0;text-align:left;vertical-align:top}
</style>
<h1>Screen map: __TITLE__</h1>
<p class=sum id=sum></p>
<div id=map></div>
<div id=findings></div>
<div id=compare></div>
<div id=cards></div>
<script>
const D=__DATA__;
const E=(t,a,...k)=>{const e=document.createElement(t);for(const[x,v]of Object.entries(a||{}))e.setAttribute(x,v);for(const c of k)e.append(c);return e};
const S=Object.fromEntries(D.screens.map(s=>[s.id,s]));
const status=Object.fromEntries((D.compare||[]).filter(p=>p.id).map(p=>[p.id,p.status]));
document.getElementById('sum').textContent=D.screens.length+' screens, '+D.edges.length+' transitions, '+D.findings.filter(f=>f.kind==='crash').length+' crash(es), '+D.combos.length+' capture combination(s) per screen'+(D.seconds?'; walk '+D.seconds.explore+' s, captures '+D.seconds.capture+' s':'')+(D.incomplete.length?'. Incomplete: '+D.incomplete.join('; '):'')+'.';
// Layout: a column per depth, screens in discovery order; EXIT/CRASH/SKIPPED targets are not drawn as nodes.
const cols={};D.screens.forEach(s=>(cols[s.depth]=cols[s.depth]||[]).push(s));
const W=150,H=84,GX=90,GY=26,pos={};
Object.entries(cols).forEach(([d,l])=>l.forEach((s,i)=>pos[s.id]={x:20+d*(W+GX),y:20+i*(H+GY)}));
const maxCol=Math.max(...Object.keys(cols).map(Number),0),maxRows=Math.max(...Object.values(cols).map(l=>l.length),1);
const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');
svg.setAttribute('width',40+(maxCol+1)*(W+GX));svg.setAttribute('height',40+maxRows*(H+GY));
const NS='http://www.w3.org/2000/svg';const N=(t,a,...k)=>{const e=document.createElementNS(NS,t);for(const[x,v]of Object.entries(a||{}))e.setAttribute(x,v);for(const c of k)e.append(c);return e};
const edgeEls=[];
const seenPair={};
D.edges.forEach(e=>{if(!pos[e.from]||!pos[e.to])return;const k=e.from+'>'+e.to;if(seenPair[k]){seenPair[k].t.textContent+=', '+e.action;return}
 const a=pos[e.from],b=pos[e.to];let d;
 if(e.from===e.to){d=`M${a.x+W},${a.y+20} c40,-30 40,50 0,40`}
 else{const x1=a.x+W,y1=a.y+H/2,x2=b.x,y2=b.y+H/2;d=b.x>a.x?`M${x1},${y1} C${x1+GX/2},${y1} ${x2-GX/2},${y2} ${x2},${y2}`:`M${a.x+W/2},${a.y+H} C${a.x+W/2},${a.y+H+60} ${b.x+W/2},${b.y-60} ${b.x+W/2},${b.y}`}
 const p=N('path',{d,class:'edge'});const t=N('title');t.textContent=e.action;p.append(t);seenPair[k]={t};svg.append(p);edgeEls.push([e,p])});
function select(id){document.querySelectorAll('.sel').forEach(x=>x.classList.remove('sel'));document.querySelectorAll('[data-id="'+id+'"]').forEach(x=>x.classList.add('sel'));
 edgeEls.forEach(([e,p])=>p.classList.toggle('hot',e.from===id||e.to===id));const c=document.getElementById('card-'+id);if(c)c.scrollIntoView({behavior:'smooth',block:'start'})}
const shotOf=id=>{const s=D.shots[id]||{};return s[D.combos[0]]||Object.values(s)[0]||''};
D.screens.forEach(s=>{const p=pos[s.id];const cls=['node'];if(status[s.id]&&status[s.id]!=='same')cls.push(status[s.id]);
 const g=N('g',{class:cls.join(' '),'data-id':s.id,transform:`translate(${p.x},${p.y})`});g.addEventListener('click',()=>select(s.id));
 g.append(N('rect',{width:W,height:H,rx:8}));const im=shotOf(s.id);if(im)g.append(N('image',{href:im,x:6,y:6,width:40,height:72,preserveAspectRatio:'xMidYMid meet'}));
 const t1=N('text',{x:52,y:22});t1.textContent=s.activity.split('.').pop();const t2=N('text',{x:52,y:38});t2.textContent=(s.title||'').slice(0,18);
 const t3=N('text',{x:52,y:54});t3.textContent=s.id.split('-').pop()+' · '+s.nodes+' nodes';g.append(t1,t2,t3);svg.append(g)});
document.getElementById('map').append(svg);
// Findings
const fd=document.getElementById('findings');
const walkF=D.findings.filter(f=>f.kind!=='note'&&f.kind!=='sweep'),sweepF=D.findings.filter(f=>f.kind==='sweep');
if(walkF.length){fd.append(E('h2',{},'Walk findings'));
 walkF.forEach(f=>{const d=E('div',{class:'card'},E('span',{class:'badge '+(f.kind==='crash'?'bad':'warn')},f.kind),' ',(f.build||''),E('pre',{},f.detail||''),
 E('small',{},'path: '),E('code',{},JSON.stringify(f.path)));fd.append(d)})}
if(D.sweep){fd.append(E('h2',{},'Resilience sweep'));
 fd.append(E('p',{class:'muted'},D.sweep.plan.join(', ')+': '+D.sweep.checked+' cases in '+D.sweep.seconds+' s, '+sweepF.filter(f=>!f.allowed).length+' finding(s), '+sweepF.filter(f=>f.allowed).length+' allowed'+(D.sweep.stale.length?'. Allow-list entries matching nothing: '+D.sweep.stale.join('; '):'')+'.'));
 sweepF.forEach(f=>{const c=E('div',{class:'card'},E('span',{class:'badge '+(f.allowed?'warn':'bad')},f.allowed?'allowed':f.rule),' ',E('b',{},f.perturbation),' · ',f.rule+' · ',
   (S[f.screen]?E('a',{onclick:'select("'+f.screen+'")'},f.screen):f.screen),E('div',{},f.detail),
   E('small',{},'reproduce with drive.py: '),E('pre',{},f.repro.concat(f.repro_cmds||[]).join('\n')));
  const row=E('div',{class:'shots'});[['before',f.before],['after',f.after],['difference',f.diff]].forEach(([k,v])=>{if(v){const im=D.sweepImages[v];if(im)row.append(E('figure',{},E('img',{src:im}),E('figcaption',{},k)))}});
  c.append(row);fd.append(c)})}
// Comparison
const cp=document.getElementById('compare');
if(D.compare){cp.append(E('h2',{},'Against the base build'));
 const t=E('table',{});D.compare.forEach(p=>{const cl=p.status==='same'?'ok':p.status==='changed'?'warn':'bad';
  t.append(E('tr',{},E('td',{},E('span',{class:'badge '+cl},p.status)),E('td',{},p.id||p.base),E('td',{class:'muted'},p.why||'')))});cp.append(t);
 D.compare.filter(p=>p.status!=='same'&&p.id&&D.diffs[p.id]).forEach(p=>{const d=D.diffs[p.id];const row=E('div',{class:'shots'});
  Object.entries(d).forEach(([c,x])=>{if(x.map||x.differs){const b=(D.baseShots[p.base]||{})[c],n=(D.shots[p.id]||{})[c];
   if(b)row.append(E('figure',{},E('img',{src:b}),E('figcaption',{},'base, '+c)));if(n)row.append(E('figure',{},E('img',{src:n}),E('figcaption',{},'new, '+c)));
   row.append(E('figure',{},x.map?E('img',{src:x.map}):'',E('figcaption',{},'difference: '+(x.error||x.pixels+' px differ'))))}});
  cp.append(E('h3',{},p.id),row)});
 D.compare.filter(p=>p.status==='gone').forEach(p=>cp.append(E('p',{class:'muted'},'Gone: '+p.base)))}
// Cards
const cd=document.getElementById('cards');cd.append(E('h2',{},'Screens'));
D.screens.forEach(s=>{const c=E('div',{class:'card',id:'card-'+s.id,'data-id':s.id});
 const st=status[s.id];
 c.append(E('h3',{},s.id,' ',st&&st!=='same'?E('span',{class:'badge '+(st==='new'?'ok':'warn')},st):''),
  E('div',{class:'muted'},s.activity+' · depth '+s.depth+' · '+s.nodes+' nodes'+(s.settled===false?' · never settled (animates)':'')+(s.flaky?' · FLAKY: '+s.flaky:'')));
 const steps=[(s.path.start?'restart '+s.path.start:'restart')].concat(s.path.steps);
 c.append(E('small',{},'replay with drive.py: '),E('pre',{},steps.join('\n')));
 const out=D.edges.filter(e=>e.from===s.id);
 if(out.length){const t=E('table',{});out.forEach(e=>{const a=E('a',{},e.to||'(not followed)');if(S[e.to])a.onclick=()=>select(e.to);
   t.append(E('tr',{},E('td',{},e.action),E('td',{},e.kind==='skipped'?E('span',{class:'warn'},'skipped'):a),E('td',{class:'muted'},e.note||'')))});c.append(t)}
 const row=E('div',{class:'shots'});Object.entries(D.shots[s.id]||{}).forEach(([k,v])=>row.append(E('figure',{},E('img',{src:v,loading:'lazy'}),E('figcaption',{},k))));c.append(row);cd.append(c)});
</script>
"""


# ------------------------------------------------------------------------------------------------------ main

def parse(argv):
    if "--" in argv:
        i = argv.index("--")
        argv, tail = argv[:i], argv[i + 1:]
    else:
        tail = []
    p = argparse.ArgumentParser(prog="crawl.py", description=__doc__.split("\n\n")[0], add_help=True)
    p.add_argument("apk")
    p.add_argument("--out", required=True)
    p.add_argument("--base", help="another build of the app, walked the same way and compared")
    p.add_argument("--base-option", default="", help="apksim options for the base build's launches only (with no --base: the same APK)")
    p.add_argument("--start", action="append", help="a deep link to start a walk from (repeatable); default the launcher activity")
    p.add_argument("--max-screens", type=int)
    p.add_argument("--max-seconds", type=float)
    p.add_argument("--max-depth", type=int)
    p.add_argument("--stable-ms", type=int, default=200, help="a screen is read until two reads this far apart agree (loads that settle does not wait for)")
    p.add_argument("--max-actions", type=int, default=40, help="controls tried on one screen")
    p.add_argument("--max-repeat", type=int, default=4, help="a screen reached by this many identical steps in a row is not followed")
    p.add_argument("--max-scrolls", type=int, default=4, help="scroll-downs looking for more controls on one screen")
    p.add_argument("--skip-text", action="append", help="regex of labels never tapped (replaces the default list; repeatable)")
    p.add_argument("--tap-unlabeled", action="store_true", help="also tap controls with no text or description")
    p.add_argument("--fill", help="type this text into the text fields of each screen (otherwise they are left alone)")
    p.add_argument("--matrix", action="append", default=[], metavar="AXIS=V,V", help="captures: device, appearance, locale, fontscale")
    p.add_argument("--matrix-file", help="a snapshot spec: its matrix and exclude")
    p.add_argument("--no-captures", action="store_true")
    p.add_argument("--setting", action="append", default=[])
    p.add_argument("--sdcard-file", action="append", default=[])
    p.add_argument("--keep-data", action="store_true", help="use the data slot as it is (one instance, no --fresh)")
    p.add_argument("-j", "--jobs", type=int, default=3)
    p.add_argument("--diff-delta", type=int, default=8)
    p.add_argument("--diff-pixels", type=float, default=0, help="share (or count, from 1) of differing pixels allowed")
    p.add_argument("--sweep", help=f"resilience sweep after the walk: {','.join(PERTURBATIONS)} or all (fontscale=1.5, rtl=he-IL take a value)")
    p.add_argument("--allow", help="file of accepted sweep findings: lines of 'RULE SCREEN [DETAIL TEXT]', * for any")
    p.add_argument("--junit", help="JUnit XML of the sweep (default OUT/junit.xml): a failing case per finding")
    p.add_argument("--sweep-actions", type=int, default=3, help="states per screen the state perturbations try: as reached, then after each of this many controls that stay on it minus one")
    p.add_argument("--sweep-pixels", type=float, default=0.002, help="share of pixels that may differ between a screen before and after a perturbation")
    p.add_argument("--spinner-seconds", type=float, default=6, help="how long a progress indicator may stay up offline")
    args = p.parse_args(argv)
    args.apksim = list(tail)
    for s in args.setting:
        args.apksim += ["--setting", s]
    for s in args.sdcard_file:
        args.apksim += ["--sdcard-file", s]
    if args.keep_data:
        args.jobs = 1
        if args.base or args.base_option:
            fail("--keep-data cannot be used with --base: two walks cannot start from one prepared data slot")
    if args.jobs < 1:
        fail("-j takes 1 or more")
    return args


def matrix_of(args):
    matrix, exclude = {}, []
    if args.matrix_file:
        try:
            with open(args.matrix_file) as f:
                spec = json.load(f)
        except (OSError, ValueError) as e:
            fail(f"cannot read {args.matrix_file}: {e}")
        matrix, exclude = dict(spec.get("matrix", {})), spec.get("exclude", [])
    for m in args.matrix:
        k, _, v = m.partition("=")
        if k not in AXES or not v:
            fail(f"--matrix takes AXIS=V,V with an axis of {', '.join(AXES)}")
        matrix[k] = v.split(",")
    return snapshot.expand_matrix(matrix, exclude)


def main():
    args = parse(sys.argv[1:])
    apk = os.path.abspath(args.apk)
    if not os.path.isfile(apk):
        fail(f"no APK {apk}")
    base_apk = os.path.abspath(args.base) if args.base else (apk if args.base_option else None)
    if base_apk and not os.path.isfile(base_apk):
        fail(f"no APK {base_apk}")
    combos = [] if args.no_captures else matrix_of(args)
    out = os.path.abspath(args.out)
    for d in ("screens", "base-screens", "diff"):
        shutil.rmtree(os.path.join(out, d), ignore_errors=True)
    os.makedirs(out, exist_ok=True)
    t0 = time.time()
    new = Explorer("new", apk, args, out, [])
    base = None
    try:
        new.pool(args.jobs)
        new.explore()
        log(f"[new] {len(new.screens)} screens, {len(new.edges)} transitions in {new.t_explore:.1f} s")
        if combos:
            new.capture(combos)
            log(f"[new] {len(combos)} combination(s) captured in {new.t_capture:.1f} s")
        doc = new.doc()
        doc["combos"] = [snapshot.combo_name(c) for c in combos]
        sweep = None
        if args.sweep:
            sweep = Sweep(new, parse_sweep(args.sweep), args, read_allow(args.allow))
            sweep.run()
            sweep.finish()
            doc["sweep"] = {"plan": [n if not v else f"{n}={v}" for n, v in sweep.plan], "checked": len(sweep.checked),
                            "seconds": round(sweep.seconds, 1), "allow": args.allow,
                            "stale": [f"{r['rule']} {r['screen']} {r['detail']}".strip() for r in sweep.allow if not r["used"]]}
            doc["findings"] += sweep.found
            log(f"[sweep] {len(sweep.checked)} cases in {sweep.seconds:.1f} s: {len(sweep.found)} finding(s), "
                f"{sum(1 for f in sweep.found if f['allowed'])} allowed")
            for f in sweep.found:
                log(f"{'allowed' if f['allowed'] else 'FINDING':>8}  {f['perturbation']:<15} {f['rule']:<18} {f['screen']}  {f['detail']}")
        if base_apk:
            # The new build's instances end first: they hold data slots, and a base instance would take the next
            # ones (another device identity), and twice the JVMs would be alive.
            new.close()
            base = Explorer("base", base_apk, args, out, shlex.split(args.base_option), shots="base-screens")
            base.pool(args.jobs)
            base.explore()
            log(f"[base] {len(base.screens)} screens in {base.t_explore:.1f} s")
            if combos:
                base.capture(combos)
            pairs = pair_builds(new, base, args)
            diffs = {}
            inst = base.instances[0]
            for p in pairs:
                if not (p["id"] and p["base"]):
                    continue
                d = {}
                ns, bs = new.screens[p["id"]], base.screens[p["base"]]
                unstable = set(ns.get("unsettled", [])) | set(bs.get("unsettled", []))
                if unstable:
                    p["unstable"] = sorted(unstable)  # it animates for ever there: its captures differ from run to run, so they are not compared
                for c in doc["combos"]:
                    a_, b_ = ns["captures"].get(c), bs["captures"].get(c)
                    if c in unstable:
                        continue
                    if not (a_ and b_):
                        doc["findings"].append({"kind": "compare", "build": "new", "path": ns["path"],
                                                "detail": f"{p['id']} {c}: no capture in {'the new' if not a_ else 'the base'} build to compare"})
                        continue
                    if a_ and b_:
                        d[c] = diff_images(inst, os.path.join(out, b_), os.path.join(out, a_), c,
                                           os.path.join(out, "diff", p["id"]), args.diff_delta, args.diff_pixels, out)
                        if d[c].get("error"):
                            doc["findings"].append({"kind": "compare", "build": "new", "path": ns["path"],
                                                    "detail": f"{p['id']} {c}: the image comparison failed: {d[c]['error']}"})
                if d:
                    diffs[p["id"]] = d
                if unstable and p["status"] == "same":
                    p["why"] = "animates for ever in " + ", ".join(sorted(unstable)) + ": those captures are not compared"
                if p["status"] == "same" and any(x.get("differs") for x in d.values()):
                    p["status"] = "changed"
                    p["why"] = "looks different: " + ", ".join(c for c, x in d.items() if x.get("differs"))
            doc["compare"] = pairs
            doc["diffs"] = diffs
            doc["base_screens"] = base.doc()["screens"]
            doc["base_edges"] = base.edges
            doc["findings"] += [f for f in base.findings if f["kind"] != "note"]
            doc["incomplete"] += [f"base: {x}" for x in base.incomplete]
        doc["seconds"]["total"] = round(time.time() - t0, 1)
    finally:
        new.close()
        if base:
            base.close()
    html_report(out, doc)
    with open(os.path.join(out, "crawl.json"), "w") as f:
        json.dump(doc, f, indent=1)
    log(f"report: {os.path.join(out, 'report.html')}")
    if args.sweep:
        junit(args.junit or os.path.join(out, "junit.xml"), sweep)
    crashes = [f for f in doc["findings"] if f["kind"] in ("crash", "compare") or (f["kind"] == "sweep" and not f["allowed"])]
    differs = [p for p in doc.get("compare", []) if p["status"] != "same"]
    for p in differs:
        log(f"{p['status']:>8}  {p['id'] or p['base']}  {p.get('why') or ''}")
    sys.exit(1 if crashes or differs else 0)


if __name__ == "__main__":
    main()
