#!/usr/bin/env python3
"""Explores an APK's screens breadth-first over the control channel and maps them (docs/crawl.md).

Usage: crawl.py APK --out DIR [--base APK2] [--start URL]... [--max-screens N] [--max-seconds S] [--max-depth N]
                [--skip-text REGEX]... [--tap-unlabeled] [--fill TEXT] [--matrix AXIS=V,V]... [--matrix-file SPEC.json]
                [-j N] [--setting NS:KEY=VALUE]... [--sdcard-file PATH=text:TEXT]... [--keep-data] [--keep-app-state]
                [--base-option "APKSIM OPTIONS"] [--sweep LIST ...] [--seed RUN (--only ID,... | --only-file PATH)]
                [--events] [--screen-titles] [-- APKSIM_OPTION...]

Starts N headless instances (`apksim --headless --snapshot`, as scripts/snapshot.py does) and walks the app level by
level: each screen reached is replayed from a fresh start, every control on it is tried in turn, and where it leads
is a screen (activity + structural hash of `nodes tree`, see docs/crawl.md for what that does and does not tell
apart) or a crash or the end of the app. Then every screen is captured in every combination of the matrix
(device, appearance, locale, fontscale: snapshot.py's axes). With --base the other build is walked the same way and
the two are compared: screens new, gone and changed, with a difference image.

Writes OUT/report.html (a clickable map and a card per screen), OUT/crawl.json (graph, paths, captures, findings),
OUT/screens/ (captures) and OUT/logs/. Exit status: 0 when the walk finished, 1 when it found crashes (or, with --base,
differences), 2 for bad arguments or an instance that never started, 143 when stopped (SIGTERM or SIGINT: the walk so
far is written, `"stopped": true`, its instances killed). crawl.json's `unexplored` lists the screens found and not
explored (or explored in part) and `cut_by` what cut the walk short (max-seconds, max-screens, max-depth, stopped):
`--seed OUT --only-file IDS` with those ids goes on from there.

--seed RUN with --only ID,... starts from an earlier walk's crawl.json and walks only the named screens again (and any
new screen they lead to); the rest is carried over with its captures (docs/crawl.md "Seeded walks"). --events adds a
line of JSON per finished screen and level on stdout.
"""
import xml.etree.ElementTree as ET
import argparse, base64, concurrent.futures, hashlib, html, itertools, json, os, re, shlex, shutil, signal, subprocess, sys
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
# A replay step or screen that is not there yet (data from the network, a list that fills in late): wait this long, then
# settle for longer, before trying again (Instance.wait_longer).
SETTLE_LONG = "settle 6000 300"
PATIENCE_S = 1.5
WAYPOINT_SETTLE = "settle 800 60"
SCROLL_SETTLE = "settle 450 60"
DISMISS_LABELS = frozenset({"back", "navigate up", "close", "cancel", "not now", "dismiss"})
LAUNCH_CONFIG_FLAGS = frozenset({"--device", "--dark", "--appearance", "--locale", "--font-scale", "--activity"})
TIME_RE = re.compile(r"\b\d{1,2}:\d{2}\b")
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


# --screen-titles: the screen's title (title_of) is part of its identity, so screens built from one layout but named
# differently (a detail screen per item) are told apart. Off by default: text is not structure (docs/crawl.md).
TITLES_ARE_IDENTITY = False


def structure(activity, recs):
    kids = build(recs)
    w, h = screen_size(recs)
    c = "|".join(canon(recs, kids, i, w, h, False) for i in kids[-1])
    if TITLES_ARE_IDENTITY:
        c += "#title=" + title_of(recs)
    digest = hashlib.sha1((activity + "#" + c).encode()).hexdigest()[:8]
    return f"{activity.rsplit('.', 1)[-1]}-{digest}", len(recs)


def screen_sig(recs):
    """Visible text and interactive-control state of a screen, used to confirm an in-place return or warm restart left
    the UI unchanged. Clock/timer strings (HH:MM) are stripped so live timers do not force cold relaunches."""
    texts = tuple(TIME_RE.sub("", r.text) for r in recs if "v" in r.flags and r.text)
    controls = tuple((r.cls, r.rid, r.flags, r.box, TIME_RE.sub("", r.text), TIME_RE.sub("", r.desc)) for r in recs
                     if "v" in r.flags and any(c in r.flags for c in "ckedK"))
    return (texts, controls)


def dismiss_cmd(recs, skip_re):
    """A single visible Back/Close/Cancel/Not-now control on [recs] that can return to the previous screen, or None."""
    acts = candidates(recs, skip_re, False)
    for a in acts:
        if a["kind"] == "control" and not a["skipped"] and a["label"].strip().lower() in DISMISS_LABELS:
            return a["cmd"]
    return None


# --------------------------------------------------------------------------------------------------- instances

class ReplayFailed(Exception):
    pass


# Files in a data slot that no screen shows, left out of Instance.data_state (paths relative to the slot).
BOOKKEEPING = re.compile(r"(^|/)(cache|code_cache)/|^files/profileInstalled$|^no_backup/androidx\.work\.workdb|-shm$|"
                         r"(^|/)\.lock$")


_MISSING = object()


def shared_prefs(path):
    """A SharedPreferences file's values, {name: (type, value)}, or None when it is not one (or cannot be read)."""
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError):
        return None
    if root.tag != "map":
        return None
    out = {}
    for e in root:
        v = e.get("value")
        if v is None:
            v = tuple(sorted(c.text or "" for c in e)) if e.tag == "set" else (e.text or "")
        out[e.get("name")] = (e.tag, v)
    return out


class Gone(Exception):
    """The instance ended: [crash] says how."""

    def __init__(self, crash):
        super().__init__(crash.get("detail", "the instance ended"))
        self.crash = crash


class Instance(snapshot.Worker):
    """A headless instance and what a walk does with it: replaying paths, reading the screen, capturing."""
    fresh_at_root = False
    last_settled = True

    def __init__(self, i, ctx):
        super().__init__(i, [dict(DEFAULT_COMBO)], ctx)
        self.stage = os.path.join(ctx["stage"], str(i))
        os.makedirs(self.stage, exist_ok=True)
        self.generation = 0
        self.applied = {a: None for a in AXES}  # unknown: a launch option may have set any of them
        self.fresh_at_root = False
        self.last_settled = True
        self.crashes = ctx["crashes"]
        self.slot = None      # the data slot's folders (the slot and its .device), read from the launch's log
        self.baseline = None  # what they held once this launch's app had started (see reach)

    def launch(self):
        if STOPPING.is_set():  # the walk is being stopped: no instance is started again, no copy made again
            raise Gone({"detail": "the walk was stopped"})
        if self.ctx.get("restore"):
            # --keep-data: this instance's copy of the prepared slot, made again from it (the data the app had at launch).
            if self.proc is not None and self.proc.poll() is None:
                self.quit()
            self.own_options = self.ctx["restore"](self.i)
        self.generation += 1
        self.slot = self.baseline = None
        self.log = os.path.join(self.ctx["out"], "logs", f"{self.ctx['name']}-instance-{self.i}.{self.generation}.log")
        self.proc = self.file = self.sock = None
        has_override = any(o in LAUNCH_CONFIG_FLAGS for o in self.ctx["apksim"])
        self.applied = {a: None for a in AXES} if has_override else dict(DEFAULT_COMBO)
        self.fresh_at_root = not has_override
        self.last_settled = True
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

    def settle(self, cmd=SETTLE):
        """Waits for the screen to be still; a screen that animates for ever is read as it is."""
        ok = not self.say(cmd).startswith("error")
        self.last_settled = ok
        return ok

    def wait_longer(self):
        """More time for what a replay expects and does not find yet: a label that comes with the network's answer, a
        screen still loading. A real wait first, as `settle` does not wait for a request or a coroutine's delay()."""
        time.sleep(self.ctx.get("patience_s", PATIENCE_S))
        self.say(SETTLE_LONG)

    def step(self, cmd, alt=None, settle_cmd=SETTLE):
        """One replay step, tried again after wait_longer when it fails (a reply that failed did nothing), then as [alt]
        (the same tap by its coordinates, for a label that live data changed) when given."""
        try:
            self.expect_ok(cmd)
        except ReplayFailed:
            self.wait_longer()
            try:
                self.expect_ok(cmd)
            except ReplayFailed:
                if not alt or alt == cmd:
                    raise
                self.tap_checked(alt)
        if settle_cmd:
            self.settle(settle_cmd)

    def tap_checked(self, cmd):
        """A tap by coordinates standing in for a label that is gone: only on a control there now that the skip list
        allows (a layout that moved must not put a Delete or Sign out under the old point)."""
        m = re.fullmatch(r"tap (-?\d+) (-?\d+)", cmd)
        if m:
            x, y = int(m[1]), int(m[2])
            here = [a for a in candidates(parse_tree(self.say("nodes tree")), self.ctx["skip_re"], True)
                    if a["kind"] == "control" and a["box"][0] <= x < a["box"][2] and a["box"][1] <= y < a["box"][3]]
            if not here:
                raise ReplayFailed(f"{cmd!r}: no control there now")
            inner = min(here, key=lambda a: (a["box"][2] - a["box"][0]) * (a["box"][3] - a["box"][1]))
            if inner["skipped"]:
                raise ReplayFailed(f"{cmd!r}: the control there now is {inner['label']!r} ({inner['skipped']})")
        return self.expect_ok(cmd)

    # --- reading
    def observe(self, expected_id=None):
        """The screen as it is: read until two reads in a row agree (a list still composing, a transition ending).
        When [expected_id] is given (confirming arrival at a known screen), a matching first read returns immediately."""
        prev = None
        for attempt in range(4):
            act = self.say("activity")
            if act == "none":
                return {"id": "EXIT", "activity": "none", "nodes": 0, "recs": []}
            recs = parse_tree(self.say("nodes tree"))
            sid, n = structure(act, recs)
            if expected_id is not None and sid == expected_id:
                break
            if sid == prev:
                break
            prev = sid
            if attempt < 3:
                # `settle` does not wait for a coroutine's delay() or a posted Runnable: a screen that fills in a
                # moment after it is still is read again until two reads --stable-ms apart agree.
                time.sleep(self.ctx.get("stable_ms", 200) / 1000)
        return {"id": sid, "activity": act, "nodes": n, "recs": recs}

    def wait_waypoint(self, want_id, prev_id=None, timeout=1.5):
        """Polls `activity` + `nodes tree` with backoff until [want_id] appears or two reads agree on a new screen."""
        delays = (0.06, 0.08, 0.12, 0.18, 0.25, 0.35, 0.45)
        deadline = time.monotonic() + timeout
        last = None
        seen_other = None
        for d in delays:
            rem = deadline - time.monotonic()
            if rem <= 0:
                break
            time.sleep(min(d, rem))
            act = self.say("activity")
            if act == "none":
                return {"id": "EXIT", "activity": "none", "nodes": 0, "recs": []}
            recs = parse_tree(self.say("nodes tree"))
            sid, n = structure(act, recs)
            last = {"id": sid, "activity": act, "nodes": n, "recs": recs}
            if sid == want_id:
                return last
            if sid != prev_id:
                if sid == seen_other:
                    return last
                seen_other = sid
        return last or self.observe(expected_id=want_id)

    # --- moving
    def configure(self, combo):
        for axis in AXES:
            if fmt(self.applied[axis]) != fmt(combo[axis]):
                self.expect_ok(f"{axis} {fmt(combo[axis])}")
                self.applied[axis] = combo[axis]

    # --- the app's data between replays
    def slot_dirs(self):
        """The data slot this launch took and its device folder (/sdcard, Settings), from the log's `data slot` line."""
        if self.slot is None:
            try:
                with open(self.log, errors="replace") as f:
                    m = re.search(r"^\[apksim\] data slot (.+) pid \d+$", f.read(), re.M)
            except OSError:
                m = None
            if not m:
                if not getattr(self, "warned_slot", False):
                    self.warned_slot = True
                    log(f"instance {self.i}: no `data slot` line in {self.log}: its replays are not checked for data "
                        "the app wrote (as with --keep-app-state)")
                self.slot = []
                return []
            self.slot = [m[1], m[1] + ".device"]
        return self.slot

    def data_state(self):
        """What the slot holds, {file: state}: a SharedPreferences file as its values ({key: value}), any other as
        (size, mtime), so a write anywhere changes it. Left out: what no screen reads (BOOKKEEPING): an app's caches,
        AndroidX's profile installer (which writes once some seconds after every start, so each new launch would want
        another), WorkManager's own database, SQLite's shared memory index (-shm, which readers write too), the slot's
        lock."""
        out = {}
        for root in self.slot_dirs():
            for d, dirs, files in os.walk(root):
                dirs.sort()
                for n in sorted(files):
                    p = os.path.join(d, n)
                    rel = os.path.relpath(p, root)
                    if BOOKKEEPING.search(rel):
                        continue
                    prefs = shared_prefs(p) if rel.startswith("shared_prefs/") and n.endswith(".xml") else None
                    if prefs is not None:
                        out[rel] = prefs
                        continue
                    try:
                        st = os.stat(p)
                    except OSError:
                        continue
                    out[rel] = (st.st_size, st.st_mtime_ns)
        # Kept by the instance, not the slot, and outliving `restart` too: a permission request no one answered (the
        # walk never answers one) and runtime grants made since the launch.
        out["<permission>"] = self.say("permission")
        return out

    def changed_files(self):
        """What was written since the baseline (see reach): files added, removed or written, and for preferences the
        keys whose value changed (`file#key`). A preference key that appears is a change unless it appeared with that
        value while a path was replayed (learn_defaults): opening a settings screen writes its defaults (a fresh slot
        has none), which every replay through it repeats, while a control tried on a screen (a toggle) is not on a path."""
        if self.baseline is None:
            return []
        before, now = self.baseline, self.data_state()
        out = []
        for f in sorted(before.keys() | now.keys()):
            a, b = before.get(f), now.get(f)
            if a == b:
                continue
            if isinstance(b, dict) and (a is None or isinstance(a, dict)):
                for k in sorted((a or {}).keys() | b.keys()):
                    if (a or {}).get(k) == b.get(k):
                        continue
                    if k not in (a or {}) and k in b:
                        with self.ctx["lock"]:
                            known = self.ctx["first_values"].get((f, k), _MISSING)
                        if known == b[k]:
                            continue
                    out.append(f"{f}#{k}")
                continue
            out.append(f)
        return out

    def fresh_start(self):
        """A running instance whose app has not written to its data since its launch's first replay: this one, or a
        new launch on an emptied slot. A case that sets state on the instance before replaying (`network offline`)
        calls this first and replays with relaunch=False, so the replay cannot replace the instance it set up."""
        self.ensure()
        try:
            changed = self.changed_files() if self.from_launch_data() else []
        except Gone:
            self.close()
            self.launch()
            return
        if changed:
            self.quit()
            self.launch()
            with self.ctx["lock"]:
                self.ctx["relaunches"][0] += 1
                for f in changed:
                    self.ctx["relaunch_files"][f] = self.ctx["relaunch_files"].get(f, 0) + 1

    def from_launch_data(self):
        """Whether replays start from the data the app had at its launch: an emptied slot (--fresh) or, with --keep-data,
        a new copy of the prepared one. Not with --keep-app-state."""
        return bool(self.ctx.get("fresh") or self.ctx.get("restore")) and not self.ctx.get("keep_state")

    def learn_defaults(self):
        """After a path's steps (navigation, before any control is tried): preference keys that appeared since the
        baseline are the defaults the screens on the way wrote, which every replay through them writes again; their
        values are kept as the walk's own for those keys (changed_files)."""
        if self.baseline is None:
            return
        now = self.data_state()
        with self.ctx["lock"]:
            for f, b in now.items():
                a = self.baseline.get(f)
                if isinstance(b, dict) and (a is None or isinstance(a, dict)):
                    for k, v in b.items():
                        if k not in (a or {}):
                            self.ctx["first_values"].setdefault((f, k), v)

    def reach(self, path, extra=(), relaunch=True, patient=False, alt=None, waypoints=None, explore_fast=False):
        """Back to a screen: the default configuration, a fresh start (at the path's deep link), the path's steps.
        From the data the app had at its start: a launch whose app has written to its data since is replaced by a
        new one on an emptied slot (--fresh) first, or with --keep-data on a new copy of the prepared slot.
        [patient] waits longer for the start screen first; [alt] is the path's steps with text taps as coordinates,
        each tried when its step fails twice (Explorer.alt_paths)."""
        self.fresh_start() if relaunch else self.ensure()
        self.configure(DEFAULT_COMBO)
        if self.fresh_at_root and not path.get("start") and not self.ctx.get("record_deps"):
            self.fresh_at_root = False
            self.settle(WAYPOINT_SETTLE if (explore_fast and path["steps"]) else SETTLE)
        else:
            self.fresh_at_root = False
            self.say(f"clock {CLOCK}")
            self.expect_ok("restart" + (f" {path['start']}" if path.get("start") else ""))
            self.settle(WAYPOINT_SETTLE if (explore_fast and path["steps"]) else SETTLE)
        if patient:
            self.wait_longer()
        if self.baseline is None and self.from_launch_data():
            # Once the app's start has written what it writes at every start (taken after a restart and a settle).
            self.baseline = self.data_state()
        steps = list(path["steps"])
        alt = list(alt) if alt and len(alt) == len(steps) else [None] * len(steps)
        last_obs = None
        prev_wp = None
        unsettled = self.ctx["unsettled"]
        for idx, (step, other) in enumerate(zip(steps, alt)):
            want = waypoints[idx] if (waypoints and idx < len(waypoints)) else None
            is_last = (idx == len(steps) - 1) and not extra
            self.step(step, other, settle_cmd=None)
            last_obs = None
            if want is not None and want in unsettled:
                last_obs = self.wait_waypoint(want, prev_id=prev_wp)
                self.last_settled = False
            elif explore_fast and step.startswith("drag "):
                self.settle(SCROLL_SETTLE)
            elif want is not None and not is_last:
                ok = self.settle(WAYPOINT_SETTLE)
                if not ok:
                    last_obs = self.wait_waypoint(want, prev_id=prev_wp, timeout=0.4)
                    if last_obs["id"] == want:
                        with self.ctx["lock"]:
                            unsettled.add(want)
            else:
                ok = self.settle(SETTLE)
                if not ok and want is not None:
                    with self.ctx["lock"]:
                        unsettled.add(want)
            prev_wp = want
        if self.from_launch_data():
            self.learn_defaults()
        for step in extra:
            self.step(step, settle_cmd=SCROLL_SETTLE if (explore_fast and step.startswith("drag ")) else SETTLE)
            last_obs = None
        self.say("pick cancel")
        return last_obs

    def read_deps(self):
        """--record-deps: the classes whose code ran and the resources resolved since the last `restart` (each opens a
        span: dex.Probes, ResourceHits), as {classes, resources}, classes as the probes name them (`a.B$c`)."""
        return {"classes": deps_list(self.say("deps read")), "resources": deps_list(self.say("deps resources"))}

    def read_start_deps(self):
        """--record-deps: the app-start span (launch to the first activity), kept by the instance once per process."""
        deadline = time.time() + 30
        while True:
            reply = self.say("deps start")
            if not reply.startswith("error") or time.time() > deadline:
                break
            time.sleep(0.2)
        return {"classes": deps_list(reply), "resources": deps_list(self.say("deps start resources")),
                "packages": self.say("deps")}

    def reach_screen(self, screen, extra=(), patient=False, alt=None, lenient=False, waypoints=None, explore_fast=False, relaunch=True):
        """Replays the screen's path and checks where it ended; [extra] steps (scrolls, a fill) follow the check.
        A screen of another structure is read again after wait_longer. [lenient]: one of another structure in the
        same activity will do (a screen whose content changes between launches), returned with "drift" set."""
        last_obs = self.reach(screen["path"], relaunch=relaunch, patient=patient, alt=alt,
                              waypoints=waypoints, explore_fast=explore_fast)
        got = last_obs if (last_obs is not None and last_obs["id"] == screen["id"]) else self.observe(expected_id=screen["id"])
        if got["id"] != screen["id"] and not lenient and relaunch:
            self.wait_longer()
            got = self.observe(expected_id=screen["id"])
        if got["id"] != screen["id"]:
            if not (lenient and got["activity"] == screen["activity"]):
                raise ReplayFailed(f"replay reached {got['id']}, not {screen['id']}")
            got["drift"] = True
        for step in extra:
            self.step(step, settle_cmd=SCROLL_SETTLE if (explore_fast and step.startswith("drag ")) else SETTLE)
        return got


def fmt(v):
    return snapshot.fmt(v)


class DepsOff(RuntimeError):
    """An instance answered `deps` with an error (the probes are off): the walk cannot record what it was asked to."""


def deps_list(reply):
    """`deps N: a,b` / `deps resources N: a,b` -> [a, b]; an error answer stops the walk (the probes are off)."""
    if reply.startswith("error") or ":" not in reply:
        raise DepsOff(f"--record-deps: the instance answered {reply!r}")
    body = reply.split(":", 1)[1].strip()
    return sorted(x for x in body.split(",") if x)


class DepsMap:
    """--record-deps: probed class -> source files, from the APK's DEX (scripts/affected.py's map: the DEX source file
    name, its package, and Kotlin's SMAP for inlined code), and whether that map can be trusted."""

    def __init__(self, apk, packages):
        import affected  # scripts/affected.py
        self.packages = packages
        self.map = affected.source_map([apk], packages)
        # A class counts as sourceless when the map gives it no file even through its outer class (D8's synthetic
        # lambdas name none of their own, and the map takes their outer class's).
        total = len(self.map)
        nosource = sum(1 for keys in self.map.values() if not keys)
        renamed = sum(1 for c in self.map if re.fullmatch(r"[a-z]{1,2}", c.rsplit("/", 1)[-1].split("$")[0]))
        why = []
        if total == 0:
            why.append(f"the APK has no classes in the probed packages {','.join(packages)}")
        else:
            if nosource > total * 0.1:
                why.append(f"{nosource} of {total} classes in {','.join(packages)} name no source file (R8 without "
                           "-keepattributes SourceFile?): their files cannot be told")
            if renamed > total * 0.3:
                why.append(f"{renamed} of {total} classes in {','.join(packages)} have one- or two-letter names (R8 "
                           "renaming, no mapping file): classes are merged and inlined, so files may be missing")
        self.unreliable = "; ".join(why) or None

    def sources(self, classes):
        out = set()
        for c in classes:
            out.update(self.map.get(c.replace(".", "/"), ()))
        return sorted(out)

    def shape(self, raw):
        return {"classes": raw["classes"], "sources": self.sources(raw["classes"]), "resources": raw["resources"]}


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
    return f"drag {x} {t + (b - t) * 3 // 4} {x} {t + (b - t) // 4} 300"  # no fling: the same scroll every run


def apk_package(apk):
    """The package an APK's binary manifest names (scripts/subsystem-audit.py's reader)."""
    import importlib.util, zipfile
    spec = importlib.util.spec_from_file_location("subsystem_audit", os.path.join(os.path.dirname(os.path.abspath(__file__)), "subsystem-audit.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    with zipfile.ZipFile(apk) as z:
        return mod.manifest_info(z.read("AndroidManifest.xml"))["package"]


def slot_serial(slot):
    """The serial apksim gives a slot with no --serial (DeviceIdentity.defaultSerial)."""
    h = hashlib.sha256(f"apksim-serial:{slot}".encode()).digest()
    digits = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    out = [digits[b % 36] for b in h[:16]]
    out[0], out[1] = chr(ord("A") + h[16] % 26), chr(ord("0") + h[17] % 10)
    return "".join(out)


def slot_android_id(slot):
    """The ANDROID_ID apksim gives a slot whose settings have none (InstallState.slotAndroidId)."""
    return hashlib.sha256(f"apksim-android-id:{slot}".encode()).digest()[:8].hex()


class KeptSlot:
    """--keep-data: the prepared data slot (the one the caller's own launch takes: `--data-slots NAME` gives NAME-0, else
    `default`), never written by the walk. Each instance runs on a copy of its own (an APFS clone, or a reflink where the
    file system has them), made again from the prepared slot at each of its launches, so every replay starts from the
    data the app had at launch, as on an emptied slot without --keep-data; and any number of instances (-j) start
    signed in. The copies are slots `kTOKENI-0` in the app's data folder, removed at the end. Each copy keeps the
    prepared slot's device identity: its ANDROID_ID is in its settings (or is seeded as the prepared slot's), its serial
    is passed as --serial (both come from a slot's name otherwise)."""

    def __init__(self, apk, options):
        opts, name, i = [], None, 0
        while i < len(options):
            if options[i] == "--data-slots" and i + 1 < len(options):
                name = options[i + 1]
                i += 2
                continue
            opts.append(options[i])
            i += 1
        self.source_name = f"{name}-0" if name else "default"
        try:
            pkg = apk_package(apk)
        except Exception as e:  # noqa: BLE001
            fail(f"--keep-data: could not read the APK's package: {e}")
        state = os.environ.get("APKSIM_HOME") or (os.path.expanduser("~/.apksim") if os.path.exists(
            os.path.join(ROOT, "work", "release")) else os.path.join(ROOT, "work"))
        self.data = os.path.join(state, "cache", "data", pkg)
        self.source = os.path.join(self.data, self.source_name)
        if not os.path.isdir(self.source):
            fail(f"--keep-data: no prepared data slot at {self.source} (prepare it with a launch of the same "
                 f"--data-slots{' ' + name if name else ''} first)")
        self.check_unheld()
        self.sweep_stale()
        self.token = os.urandom(4).hex()
        if "--serial" not in opts:
            opts += ["--serial", slot_serial(self.source_name)]
        if not any(o.startswith("secure:android_id=") for o in opts) and not self.has_android_id():
            opts += ["--setting", f"secure:android_id={slot_android_id(self.source_name)}"]
        self.options = opts
        self.made = set()
        self.lock = threading.Lock()

    def check_unheld(self):
        """The prepared slot must not be in use (an instance holding it may be writing while it is copied)."""
        import fcntl
        lock = os.path.join(self.source, ".lock")
        try:
            fd = os.open(lock, os.O_RDWR | os.O_CREAT, 0o644)
        except OSError:
            return
        try:
            fcntl.lockf(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            fcntl.lockf(fd, fcntl.LOCK_UN)
        except OSError:
            fail(f"--keep-data: the data slot {self.source} is in use by a running apksim: quit it first")
        finally:
            os.close(fd)

    def sweep_stale(self):
        """Copies a crawl that was killed outright (SIGKILL, a crash) left: clones of the prepared data, which nothing
        else removes. Only copies unused for 6 hours and not held by a running instance."""
        import fcntl
        try:
            names = os.listdir(self.data)
        except OSError:
            return
        for n in names:
            if not re.fullmatch(r"k[0-9a-f]{8}\d+-0", n):
                continue
            d = os.path.join(self.data, n)
            try:
                if time.time() - max(os.path.getmtime(d), os.path.getmtime(os.path.join(d, ".lock"))
                                     if os.path.exists(os.path.join(d, ".lock")) else 0) < 6 * 3600:
                    continue
                fd = os.open(os.path.join(d, ".lock"), os.O_RDWR | os.O_CREAT, 0o644)
            except OSError:
                continue
            try:
                fcntl.lockf(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                continue  # in use
            finally:
                os.close(fd)
            shutil.rmtree(d, ignore_errors=True)
            shutil.rmtree(d + ".device", ignore_errors=True)
            log(f"--keep-data: removed {n}, a copy an earlier crawl left")

    def has_android_id(self):
        try:
            with open(self.source + ".device/settings.properties", errors="replace") as f:
                return any(ln.startswith("secure/android_id=") for ln in f)
        except OSError:
            return False

    def slot(self, i):
        return os.path.join(self.data, f"k{self.token}{i}-0")

    def restore(self, i):
        """Instance i's copy, made afresh from the prepared slot; the options that make its launch take it."""
        dst = self.slot(i)
        with self.lock:
            self.made.add(i)
        for d in (dst, dst + ".device"):
            shutil.rmtree(d, ignore_errors=True)
        for s, d in ((self.source, dst), (self.source + ".device", dst + ".device")):
            if os.path.isdir(s):
                copy_tree(s, d)
        return ["--data-slots", f"k{self.token}{i}"]

    def remove(self):
        with self.lock:
            made, self.made = sorted(self.made), set()
        for i in made:
            for d in (self.slot(i), self.slot(i) + ".device"):
                shutil.rmtree(d, ignore_errors=True)


def copy_tree(src, dst):
    """A copy of a folder, cloned (APFS clonefile, a reflink) where the file system can, else copied."""
    if sys.platform == "darwin":
        r = subprocess.run(["cp", "-cR", src, dst], capture_output=True)
        if r.returncode == 0:
            return
        shutil.rmtree(dst, ignore_errors=True)
    elif shutil.which("cp"):
        r = subprocess.run(["cp", "-a", "--reflink=auto", src, dst], capture_output=True)
        if r.returncode == 0:
            return
        shutil.rmtree(dst, ignore_errors=True)
    shutil.copytree(src, dst, symlinks=True)


class Explorer:
    """One walk of one APK: the graph, the findings and the captures."""

    def __init__(self, name, apk, args, out, extra_options, shots="screens", combos=()):
        self.name, self.apk, self.args, self.out, self.shots = name, apk, args, out, shots
        self.capture_combos = list(combos)
        self.default_combo_name = snapshot.combo_name(DEFAULT_COMBO)
        self.inline_capture = (any(snapshot.combo_name(c) == self.default_combo_name for c in self.capture_combos)
                               and not args.keep_app_state)
        self.explore_fast = not (args.base or args.base_option or args.sweep)
        self.options = list(args.apksim) + list(extra_options)
        # Never the owner's saved sign-ins, and a slot namespace of the crawler's own: --fresh would empty an ordinary
        # launch's `default` slot (--keep-data walks copies of a prepared slot: KeptSlot; an option of the caller's own wins).
        self.options += ["--no-autofill"]
        # Headless walks have no speakers and need not render animating screens at 120 fps (Features.java: an animating
        # screen costs about a full core at 120 fps headless). Caller-supplied options win.
        if "--max-fps" not in self.options:
            self.options += ["--max-fps", "30"]
        if "--audio" not in self.options:
            self.options += ["--audio", "off"]
        # One device identity for every instance (the serial and ANDROID_ID come from the slot's name otherwise, and a
        # screen that shows one would differ between instances, and between builds walked on other slots).
        # Not with --keep-data: the prepared slot's own identity is what its sign-in was made under (KeptSlot passes it on).
        if not args.keep_data and "--serial" not in self.options:
            self.options += ["--serial", "CRAWL0000000000"]
        if not args.keep_data and not any(o.startswith("secure:android_id=") for o in self.options):
            self.options += ["--setting", "secure:android_id=00c0ffee00c0ffee"]
        if not args.keep_data and "--data-slots" not in self.options:
            self.options += ["--data-slots", "crawl"]
        self.kept = KeptSlot(apk, self.options) if args.keep_data else None
        if self.kept:
            self.options = self.kept.options
        self.screens = {}      # id -> screen
        self.waypoints = {}    # id -> list of screen ids (or None) reached after each step of path["steps"]
        self.order = []        # ids in discovery order
        self.edges = []        # {from, action, to, ...}
        self.findings = []     # crashes and the like
        self.lock = threading.Lock()
        self.incomplete = []
        self.skip_re = re.compile("|".join(f"(?:{p})" for p in (args.skip_text or [DEFAULT_SKIP])), re.I)
        self.budget = Budget(args)
        self.t_explore = self.t_capture = 0.0
        self.explored = set()   # ids whose controls were tried in this run
        self.partial = set()    # ids whose controls were tried only in part (the time ran out on them)
        self.seed_unexplored = set()  # a seeded walk: the seed's own unexplored screens (carried as they were)
        self.cut_by = set()     # what left screens unexplored: max-seconds, max-screens, max-depth, stopped
        self.captured_now = set()  # a seeded walk: carried screens captured in this run (the seed, stopped, had none)
        self.announced = set()  # ids --events has told
        self.first_recs = {}    # id -> the records the screen showed when it was found (a flaky screen's controls)
        self.alt_paths = {}     # id -> its path's steps with every tap as coordinates (Instance.reach's alt)
        os.makedirs(os.path.join(out, "logs"), exist_ok=True)
        self.stage = tempfile.mkdtemp(prefix="apksim-crawl-")
        if re.search(r"\s", self.stage):
            fail(f"the temporary folder {self.stage!r} has whitespace in its path: set TMPDIR")
        # Pixel-exact CPU rendering (--snapshot) is needed for cross-build / sweep diffs; plain walks use GPU rendering.
        use_snapshot = bool(args.base or args.base_option or args.sweep)
        self.ctx = {"spec": {"timezone": "UTC", "clock": CLOCK}, "apk": apk, "apksim": self.options, "out": out,
                    "stage": self.stage, "snapshot": use_snapshot, "fresh": not args.keep_data, "screens": [],
                    "record": lambda r: None, "crashes": self.findings, "stable_ms": args.stable_ms, "name": name,
                    "keep_state": args.keep_app_state, "restore": self.kept.restore if self.kept else None,
                    "relaunches": [0], "relaunch_files": {}, "first_values": {}, "unsettled": set(),
                    "record_deps": bool(args.record_deps), "lock": threading.Lock(),
                    "spawn": (SPAWN, STOPPING), "skip_re": self.skip_re}
        self.instances = []
        self.deps_map = None     # --record-deps: DepsMap, once the probed packages are known
        self.start_deps = None   # --record-deps: the app-start span, read from the first instance
        if args.record_deps:
            self.options += ["--jvm", "-Dapksim.deps=true"]
            if args.deps_packages:
                self.options += ["--jvm", f"-Dapksim.deps.packages={args.deps_packages}"]

    def record_start_deps(self):
        """--record-deps: the app-start span, once per walk, and the class -> source map for the probed packages."""
        raw = self.instances[0].read_start_deps()
        m = re.search(r"packages (\S+?),? epoch", raw.pop("packages"))
        packages = [p for p in (m[1] if m else "").split(",") if p]
        self.deps_map = DepsMap(self.apk, packages)
        self.start_deps = self.deps_map.shape(raw)

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
        if self.args.record_deps:
            try:
                self.record_start_deps()
            except Gone as e:
                self.close()
                fail(f"--record-deps: the first instance ended before its start was read: {e.crash.get('detail')}")

    def close(self):
        for inst in self.instances:
            inst.quit()
        if self.kept:
            self.kept.remove()
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

    def event(self, kind, **fields):
        """--events: one line of JSON on stdout, beside the human lines (which stay as they are)."""
        if self.args.events:
            print(json.dumps(dict({"event": kind, "build": self.name}, **fields)), flush=True)

    def screen_event(self, screen, explored=True):
        if screen["id"] in self.announced:
            return
        self.announced.add(screen["id"])
        ev = {"id": screen["id"], "origin": screen.get("origin", "walked"), "depth": screen["depth"],
              "title": screen.get("title", "")}
        if screen.get("replaces"):
            ev["replaces"] = screen["replaces"]
        if not explored:
            ev["explored"] = False
        self.event("screen", **ev)

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
                    self.cut_by.add("max-screens")
                    continue
                level.append(self.add_screen(obs, {"start": start, "steps": []}, None, 0))
        self.walk(level)
        self.t_explore = time.time() - t0

    def walk(self, level):
        """Explores level after level from [level]: each screen's controls tried, new screens one level deeper."""
        args = self.args
        cut = False  # the time ran out inside a level: some screens or controls were left
        while level and not self.budget.out_of_time():
            depth = min(s["depth"] for s in level)
            log(f"[{self.name}] depth {depth}: {len(level)} screen(s) to explore, {len(self.screens)} known")
            self.event("level", depth=depth, screens=len(level), known=len(self.screens))
            if self.budget.max_depth is not None:
                deep = [s for s in level if s["depth"] >= self.budget.max_depth]
                if deep:
                    self.incomplete.append(f"--max-depth {self.budget.max_depth}: {', '.join(s['id'] for s in deep)} not explored")
                    self.cut_by.add("max-depth")
                    level = [s for s in level if s["depth"] < self.budget.max_depth]
                    if not level:
                        break
            level.sort(key=lambda s: s["id"])
            results = self.explore_level(level)
            nxt = []
            # Merged in screen order, then action order: the same graph whatever the instances' timing.
            for screen, res in zip(level, results):
                if res is None or res.get("truncated"):
                    cut = True
                if res is None:
                    continue
                screen["actions"] = res["actions"]
                screen["flaky"] = res.get("flaky")
                if res.get("deps") is not None:
                    screen["deps"] = self.deps_map.shape(res["deps"])
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
                            self.cut_by.add("max-screens")
                            edge["note"] = "not followed: --max-screens"
                            edge["to"] = None
                        else:
                            new = self.add_screen(to, self.full_path(screen, e["steps"]), screen["id"], screen["depth"] + 1,
                                                  step_count=len(e["steps"]))
                            if e.get("alt"):
                                self.alt_paths[new["id"]] = list(self.alt_paths.get(screen["id"]) or screen["path"]["steps"]) \
                                    + list(e["steps"][:-1]) + [e["alt"]]
                            nxt.append(new)
                    self.edges.append(edge)
                for f in res["findings"]:
                    self.findings.append(f)
                # Explored only now: a stop that comes in the middle of this merge lists the screen as unexplored, and a
                # continuation walks it again (its edges merged so far are dropped then, as for any --only screen).
                self.explored.add(screen["id"])
                if res.get("truncated"):
                    self.partial.add(screen["id"])
                self.screen_event(screen)
            level = nxt
        if (level or cut) and self.budget.out_of_time():
            self.cut_by.add("max-seconds")
            self.incomplete.append(f"--max-seconds {self.args.max_seconds}: {len(level)} screen(s) not explored"
                                   + (", and some controls of the last level not tried" if cut else ""))
        for sid in self.order:  # found and never explored (a budget): said too, so a reader has every screen
            if self.screens[sid].get("origin") == "walked":
                self.screen_event(self.screens[sid], explored=False)

    # --- a seeded walk: an earlier walk's graph, with only the named screens walked again
    def explore_seeded(self, seed, only):
        t0 = time.time()
        for s in seed["screens"]:
            c = dict(s)
            c["origin"] = "carried"
            c.pop("replaces", None)
            self.screens[c["id"]] = c
            self.order.append(c["id"])
            if c.get("settled") is False:
                self.ctx["unsettled"].add(c["id"])
        for sid in self.order:
            self.seed_waypoints(sid)
        self.edges = [dict(e) for e in seed["edges"] if e["from"] not in only]
        targets = [self.screens[o] for o in only]

        def under(path, s):
            p = s["path"]
            return isinstance(path, dict) and path.get("start") == p.get("start") and \
                list(path.get("steps") or [])[:len(p["steps"])] == list(p["steps"])
        # The seed's findings on screens carried over stand; those on or past a screen walked again are found again.
        self.findings.extend(f for f in seed.get("findings", []) if f.get("kind") not in ("sweep", "compare")
                             and not any(under(f.get("path"), t) for t in targets))
        # A stop of the seed is not carried: this walk goes on from it, and says itself whether it is cut short.
        self.incomplete += [f"seed: {x}" for x in seed.get("incomplete", []) if not x.startswith("stopped (")]
        # Carried screens still to do: the seed's unexplored ones, and the --only ones until they are walked again (their
        # edges are dropped above, so a stop before then must name them).
        self.seed_unexplored = set(seed.get("unexplored", [])) | set(only)
        for sid in self.order:
            if sid not in only:
                self.screen_event(self.screens[sid])
        # First the --only screens: each reached by its stored path and observed again.
        results = self.run_on_pool(targets, self.observe_at)
        now = {t["id"]: t["id"] for t in targets}  # an --only id -> the screen at its position now
        level = []
        for screen, r in zip(targets, results):
            old = screen["id"]
            screen["origin"] = "walked"
            for k in ("captures", "settled", "unsettled", "flaky", "actions", "deps"):
                screen.pop(k, None)
            screen["captures"], screen["actions"] = {}, []
            if r.get("crash"):
                self.findings.append(self.crash_finding(r["crash"], screen["path"]))
                screen["flaky"] = "ended on arrival"
                self.explored.add(old)
                self.screen_event(screen)
                continue
            obs = r["obs"]
            if r.get("note"):
                self.finding("replay", f"{old}: the stored path no longer replays ({r['note']}): what it reaches stands for it",
                             screen["path"])
            if obs["id"] in PSEUDO:
                screen["flaky"] = "the stored path ends the app"
                self.explored.add(old)
                self.screen_event(screen)
                continue
            if obs["id"] == old:
                screen.update(activity=obs["activity"], nodes=obs["nodes"], title=title_of(obs["recs"]),
                              texts=texts_list(obs["recs"]))
                if screen not in level:
                    level.append(screen)
                continue
            # The path now arrives at another structure: that is the screen at this position.
            new_id = obs["id"]
            if new_id in self.screens:
                ns = self.screens[new_id]
                if ns.get("origin") == "carried":
                    self.edges = [e for e in self.edges if e["from"] != new_id]
                    for k in ("captures", "settled", "unsettled", "flaky", "deps"):
                        ns.pop(k, None)
                    ns["captures"], ns["actions"], ns["origin"] = {}, [], "walked"
                self.order.remove(old)
            else:
                ns = {"id": new_id, "activity": obs["activity"], "nodes": obs["nodes"], "path": screen["path"],
                      "parent": screen["parent"], "depth": screen["depth"], "actions": [], "captures": {},
                      "title": title_of(obs["recs"]), "texts": texts_list(obs["recs"]), "origin": "walked"}
                self.screens[new_id] = ns
                self.order[self.order.index(old)] = new_id
            ns["replaces"] = old
            now[old] = new_id
            del self.screens[old]
            for e in self.edges:
                if e["to"] == old:
                    e["to"] = new_id
            for s in self.screens.values():
                if s.get("parent") == old:
                    s["parent"] = new_id
            level = [x for x in level if x["id"] != old]
            if ns not in level:
                level.append(ns)
            log(f"[{self.name}] {old} is now {new_id}: its path arrives at another structure")
        self.walk(level)
        left = [now[o] for o in only if now[o] in self.screens and now[o] not in self.explored]
        if left:
            self.incomplete.append(f"--max-seconds {self.args.max_seconds}: --only screen(s) not explored: {', '.join(left)}")
            self.cut_by.add("max-seconds")
        self.t_explore = time.time() - t0

    def observe_at(self, inst, screen):
        """An --only screen's stored path replayed: what it reaches now ({obs}, a {note} when a step failed) or a {crash}."""
        note = None
        wp = self.waypoints.get(screen["id"])
        for attempt in range(2):
            try:
                inst.reach(screen["path"], waypoints=wp, explore_fast=self.explore_fast)
                return {"obs": inst.observe()}
            except ReplayFailed as e:
                note = str(e)
                if attempt:
                    try:
                        return {"obs": inst.observe(), "note": note}
                    except Gone as g:
                        return {"crash": g.crash}
            except Gone as e:
                if attempt:
                    return {"crash": e.crash}
        return {"crash": {"kind": "died", "detail": note or "unreachable"}}

    def carry_captures(self, src):
        """The carried screens' captures, linked (else copied) from the seed into OUT."""
        for sid in self.order:
            s = self.screens[sid]
            if s.get("origin") != "carried" or sid in self.captured_now:
                continue
            caps = {}
            for name, rel in (s.get("captures") or {}).items():
                dst = os.path.join(self.out, self.shots, sid, name + ".png")
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                try:
                    try:
                        os.link(src(rel), dst)
                    except OSError:
                        shutil.copyfile(src(rel), dst)
                    caps[name] = f"{self.shots}/{sid}/{name}.png"
                except OSError as e:
                    self.finding("capture", f"{sid} {name}: the seed's capture could not be carried: {e}", s["path"])
            s["captures"] = caps

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

    def seed_waypoints(self, sid):
        """Reconstructs step-by-step waypoint screen ids for a carried screen from its parent chain."""
        if sid in self.waypoints:
            return self.waypoints[sid]
        s = self.screens.get(sid)
        if not s:
            return None
        steps = s["path"]["steps"]
        parent = s.get("parent")
        if not steps or not parent or parent not in self.screens:
            wp = [] if not steps else [None] * (len(steps) - 1) + [sid]
            self.waypoints[sid] = wp
            return wp
        p_steps = self.screens[parent]["path"]["steps"]
        p_wp = self.seed_waypoints(parent)
        if p_wp is not None and len(steps) > len(p_steps) and steps[:len(p_steps)] == p_steps:
            wp = list(p_wp) + [None] * (len(steps) - len(p_steps) - 1) + [sid]
        else:
            wp = [None] * (len(steps) - 1) + [sid]
        self.waypoints[sid] = wp
        return wp

    def add_screen(self, obs, path, parent, depth, step_count=0):
        s = {"id": obs["id"], "activity": obs["activity"], "nodes": obs["nodes"], "path": path, "parent": parent,
             "depth": depth, "actions": [], "captures": {}, "title": title_of(obs["recs"]),
             "texts": texts_list(obs["recs"]), "origin": "walked"}
        self.screens[s["id"]] = s
        self.order.append(s["id"])
        self.first_recs[s["id"]] = obs.get("recs")
        steps = path["steps"]
        if not steps or parent is None:
            self.waypoints[s["id"]] = []
        else:
            p_wp = self.waypoints.get(parent)
            if p_wp is not None and step_count > 0 and len(p_wp) + step_count == len(steps):
                self.waypoints[s["id"]] = list(p_wp) + [None] * (step_count - 1) + [s["id"]]
            else:
                self.waypoints[s["id"]] = [None] * (len(steps) - 1) + [s["id"]]
        return s

    def arrive(self, inst, screen, extra=(), lenient=False, waypoints=None, explore_fast=False, relaunch=True):
        """At the screen, [extra] steps done. [lenient]: a flaky screen, which may now show another structure of the
        same activity, or be behind a label that live data changed (its path's taps tried by their coordinates)."""
        if not lenient:
            return inst.reach_screen(screen, extra, waypoints=waypoints, explore_fast=explore_fast, relaunch=relaunch)
        got = inst.reach_screen(screen, extra, alt=self.alt_paths.get(screen["id"]), lenient=True,
                                waypoints=waypoints, explore_fast=explore_fast, relaunch=relaunch)
        if got.get("drift"):
            # Any screen of the activity would pass (one activity holds every screen of a Compose app): not another
            # screen the walk knows, and one with some of the controls the screen had when it was found.
            if got["id"] in self.screens:
                raise ReplayFailed(f"replay reached {got['id']}, another screen, not {screen['id']}")
            mine = self.control_set(self.first_recs.get(screen["id"]) or [])
            if mine and not mine & self.control_set(got["recs"]):
                raise ReplayFailed(f"replay reached {got['id']}, which has none of {screen['id']}'s controls")
        return got

    def capture_inline(self, inst, screen):
        """Takes the default combination's capture while the instance is already cleanly on [screen] during explore."""
        if not self.inline_capture or screen["depth"] < 1:
            return
        name = self.default_combo_name
        if name in screen.get("captures", {}):
            return
        base_dir = os.path.join(self.out, self.shots, screen["id"])
        os.makedirs(base_dir, exist_ok=True)
        staged = os.path.join(inst.stage, "capture.png")
        try:
            settled = inst.last_settled and (screen["id"] not in self.ctx["unsettled"])
            inst.expect_ok(f"shot {staged}")
            shutil.move(staged, os.path.join(base_dir, name + ".png"))
            screen["captures"][name] = f"{self.shots}/{screen['id']}/{name}.png"
            screen["settled"] = settled if "settled" not in screen else settled and screen["settled"]
            if not settled:
                screen.setdefault("unsettled", []).append(name)
        except ReplayFailed:
            pass

    def discover_screen(self, inst, entry):
        """Reaches [entry]'s screen, captures the default combo inline when enabled, reads deps, fills fields and scrolls
        to list its controls. Returns (ok, still_at_screen, state_at_screen)."""
        screen = entry["screen"]
        args = self.args
        wp = self.waypoints.get(screen["id"])
        here = None
        lenient = False  # the screen's path does not replay to it: its controls are tried where the path leads now
        flaky = None
        aliases = set()  # structures that are this screen: scrolled, or a flaky screen's other arrivals
        for attempt in range(3):
            try:
                here = inst.reach_screen(screen, patient=attempt == 2, waypoints=wp, explore_fast=self.explore_fast)
                break
            except ReplayFailed as e:
                flaky = str(e)
            except Gone as e:
                entry["findings"].append(self.crash_finding(e.crash, screen["path"]))
                entry["flaky"] = "ended on arrival"
                entry["failed"] = True
                return False, False, None
        if here is None:
            # Not a dead end: the walk reached this screen once, so its controls (as it showed them then) are tried on
            # what its path reaches now in the same activity, and what they lead to is followed. Still flaky.
            entry["findings"].append({"kind": "flaky", "detail": f"{screen['id']}: {flaky}", "path": screen["path"], "build": self.name})
            lenient = True
            try:
                here = self.arrive(inst, screen, lenient=True, waypoints=wp, explore_fast=self.explore_fast)
            except ReplayFailed as e:
                entry["flaky"] = f"{flaky}; then {e}"
                entry["failed"] = True
                return False, False, None
            except Gone as e:
                entry["findings"].append(self.crash_finding(e.crash, screen["path"]))
                entry["flaky"] = flaky
                entry["failed"] = True
                return False, False, None
            flaky += "; its controls were tried where its path leads now"
            if here.get("drift"):
                aliases.add(here["id"])
            if self.first_recs.get(screen["id"]):
                here = dict(here, recs=self.first_recs[screen["id"]])
        entry["here"] = here
        entry["lenient"] = lenient
        if lenient:
            entry["flaky"] = flaky
            entry["relaunch_needed"] = True
        try:
            self.capture_inline(inst, screen)
        except Gone as e:
            entry["findings"].append(self.crash_finding(e.crash, screen["path"]))
            entry["flaky"] = flaky or "ended on arrival"
            entry["failed"] = True
            return False, False, None
        if args.record_deps:
            # The span from the `restart` that began this replay to the screen read as settled (docs/crawl.md).
            try:
                entry["deps"] = inst.read_deps()
            except Gone as e:
                entry["findings"].append(self.crash_finding(e.crash, screen["path"]))
                entry["flaky"] = "ended on arrival"
                entry["failed"] = True
                return False, False, None
        prep = []
        filled = None
        if args.fill is not None:
            for a in candidates(here["recs"], self.skip_re, args.tap_unlabeled):
                if a["kind"] == "field":
                    prep += [f"tap {a['x']} {a['y']}", f"type {args.fill}"]
            try:
                for step in prep:
                    inst.expect_ok(step)
                inst.settle(SETTLE)
                filled = parse_tree(inst.say("nodes tree"))
            except ReplayFailed:
                prep = []
            except Gone as e:
                entry["findings"].append(self.crash_finding(e.crash, self.full_path(screen, prep)))
                entry["flaky"] = "ended while filling fields"
                entry["failed"] = True
                return False, False, None
        entry["prep"] = prep
        recs = filled if prep and filled is not None else here["recs"]
        entry["sig"] = screen_sig(recs)
        acts = [a for a in candidates(recs, self.skip_re, args.tap_unlabeled) if a["kind"] == "control"]
        # Controls below the fold: scroll and read again, until nothing new shows.
        scrolls = []
        seen = {a["label"] for a in acts}
        for k in range(1, args.max_scrolls + 1):
            sc = scroll_cmd(recs)
            if not sc:
                break
            try:
                inst.expect_ok(sc)
                inst.settle(SCROLL_SETTLE if self.explore_fast else SETTLE)
                scrolled = parse_tree(inst.say("nodes tree"))
            except ReplayFailed:
                break
            except Gone as e:
                entry["findings"].append(self.crash_finding(e.crash, self.full_path(screen, prep + scrolls + [sc])))
                entry["flaky"] = "ended while scrolling"
                entry["failed"] = True
                return False, False, None
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
        entry["acts"] = acts
        entry["aliases"] = aliases
        if len(acts) > args.max_actions:
            entry["findings"].append({"kind": "note", "detail": f"{screen['id']}: {len(acts)} controls, the first {args.max_actions} tried",
                                      "path": screen["path"], "build": self.name})
        for idx, a in enumerate(acts[:args.max_actions]):
            if a["skipped"]:
                entry["edges_by_idx"][idx] = {"label": a["label"], "cmd": a["cmd"], "kind": "skipped", "to": {"id": "SKIPPED"},
                                              "steps": [], "note": a["skipped"]}
            else:
                entry["todo"].append((idx, a))
        at_ok = (not scrolls) and (not lenient)
        try:
            s_state = inst.data_state() if inst.from_launch_data() else None
        except Gone:
            at_ok, s_state = False, None
        return True, at_ok, s_state

    def control_set(self, recs):
        """A screen's controls by label, numbers left out (a temperature, a count): what stays of it as its data changes."""
        return frozenset(re.sub(r"\d+", "#", a["label"]) for a in candidates(recs, self.skip_re, True)
                         if a["kind"] == "control")

    def explore_level(self, level):
        """Explores every screen in [level] across the instance pool with action-level work stealing, in-place reuse,
        and self-verifying warm restarts (avoiding JVM relaunches when only navigation writes are present)."""
        if not level:
            return []
        entries = [{"screen": s, "discovered": False, "here": None, "sig": None, "prep": [], "acts": [],
                    "aliases": set(), "deps": None, "flaky": None, "lenient": False, "failed": False,
                    "todo": [], "next_pos": 0, "edges_by_idx": {}, "findings": [], "truncated": False,
                    "back_failed": False, "relaunch_needed": False} for s in level]
        cond = threading.Condition()
        undiscovered = list(entries)
        discovered = []
        in_discovery = [0]

        def worker(inst):
            cur = None
            at_screen = False
            try_restart = False
            state_at_s = None
            while not STOPPING.is_set():
                if self.budget.out_of_time():
                    return
                mode = None
                entry = None
                act_idx = act = None
                with cond:
                    while True:
                        if STOPPING.is_set() or self.budget.out_of_time():
                            return
                        if cur is not None and (at_screen or try_restart) and cur["next_pos"] < len(cur["todo"]):
                            act_idx, act = cur["todo"][cur["next_pos"]]
                            cur["next_pos"] += 1
                            mode, entry = "action", cur
                            break
                        if undiscovered:
                            entry = undiscovered.pop(0)
                            in_discovery[0] += 1
                            mode = "discover"
                            break
                        if cur is not None and cur["next_pos"] < len(cur["todo"]):
                            act_idx, act = cur["todo"][cur["next_pos"]]
                            cur["next_pos"] += 1
                            mode, entry = "action", cur
                            break
                        best = None
                        best_rem = 0
                        for e in discovered:
                            rem = len(e["todo"]) - e["next_pos"]
                            if rem > best_rem:
                                best, best_rem = e, rem
                        if best is not None:
                            act_idx, act = best["todo"][best["next_pos"]]
                            best["next_pos"] += 1
                            cur = best
                            at_screen = False
                            try_restart = False
                            state_at_s = None
                            mode, entry = "action", best
                            break
                        if in_discovery[0] > 0:
                            cond.wait(timeout=0.05)
                            continue
                        return
                if mode == "discover":
                    ok, at_ok, s_state = self.discover_screen(inst, entry)
                    with cond:
                        entry["discovered"] = True
                        in_discovery[0] -= 1
                        if ok:
                            discovered.append(entry)
                        cond.notify_all()
                    if ok:
                        cur, at_screen, try_restart, state_at_s = entry, at_ok, (not at_ok and s_state is not None and not entry["lenient"]), s_state
                    else:
                        cur, at_screen, try_restart, state_at_s = None, False, False, None
                    continue
                # mode == "action"
                screen = entry["screen"]
                prep = entry["prep"]
                a = act
                steps = prep + a["prefix"] + [a["cmd"]]
                alt = f"tap {a['x']} {a['y']}"
                note = None
                lenient = entry["lenient"]
                wp = self.waypoints.get(screen["id"])
                try:
                    if not (at_screen and not a["prefix"]):
                        reused = False
                        if try_restart and not lenient and not entry["relaunch_needed"] and state_at_s is not None and inst.from_launch_data():
                            try:
                                got = inst.reach_screen(screen, waypoints=wp, explore_fast=self.explore_fast, relaunch=False)
                                if screen_sig(got["recs"]) == entry["sig"]:
                                    for step in prep + a["prefix"]:
                                        inst.expect_ok(step)
                                        inst.settle(SCROLL_SETTLE if (self.explore_fast and step.startswith("drag ")) else SETTLE)
                                    state_at_s = inst.data_state()
                                    reused = True
                                else:
                                    entry["relaunch_needed"] = True
                            except ReplayFailed:
                                entry["relaunch_needed"] = True
                        if not reused:
                            try:
                                got = self.arrive(inst, screen, prep + a["prefix"], lenient, waypoints=wp, explore_fast=self.explore_fast)
                            except ReplayFailed:
                                if lenient:
                                    raise
                                # It replayed for the screen and not now: try this control where the path leads now.
                                got = self.arrive(inst, screen, prep + a["prefix"], True, waypoints=wp, explore_fast=self.explore_fast)
                            if got.get("drift"):
                                with cond:
                                    entry["aliases"].add(got["id"])
                                note = "tried on another structure of this screen (its replay is flaky)"
                            state_at_s = inst.data_state() if inst.from_launch_data() else None
                    at_screen = False
                    try_restart = False
                    try:
                        inst.expect_ok(a["cmd"])
                    except ReplayFailed as e:
                        # tap-text compares the label as the app has it; `nodes tree` flattens tabs and line breaks
                        if not a["cmd"].startswith("tap-text") or "not found" not in str(e):
                            raise
                        inst.wait_longer()  # a label that comes with data still loading
                        try:
                            inst.expect_ok(a["cmd"])
                        except ReplayFailed:
                            a["cmd"] = alt
                            steps = prep + a["prefix"] + [a["cmd"]]
                            inst.tap_checked(a["cmd"])
                    settled = inst.settle(SETTLE)
                    inst.say("pick cancel")
                    to = inst.observe()
                    if not settled and to["id"] not in PSEUDO:
                        with self.ctx["lock"]:
                            self.ctx["unsettled"].add(to["id"])
                    if to["id"] == "EXIT":
                        edge = {"label": a["label"], "cmd": a["cmd"], "kind": "control", "to": {"id": "EXIT"}, "steps": steps,
                                "note": "the app has no activity left"}
                    else:
                        edge = {"label": a["label"], "cmd": a["cmd"], "kind": "control", "to": to, "steps": steps, "alt": alt}
                        if note:
                            edge["note"] = note
                    with cond:
                        entry["edges_by_idx"][act_idx] = edge
                        more_here = entry["next_pos"] < len(entry["todo"])
                except ReplayFailed as e:
                    with cond:
                        entry["edges_by_idx"][act_idx] = {"label": a["label"], "cmd": a["cmd"], "kind": "control",
                                                          "to": {"id": "FAILED"}, "steps": steps, "note": str(e)}
                    at_screen = False
                    try_restart = False
                    continue
                except Gone as e:
                    f = self.crash_finding(e.crash, self.full_path(screen, steps))
                    with cond:
                        entry["findings"].append(f)
                        entry["edges_by_idx"][act_idx] = {"label": a["label"], "cmd": a["cmd"], "kind": "control",
                                                          "to": {"id": "CRASH" if f["kind"] == "crash" else "EXIT"}, "steps": steps,
                                                          "note": f["detail"].splitlines()[0][:200]}
                    at_screen = False
                    try_restart = False
                    state_at_s = None
                    continue
                # Speculative in-place return or warm restart when disk state is unchanged since arriving at [screen].
                if more_here and not prep and not lenient and to["id"] not in PSEUDO:
                    ret_cmd = None
                    try:
                        clean_disk = (not inst.from_launch_data()) or (state_at_s is not None and inst.data_state() == state_at_s)
                        if clean_disk:
                            if not a["prefix"] and to["id"] == screen["id"] and screen_sig(to["recs"]) == entry["sig"]:
                                at_screen = True
                            elif not a["prefix"] and to["id"] != screen["id"]:
                                if to["activity"] != screen["activity"]:
                                    ret_cmd = "back" if not entry["back_failed"] else dismiss_cmd(to["recs"], self.skip_re)
                                else:
                                    ret_cmd = dismiss_cmd(to["recs"], self.skip_re) or (None if entry["back_failed"] else "back")
                                if ret_cmd:
                                    inst.expect_ok(ret_cmd)
                                    obs = inst.wait_waypoint(screen["id"], prev_id=to["id"], timeout=0.4)
                                    if obs["id"] == "EXIT":
                                        if ret_cmd == "back":
                                            entry["back_failed"] = True
                                        inst.quit()
                                        state_at_s = None
                                    elif obs["id"] == screen["id"] and screen_sig(obs["recs"]) == entry["sig"]:
                                        if (not inst.from_launch_data()) or inst.data_state() == state_at_s:
                                            at_screen = True
                                    elif ret_cmd == "back":
                                        entry["back_failed"] = True
                            if not at_screen and not entry["relaunch_needed"] and state_at_s is not None:
                                # Action did not mutate disk state: next action on this screen can try warm `restart`
                                # and verify id + sig + data_state before falling back to a full JVM relaunch.
                                if inst.proc is not None and inst.proc.poll() is None:
                                    try_restart = True
                    except ReplayFailed:
                        if ret_cmd == "back":
                            entry["back_failed"] = True
                    except Gone:
                        if ret_cmd == "back":
                            entry["back_failed"] = True
                        inst.close()
                        state_at_s = None

        with concurrent.futures.ThreadPoolExecutor(len(self.instances)) as ex:
            list(ex.map(worker, self.instances))

        results = []
        for entry in entries:
            if not entry["discovered"]:
                results.append(None)
                continue
            screen = entry["screen"]
            acts = entry["acts"]
            aliases = entry["aliases"]
            edges = [entry["edges_by_idx"][i] for i in sorted(entry["edges_by_idx"])]
            # A flaky screen's control that leads to the same controls and title in the same activity (a refresh) is
            # this screen with other data, not a new screen: followed, each would be a new flaky screen, level after level.
            mine = self.control_set(entry["here"]["recs"]) if entry["lenient"] and entry["here"] else None
            for e in edges:
                to = e["to"]
                if to["id"] in aliases:
                    e["to"] = {"id": screen["id"]}
                elif mine and to.get("recs") is not None and to.get("activity") == screen["activity"] \
                        and self.control_set(to["recs"]) == mine \
                        and re.sub(r"\d+", "#", title_of(to["recs"])) == re.sub(r"\d+", "#", title_of(entry["here"]["recs"])):
                    e["to"] = {"id": screen["id"]}
                    e["note"] = "the same controls with other data (this screen is flaky)"
            expected = len(acts[:self.args.max_actions])
            truncated = (not entry["failed"]) and (len(edges) < expected)
            results.append({"actions": [{k: a[k] for k in ("label", "cmd", "kind", "box", "skipped", "scrolls")} for a in acts],
                            "edges": edges, "findings": entry["findings"], "truncated": truncated,
                            "flaky": entry["flaky"], "deps": entry["deps"]})
        return results

    def crash_finding(self, crash, path):
        kind = "crash" if crash["kind"] == "crash" else crash["kind"]
        return {"kind": kind, "detail": crash["detail"], "path": path, "build": self.name, "exit": crash.get("exit"),
                "log": crash.get("log"), "after": crash.get("after")}

    # --- captures
    def capture(self, combos, ids=None):
        t0 = time.time()
        ids = sorted(self.screens if ids is None else ids)
        pending = []
        for sid in ids:
            screen = self.screens[sid]
            caps = screen.get("captures") or {}
            need = [c for c in combos
                    if not (snapshot.combo_name(c) in caps and os.path.isfile(os.path.join(self.out, caps[snapshot.combo_name(c)])))]
            if need:
                pending.append((screen, need))
        if not pending:
            self.t_capture = time.time() - t0
            return
        jobs = len(self.instances)
        chunks = max(1, min(max(len(n) for _, n in pending), -(-jobs // max(1, len(pending)))))
        tasks = []
        for screen, need in pending:
            for c in range(chunks):
                part = need[c::chunks]
                if part:
                    tasks.append((screen, part))
        self.run_on_pool(tasks, self.capture_task)
        self.t_capture = time.time() - t0

    def capture_task(self, inst, task):
        screen, combos = task
        base_dir = os.path.join(self.out, self.shots, screen["id"])
        os.makedirs(base_dir, exist_ok=True)
        wp = self.waypoints.get(screen["id"])
        for combo in combos:
            name = snapshot.combo_name(combo)
            staged = os.path.join(inst.stage, "capture.png")
            try:
                # Replayed for every combination: the one before may have closed a dialog or moved a scroll position.
                try:
                    inst.reach_screen(screen, waypoints=wp)
                except ReplayFailed:
                    # A flaky screen (or one below it) is captured where its path leads now, as it was explored.
                    self.arrive(inst, screen, lenient=bool(screen.get("flaky")), waypoints=wp)
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

    def unexplored(self):
        out = []
        for sid in self.order:
            s = self.screens.get(sid) or {}
            if s.get("origin") == "carried":
                if sid in self.seed_unexplored:
                    out.append(sid)
            elif sid not in self.explored or sid in self.partial:
                out.append(sid)
        return out

    def doc(self):
        d = {"build": self.name, "apk": os.path.basename(self.apk), "screens": [self.screens[i] for i in self.order],
             "edges": self.edges, "findings": self.findings, "incomplete": self.incomplete,
             "seconds": {"explore": round(self.t_explore, 1), "capture": round(self.t_capture, 1)}}
        # Screens found and never explored (or explored in part: the time ran out on them), in discovery order, and what
        # cut the walk short: `--seed RUN --only-file` with these ids goes on from where it stopped.
        d["unexplored"] = self.unexplored()
        if self.partial:
            d["partly_explored"] = [i for i in self.order if i in self.partial]
        d["cut_by"] = sorted(self.cut_by)
        # Launches that replaced an instance whose app had written to its data (Instance.reach).
        d["relaunches"] = self.ctx["relaunches"][0]
        if self.ctx["relaunch_files"]:
            d["relaunch_files"] = dict(sorted(self.ctx["relaunch_files"].items(), key=lambda kv: -kv[1]))
        if self.start_deps is not None:
            d["start_deps"] = self.start_deps
            d["deps_packages"] = self.deps_map.packages
            if self.deps_map.unreliable:
                d["deps_unreliable"] = self.deps_map.unreliable
        return d


def repeated(steps, n):
    """The path ends in the same step n times (a counter, a stack of dialogs: a chain that never ends)."""
    return len(steps) >= n and len(set(steps[-n:])) == 1


def texts_list(recs):
    return [r.text[:80] for r in recs if r.text and "v" in r.flags][:40]


def title_of(recs):
    """A name for the screen's card: its first text that is not a number."""
    for r in recs:
        if r.text and "v" in r.flags and not r.text.strip().isdigit():
            return r.text[:40]
    return ""


# -------------------------------------------------------------------------------------------- resilience sweep

PERTURBATIONS = ("process-death", "recreate", "rotate", "offline", "permission-deny", "fontscale", "rtl", "dark")


def parse_sweep(spec):
    """'recreate,fontscale=1.5,rtl' (or 'all') -> [(name, parameter)]; a perturbation named twice keeps its last entry."""
    chosen = {}
    for item in [x for x in (spec or "").split(",") if x]:
        if item == "all":
            for n in PERTURBATIONS:
                chosen.setdefault(n, None)
            continue
        name, _, param = item.partition("=")
        if name not in PERTURBATIONS:
            fail(f"--sweep: unknown perturbation {name!r} (known: {', '.join(PERTURBATIONS)}, all)")
        if name == "fontscale" and param:
            try:
                if float(param) <= 0:
                    raise ValueError
            except ValueError:
                fail(f"--sweep fontscale expects a number above 0, not {param!r}")
        chosen[name] = param or None
    return list(chosen.items())


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
        self.skipped = []   # cases left out on purpose
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
                steps = list(e.get("steps") or [e["cmd"]])  # with a --fill, the fills come first
                if len(out) <= self.args.sweep_actions and steps not in out:
                    out.append(steps)
        return out

    def screen(self, inst, screen):
        for kind, param in self.plan:
            if self.ex.budget.out_of_time():
                # The time is up: what is left is said, not dropped (a sweep that ran nothing must not look green).
                self.omit(kind, screen)
                continue
            fn = getattr(self, "sw_" + kind.replace("-", "_"))
            inst.cmds = []
            inst.net_changed = False
            try:
                fn(inst, screen, param)
            except ReplayFailed as e:
                self.note(kind, screen, "replay", f"could not set up the case: {e}", [])
            except Gone as e:
                c = e.crash
                cmds = list(getattr(inst, "cmds", []))
                before = [x for x in cmds if x == "network offline"]  # set before the screen is opened, as the case does
                self.note(kind, screen, "crash" if c["kind"] == "crash" else "exit", c["detail"], getattr(inst, "case", []),
                          log=c.get("log"), pre=before, repro_cmds=[x for x in cmds if x not in before])
            except Exception as e:  # noqa: BLE001  (a bug of the sweep's own is a finding, not a dead worker)
                self.note(kind, screen, "sweep-error", f"{type(e).__name__}: {e}", [])
            finally:
                if kind == "offline" and getattr(inst, "net_changed", False):  # only a case that went offline: back to the launch's profile
                    opts = self.ex.options
                    profile = opts[opts.index("--network-profile") + 1] if "--network-profile" in opts else "online"
                    try:
                        inst.send(f"network {profile}")
                    except (snapshot.Closed, OSError, ValueError, AttributeError):
                        pass

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
        return {"id": obs["id"], "screen": screen["id"], "recs": obs["recs"], "texts": texts_of(obs["recs"]), "settled": settled,
                "checks": ["K" in r.flags for r in obs["recs"] if "k" in r.flags and "v" in r.flags],
                "shot": os.path.relpath(path, self.ex.out)}

    def differ(self, inst, a, b, tag):
        """Why two reads of a screen are not the same state, or None."""
        if a["id"] != b["id"]:
            return f"the screen is {b['id']}, not {a['id']}", None
        if a["texts"] != b["texts"]:
            lost = [t for t in a["texts"] if t not in b["texts"]]
            gained = [t for t in b["texts"] if t not in a["texts"]]
            return "text changed: " + "; ".join([f"'{t}' gone" for t in lost[:4]] + [f"'{t}' appeared" for t in gained[:4]]), None
        if a["checks"] != b["checks"]:  # before the image comparison, which a screen that never settles skips
            return "a switch or checkbox is not in the state it was", None
        if a["settled"] and b["settled"]:
            d = diff_images(inst, os.path.join(self.ex.out, a["shot"]), os.path.join(self.ex.out, b["shot"]), tag,
                            os.path.join(self.dir, a["screen"], "diff"), self.args.diff_delta, self.args.sweep_pixels,
                            self.ex.out)
            if d.get("error"):
                return f"the image comparison failed: {d['error']}", None
            if d.get("differs"):
                return f"it looks different: {d['pixels']} pixels differ", d.get("map")
        return None, None

    # --- state kept across a configuration change, a rebuild or a rotation
    def roundtrip(self, inst, screen, kind, forward, back):
        for steps in self.states_of(screen):
            if self.ex.budget.out_of_time():
                self.omit(kind, screen)
                break
            label = (" + ".join(steps) or "as reached")
            inst.case = steps
            inst.cmds = []  # this state's commands only, for a crash's reproduction
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
            if self.ex.budget.out_of_time():
                return None  # the time is up: not an answer
            time.sleep(0.5)

    def sw_offline(self, inst, screen, param):
        opts = self.ex.options
        if any(o in opts for o in ("--network", "--no-network", "--network-profile")):
            with self.lock:  # the caller's network rules (replay, a profile, no network) must not be overridden
                self.skipped.append({"perturbation": "offline", "screen": screen["id"],
                                     "why": "the launch options restrict the network"})
            return
        inst.case = []
        self.case("offline", screen, "as reached")
        inst.fresh_start()  # an earlier case may have ended the app, or left data written: a relaunch now, not below
        inst.net_changed = True
        inst.say("network offline")
        inst.cmds.append("network offline")
        inst.reach(screen["path"], relaunch=False)  # the path with no network, from a fresh start
        up = self.spinner(inst)
        if up is None:
            self.omit("offline", screen)
            return
        if up:
            inst.say("network online")
            inst.reach(screen["path"])
            online = self.spinner(inst)
            if online is None:  # the time ran out before the online comparison: the case is not done
                self.omit("offline", screen)
                return
            if online is False:  # a spinner that only offline leaves up
                self.note("offline", screen, "endless-spinner",
                          f"still a progress indicator {self.args.spinner_seconds:g} s after the screen opened with no network "
                          "(online it goes)", [], pre=["network offline"])

    # --- permissions denied
    def sw_permission_deny(self, inst, screen, param):
        """`permission deny` answers the requests pending when it runs, so it follows each state: the controls
        tapped there (a camera button) ask, and are told no. Requests a replay raises before that are not answered."""
        for steps in self.states_of(screen):
            if self.ex.budget.out_of_time():
                self.omit("permission-deny", screen)
                break
            inst.case = steps
            inst.cmds = []
            inst.reach_screen(screen, steps)
            inst.cmds.append("permission deny")
            reply = inst.say("permission deny")
            if reply.startswith("error: app crashed"):
                raise Gone(inst.ended("permission deny"))
            if reply.startswith("error"):  # the app's onRequestPermissionsResult threw: not a pass
                self.note("permission-deny", screen, "callback-failed", f"denying the permission request failed: {reply}", steps,
                          repro_cmds=list(inst.cmds))
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
            if not reply.startswith("no issues in"):  # the audit did not run: not a pass
                self.note("dark", screen, "audit-failed", f"the accessibility audit gave: {reply[:200]}", [],
                          repro_cmds=["appearance dark", "a11y"])
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
    found_keys = {(f["perturbation"], f["screen"]) for f in sweep.found}
    done = set()
    for c in sorted(sweep.checked, key=lambda c: (c["perturbation"], c["screen"], c["case"])):
        key = (c["perturbation"], c["screen"])
        if key in done or key in found_keys:
            continue
        done.add(key)
        cases.append(f'<testcase classname={quoteattr("crawl." + c["perturbation"])} name={quoteattr(c["screen"])}/>')
    for o in sweep.skipped:
        cases.append(f'<testcase classname={quoteattr("crawl." + o["perturbation"])} name={quoteattr(o["screen"])}>'
                     f'<skipped message={quoteattr("not run: " + o["why"])}/></testcase>')
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
           f'skipped="{skipped + len(sweep.omitted) + len(sweep.skipped)}" time="{sweep.seconds:.1f}">\n' + "\n".join(cases) + "\n</testsuite>\n")
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
    p.add_argument("--screen-titles", action="store_true",
                   help="tell screens with the same layout apart by their title (the first text that is not a number): a "
                        "walk of an app whose screens share templates otherwise records them as one screen")
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
    p.add_argument("--keep-data", action="store_true", help="start from a prepared data slot (the one this run's --data-slots names, else default): each instance runs on a copy of it, made again at each relaunch, and the slot itself is not written")
    p.add_argument("--keep-app-state", action="store_true",
                   help="replay on an instance whose app has written to its data since its launch (faster; walks can "
                        "then differ: by default such an instance is launched again on an emptied slot first)")
    p.add_argument("-j", "--jobs", type=int, default=3)
    p.add_argument("--diff-delta", type=int, default=8)
    p.add_argument("--diff-pixels", type=float, default=0, help="share (or count, from 1) of differing pixels allowed")
    p.add_argument("--sweep", help=f"resilience sweep after the walk: {','.join(PERTURBATIONS)} or all (fontscale=1.5, rtl=he-IL take a value)")
    p.add_argument("--allow", help="file of accepted sweep findings: lines of 'RULE SCREEN [DETAIL TEXT]', * for any")
    p.add_argument("--junit", help="JUnit XML of the sweep (default OUT/junit.xml): a failing case per finding")
    p.add_argument("--sweep-actions", type=int, default=3, help="how many controls that stay on the screen the state perturbations tap, one at a time, besides the screen as reached (default 3: as reached and up to three taps; 0: as reached only)")
    p.add_argument("--sweep-pixels", type=float, default=0.002, help="share of pixels that may differ between a screen before and after a perturbation")
    p.add_argument("--seed", metavar="RUN", help="an earlier walk's OUT folder: start from its graph and walk again only the --only screens")
    p.add_argument("--only", help="with --seed: the screen ids to walk again, comma-separated")
    p.add_argument("--only-file", help="with --seed: a file of screen ids to walk again, one per line")
    p.add_argument("--events", action="store_true", help="also print a line of JSON per finished screen and per level")
    p.add_argument("--record-deps", action="store_true", help="per screen, the app classes that ran, their source files and the resources resolved (crawl.json deps, start_deps; docs/crawl.md)")
    p.add_argument("--deps-packages", help="with --record-deps: packages to probe, comma-separated (default the app package's first two parts)")
    p.add_argument("--spinner-seconds", type=float, default=6, help="how long a progress indicator may stay up offline")
    args = p.parse_args(argv)
    args.apksim = list(tail)
    for s in args.setting:
        args.apksim += ["--setting", s]
    for s in args.sdcard_file:
        args.apksim += ["--sdcard-file", s]
    if args.keep_data:
        if args.base or args.base_option:
            fail("--keep-data cannot be used with --base: two walks cannot start from one prepared data slot")
    if args.jobs < 1:
        fail("-j takes 1 or more")
    only = [x.strip() for x in (args.only or "").split(",") if x.strip()]
    if args.only_file:
        try:
            with open(args.only_file) as f:
                only += [ln.strip() for ln in f if ln.strip() and not ln.strip().startswith("#")]
        except OSError as e:
            fail(f"cannot read {args.only_file}: {e}")
    args.only_ids = list(dict.fromkeys(only))
    if args.deps_packages and not args.record_deps:
        fail("--deps-packages goes with --record-deps")
    if args.seed:
        # An --only-file with no ids is allowed: a walk stopped during its captures has every screen explored, and its
        # continuation only takes the captures it lacks.
        if not args.only_ids and not args.only_file:
            fail("--seed needs --only or --only-file: a seeded walk walks only the screens named, and with none it would "
                 "walk nothing (to walk everything again, leave out --seed)")
        if args.base or args.base_option:
            fail("--seed cannot be used with --base or --base-option: a comparison walks both builds in full")
        if args.sweep:
            fail("--seed cannot be used with --sweep: the sweep runs on every screen, carried ones too")
    elif args.only or args.only_file:
        fail("--only and --only-file name screens of a --seed walk: give --seed RUN")
    return args


def load_seed(args, out, combos):
    """The seed's crawl.json, checked against this run, and where its captures are read from."""
    seed_dir = os.path.abspath(args.seed)
    try:
        with open(os.path.join(seed_dir, "crawl.json")) as f:
            seed = json.load(f)
    except (OSError, ValueError) as e:
        fail(f"--seed: cannot read {os.path.join(seed_dir, 'crawl.json')}: {e}")
    known = {s["id"] for s in seed.get("screens", [])}
    unknown = [o for o in args.only_ids if o not in known]
    if unknown:
        fail(f"--only: the seed has no screen {', '.join(unknown)}")
    names = [snapshot.combo_name(c) for c in combos]
    if list(seed.get("combos", [])) != names:
        fail(f"--seed: the seed was captured in {seed.get('combos', [])} and this walk captures {names}: give the seed's "
             "matrix, or walk in full (carried screens keep the seed's captures)")
    if bool(seed.get("screen_titles", False)) != bool(args.screen_titles):
        fail("--seed: the seed and this walk differ in --screen-titles, so their screen ids do not match: give both the "
             "same setting")
    src_dir = seed_dir
    stranded = os.path.join(seed_dir, ".seed", "screens")
    if os.path.isdir(stranded):
        # A seeded run on OUT itself that ended early (an instance never started, or a stop during its captures) left the
        # seed's captures aside: back first, beside any the stopped run took (those are newer and kept).
        here = os.path.join(seed_dir, "screens")
        if not os.path.isdir(here):
            os.rename(stranded, here)
        else:
            for name in os.listdir(stranded):
                if not os.path.exists(os.path.join(here, name)):
                    os.rename(os.path.join(stranded, name), os.path.join(here, name))
            shutil.rmtree(os.path.join(seed_dir, ".seed"), ignore_errors=True)
    if os.path.realpath(seed_dir) == os.path.realpath(out):
        # The seed is OUT itself: its captures are moved aside before OUT's screens/ is emptied, and read from there.
        src_dir = os.path.join(out, ".seed")
        shutil.rmtree(src_dir, ignore_errors=True)
        os.makedirs(src_dir)
        if os.path.isdir(os.path.join(out, "screens")):
            os.rename(os.path.join(out, "screens"), os.path.join(src_dir, "screens"))
    return seed, src_dir


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


STOP = {"walk": None, "out": None, "t0": None, "combos": [], "base": None}  # base: the base build's walk, once started
STOPPING = threading.Event()  # set by on_stop: Instance.launch refuses from then on
SPAWN = threading.RLock()      # held around an instance's Popen (snapshot.Worker.start) and on_stop's kills


def kill_instances(walks):
    with SPAWN:
        for w in walks:
            for inst in w.instances:
                try:
                    if inst.proc is not None and inst.proc.poll() is None:
                        inst.proc.kill()
                except Exception:  # noqa: BLE001
                    pass


def on_stop(signum, frame):
    """SIGTERM or SIGINT (Greenroom's Stop): the walk so far is written as a walk cut short, the instances end, exit 143.
    Fast, as a caller gives a few seconds before it kills the process tree: what is known is written as it is (the
    screens found, the transitions merged, the captures taken), with no replay or capture of more. No instance starts
    once it has begun (STOPPING), the document is read before the instances are killed (so their deaths are not
    findings), and the kill is made again last, for one a pool thread had in Popen meanwhile."""
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    signal.signal(signal.SIGINT, signal.SIG_IGN)
    STOPPING.set()
    walk = STOP["walk"]
    if walk is None:
        os._exit(143)
    walks = [walk] + ([STOP["base"]] if STOP["base"] else [])
    try:
        if walk.t_explore == 0:
            walk.t_explore = time.time() - STOP["t0"]
        walk.cut_by.add("stopped")
        doc = None
        for _ in range(5):  # the pool's threads may still be appending
            try:
                doc = json.loads(json.dumps(walk.doc()))
                break
            except RuntimeError:
                time.sleep(0.05)
        kill_instances(walks)
        if doc is None:
            log("stopped: the walk could not be read while its threads ran: crawl.json not written")
        else:
            sig = "SIGTERM" if signum == signal.SIGTERM else "SIGINT"
            doc["incomplete"].append(f"stopped ({sig}) after {time.time() - STOP['t0']:.0f} s"
                                     + (" during the base build's walk (this is the new build's)" if STOP["base"] else "")
                                     + f": {len(doc['unexplored'])} screen(s) not explored")
            doc["stopped"] = True
            doc["combos"] = [snapshot.combo_name(c) for c in STOP["combos"]]
            doc["seconds"]["total"] = round(time.time() - STOP["t0"], 1)
            with open(os.path.join(STOP["out"], "crawl.json.part"), "w") as f:
                json.dump(doc, f, indent=1)
            os.replace(os.path.join(STOP["out"], "crawl.json.part"), os.path.join(STOP["out"], "crawl.json"))
            try:
                html_report(STOP["out"], doc)
            except Exception:  # noqa: BLE001
                pass
            log(f"stopped: {len(doc['screens'])} screens, {len(doc['unexplored'])} not explored, written to "
                f"{os.path.join(STOP['out'], 'crawl.json')}")
    finally:
        try:
            kill_instances(walks)
            for w in walks:
                if w.kept:
                    w.kept.remove()
                shutil.rmtree(w.stage, ignore_errors=True)
        finally:
            os._exit(143)


def main():
    args = parse(sys.argv[1:])
    global TITLES_ARE_IDENTITY
    TITLES_ARE_IDENTITY = args.screen_titles
    apk = os.path.abspath(args.apk)
    if not os.path.isfile(apk):
        fail(f"no APK {apk}")
    base_apk = os.path.abspath(args.base) if args.base else (apk if args.base_option else None)
    if base_apk and not os.path.isfile(base_apk):
        fail(f"no APK {base_apk}")
    combos = [] if args.no_captures else matrix_of(args)
    out = os.path.abspath(args.out)
    seed = src_dir = None
    if args.seed:
        seed, src_dir = load_seed(args, out, combos)
        if "start_deps" in seed and not args.record_deps:
            log("note: the seed recorded deps and this walk does not: screens walked again will have none (add --record-deps)")
    for d in ("screens", "base-screens", "diff"):
        shutil.rmtree(os.path.join(out, d), ignore_errors=True)
    os.makedirs(out, exist_ok=True)
    t0 = time.time()
    new = Explorer("new", apk, args, out, [], combos=combos)
    base = None
    STOP.update(walk=new, out=out, t0=t0, combos=combos)
    signal.signal(signal.SIGTERM, on_stop)
    signal.signal(signal.SIGINT, on_stop)
    try:
        new.pool(args.jobs)
        if seed is not None:
            new.explore_seeded(seed, args.only_ids)
        else:
            new.explore()
        log(f"[new] {len(new.screens)} screens, {len(new.edges)} transitions in {new.t_explore:.1f} s")
        if combos:
            ids = None
            if seed is not None:
                # The --only screens, and carried ones the seed has no captures for (a walk stopped before its captures).
                missing = [i for i in new.order if seed.get("stopped") and new.screens[i]["origin"] == "carried"
                           and any(snapshot.combo_name(c) not in (new.screens[i].get("captures") or {}) for c in combos)]
                new.captured_now = set(missing)
                ids = [i for i in new.order if new.screens[i]["origin"] == "walked"] + missing
            new.capture(combos, ids)
            log(f"[new] {len(combos)} combination(s) captured in {new.t_capture:.1f} s")
        if seed is not None:
            new.carry_captures(lambda rel: os.path.join(src_dir, rel))
            if os.path.basename(src_dir) == ".seed":
                shutil.rmtree(src_dir, ignore_errors=True)
        doc = new.doc()
        doc["combos"] = [snapshot.combo_name(c) for c in combos]
        doc["screen_titles"] = bool(args.screen_titles)
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
            base = Explorer("base", base_apk, args, out, shlex.split(args.base_option), shots="base-screens", combos=combos)
            STOP.update(base=base)
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
    except DepsOff as e:
        fail(str(e))  # the finally below still closes the instances
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
    code = 1 if crashes or differs else 0
    if args.events:
        print(json.dumps({"event": "done", "exit": code, "screens": len(doc["screens"]), "edges": len(doc["edges"]),
                          "incomplete": doc["incomplete"], "seconds": doc["seconds"]}), flush=True)
    sys.exit(code)


if __name__ == "__main__":
    main()
