#!/usr/bin/env python3
"""Explores an APK's screens breadth-first over the control channel and maps them (docs/crawl.md).

Usage: crawl.py APK --out DIR [--base APK2] [--start URL]... [--max-screens N] [--max-seconds S] [--max-depth N]
                [--skip-text REGEX]... [--tap-unlabeled] [--fill TEXT] [--matrix AXIS=V,V]... [--matrix-file SPEC.json]
                [-j N] [--setting NS:KEY=VALUE]... [--sdcard-file PATH=text:TEXT]... [--standby] [--keep-data] [--keep-app-state] [--data-ignore PATTERN]...
                [--base-option "APKSIM OPTIONS"] [--sweep LIST ...] [--seed RUN (--only ID,... | --only-file PATH)]
                [--events] [--screen-titles] [--freeze-network] [--deterministic] [-- APKSIM_OPTION...]

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
import argparse, base64, concurrent.futures, difflib, hashlib, html, itertools, json, os, re, shlex, shutil, signal, subprocess, sys
import fnmatch, sqlite3, struct, tempfile, threading, time, urllib.parse

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
SETTLE_FAST = "settle 1500 80 1000 tree 80"
# A replay step or screen that is not there yet (data from the network, a list that fills in late): wait this long, then
# settle for longer, before trying again (Instance.wait_longer).
SETTLE_LONG = "settle 6000 300"
SETTLE_LONG_FAST = "settle 6000 180 1000 tree 180"
PATIENCE_S = 1.5
WAYPOINT_SETTLE = "settle 800 60"
WAYPOINT_SETTLE_FAST = "settle 800 40 1000 tree 50"
SCROLL_SETTLE = "settle 450 60"
SCROLL_SETTLE_FAST = "settle 450 40 1000 tree 40"
FAST_SETTLES = {
    SETTLE: SETTLE_FAST,
    SETTLE_LONG: SETTLE_LONG_FAST,
    WAYPOINT_SETTLE: WAYPOINT_SETTLE_FAST,
    SCROLL_SETTLE: SCROLL_SETTLE_FAST,
}
DISMISS_LABELS = frozenset({"back", "navigate up", "close", "cancel", "not now", "dismiss"})
LAUNCH_CONFIG_FLAGS = frozenset({"--device", "--dark", "--appearance", "--locale", "--font-scale", "--activity"})
TIME_RE = re.compile(r"\b\d{1,2}:\d{2}\b")
PSEUDO = ("EXIT", "CRASH", "SKIPPED", "FAILED")  # where an edge leads when it does not lead to a screen
ARRIVE_MISSES = 3  # replays in a row that miss a screen before its other controls are given up (each is a launch)


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


# How a screen's id is made (canon), written to crawl.json as "screen_ids". 1: every record placed by its own centre.
# 2: a text with nothing under it, not a control, is placed where its parent is, so a label whose text is longer ("Massachusetts,
# United States" against "Florida, United States") does not move into the next third and make another screen. A seeded
# walk makes ids the way its seed did (load_seed), so it is compared with the seed's screens.
SCREEN_IDS = 2


def canon(recs, kids, i, w, h, scrolling, at=None):
    """A record's structure as a string: class, id, the flags that say what it is for (not its state), where it sits
    coarsely (a third of the width; top, middle or bottom of the height, not inside a scroller, whose content moves),
    and its children, with runs of identical consecutive siblings (the rows of a list) written once. [at] is the
    parent's place, which a text with no children that is not a control takes (SCREEN_IDS 2): its box follows its
    text's length, where a control's place is part of the layout."""
    r = recs[i]
    if SCREEN_IDS >= 2 and at is not None and r.text and not kids.get(i) and not any(c in r.flags for c in "cle"):
        col, cy = at
    else:
        cx, cy = r.centre()
        col = min(2, max(0, cx * 3 // w))
    row = "-" if scrolling else ("T" if cy < h * 0.15 else "B" if cy > h * 0.85 else "M")
    flags = "".join(c for c in STRUCT_FLAGS if c in r.flags)
    inner = scrolling or "s" in r.flags
    parts = []
    for k in kids.get(i, []):
        s = canon(recs, kids, k, w, h, inner, (col, cy))
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

# Tables a SQLite database keeps for its framework, not for a screen (Room's bookkeeping, SQLite's own).
SQLITE_BOOKKEEPING = {"room_master_table", "android_metadata", "sqlite_sequence", "room_table_modification_log"}


_MISSING = object()
_CONFLICT = object()


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


_SQLITE_CACHE = {}
_SQLITE_BASE_CACHE = {}
_SQLITE_LOCK = threading.Lock()
_NOT_SQLITE = object()


def _stat_key(path):
    try:
        st = os.stat(path)
        return (st.st_size, st.st_mtime_ns)
    except OSError:
        return None


def _sqlite_wal_pages(wal_path, page_size):
    """Set of modified DB page numbers present in active frames of [wal_path], or None if unreadable/incompatible.
    Seeks directly to each 16-byte frame header so multi-MB WALs (e.g. cached blobs) are inspected with ~KB of I/O."""
    try:
        with open(wal_path, "rb") as f:
            hdr = f.read(32)
            if len(hdr) < 32:
                return set()
            magic, _fmt, ps, _chk, salt1, salt2 = struct.unpack(">6I", hdr[:24])
            if magic not in (0x377F0682, 0x377F0683) or ps != page_size:
                return None
            f.seek(0, os.SEEK_END)
            end = f.tell()
            stride = 24 + ps
            pages = set()
            off = 32
            while off + stride <= end:
                f.seek(off)
                fh = f.read(16)
                if len(fh) < 16:
                    break
                pgno, _db_sz, fs1, fs2 = struct.unpack(">4I", fh)
                if fs1 != salt1 or fs2 != salt2:
                    break
                pages.add(pgno)
                off += stride
            return pages
    except OSError:
        return set()


def _sqlite_btree_pages(buf, ps, root):
    """All B-tree pages (interior + leaf) belonging to the table rooted at [root] in main DB [buf], or None."""
    pages = set()
    stack = [root]
    buf_len = len(buf)
    while stack:
        p = stack.pop()
        if p in pages or p < 1:
            continue
        off = (p - 1) * ps
        if off + ps > buf_len:
            return None
        pages.add(p)
        hdr_off = off + (100 if p == 1 else 0)
        ptype = buf[hdr_off]
        if ptype == 0x05 or ptype == 0x02:
            ncells = struct.unpack(">H", buf[hdr_off + 3:hdr_off + 5])[0]
            right = struct.unpack(">I", buf[hdr_off + 8:hdr_off + 12])[0]
            stack.append(right)
            ptrs_off = hdr_off + 12
            if ptrs_off + 2 * ncells > off + ps:
                return None
            for i in range(ncells):
                cell_off = off + struct.unpack(">H", buf[ptrs_off + 2 * i:ptrs_off + 2 * i + 2])[0]
                if cell_off + 4 > off + ps:
                    return None
                stack.append(struct.unpack(">I", buf[cell_off:cell_off + 4])[0])
        elif ptype != 0x0D and ptype != 0x0A:
            return None
    return pages


def _sqlite_hash_table(con, t):
    """Order-independent hash of all rows in table [t]."""
    s1 = s2 = cnt = 0
    for row in con.execute('select * from "%s"' % t.replace('"', '""')):
        h = hash(row)
        s1 = (s1 + h) & 0xFFFFFFFFFFFFFFFF
        s2 ^= h
        cnt += 1
    return (cnt, s1, s2)


def _sqlite_build_base(path, hdr100, wal_stat, roots, out):
    """Maps main-DB B-tree pages to table names so future WAL writes only re-scan the 1-2 modified tables."""
    ps = struct.unpack(">H", hdr100[16:18])[0]
    if ps == 1:
        ps = 65536
    if ps < 512:
        return None
    init_wal = _sqlite_wal_pages(path + "-wal", ps) if wal_stat is not None else set()
    if init_wal is None or 1 in init_wal:
        return None
    try:
        with open(path, "rb") as f:
            buf = f.read()
    except OSError:
        return None
    page_to_table = {}
    always_scan = set()
    for name, rp in roots:
        if name in SQLITE_BOOKKEEPING or name.startswith("sqlite_"):
            continue
        if not rp or rp < 1:
            always_scan.add(name)
            continue
        pgs = _sqlite_btree_pages(buf, ps, rp)
        if pgs is None:
            always_scan.add(name)
            continue
        for p in pgs:
            page_to_table[p] = name
    for p in init_wal:
        t = page_to_table.get(p)
        if t:
            always_scan.add(t)
    return (ps, dict(out), page_to_table, frozenset(always_scan))


def sqlite_tables(path):
    """sqlite_tables_read, kept while the database file and its -wal and -journal keep their size and date.
    When only the -wal changes on a DB whose main file is unchanged (including clones of a prepared slot), parses
    active WAL frame headers to re-scan only the modified tables instead of re-reading huge untouched tables."""
    main_st = _stat_key(path)
    wal_st = _stat_key(path + "-wal")
    jnl_st = _stat_key(path + "-journal")
    key = (path, main_st, wal_st, jnl_st)
    with _SQLITE_LOCK:
        hit = _SQLITE_CACHE.get(path)
    if hit and hit[0] == key:
        return hit[1]
    hdr = None
    try:
        with open(path, "rb") as f:
            hdr = f.read(4096)
    except OSError:
        return None
    if len(hdr) < 16 or hdr[:16] != b"SQLite format 3\0":
        with _SQLITE_LOCK:
            _SQLITE_CACHE[path] = (key, None)
        return None
    hdr100 = hdr[:100]
    # Key base cache by (basename, file size, SQLite header + page-1 digest) so APFS clones with fresh mtimes share it.
    base_key = (os.path.basename(path), main_st[0], hdr100, hash(hdr)) if (main_st is not None and jnl_st is None and len(hdr100) == 100) else None
    if base_key is not None:
        with _SQLITE_LOCK:
            base = _SQLITE_BASE_CACHE.get(base_key)
        if base is not None:
            ps, base_hashes, page_to_table, always_scan = base
            wal_pages = _sqlite_wal_pages(path + "-wal", ps) if wal_st is not None else set()
            if wal_pages is not None and 1 not in wal_pages:
                dirty = set(always_scan)
                for p in wal_pages:
                    t = page_to_table.get(p)
                    if t:
                        dirty.add(t)
                if not dirty:
                    out = dict(base_hashes)
                else:
                    out = None
                    try:
                        con = sqlite3.connect("file:" + urllib.parse.quote(path) + "?mode=ro", uri=True, timeout=2)
                        try:
                            con.text_factory = bytes
                            out = dict(base_hashes)
                            for t in sorted(dirty):
                                out[t] = _sqlite_hash_table(con, t)
                        finally:
                            con.close()
                    except sqlite3.Error:
                        out = None
                if out is not None:
                    with _SQLITE_LOCK:
                        if len(_SQLITE_CACHE) > 256:
                            _SQLITE_CACHE.clear()
                        _SQLITE_CACHE[path] = (key, out)
                    return out
    out, roots = sqlite_tables_read(path, with_roots=True)
    if out is _NOT_SQLITE:
        with _SQLITE_LOCK:
            _SQLITE_CACHE[path] = (key, None)
        return None
    if out is not None:
        if base_key is not None and roots is not None:
            base = _sqlite_build_base(path, hdr100, wal_st, roots, out)
            if base is not None:
                with _SQLITE_LOCK:
                    if len(_SQLITE_BASE_CACHE) > 64:
                        _SQLITE_BASE_CACHE.clear()
                    _SQLITE_BASE_CACHE[base_key] = base
        with _SQLITE_LOCK:
            if len(_SQLITE_CACHE) > 256:
                _SQLITE_CACHE.clear()
            _SQLITE_CACHE[path] = (key, out)
    return out


def sqlite_tables_read(path, with_roots=False):
    """A SQLite database's content, {table: hash of its rows}, read through a read-only connection (which reads the -wal
    beside it too), _NOT_SQLITE when it is not a database, or None when a database cannot be opened (then
    Instance.data_state uses size and date and keeps its -wal/-journal). A write-ahead log changes with any write, a
    checkpoint or Room's invalidation tracker included; only the rows say whether a screen would show something else."""
    try:
        with open(path, "rb") as f:
            if f.read(16) != b"SQLite format 3\0":
                return (_NOT_SQLITE, None) if with_roots else _NOT_SQLITE
    except OSError:
        return (None, None) if with_roots else None
    try:
        con = sqlite3.connect("file:" + urllib.parse.quote(path) + "?mode=ro", uri=True, timeout=2)
    except sqlite3.Error:
        return (None, None) if with_roots else None
    try:
        out = {}
        roots = con.execute("select name, rootpage from sqlite_master where type = 'table' order by name").fetchall()
        # SQLite does not enforce UTF-8 in TEXT columns; reading them as bytes avoids OperationalError on raw bytes.
        con.text_factory = bytes
        for t, _rp in roots:
            if t in SQLITE_BOOKKEEPING or t.startswith("sqlite_"):
                continue
            out[t] = _sqlite_hash_table(con, t)
        return (out, roots) if with_roots else out
    except sqlite3.Error:
        return (None, None) if with_roots else None
    finally:
        con.close()


def data_ignored(patterns, rel, key=None):
    """Whether --data-ignore PATTERN names the file `rel` (fnmatch) as a whole, or with a key `rel#key` (a
    SharedPreferences key or a database table)."""
    for pat in patterns:
        f, _, k = pat.partition("#")
        if not fnmatch.fnmatchcase(rel, f):
            continue
        if not k if key is None else (not k or fnmatch.fnmatchcase(key, k)):
            return True
    return False


EVENT_LOCK = threading.Lock()


class Gone(Exception):
    """The instance ended: [crash] says how."""

    def __init__(self, crash):
        super().__init__(crash.get("detail", "the instance ended"))
        self.crash = crash


class Instance(snapshot.Worker):
    """A headless instance and what a walk does with it: replaying paths, reading the screen, capturing."""
    fresh_at_root = False
    last_settled = True
    last_tree_stable = False
    parked = None

    def __init__(self, i, ctx):
        super().__init__(i, [dict(DEFAULT_COMBO)], ctx)
        self.stage = os.path.join(ctx["stage"], str(i))
        os.makedirs(self.stage, exist_ok=True)
        self.generation = 0
        self.slot_gen = None
        self.applied = {a: None for a in AXES}  # unknown: a launch option may have set any of them
        self.fresh_at_root = False
        self.last_settled = True
        self.last_tree_stable = False
        self.crashes = ctx["crashes"]
        self.slot = None      # the data slot's folders (the slot and its .device), read from the launch's log
        self.baseline = None  # what they held once this launch's app had started (see reach)

    def launch(self):
        if STOPPING.is_set():  # the walk is being stopped: no instance is started again, no copy made again
            raise Gone({"detail": "the walk was stopped"})
        next_gen = self.generation + 1
        if self.ctx.get("restore"):
            # --keep-data: this instance's copy of the prepared slot, made again from it (the data the app had at launch).
            if self.proc is not None and self.proc.poll() is None:
                self.quit()
            try:
                self.own_options = self.ctx["restore"](self.i, next_gen)
            except TypeError:
                self.own_options = self.ctx["restore"](self.i)
            self.slot_gen = next_gen
        if NET is not None:
            # --freeze-network: answered from the walk's recording (as it is now), what it lacks fetched and kept. The
            # last launch must have ended (and written what it fetched) before that is merged.
            if self.proc is not None and self.proc.poll() is None:
                self.quit()
            kept = list(self.own_options) if self.ctx.get("restore") else []
            self.own_options = kept + NET.options(f"{self.ctx['name']}-{self.i}")
        self.generation = next_gen
        self.slot = self.baseline = None
        self.log = os.path.join(self.ctx["out"], "logs", f"{self.ctx['name']}-instance-{self.i}.{self.generation}.log")
        self.proc = self.file = self.sock = None
        has_override = any(o in LAUNCH_CONFIG_FLAGS for o in self.ctx["apksim"])
        self.applied = {a: None for a in AXES} if has_override else dict(DEFAULT_COMBO)
        self.fresh_at_root = not has_override
        self.last_settled = True
        self.parked = None
        self.start()

    def ensure(self):
        if self.file is None or self.proc is None or self.proc.poll() is not None:
            self.close()
            self.launch()

    def say(self, cmd):
        """Sends a command; an instance that ends meanwhile raises Gone with what the log says."""
        if not cmd.startswith(("ui-", "activity", "nodes", "permission", "pick ", "shot ", "stats", "ping", "deps")):
            self._cached_data_state = None
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
        if self.ctx.get("explore_fast") and cmd.startswith("drag ") and getattr(self, "_has_fast_drag", True):
            reply = self.say("fast-" + cmd)
            if reply.startswith("unknown"):
                self._has_fast_drag = False
                reply = self.say(cmd)
        else:
            reply = self.say(cmd)
        if reply.startswith("error: app crashed"):  # the command ran app code that threw: the process is ending
            raise Gone(self.ended(cmd))
        if reply.startswith(snapshot.FAILED_REPLIES):
            raise ReplayFailed(f"{cmd!r}: {reply}")
        return reply

    def settle(self, cmd=SETTLE):
        """Waits for the screen to be still; a screen that animates for ever is read as it is."""
        if self.ctx.get("explore_fast"):
            cmd = FAST_SETTLES.get(cmd, cmd)
        reply = self.say(cmd)
        ok = not reply.startswith("error")
        self.last_settled = ok
        self.last_tree_stable = "(tree stable)" in reply
        return ok

    def wait_longer(self):
        """More time for what a replay expects and does not find yet: a label that comes with the network's answer, a
        screen still loading. A real wait first, as `settle` does not wait for a request or a coroutine's delay()."""
        patience = self.ctx.get("patience_s", PATIENCE_S)
        if self.ctx.get("explore_fast"):
            time.sleep(min(patience, 0.4))
            self.settle(SETTLE_LONG)
        else:
            time.sleep(patience)
            self.say(SETTLE_LONG)

    def step(self, cmd, alt=None, settle_cmd=SETTLE):
        """One replay step, tried again after wait_longer when it fails (a reply that failed did nothing), then as [alt]
        (the same tap by its coordinates, for a label that live data changed) when given."""
        self.last_tree_stable = False
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
    def read_ui(self):
        """Reads (activity, recs) in one round-trip via `ui-tree`, falling back to `activity` + `nodes tree`."""
        if getattr(self, "_has_ui_tree", True):
            reply = self.say("ui-tree fast" if self.ctx.get("explore_fast") else "ui-tree")
            if reply == "none":
                return "none", []
            if "\x1d" in reply:
                act, tree = reply.split("\x1d", 1)
                return act, parse_tree(tree)
            if reply.startswith("unknown"):
                self._has_ui_tree = False
        act = self.say("activity")
        if act == "none":
            return "none", []
        return act, parse_tree(self.say("nodes tree"))

    def observe(self, expected_id=None):
        """The screen as it is: read until two reads in a row agree (a list still composing, a transition ending).
        When [expected_id] is given (confirming arrival at a known screen), a matching first read returns immediately."""
        single = self.last_tree_stable and expected_id is None and self.ctx.get("explore_fast")
        self.last_tree_stable = False
        prev = None
        for attempt in range(1 if single else 4):
            act, recs = self.read_ui()
            if act == "none":
                return {"id": "EXIT", "activity": "none", "nodes": 0, "recs": []}
            sid, n = structure(act, recs)
            if expected_id is not None and sid == expected_id:
                break
            if sid == prev:
                break
            prev = sid
            if not single and attempt < 3:
                # `settle` does not wait for a coroutine's delay() or a posted Runnable: a screen that fills in a
                # moment after it is still is read again until two reads --stable-ms apart agree.
                time.sleep(self.ctx.get("stable_ms", 200) / 1000)
        return {"id": sid, "activity": act, "nodes": n, "recs": recs}

    def wait_waypoint(self, want_id, prev_id=None, timeout=1.5, exact=False):
        """Waits for [want_id] or a stabilized screen change using Host's in-process `wait-tree` (1 round-trip),
        falling back to `ui-tree` polling on older hosts. With [exact], only returns early when [want_id] arrives
        (used when waiting through async startup/splash/biometric transitions to reach the root screen)."""
        if getattr(self, "_has_wait_tree", True):
            deadline = time.monotonic() + timeout
            last = None
            for _ in range(10 if exact else 3):
                rem_ms = max(50, int((deadline - time.monotonic()) * 1000))
                reply = self.say(f"wait-tree {rem_ms}")
                if reply.startswith("unknown"):
                    self._has_wait_tree = False
                    break
                if reply == "none":
                    return {"id": "EXIT", "activity": "none", "nodes": 0, "recs": []}
                if "\x1d" in reply:
                    act, body = reply.split("\x1d", 1)
                    recs = parse_tree(body)
                    sid, n = structure(act, recs)
                    last = {"id": sid, "activity": act, "nodes": n, "recs": recs}
                    if sid == want_id or (not exact and sid != prev_id) or time.monotonic() >= deadline:
                        return last
                    continue
                break
            if getattr(self, "_has_wait_tree", True) and last is not None:
                return last
        delays = (0.02, 0.025, 0.035, 0.05, 0.08, 0.12, 0.18, 0.25)
        deadline = time.monotonic() + timeout
        last = None
        seen_other = None
        for d in delays:
            rem = deadline - time.monotonic()
            if rem <= 0:
                break
            time.sleep(min(d, rem))
            act, recs = self.read_ui()
            if act == "none":
                return {"id": "EXIT", "activity": "none", "nodes": 0, "recs": []}
            sid, n = structure(act, recs)
            last = {"id": sid, "activity": act, "nodes": n, "recs": recs}
            if sid == want_id:
                return last
            if not exact and sid != prev_id:
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

    def data_state(self, use_cache=False):
        """What the slot holds, {file: state}: a SharedPreferences file as its values ({key: value}), any other as
        (size, mtime), so a write anywhere changes it; a SQLite database as its tables' content ({table: hash}, see
        sqlite_tables: its -wal and -journal are left out, unless it cannot be read: then they are compared like any file). Left out: what no screen reads (BOOKKEEPING): an app's caches,
        AndroidX's profile installer (which writes once some seconds after every start, so each new launch would want
        another), WorkManager's own database, SQLite's shared memory index (-shm, which readers write too), the slot's
        lock."""
        if use_cache:
            cached = getattr(self, "_cached_data_state", None)
            if cached is not None:
                return cached
        out = {}
        ignore = self.ctx.get("data_ignore") or ()
        read_dbs = set()
        for root in self.slot_dirs():
            for d, dirs, files in os.walk(root):
                dirs.sort()
                for n in sorted(files):
                    p = os.path.join(d, n)
                    rel = os.path.relpath(p, root)
                    if BOOKKEEPING.search(rel) or data_ignored(ignore, rel):
                        continue
                    base = rel[:-4] if rel.endswith("-wal") else rel[:-8] if rel.endswith("-journal") else None
                    if base is not None and ((root, base) in read_dbs or data_ignored(ignore, base)):
                        continue    # a database read by its content or ignored: its log says nothing more
                    if rel.startswith("shared_prefs/"):
                        prefs = shared_prefs(p) if n.endswith(".xml") else None
                    else:
                        prefs = sqlite_tables(p)
                        if prefs is not None:
                            read_dbs.add((root, rel))
                    if prefs is not None:
                        if ignore:
                            prefs = {k: v for k, v in prefs.items() if not data_ignored(ignore, rel, k)}
                        out[rel] = prefs
                        continue
                    try:
                        st = os.stat(p)
                    except OSError:
                        continue
                    out[rel] = (st.st_size, st.st_mtime_ns)
        # Kept by the instance, not the slot, and outliving `restart` too: a permission request no one answered (the
        # walk never answers one) and runtime grants made since the launch.
        if not data_ignored(ignore, "<permission>"):
            out["<permission>"] = self.say("permission")
        self._cached_data_state = out
        return out

    def changed_files(self):
        """What was written since the baseline (see reach): files added, removed or written, and for preferences the
        keys whose value changed (`file#key`). A preference key that appears is a change unless it appeared with that
        value while a path was replayed (learn_defaults): opening a settings screen writes its defaults (a fresh slot
        has none), which every replay through it repeats, while a control tried on a screen (a toggle) is not on a path."""
        if self.baseline is None:
            return []
        before, now = self.baseline, self.data_state(use_cache=True)
        out = []
        with self.ctx["lock"]:
            first_vals = dict(self.ctx.get("first_values") or ())
            nav_vals = dict(self.ctx.get("nav_values") or ())
            startup_writes = {k: len(v) for k, v in (self.ctx.get("startup_writes") or {}).items()}
        for f in sorted(before.keys() | now.keys()):
            a, b = before.get(f), now.get(f)
            if a == b:
                continue
            if isinstance(b, dict) and (a is None or isinstance(a, dict)):
                for k in sorted((a or {}).keys() | b.keys()):
                    if (a or {}).get(k) == b.get(k):
                        continue
                    if k not in (a or {}) and k in b:
                        if first_vals.get((f, k), _MISSING) == b[k]:
                            continue
                    elif k in b:
                        nv = nav_vals.get((f, k))
                        if nv is not None and nv is not _CONFLICT and nv[1] >= 2 and nv[0] == b[k]:
                            continue
                        if startup_writes.get((f, k), 0) >= 3:
                            continue
                    out.append(f"{f}#{k}")
                continue
            out.append(f)
        return out

    def retire(self):
        """Non-blocking discard of a dirty instance onto a background reaper thread so the worker can immediately
        clone a fresh generation slot and grab the next warm standby without waiting ~440 ms for OS VM teardown."""
        proc, gen = self.proc, self.slot_gen
        if self.file is not None and proc is not None and proc.poll() is None:
            try:
                self.file.write("quit fast\n")
                self.file.flush()
            except OSError:
                pass
        self.close()
        self.proc = None
        self.slot_gen = None
        if proc is None:
            return
        cleanup = self.ctx.get("remove_slot")
        i = self.i

        def reap():
            for stop, wait_s in ((None, 2), (proc.terminate, 2), (proc.kill, 5)):
                if stop:
                    try:
                        stop()
                    except OSError:
                        pass
                try:
                    proc.wait(timeout=wait_s)
                    break
                except subprocess.TimeoutExpired:
                    pass
            if cleanup and gen is not None:
                cleanup(i, gen)

        t = threading.Thread(target=reap, daemon=True)
        t.start()
        reapers = self.ctx.get("reapers")
        if reapers is not None:
            with self.ctx["lock"]:
                reapers.append(t)

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
        if changed == ["<permission>"] and self.baseline.get("<permission>") == "waiting: none; granted: none":
            try:
                if self.say("permission reset") == "ok":
                    return
            except Gone:
                self.close()
                self.launch()
                return
        if changed:
            if NET is None and self.generation > 1 and self.ctx.get("restore") and self.ctx.get("remove_slot"):
                self.retire()
            else:
                try:
                    self.quit(fast=self.generation > 1 and NET is None)
                except TypeError:
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

    def learn_defaults(self, nav_depth=0):
        """After a clean path's steps (navigation, before any control is tried):
        1. Absent preference keys that appeared since baseline are recorded in first_values.
        2. Existing SQLite tables/keys updated during shallow navigation (nav_depth <= 3) with one consistent value
           across >=2 navigations are recorded in nav_values (any conflicting value marks the key mutable permanently)."""
        if self.baseline is None:
            return
        now = self.data_state(use_cache=True)
        with self.ctx["lock"]:
            nav_values = self.ctx.setdefault("nav_values", {})
            startup_writes = self.ctx.setdefault("startup_writes", {})
            launch_id = (self.i, self.generation)
            for f, b in now.items():
                a = self.baseline.get(f)
                if isinstance(b, dict) and (a is None or isinstance(a, dict)):
                    for k, v in b.items():
                        if k not in (a or {}):
                            self.ctx["first_values"].setdefault((f, k), v)
                        elif nav_depth <= 3 and (a or {}).get(k) != v:
                            if nav_depth <= 2:
                                startup_writes.setdefault((f, k), set()).add(launch_id)
                            prev = nav_values.get((f, k))
                            if prev is None:
                                nav_values[(f, k)] = (v, 1)
                            elif prev is not _CONFLICT:
                                if prev[0] == v:
                                    nav_values[(f, k)] = (v, prev[1] + 1)
                                else:
                                    nav_values[(f, k)] = _CONFLICT

    def reach(self, path, extra=(), relaunch=True, patient=False, alt=None, waypoints=None, explore_fast=False, need_deps=False):
        """Back to a screen: the default configuration, a fresh start (at the path's deep link), the path's steps.
        From the data the app had at its start: a launch whose app has written to its data since is replaced by a
        new one on an emptied slot (--fresh) first, or with --keep-data on a new copy of the prepared slot.
        [patient] waits longer for the start screen first; [alt] is the path's steps with text taps as coordinates,
        each tried when its step fails twice (Explorer.alt_paths)."""
        self.parked = None
        self.fresh_start() if relaunch else self.ensure()
        self.configure(DEFAULT_COMBO)
        if self.fresh_at_root and not path.get("start") and not (self.ctx.get("record_deps") and need_deps):
            self.fresh_at_root = False
            if explore_fast and path["steps"] and self.ctx.get("root_id") and not need_deps:
                act, recs = self.read_ui()
                if act == "none" or structure(act, recs)[0] != self.ctx["root_id"]:
                    self.wait_waypoint(self.ctx["root_id"], timeout=4.0, exact=True)
            else:
                self.settle(WAYPOINT_SETTLE if (explore_fast and path["steps"]) else SETTLE)
                if path["steps"] and self.ctx.get("root_id"):
                    act, recs = self.read_ui()
                    if act == "none" or structure(act, recs)[0] != self.ctx["root_id"]:
                        self.wait_waypoint(self.ctx["root_id"], timeout=4.0, exact=True)
        else:
            self.fresh_at_root = False
            self.say(f"clock {CLOCK}")
            self.expect_ok("restart" + (f" {path['start']}" if path.get("start") else ""))
            if explore_fast and path["steps"] and not path.get("start") and self.ctx.get("root_id") and not need_deps:
                act, recs = self.read_ui()
                if act == "none" or structure(act, recs)[0] != self.ctx["root_id"]:
                    self.wait_waypoint(self.ctx["root_id"], timeout=4.0, exact=True)
            else:
                self.settle(WAYPOINT_SETTLE if (explore_fast and path["steps"]) else SETTLE)
                if path["steps"] and not path.get("start") and self.ctx.get("root_id"):
                    act, recs = self.read_ui()
                    if act == "none" or structure(act, recs)[0] != self.ctx["root_id"]:
                        self.wait_waypoint(self.ctx["root_id"], timeout=4.0, exact=True)
        if patient:
            self.wait_longer()
        if self.baseline is None and self.from_launch_data():
            # Once the app's start has written what it writes at every start (taken after a restart and a settle).
            self.baseline = self.data_state()
        steps = list(path["steps"])
        alt = list(alt) if alt and len(alt) == len(steps) else [None] * len(steps)
        last_obs = None
        prev_wp = self.ctx.get("root_id") if not path.get("start") else None
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
                if not getattr(self, "_has_fast_drag", True):
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
        if self.from_launch_data() and relaunch:
            self.learn_defaults(nav_depth=len(steps))
        for step in extra:
            sc = (None if (explore_fast and getattr(self, "_has_fast_drag", True)) else SCROLL_SETTLE) if step.startswith("drag ") else SETTLE
            self.step(step, settle_cmd=sc)
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

    def reach_screen(self, screen, extra=(), patient=False, alt=None, lenient=False, waypoints=None, explore_fast=False, relaunch=True, need_deps=False):
        """Replays the screen's path and checks where it ended; [extra] steps (scrolls, a fill) follow the check.
        A screen of another structure is read again after wait_longer. [lenient]: one of another structure in the
        same activity will do (a screen whose content changes between launches), returned with "drift" set."""
        last_obs = self.reach(screen["path"], relaunch=relaunch, patient=patient, alt=alt,
                              waypoints=waypoints, explore_fast=explore_fast, need_deps=need_deps)
        got = last_obs if (last_obs is not None and last_obs["id"] == screen["id"]) else self.observe(expected_id=screen["id"])
        if got["id"] != screen["id"] and not lenient and relaunch:
            self.wait_longer()
            got = self.observe(expected_id=screen["id"])
        if got["id"] != screen["id"]:
            if not (lenient and got["activity"] == screen["activity"]):
                raise ReplayFailed(f"replay reached {got['id']}, not {screen['id']}")
            got["drift"] = True
        for step in extra:
            sc = (None if (explore_fast and getattr(self, "_has_fast_drag", True)) else SCROLL_SETTLE) if step.startswith("drag ") else SETTLE
            self.step(step, settle_cmd=sc)
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
        self.map = {sys.intern(k): tuple(sys.intern(s) for s in v) for k, v in affected.source_map([apk], packages).items()}
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
        cls = [sys.intern(c) for c in raw["classes"]]
        res = [sys.intern(r) for r in raw["resources"]]
        return {"classes": cls, "sources": self.sources(cls), "resources": res}


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
            if not re.fullmatch(r"k[0-9a-f]{8}\d+(?:g\d+)?-0", n):
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

    def slot_name(self, i, gen=0):
        return f"k{self.token}{i}g{gen}" if gen else f"k{self.token}{i}"

    def slot(self, i, gen=0):
        return os.path.join(self.data, f"{self.slot_name(i, gen)}-0")

    def restore(self, i, gen=0):
        """Instance i's copy, made afresh from the prepared slot; the options that make its launch take it."""
        name = self.slot_name(i, gen)
        dst = os.path.join(self.data, f"{name}-0")
        with self.lock:
            self.made.add((i, gen))
        for d in (dst, dst + ".device"):
            shutil.rmtree(d, ignore_errors=True)
        for s, d in ((self.source, dst), (self.source + ".device", dst + ".device")):
            if os.path.isdir(s):
                copy_tree(s, d)
        return ["--data-slots", name]

    def remove_one(self, i, gen=0):
        dst = self.slot(i, gen)
        for d in (dst, dst + ".device"):
            shutil.rmtree(d, ignore_errors=True)
        with self.lock:
            self.made.discard((i, gen))

    def remove(self):
        with self.lock:
            made, self.made = sorted(self.made), set()
        for entry in made:
            i, gen = entry if isinstance(entry, tuple) else (entry, 0)
            for d in (self.slot(i, gen), self.slot(i, gen) + ".device"):
                shutil.rmtree(d, ignore_errors=True)


class NetFreeze:
    """--freeze-network: every launch of the walk answered from one recording, OUT/network.har, so a screen whose content
    comes from a server shows the same data at each replay. A launch replays the recording as it is when it starts
    (copied, so a merge cannot change it under the launch) and fetches what it lacks from the server
    (`--network-unmatched fetch:FILE`); what it fetched is merged into the recording when it ends, the first answer to a
    request kept (method, URL, body: the engine's default match). A seeded walk starts from its seed's recording.

    Beside the recording, network.har.secrets (mode 600) holds the values of the answers' redacted headers (a sign-in's
    Set-Cookie), which apksim writes beside what a launch fetched: merged and copied with the recording, so a launch
    whose sign-in is replayed gets the server's cookie and its fetches are signed in. The recording itself never holds
    them. A 401/403 a launch fetched and left out because its sign-in was replayed without that cookie (or the server
    refused the replayed one) is counted here (not_kept) for crawl.json."""

    def __init__(self, out, seed_dir=None):
        # Keep per-launch fetch files under apksim's STATE/tmp so --freeze-network stays warm with --standby
        # (a standby's sandbox allows writes under STATE, whereas OUT may be outside STATE).
        state = os.environ.get("APKSIM_HOME") or (
            os.path.join(os.path.expanduser("~"), ".apksim")
            if os.path.isfile(os.path.join(ROOT, "work", "release"))
            else os.path.join(ROOT, "work"))
        net_tmp = os.path.join(state, "tmp", f"crawl-net-{os.getpid()}-{id(self):x}")
        try:
            os.makedirs(net_tmp, exist_ok=True)
            self.dir = net_tmp
            self._in_state_tmp = True
        except OSError:
            self.dir = os.path.join(out, "network")
            os.makedirs(self.dir, exist_ok=True)
            self._in_state_tmp = False
        self.master = os.path.join(out, "network.har")
        self.lock = threading.Lock()
        src = os.path.join(seed_dir, "network.har") if seed_dir else None
        if src and os.path.abspath(src) != os.path.abspath(self.master) and os.path.isfile(src):
            shutil.copyfile(src, self.master)
            self.copy_secrets(src, self.master)
        if not os.path.isfile(self.master):
            self.write(self.empty())
        self.fetched = 0  # entries merged in by this walk
        self.not_kept = 0  # 401/403 answers launches fetched and left out (a sign-in replayed without its cookie)
        self.not_kept_requests = []  # the first few, for crawl.json
        self.not_kept_seen = set()
        self.not_kept_by_launch = {}  # tag: what its running launch's file said so far (the file's count only grows)

    SECRETS = ".secrets"

    @staticmethod
    def secret_key(method, url, started):
        return (method, url, started)

    @classmethod
    def read_secrets(cls, har_path):
        """{(method, url, startedDateTime): item} of HAR's .secrets file; empty when there is none or it is cut short."""
        try:
            with open(har_path + cls.SECRETS) as f:
                doc = json.load(f)
            return {cls.secret_key(i.get("method"), i.get("url"), i.get("startedDateTime")): i for i in doc["entries"]}
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            return {}

    @classmethod
    def write_secrets(cls, har_path, items):
        path = har_path + cls.SECRETS
        tmp = path + ".tmp"
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump({"comment": "apksim: the values of the headers redacted in " + os.path.basename(har_path)
                       + ", so a replay can sign the app in again. Private: do not share or commit this file.",
                       "entries": list(items)}, f)
        os.chmod(tmp, 0o600)
        os.replace(tmp, path)

    @classmethod
    def copy_secrets(cls, src_har, dst_har):
        """dst's .secrets made a private copy of src's, or removed when src has none."""
        items = cls.read_secrets(src_har)
        if items:
            cls.write_secrets(dst_har, items.values())
        else:
            try:
                os.remove(dst_har + cls.SECRETS)
            except OSError:
                pass

    @staticmethod
    def empty():
        return {"log": {"version": "1.2", "creator": {"name": "apksim crawl.py", "version": "1"}, "entries": []}}

    @staticmethod
    def key(e):
        req = e.get("request") or {}
        return (req.get("method"), req.get("url"), ((req.get("postData") or {}).get("text") or ""))

    @staticmethod
    def read(path):
        try:
            with open(path) as f:
                doc = json.load(f)
            return doc if isinstance(doc.get("log", {}).get("entries"), list) else None
        except (OSError, ValueError, AttributeError):
            return None

    def write(self, doc):
        tmp = self.master + ".tmp"
        with open(tmp, "w") as f:
            json.dump(doc, f)
        os.replace(tmp, self.master)

    def options(self, tag):
        """The launch options of instance [tag]'s next launch; what its last launch fetched is merged first."""
        self.merge(tag)
        self.not_kept_by_launch.pop(tag, None)  # that launch has ended: the next one counts from 0
        replay = os.path.join(self.dir, f"replay-{tag}.har")
        with self.lock:
            shutil.copyfile(self.master, replay)
            self.copy_secrets(self.master, replay)
        return ["--network", "replay", replay, "--network-unmatched", "fetch:" + os.path.join(self.dir, f"fetched-{tag}.har")]

    def merge(self, tag):
        path = os.path.join(self.dir, f"fetched-{tag}.har")
        got = self.read(path)
        if got is None:
            return
        secrets = self.read_secrets(path)
        with self.lock:
            doc = self.read(self.master) or self.empty()
            have = {self.key(e) for e in doc["log"]["entries"]}
            added = 0
            new_secrets = []
            for e in got["log"]["entries"]:
                k = self.key(e)
                if k not in have:
                    have.add(k)
                    doc["log"]["entries"].append(e)
                    added += 1
                    req = e.get("request") or {}
                    item = secrets.get(self.secret_key(req.get("method"), req.get("url"), e.get("startedDateTime")))
                    if item:
                        new_secrets.append(item)
            if added:
                if new_secrets:  # before the recording: a launch that copies the entry finds its secrets
                    kept = self.read_secrets(self.master)
                    self.write_secrets(self.master, list(kept.values()) + new_secrets)
                self.write(doc)
                self.fetched += added
            left_out = got["log"].get("_apksimNotKept")
            if isinstance(left_out, dict):
                # A launch still running writes its file again, with its whole count: only the increase is new.
                count = int(left_out.get("count") or 0)
                self.not_kept += max(0, count - self.not_kept_by_launch.get(tag, 0))
                self.not_kept_by_launch[tag] = max(count, self.not_kept_by_launch.get(tag, 0))
                for r in left_out.get("requests") or []:
                    k = (r.get("method"), r.get("url"), r.get("status"))
                    if k not in self.not_kept_seen and len(self.not_kept_requests) < 20:
                        self.not_kept_seen.add(k)
                        self.not_kept_requests.append(r)
        for p in (path, path + self.SECRETS):
            try:
                os.remove(p)
            except OSError:
                pass

    def merge_all(self):
        for n in sorted(os.listdir(self.dir)):
            m = re.fullmatch(r"fetched-(.+)\.har", n)
            if m:
                self.merge(m.group(1))

    def finish(self):
        """At the end of the walk: everything fetched merged, and the launches' copies of the recording gone."""
        self.merge_all()
        for n in os.listdir(self.dir):
            if n.startswith("replay-"):
                try:
                    os.remove(os.path.join(self.dir, n))
                except OSError:
                    pass


NET = None  # --freeze-network: the walk's NetFreeze (main)


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

    def __init__(self, name, apk, args, out, extra_options, shots="screens", combos=(), snapshot_override=None):
        self.name, self.apk, self.args, self.out, self.shots = name, apk, args, out, shots
        self.capture_combos = list(combos)
        self.default_combo_name = snapshot.combo_name(DEFAULT_COMBO)
        self.inline_capture = (any(snapshot.combo_name(c) == self.default_combo_name for c in self.capture_combos)
                               and not args.keep_app_state)
        self.explore_fast = not (args.base or args.base_option or args.sweep)
        self.is_seeded = bool(args.seed)
        if getattr(args, "no_dedup_variants", False):
            self.dedup_variants = False
        elif hasattr(args, "_seed_dedup_variants") and args._seed_dedup_variants is not None:
            self.dedup_variants = bool(args._seed_dedup_variants)
        else:
            self.dedup_variants = self.explore_fast and not self.is_seeded
        self.variant_sigs = {}     # sid -> (activity, norm_title, text_set, ctrl_set, short_ctrl_set, parent_id)
        self.variant_aliases = {}  # raw_sid -> canonical_sid
        self.diff_controls = getattr(args, "diff_controls", "all") or ("changed" if getattr(args, "diff_seed", False) else "all")
        self.seed_actions = {}
        self.seed_edges_by_from = {}
        self.options = list(args.apksim) + list(extra_options)
        # Never the owner's saved sign-ins, and a slot namespace of the crawler's own: --fresh would empty an ordinary
        # launch's `default` slot (--keep-data walks copies of a prepared slot: KeptSlot; an option of the caller's own wins).
        self.options += ["--no-autofill"]
        # Headless walks have no speakers and need not render continuously animating screens at 120 fps (Host gives
        # post-input reaction frames a 120 Hz burst automatically, then drops back to this cap for idle animations).
        if "--max-fps" not in self.options:
            self.options += ["--max-fps", "30"]
        if "--audio" not in self.options:
            self.options += ["--audio", "off"]
        if self.explore_fast and not any("apksim.fastTransitions" in o for o in self.options):
            self.options += ["--jvm", "-Dapksim.fastTransitions=true"]
        if self.explore_fast and not any("apksim.lazyRaster" in o for o in self.options):
            self.options += ["--jvm", "-Dapksim.lazyRaster=true"]
        # One device identity for every instance (the serial and ANDROID_ID come from the slot's name otherwise, and a
        # screen that shows one would differ between instances, and between builds walked on other slots).
        # Not with --keep-data: the prepared slot's own identity is what its sign-in was made under (KeptSlot passes it on).
        if not args.keep_data and "--serial" not in self.options:
            self.options += ["--serial", "CRAWL0000000000"]
        if not args.keep_data and not any(o.startswith("secure:android_id=") for o in self.options):
            self.options += ["--setting", "secure:android_id=00c0ffee00c0ffee"]
        if not args.keep_data and "--data-slots" not in self.options:
            self.options += ["--data-slots", "crawl"]
        if args.keep_data and "--biometrics" not in self.options:
            self.options += ["--biometrics", "auto"]
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
        self.on_captured = None  # --diff-seed: called with (screen id, instance) once a screen's captures are all taken
        self.cap_left = {}
        self.first_recs = {}    # id -> the records the screen showed when it was found (a flaky screen's controls)
        self.alt_paths = {}     # id -> its path's steps with every tap as coordinates (Instance.reach's alt)
        os.makedirs(os.path.join(out, "logs"), exist_ok=True)
        self.stage = tempfile.mkdtemp(prefix="apksim-crawl-")
        if re.search(r"\s", self.stage):
            fail(f"the temporary folder {self.stage!r} has whitespace in its path: set TMPDIR")
        # Pixel-exact CPU rendering (--snapshot) is needed for cross-build / sweep diffs; plain walks use GPU rendering.
        use_snapshot = bool(args.base or args.base_option or args.sweep) if snapshot_override is None else bool(snapshot_override)
        self.use_snapshot = use_snapshot
        self.ctx = {"spec": {"timezone": "UTC", "clock": CLOCK}, "apk": apk, "apksim": self.options, "out": out,
                    "stage": self.stage, "snapshot": use_snapshot, "explore_fast": self.explore_fast,
                    "standby": bool((getattr(args, "diff_seed", False) or getattr(args, "standby", False)) and not use_snapshot),
                    "standby_opt_in": bool(getattr(args, "standby", False) and not use_snapshot),
                    "fresh": not args.keep_data, "screens": [],
                    "record": lambda r: None, "crashes": self.findings, "stable_ms": args.stable_ms, "name": name,
                    "keep_state": args.keep_app_state, "restore": self.kept.restore if self.kept else None,
                    "remove_slot": self.kept.remove_one if self.kept else None, "reapers": [],
                    "data_ignore": list(args.data_ignore or []),
                    "relaunches": [0], "relaunch_files": {}, "first_values": {}, "nav_values": {}, "startup_writes": {}, "unsettled": set(),
                    "record_deps": bool(args.record_deps), "lock": threading.Lock(),
                    "spawn": (SPAWN, STOPPING), "skip_re": self.skip_re}
        self.inline_shots = {}   # newly discovered sid -> (rel_path, settled) captured inline on first observation
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
        def stop(inst):
            try:
                inst.quit(fast=True)
            except TypeError:
                inst.quit()

        if len(self.instances) > 1:
            threads = [threading.Thread(target=stop, args=(inst,)) for inst in self.instances]
            for t in threads:
                t.start()
            for t in threads:
                t.join()
        else:
            for inst in self.instances:
                stop(inst)
        for t in list(self.ctx.get("reapers") or ()):
            t.join(timeout=10)
        if self.kept:
            self.kept.remove()
        if NET is not None:
            NET.finish()
        shutil.rmtree(self.stage, ignore_errors=True)
        # Clean up per-worker standby JVMs spawned during this walk so idle JVMs do not linger in RAM.
        if self.ctx.get("standby") and self.instances:
            launcher = os.path.join(ROOT, "apksim")
            if os.path.exists(launcher):
                try:
                    subprocess.run([launcher, "--stop-standbys"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5)
                except Exception:  # noqa: BLE001
                    pass

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
            line = json.dumps(dict({"event": kind, "build": self.name}, **fields)) + "\n"
            with EVENT_LOCK:  # capture threads finish screens at once: one write per line
                sys.stdout.write(line)
                sys.stdout.flush()

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
        if len(starts) == 1 and starts[0] is None and level:
            self.ctx["root_id"] = level[0]["id"]
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
                    if to["id"] is not None and to["id"] not in PSEUDO and to["id"] not in self.screens:
                        steps = self.full_path(screen, e["steps"])["steps"]
                        dup_id = self.find_variant_match(screen["id"], to, edge_cmd=e.get("cmd"))
                        if dup_id is not None:
                            with self.lock:
                                self.variant_aliases[to["id"]] = dup_id
                            shot_info = self.inline_shots.pop(to["id"], None)
                            if shot_info is not None:
                                try:
                                    shutil.rmtree(os.path.join(self.out, self.shots, to["id"]), ignore_errors=True)
                                except OSError:
                                    pass
                            edge["to"] = dup_id
                            edge["note"] = f"variant of {dup_id}"
                        elif repeated(steps, args.max_repeat):
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
                            shot_info = self.inline_shots.pop(new["id"], None)
                            if shot_info is not None:
                                rel, settled = shot_info
                                new["captures"][self.default_combo_name] = rel
                                new["settled"] = settled if "settled" not in new else settled and new["settled"]
                                if not settled:
                                    new.setdefault("unsettled", []).append(self.default_combo_name)
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
        for sid, info in list(self.inline_shots.items()):
            if info is not None and sid not in self.screens:
                shutil.rmtree(os.path.join(self.out, self.shots, sid), ignore_errors=True)
        self.inline_shots.clear()
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
            if self.dedup_variants:
                norm = lambda x: re.sub(r"\s+", " ", re.sub(r"\b\d+(?:[.,:/-]\d+)*\b", "#", x or "")).strip().lower()
                nt = norm(c.get("title") or "")
                ts = frozenset(norm(t) for t in (c.get("texts") or []) if t and len(t.strip()) > 1)
                cs = frozenset(norm(a.get("label") or "") for a in (c.get("actions") or []) if a.get("label"))
                scs = frozenset(x for x in cs if 2 <= len(x) <= 28 and not x.startswith("<view"))
                self.variant_sigs[c["id"]] = (c.get("activity", ""), bool(c.get("overlay")), nt, ts, cs, scs, c.get("parent"))
            if c.get("settled") is False:
                self.ctx["unsettled"].add(c["id"])
        if self.dedup_variants and isinstance(seed.get("variant_aliases"), dict):
            self.variant_aliases.update(seed["variant_aliases"])
        for sid in self.order:
            self.seed_waypoints(sid)
        self.seed_actions = {s["id"]: list(s.get("actions") or []) for s in seed["screens"] if s.get("actions") is not None}
        self.seed_edges_by_from = {}
        for e in seed["edges"]:
            self.seed_edges_by_from.setdefault(e["from"], []).append(dict(e))
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
                              **view_of(obs["recs"]))
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
                      "title": title_of(obs["recs"]), **view_of(obs["recs"]), "origin": "walked"}
                self.screens[new_id] = ns
                self.order[self.order.index(old)] = new_id
            ns["replaces"] = old
            now[old] = new_id
            del self.screens[old]
            if self.dedup_variants:
                self.variant_sigs.pop(old, None)
                if obs.get("recs"):
                    self.variant_sigs[new_id] = self._variant_sig(obs["activity"], obs["recs"], ns.get("parent"))
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
        """An --only screen's stored path replayed: what it reaches now ({obs}, a {note} when a step failed) or a {crash}.
        Leaves [inst] parked on the reached screen so discover_screen reuses it without a redundant replay or relaunch."""
        note = None
        wp = self.waypoints.get(screen["id"])
        for attempt in range(2):
            try:
                last_obs = inst.reach(screen["path"], waypoints=wp, explore_fast=self.explore_fast)
                obs = last_obs if (last_obs is not None and last_obs["id"] == screen["id"]) else inst.observe(expected_id=screen["id"])
                s_state = inst.data_state() if inst.from_launch_data() else None
                inst.parked = (obs["id"], obs, s_state)
                return {"obs": obs}
            except ReplayFailed as e:
                note = str(e)
                if attempt:
                    try:
                        obs = inst.observe(expected_id=screen["id"])
                        s_state = inst.data_state() if inst.from_launch_data() else None
                        inst.parked = (obs["id"], obs, s_state)
                        return {"obs": obs, "note": note}
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

    def _variant_sig(self, act, recs, parent=None):
        norm = lambda s: re.sub(r"\s+", " ", re.sub(r"\b\d+(?:[.,:/-]\d+)*\b", "#", s or "")).strip().lower()
        has_overlay = sum(1 for r in recs if r.depth == 0) > 1
        nt = norm(title_of(recs))
        ts = frozenset(norm(r.text) for r in recs if "v" in r.flags and r.text and len(r.text.strip()) > 1)
        cs = frozenset(norm(a["label"]) for a in candidates(recs, self.skip_re, False)
                       if a["kind"] == "control" and a.get("label"))
        short_cs = frozenset(c for c in cs if 2 <= len(c) <= 28 and not c.startswith("<view"))
        return (act, has_overlay, nt, ts, cs, short_cs, parent)

    def find_variant_match(self, parent_id, obs, edge_cmd=None):
        """Returns an already-known canonical screen id if [obs] is an in-place state mutation (expanded accordion,
        selected day/chip, toggled unit, scrolled viewport) or parameterized list-item template duplicate of it.
        During --diff-seed, never collapses a changed carried destination into its old seed screen."""
        if not self.dedup_variants:
            return None
        sid = obs.get("id")
        if not sid or sid in PSEUDO or sid in self.screens:
            return None
        # In --diff-seed, if the same (parent, cmd) edge previously led to a real canonical carried screen
        # (not a variant edge) and now arrives at a different structure, keep it uncollapsed so the PR diff sees it.
        diff_protected = None
        if getattr(self.args, "diff_seed", False) and parent_id is not None and edge_cmd is not None:
            for se in self.seed_edges_by_from.get(parent_id, ()):
                if se.get("cmd") == edge_cmd and not (se.get("note") or "").startswith("variant of "):
                    sto = se.get("to")
                    if sto and sto != sid and sto in self.screens and self.screens[sto].get("origin") == "carried":
                        diff_protected = sto
                        break
        with self.lock:
            aliased = self.variant_aliases.get(sid)
            if aliased and aliased in self.screens and aliased != diff_protected:
                return aliased
            recs = obs.get("recs")
            if not recs:
                return None
            act, ov, nt, ts, cs, scs, _ = self._variant_sig(obs["activity"], recs, parent_id)
            if len(ts) < 6 and len(cs) < 5:
                return None  # never collapse tiny dialogs/sheets
            def jacc(a, b):
                return (len(a & b) / len(a | b)) if (a and b) else 0.0
            # Rule 1: global same-activity equivalence (in-place mutations, repeated forms/details, scrolled accordions)
            for uid in self.order:
                if uid == diff_protected:
                    continue
                usig = self.variant_sigs.get(uid)
                if not usig or usig[0] != act or usig[1] != ov:
                    continue
                _, _, unt, uts, ucs, uscs, _ = usig
                jt = jacc(ts, uts) if (len(ts) >= 6 and len(uts) >= 6) else 0.0
                jc = jacc(cs, ucs) if (len(cs) >= 5 and len(ucs) >= 5) else 0.0
                jsc = jacc(scs, uscs) if (len(scs) >= 5 and len(uscs) >= 5) else 0.0
                if jt >= 0.65 or jc >= 0.72 or jsc >= 0.78 or (nt and nt == unt and jt >= 0.45):
                    return uid
            # Rule 2: sibling parameterized-list fan-out cap under the same parent
            if parent_id is not None:
                for uid in self.order:
                    if uid == diff_protected:
                        continue
                    usig = self.variant_sigs.get(uid)
                    if not usig or usig[0] != act or usig[1] != ov or usig[6] != parent_id:
                        continue
                    _, _, _, uts, ucs, uscs, _ = usig
                    jt = jacc(ts, uts) if (len(ts) >= 6 and len(uts) >= 6) else 0.0
                    jc = jacc(cs, ucs) if (len(cs) >= 5 and len(ucs) >= 5) else 0.0
                    jsc = jacc(scs, uscs) if (len(scs) >= 5 and len(uscs) >= 5) else 0.0
                    shared_short = sum(1 for c in (scs & uscs) if any(ch.isalpha() for ch in c))
                    if jc >= 0.55 or jt >= 0.50 or jsc >= 0.68 or shared_short >= 6:
                        return uid
            return None

    def add_screen(self, obs, path, parent, depth, step_count=0):
        s = {"id": obs["id"], "activity": obs["activity"], "nodes": obs["nodes"], "path": path, "parent": parent,
             "depth": depth, "actions": [], "captures": {}, "title": title_of(obs["recs"]),
             **view_of(obs["recs"]), "origin": "walked"}
        if sum(1 for r in obs["recs"] if r.depth == 0) > 1:
            s["overlay"] = True
        self.screens[s["id"]] = s
        self.order.append(s["id"])
        self.first_recs[s["id"]] = obs.get("recs")
        if self.dedup_variants and obs.get("recs"):
            with self.lock:
                self.variant_sigs[s["id"]] = self._variant_sig(obs["activity"], obs["recs"], parent)
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

    def arrive(self, inst, screen, extra=(), lenient=False, waypoints=None, explore_fast=False, relaunch=True, need_deps=False):
        """At the screen, [extra] steps done. [lenient]: a flaky screen, which may now show another structure of the
        same activity, or be behind a label that live data changed (its path's taps tried by their coordinates)."""
        if not lenient:
            return inst.reach_screen(screen, extra, waypoints=waypoints, explore_fast=explore_fast, relaunch=relaunch, need_deps=need_deps)
        got = inst.reach_screen(screen, extra, alt=self.alt_paths.get(screen["id"]), lenient=True,
                                waypoints=waypoints, explore_fast=explore_fast, relaunch=relaunch, need_deps=need_deps)
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
        if not self.inline_capture or (screen["depth"] < 1 and not self.is_seeded):
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
        parked_state = None
        lenient = False  # the screen's path does not replay to it: its controls are tried where the path leads now
        flaky = None
        aliases = set()  # structures that are this screen: scrolled, or a flaky screen's other arrivals
        parked = getattr(inst, "parked", None)
        inst.parked = None
        if parked is not None and parked[0] == screen["id"]:
            here, parked_state = parked[1], parked[2]
        else:
            for attempt in range(3):
                try:
                    here = inst.reach_screen(screen, patient=attempt == 2, waypoints=wp, explore_fast=self.explore_fast, need_deps=bool(args.record_deps))
                    break
                except ReplayFailed as e:
                    flaky = str(e)
                except Gone as e:
                    entry["findings"].append(self.crash_finding(e.crash, screen["path"]))
                    entry["flaky"] = "ended on arrival"
                    entry["failed"] = True
                    return False, False, None, ()
        if here is None and args.deterministic:
            # --deterministic: a route that does not replay to the same screen is not walked past.
            entry["findings"].append({"kind": "flaky", "detail": f"{screen['id']}: {flaky}; not walked (--deterministic)",
                                      "path": screen["path"], "build": self.name})
            entry["flaky"] = f"{flaky}; not walked (--deterministic)"
            entry["failed"] = True
            return False, False, None, ()
        if here is None:
            # Not a dead end: the walk reached this screen once, so its controls (as it showed them then) are tried on
            # what its path reaches now in the same activity, and what they lead to is followed. Still flaky.
            entry["findings"].append({"kind": "flaky", "detail": f"{screen['id']}: {flaky}", "path": screen["path"], "build": self.name})
            lenient = True
            try:
                here = self.arrive(inst, screen, lenient=True, waypoints=wp, explore_fast=self.explore_fast, need_deps=bool(args.record_deps))
            except ReplayFailed as e:
                entry["flaky"] = f"{flaky}; then {e}"
                entry["failed"] = True
                return False, False, None, ()
            except Gone as e:
                entry["findings"].append(self.crash_finding(e.crash, screen["path"]))
                entry["flaky"] = flaky
                entry["failed"] = True
                return False, False, None, ()
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
            return False, False, None, ()
        if args.record_deps:
            # The span from the `restart` that began this replay to the screen read as settled (docs/crawl.md).
            try:
                entry["deps"] = inst.read_deps()
            except Gone as e:
                entry["findings"].append(self.crash_finding(e.crash, screen["path"]))
                entry["flaky"] = "ended on arrival"
                entry["failed"] = True
                return False, False, None, ()
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
                _, filled = inst.read_ui()
            except ReplayFailed:
                prep = []
            except Gone as e:
                entry["findings"].append(self.crash_finding(e.crash, self.full_path(screen, prep)))
                entry["flaky"] = "ended while filling fields"
                entry["failed"] = True
                return False, False, None, ()
        entry["prep"] = prep
        recs = filled if prep and filled is not None else here["recs"]
        entry["sig"] = screen_sig(recs)
        entry["sig_by_prefix"] = {(): entry["sig"]}
        acts = [a for a in candidates(recs, self.skip_re, args.tap_unlabeled) if a["kind"] == "control"]
        # Controls below the fold: scroll and read again, until nothing new shows.
        reuse_seed_controls = (self.diff_controls in ("none", "changed") and not screen.get("replaces")
                               and screen["id"] in self.seed_actions)
        scrolls = []
        seen = {a["label"] for a in acts}
        max_scrolls = 0 if (reuse_seed_controls and self.diff_controls == "none") else args.max_scrolls
        for k in range(1, max_scrolls + 1):
            sc = scroll_cmd(recs)
            if not sc:
                break
            try:
                inst.expect_ok(sc)
                if not (self.explore_fast and getattr(inst, "_has_fast_drag", True)):
                    inst.settle(SCROLL_SETTLE if self.explore_fast else SETTLE)
                _, scrolled = inst.read_ui()
            except ReplayFailed:
                break
            except Gone as e:
                entry["findings"].append(self.crash_finding(e.crash, self.full_path(screen, prep + scrolls + [sc])))
                entry["flaky"] = "ended while scrolling"
                entry["failed"] = True
                return False, False, None, ()
            scrolls.append(sc)
            recs = scrolled
            aliases.add(structure(here["activity"], recs)[0])
            entry["sig_by_prefix"][tuple(scrolls)] = screen_sig(recs)
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
        def norm_label(lbl):
            return re.sub(r"\d+", "#", lbl)

        prev_labels = ({norm_label(a["label"]) for a in self.seed_actions[screen["id"]] if a.get("kind") == "control"}
                       if reuse_seed_controls else set())
        carried_by_action = ({e["action"]: e for e in self.seed_edges_by_from.get(screen["id"], []) if e.get("kind") == "control"}
                             if reuse_seed_controls else {})
        carried_by_norm = ({norm_label(e["action"]): e for e in self.seed_edges_by_from.get(screen["id"], []) if e.get("kind") == "control"}
                           if reuse_seed_controls else {})
        for idx, a in enumerate(acts[:args.max_actions]):
            if a["skipped"]:
                entry["edges_by_idx"][idx] = {"label": a["label"], "cmd": a["cmd"], "kind": "skipped", "to": {"id": "SKIPPED"},
                                              "steps": [], "note": a["skipped"]}
            elif reuse_seed_controls and (self.diff_controls == "none" or norm_label(a["label"]) in prev_labels):
                ce = carried_by_action.get(a["label"]) or carried_by_norm.get(norm_label(a["label"]))
                target_id = ce.get("to") if ce else None
                if ce and (target_id is None or target_id in self.screens or target_id in PSEUDO):
                    edge = {"label": a["label"], "cmd": ce.get("cmd", a["cmd"]), "kind": "control",
                            "to": {"id": target_id}, "steps": list(ce.get("steps") or [])}
                    if ce.get("note"):
                        edge["note"] = ce["note"]
                    entry["edges_by_idx"][idx] = edge
                elif self.diff_controls == "none":
                    entry["edges_by_idx"][idx] = {"label": a["label"], "cmd": a["cmd"], "kind": "skipped",
                                                  "to": {"id": "SKIPPED"}, "steps": [], "note": "not tapped (--diff-controls none)"}
                else:
                    entry["todo"].append((idx, a))
            else:
                entry["todo"].append((idx, a))
        at_ok = (not lenient)
        at_prefix = tuple(scrolls)
        if parked_state is not None and not scrolls and not prep:
            s_state = parked_state
        else:
            try:
                s_state = inst.data_state(use_cache=not scrolls and not prep) if inst.from_launch_data() else None
            except Gone:
                at_ok, s_state = False, None
        return True, at_ok, s_state, at_prefix

    def control_set(self, recs):
        """A screen's controls by label, numbers left out (a temperature, a count): what stays of it as its data changes."""
        return frozenset(re.sub(r"\d+", "#", a["label"]) for a in candidates(recs, self.skip_re, True)
                         if a["kind"] == "control")

    def explore_level(self, level):
        """Explores every screen in [level] across the instance pool with action-level work stealing, in-place reuse,
        and self-verifying warm restarts (avoiding JVM relaunches when only navigation writes are present)."""
        if not level:
            return []
        entries = [{"screen": s, "discovered": False, "here": None, "sig": None, "sig_by_prefix": {}, "prep": [], "acts": [],
                    "aliases": set(), "deps": None, "flaky": None, "lenient": False, "failed": False,
                    "todo": [], "next_pos": 0, "edges_by_idx": {}, "findings": [], "truncated": False,
                    "back_failed": False, "failed_returns": set(), "relaunch_needed": False, "restart_misses": 0, "arrive_misses": 0} for s in level]
        cond = threading.Condition()
        undiscovered = list(entries)
        discovered = []
        in_discovery = [0]

        def pop_todo(entry, pref=None):
            """Pops the next action from [entry]['todo']. When parked at scroll prefix [pref], prefers an action at
            [pref] (exact in-place) or extending [pref] (scroll-forward in-place) to avoid re-replaying from root."""
            todo = entry["todo"]
            pos = entry["next_pos"]
            if pos >= len(todo):
                return None
            if pref is not None:
                pick = None
                for k in range(pos, len(todo)):
                    ap = tuple(todo[k][1]["prefix"])
                    if ap == pref:
                        pick = k
                        break
                    if pick is None and len(ap) > len(pref) and ap[:len(pref)] == pref:
                        pick = k
                if pick is not None and pick != pos:
                    todo[pos], todo[pick] = todo[pick], todo[pos]
            item = todo[pos]
            entry["next_pos"] = pos + 1
            return item

        def worker(inst):
            cur = None
            at_screen = False
            at_prefix = ()
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
                            act_idx, act = pop_todo(cur, at_prefix if at_screen else None)
                            mode, entry = "action", cur
                            break
                        if undiscovered:
                            p = getattr(inst, "parked", None)
                            pick = next((i for i, e in enumerate(undiscovered) if p is not None and e["screen"]["id"] == p[0]), 0)
                            entry = undiscovered.pop(pick)
                            in_discovery[0] += 1
                            mode = "discover"
                            break
                        if cur is not None and cur["next_pos"] < len(cur["todo"]):
                            act_idx, act = pop_todo(cur, at_prefix if at_screen else None)
                            mode, entry = "action", cur
                            break
                        best = None
                        best_rem = 0
                        for e in discovered:
                            rem = len(e["todo"]) - e["next_pos"]
                            if rem > best_rem:
                                best, best_rem = e, rem
                        if best is not None:
                            act_idx, act = pop_todo(best)
                            cur = best
                            at_screen = False
                            at_prefix = ()
                            try_restart = False
                            state_at_s = None
                            mode, entry = "action", best
                            break
                        if in_discovery[0] > 0:
                            cond.wait(timeout=0.05)
                            continue
                        return
                if mode == "discover":
                    ok, at_ok, s_state, disc_prefix = self.discover_screen(inst, entry)
                    with cond:
                        entry["discovered"] = True
                        in_discovery[0] -= 1
                        if ok:
                            discovered.append(entry)
                        cond.notify_all()
                    if ok:
                        cur, at_screen, at_prefix, try_restart, state_at_s = (
                            entry, at_ok, disc_prefix if at_ok else (),
                            (not at_ok and s_state is not None and not entry["lenient"]), s_state)
                    else:
                        cur, at_screen, at_prefix, try_restart, state_at_s = None, False, (), False, None
                    continue
                # mode == "action"
                screen = entry["screen"]
                prep = entry["prep"]
                a = act
                want_prefix = tuple(a["prefix"])
                steps = prep + a["prefix"] + [a["cmd"]]
                alt = f"tap {a['x']} {a['y']}"
                note = None
                lenient = entry["lenient"]
                wp = self.waypoints.get(screen["id"])
                arrived = False
                try:
                    if at_screen and len(want_prefix) >= len(at_prefix) and want_prefix[:len(at_prefix)] == at_prefix:
                        # Already on [screen] at [at_prefix]: apply any remaining scroll steps in place.
                        for step in want_prefix[len(at_prefix):]:
                            inst.expect_ok(step)
                            if not (self.explore_fast and step.startswith("drag ") and getattr(inst, "_has_fast_drag", True)):
                                inst.settle(SCROLL_SETTLE if self.explore_fast else SETTLE)
                    else:
                        reused = False
                        if try_restart and not lenient and state_at_s is not None and inst.from_launch_data():
                            try:
                                got = inst.reach_screen(screen, waypoints=wp, explore_fast=self.explore_fast, relaunch=False)
                                if screen_sig(got["recs"]) == entry["sig"]:
                                    for step in prep + a["prefix"]:
                                        inst.expect_ok(step)
                                        if not (self.explore_fast and step.startswith("drag ") and getattr(inst, "_has_fast_drag", True)):
                                            inst.settle(SCROLL_SETTLE if (self.explore_fast and step.startswith("drag ")) else SETTLE)
                                    state_at_s = inst.data_state(use_cache=not prep and not a["prefix"])
                                    reused = True
                                else:
                                    with cond:
                                        entry["restart_misses"] += 1
                                        if entry["restart_misses"] >= 3:
                                            entry["relaunch_needed"] = True
                            except ReplayFailed:
                                with cond:
                                    entry["restart_misses"] += 1
                                    if entry["restart_misses"] >= 3:
                                        entry["relaunch_needed"] = True
                        if not reused:
                            try:
                                got = self.arrive(inst, screen, prep + a["prefix"], lenient, waypoints=wp, explore_fast=self.explore_fast)
                            except ReplayFailed:
                                if lenient or self.args.deterministic:
                                    raise
                                # It replayed for the screen and not now: try this control where the path leads now.
                                got = self.arrive(inst, screen, prep + a["prefix"], True, waypoints=wp, explore_fast=self.explore_fast)
                            if got.get("drift"):
                                with cond:
                                    entry["aliases"].add(got["id"])
                                note = "tried on another structure of this screen (its replay is flaky)"
                            state_at_s = inst.data_state(use_cache=not prep and not a["prefix"]) if inst.from_launch_data() else None
                    arrived = True
                    with cond:
                        entry["arrive_misses"] = 0
                    at_screen = False
                    at_prefix = ()
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
                            if self.args.deterministic:
                                raise  # the label is there or not from one launch to the next: its old place is a guess
                            a["cmd"] = alt
                            steps = prep + a["prefix"] + [a["cmd"]]
                            inst.tap_checked(a["cmd"])
                    settled = inst.settle(SETTLE)
                    inst.say("pick cancel")
                    to = inst.observe()
                    if not settled and to["id"] not in PSEUDO:
                        with self.ctx["lock"]:
                            self.ctx["unsettled"].add(to["id"])
                    # Inline capture for newly discovered screens (depth >= 1) while the instance is already cleanly on [to].
                    if (self.inline_capture and to["id"] not in PSEUDO and to["id"] not in self.screens
                            and self.find_variant_match(screen["id"], to, edge_cmd=a["cmd"]) is None):
                        take_shot = False
                        with self.lock:
                            if to["id"] not in self.inline_shots:
                                self.inline_shots[to["id"]] = None  # reserve so only one worker captures it
                                take_shot = True
                        if take_shot:
                            base_dir = os.path.join(self.out, self.shots, to["id"])
                            os.makedirs(base_dir, exist_ok=True)
                            staged = os.path.join(inst.stage, "capture.png")
                            try:
                                inst.expect_ok(f"shot {staged}")
                                dst_rel = f"{self.shots}/{to['id']}/{self.default_combo_name}.png"
                                shutil.move(staged, os.path.join(self.out, dst_rel))
                                with self.lock:
                                    self.inline_shots[to["id"]] = (dst_rel, bool(settled))
                            except ReplayFailed:
                                with self.lock:
                                    self.inline_shots.pop(to["id"], None)
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
                        if not arrived:
                            # The screen's path, not this control, failed. A flaky screen's replay that lands elsewhere
                            # tends to land there every time: after a few in a row, its other controls are not given a
                            # launch each to find that out again (they are FAILED edges with the same note).
                            entry["arrive_misses"] += 1
                            if entry["arrive_misses"] >= ARRIVE_MISSES:
                                note = f"not tried: {entry['arrive_misses']} replays in a row missed the screen ({e})"
                                while entry["next_pos"] < len(entry["todo"]):
                                    i, rest = entry["todo"][entry["next_pos"]]
                                    entry["next_pos"] += 1
                                    entry["edges_by_idx"][i] = {"label": rest["label"], "cmd": rest["cmd"], "kind": "control",
                                                                "to": {"id": "FAILED"}, "steps": prep + rest["prefix"] + [rest["cmd"]],
                                                                "note": note}
                                entry["flaky"] = entry["flaky"] or note
                                if not any(f.get("detail", "").startswith(f"{screen['id']}: controls not tried") for f in entry["findings"]):
                                    entry["findings"].append({"kind": "flaky", "path": screen["path"], "build": self.name,
                                                              "detail": f"{screen['id']}: controls not tried after {ARRIVE_MISSES} replays in a row missed it ({e})"})
                    at_screen = False
                    at_prefix = ()
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
                    at_prefix = ()
                    try_restart = False
                    state_at_s = None
                    continue
                # Speculative in-place return or warm restart when exploring [screen].
                if more_here and not prep and not lenient and to["id"] not in PSEUDO:
                    ret_cmd = None
                    want_sig = entry["sig_by_prefix"].get(want_prefix, entry["sig"])
                    same_ids = {screen["id"]} | entry["aliases"]
                    try:
                        clean_disk = (not inst.from_launch_data()) or (state_at_s is not None and inst.data_state() == state_at_s)
                        if clean_disk:
                            if to["id"] in same_ids and screen_sig(to["recs"]) == want_sig:
                                at_screen = True
                                at_prefix = want_prefix
                            elif to["id"] not in same_ids:
                                dc = dismiss_cmd(to["recs"], self.skip_re)
                                if dc and (to["id"], dc) in entry["failed_returns"]:
                                    dc = None
                                back_ok = (not entry["back_failed"]) and ((to["id"], "back") not in entry["failed_returns"])
                                if to["activity"] != screen["activity"]:
                                    ret_cmd = "back" if back_ok else dc
                                else:
                                    ret_cmd = dc or ("back" if back_ok else None)
                                if ret_cmd:
                                    inst.expect_ok(ret_cmd)
                                    obs = inst.wait_waypoint(screen["id"], prev_id=to["id"], timeout=0.18)
                                    if obs["id"] == "EXIT":
                                        entry["failed_returns"].add((to["id"], ret_cmd))
                                        if ret_cmd == "back":
                                            entry["back_failed"] = True
                                        inst.quit()
                                        state_at_s = None
                                    elif obs["id"] in same_ids and screen_sig(obs["recs"]) == want_sig:
                                        if (not inst.from_launch_data()) or inst.data_state() == state_at_s:
                                            at_screen = True
                                            at_prefix = want_prefix
                                    elif obs["id"] == screen["id"] and screen_sig(obs["recs"]) == entry["sig"]:
                                        if (not inst.from_launch_data()) or inst.data_state() == state_at_s:
                                            at_screen = True
                                            at_prefix = ()
                                    else:
                                        entry["failed_returns"].add((to["id"], ret_cmd))
                                        # Only disable hardware `back` globally for this screen after repeated misses
                                        # (a single filter-chip or modal miss should not disable `back` for sub-screens).
                                        if ret_cmd == "back" and sum(1 for _, rc in entry["failed_returns"] if rc == "back") >= 3:
                                            entry["back_failed"] = True
                        if not at_screen and state_at_s is not None and (clean_disk or (self.explore_fast and not entry["relaunch_needed"])):
                            # Next action on this screen can try self-verifying warm `restart` (which verifies exact
                            # id + screen_sig and falls back to a clean JVM relaunch if disk state affected the screen).
                            if inst.proc is not None and inst.proc.poll() is None:
                                try_restart = True
                    except ReplayFailed:
                        if ret_cmd:
                            entry["failed_returns"].add((to["id"], ret_cmd))
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
        self.cap_left = {}
        for screen, _ in tasks:
            self.cap_left[screen["id"]] = self.cap_left.get(screen["id"], 0) + 1
        self.run_on_pool(tasks, self.capture_task)
        self.t_capture = time.time() - t0

    def capture_task(self, inst, task):
        try:
            self.capture_task_inner(inst, task)
        finally:
            screen = task[0]
            with self.lock:
                # A task not counted by capture() (called on its own) is its screen's only one.
                left = self.cap_left.get(screen["id"], 1) - 1
                done = left <= 0
                if done:
                    self.cap_left.pop(screen["id"], None)
                else:
                    self.cap_left[screen["id"]] = left
            if done and self.on_captured:
                # Every combination of this screen is captured (or failed): --diff-seed compares it now.
                try:
                    self.on_captured(screen["id"], inst)
                except Gone as e:
                    with self.lock:
                        self.findings.append(self.crash_finding(e.crash, screen["path"]))

    def capture_task_inner(self, inst, task):
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
                    inst.reach_screen(screen, waypoints=wp, explore_fast=self.explore_fast)
                except ReplayFailed:
                    # A flaky screen (or one below it) is captured where its path leads now, as it was explored.
                    self.arrive(inst, screen, lenient=bool(screen.get("flaky")), waypoints=wp, explore_fast=self.explore_fast)
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


def text_boxes(recs):
    """The bounds [l, t, r, b] of each entry of texts_list(recs), in the same order (issue 630)."""
    return [list(r.box) for r in recs if r.text and "v" in r.flags][:40]


def view_of(recs):
    """A screen's texts with their bounds and the display's size in pixels (what a capture's pixels are)."""
    w, h = screen_size(recs)
    return {"texts": texts_list(recs), "text_boxes": text_boxes(recs), "size": [w, h]}


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


def diff_seq(old, new, old_boxes=None, new_boxes=None):
    """Sequence diff of two string lists -> {'replaced': [[a, b], ...], 'added': [...], 'removed': [...]}.
    With the lists' bounds (one box per entry), also 'bounds' (issue 630): the same shape holding each entry's box in
    the build it exists in (a replaced pair has both: [old box, new box]); None where a box is not known."""
    sm = difflib.SequenceMatcher(None, old, new)
    replaced, added, removed = [], [], []
    rb, ab, vb = [], [], []

    def box(boxes, i):
        return boxes[i] if boxes is not None and i < len(boxes) else None

    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            continue
        if tag == "replace":
            n = min(i2 - i1, j2 - j1)
            for k in range(n):
                replaced.append([old[i1 + k], new[j1 + k]])
                rb.append([box(old_boxes, i1 + k), box(new_boxes, j1 + k)])
            removed.extend(old[i1 + n:i2])
            vb.extend(box(old_boxes, i) for i in range(i1 + n, i2))
            added.extend(new[j1 + n:j2])
            ab.extend(box(new_boxes, j) for j in range(j1 + n, j2))
        elif tag == "delete":
            removed.extend(old[i1:i2])
            vb.extend(box(old_boxes, i) for i in range(i1, i2))
        elif tag == "insert":
            added.extend(new[j1:j2])
            ab.extend(box(new_boxes, j) for j in range(j1, j2))
    out = {}
    if replaced:
        out["replaced"] = replaced
    if added:
        out["added"] = added
    if removed:
        out["removed"] = removed
    if out and (old_boxes is not None or new_boxes is not None):
        bounds = {}
        for k, v in (("replaced", rb), ("added", ab), ("removed", vb)):
            if v and any(b is not None and b != [None, None] for b in v):
                bounds[k] = v
        if bounds:
            out["bounds"] = bounds
    return out


def ui_diff(base_s, new_s, above_fold_only=False):
    """Semantic diff of two screens' title, visible texts (excluding clock ticks) and controls."""
    out = {}
    bt, nt = base_s.get("title") or "", new_s.get("title") or ""
    if bt != nt and not (TIME_RE.search(bt) and TIME_RE.search(nt)):
        out["title"] = {"from": bt, "to": nt}
    def keep_texts(sc):
        texts, boxes = sc.get("texts") or [], sc.get("text_boxes")
        idx = [i for i, t in enumerate(texts) if not TIME_RE.search(t)]
        return [texts[i] for i in idx], ([boxes[i] if i < len(boxes) else None for i in idx] if boxes is not None else None)

    b_texts, b_tb = keep_texts(base_s)
    n_texts, n_tb = keep_texts(new_s)
    td = diff_seq(b_texts, n_texts, b_tb, n_tb)
    if td:
        out["texts"] = td

    def keep_acts(sc):
        acts = [a for a in (sc.get("actions") or [])
                if a.get("kind") == "control" and (not above_fold_only or a.get("scrolls", 0) == 0)
                and not TIME_RE.search(a.get("label", ""))]
        return [a["label"] for a in acts], [a.get("box") for a in acts]

    b_acts, b_ab = keep_acts(base_s)
    n_acts, n_ab = keep_acts(new_s)
    ad = diff_seq(b_acts, n_acts, b_ab, n_ab)
    if ad:
        out["controls"] = ad
    size = new_s.get("size") or base_s.get("size")
    if out and size:
        out["size"] = list(size)
    return out


def format_ui_diff(ud):
    """Short human-readable summary of a ui_diff dict."""
    parts = []
    if ud.get("title"):
        parts.append(f"title {ud['title']['from']!r} -> {ud['title']['to']!r}")
    for key, label in (("texts", "text"), ("controls", "control")):
        d = ud.get(key) or {}
        for a, b in (d.get("replaced") or [])[:3]:
            parts.append(f"{label} {a!r} -> {b!r}")
        if d.get("added"):
            parts.append(f"+{label} " + ", ".join(repr(x) for x in d["added"][:3]))
        if d.get("removed"):
            parts.append(f"-{label} " + ", ".join(repr(x) for x in d["removed"][:3]))
    return "; ".join(parts)


class SeedCompare:
    """Compares a --seed walk's walked/new screens against the seed's crawl.json and captures without re-walking base.
    The pairs are made once the walk is done; each walked pair is compared by [compare] as soon as its screen has been
    captured (Explorer.on_captured), which also prints its `compare` event, and [finish] compares what is left."""

    def __init__(self, new, seed, src_dir, doc, args, out):
        self.new, self.seed, self.src_dir, self.doc, self.args, self.out = new, seed, src_dir, doc, args, out
        self.lock = threading.Lock()
        seed_by_id = self.seed_by_id = {s["id"]: s for s in seed.get("screens", [])}
        pairs = self.pairs = []
        matched_base = set()
        for sid in new.order:
            ns = new.screens[sid]
            if ns.get("origin") == "carried":
                pairs.append({"id": sid, "base": sid, "status": "same", "why": None})
                matched_base.add(sid)
                continue
            old_id = ns.get("replaces")
            if old_id and old_id in seed_by_id:
                pairs.append({"id": sid, "base": old_id, "status": "changed",
                              "why": f"structure differs: {old_id} in the base build"})
                matched_base.add(old_id)
            elif sid in seed_by_id:
                pairs.append({"id": sid, "base": sid, "status": "same", "why": None})
                matched_base.add(sid)
            else:
                pairs.append({"id": sid, "base": None, "status": "new", "why": "new screen"})
        for bs in seed.get("screens", []):
            if bs["id"] not in matched_base:
                pairs.append({"id": None, "base": bs["id"], "status": "gone",
                              "why": "no screen of the new build reaches it"})
        lost = lost_carried(new, seed)
        for p in pairs:
            if p["status"] == "same" and p["id"] in lost and new.screens[p["id"]].get("origin") == "carried":
                if lost[p["id"]] == "gone":
                    p.update(id=None, status="gone", why="no transition of the new graph leads to it any more: every screen "
                             "that led to it was walked again or is itself gone")
                else:
                    p.update(status="unknown", why="no transition of the new graph leads to it, but a screen that led to it "
                             "was not walked again (or the seed never explored it), so it may still exist")
        self.diffs = {}
        self.base_screens = []
        self.base_by_pair = {}
        self.todo = {}  # screen id -> its pair, until compared
        for p in pairs:
            if p["status"] == "gone" and p["base"] in seed_by_id:
                bs = self.stage_base_captures(seed_by_id[p["base"]])
                if not any(b is bs for b in self.base_screens):
                    self.base_screens.append(bs)
                continue
            if not (p["id"] and p["base"]):
                continue
            if new.screens[p["id"]].get("origin") != "walked":
                continue
            bs = self.stage_base_captures(seed_by_id[p["base"]])
            if not any(b is bs for b in self.base_screens):
                self.base_screens.append(bs)
            self.base_by_pair[p["id"]] = bs
            self.todo[p["id"]] = p

    def stage_base_captures(self, bs):
        # Two pairs can name one base screen (a kept id and a screen that replaces it): stage it once.
        staged = self.__dict__.setdefault("_staged_base", {})
        if bs["id"] in staged:
            return staged[bs["id"]]
        bc = dict(bs)
        caps = {}
        for name, rel in (bs.get("captures") or {}).items():
            src = os.path.join(self.src_dir, rel)
            if not os.path.isfile(src):
                continue
            dst = os.path.join(self.out, "base-screens", bs["id"], name + ".png")
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            if not os.path.exists(dst):
                try:
                    os.link(src, dst)
                except OSError:
                    try:
                        shutil.copyfile(src, dst)
                    except shutil.SameFileError:
                        pass
            caps[name] = f"base-screens/{bs['id']}/{name}.png"
        bc["captures"] = caps
        staged[bs["id"]] = bc
        return bc

    def compare(self, sid, inst):
        """Compares one walked screen with its base and prints its `compare` event. Once only."""
        with self.lock:
            p = self.todo.pop(sid, None)
        if p is None:
            return
        doc, args, out = self.doc, self.args, self.out
        ns, bs = self.new.screens[sid], self.base_by_pair[sid]
        ud = ui_diff(bs, ns, above_fold_only=(getattr(args, "diff_controls", "all") == "none"))
        if ud:
            p["ui_diff"] = ud
            summary = format_ui_diff(ud)
            if p["status"] == "same":
                p["status"] = "changed"
                p["why"] = summary
            else:
                p["why"] = (p["why"] + "; " + summary) if p.get("why") else summary
        d = {}
        unstable = set(ns.get("unsettled", [])) | set(bs.get("unsettled", []))
        if unstable:
            p["unstable"] = sorted(unstable)
        for c in doc["combos"]:
            a_, b_ = ns["captures"].get(c), bs["captures"].get(c)
            if c in unstable:
                continue
            if not (a_ and b_):
                doc["findings"].append({"kind": "compare", "build": "new", "path": ns["path"],
                                        "detail": f"{p['id']} {c}: no capture in {'the new' if not a_ else 'the base'} build to compare"})
                continue
            if inst is not None:
                try:
                    d[c] = diff_images(inst, os.path.join(out, b_), os.path.join(out, a_), c,
                                       os.path.join(out, "diff", p["id"]), args.diff_delta, args.diff_pixels, out)
                except Gone as e:
                    # An instance that ends in a comparison must not leave the pair half-compared as "same".
                    d[c] = {"error": f"the instance ended during the comparison: {e}"}
                if d[c].get("error"):
                    doc["findings"].append({"kind": "compare", "build": "new", "path": ns["path"],
                                            "detail": f"{p['id']} {c}: the image comparison failed: {d[c]['error']}"})
        if d:
            with self.lock:
                self.diffs[p["id"]] = d
        if unstable and p["status"] == "same":
            p["why"] = "animates for ever in " + ", ".join(sorted(unstable)) + ": those captures are not compared"
        if any(x.get("differs") for x in d.values()):
            look = "looks different: " + ", ".join(c for c, x in d.items() if x.get("differs"))
            if p["status"] == "same":
                p["status"] = "changed"
                p["why"] = look
            elif look not in (p.get("why") or ""):
                p["why"] = (p["why"] + "; " + look) if p.get("why") else look
        ev = {k: p[k] for k in ("id", "base", "status", "why", "ui_diff", "unstable") if p.get(k) is not None or k in ("why",)}
        if d:
            ev["diffs"] = d
        self.new.event("compare", **ev)

    def finish(self, inst):
        """Compares what no capture callback reached (screens already captured, or no combinations), then fills the doc."""
        for sid in list(self.todo):
            self.compare(sid, inst)
        for p in self.pairs:
            if p["status"] == "new":
                self.new.event("compare", id=p["id"], base=None, status="new", why=p["why"])
        doc = self.doc
        doc["compare"] = self.pairs
        doc["diffs"] = self.diffs
        doc["base_screens"] = self.base_screens
        doc["base_edges"] = self.seed.get("edges", [])
        doc["edge_changes"] = compare_edges(self.new, self.seed)


def lost_carried(new, seed):
    """Carried screens that no transition of the new graph leads to from a start: {id: "gone" | "unknown"}.
    Gone when every screen that led to it (in the seed) was walked again or is itself gone; unknown when one that led
    to it was neither (the seed never explored it) or it had no recorded way in."""
    ids = set(new.screens)
    seen = {sid for sid in ids if new.screens[sid].get("parent") is None}
    todo = list(seen)
    out_edges = {}
    for e in new.edges:
        out_edges.setdefault(e["from"], []).append(e["to"])
    while todo:
        for t in out_edges.get(todo.pop(), []):
            if t in ids and t not in seen:
                seen.add(t)
                todo.append(t)
    lost = [sid for sid in ids if sid not in seen and new.screens[sid].get("origin") == "carried"]
    if not lost:
        return {}
    walked_old = set()  # seed ids of screens walked again whose controls were all tried
    explored, partial = getattr(new, "explored", None), getattr(new, "partial", set())
    # --diff-controls none reads only the first screenful and taps nothing: a control out of sight is not a control gone.
    all_tried = getattr(new, "diff_controls", "all") != "none"
    unfollowed = {e["from"] for e in new.edges if e.get("to") is None}  # a budget kept a transition from being followed
    for sid in ids:
        ns = new.screens[sid]
        if all_tried and ns.get("origin") == "walked" and not ns.get("flaky") and sid not in partial \
                and sid not in unfollowed and (explored is None or sid in explored):
            walked_old.add(sid)
            if ns.get("replaces"):
                walked_old.add(ns["replaces"])
    lost_set = set(lost)
    inbound = {}
    for e in seed.get("edges", []):
        if e.get("to") in lost_set:
            inbound.setdefault(e["to"], set()).add(e["from"])
    unexplored = set(seed.get("unexplored", []))
    status = {}
    for sid in lost:
        srcs = inbound.get(sid, set())
        bad = not srcs or any(f in unexplored and f not in walked_old for f in srcs) \
            or any(f not in walked_old and f not in lost_set for f in srcs)
        status[sid] = "unknown" if bad else "gone"
    changed = True
    while changed:  # a screen that only the unknown ones led to may still exist
        changed = False
        for sid in lost:
            if status[sid] == "gone" and any(status.get(f) == "unknown" for f in inbound.get(sid, ())):
                status[sid] = "unknown"
                changed = True
    return status


def compare_edges(new, seed):
    """Transitions out of screens walked again, new graph against the seed: added, removed, retargeted."""
    replaced = {ns["replaces"]: sid for sid, ns in new.screens.items() if ns.get("replaces")}
    walked = {sid for sid, ns in new.screens.items() if ns.get("origin") == "walked"}

    def key_all(edges, mapf):
        out, count = {}, {}
        for e in edges:
            f = mapf(e["from"])
            if f not in walked or e.get("kind") == "skipped" or e["to"] in ("SKIPPED", "FAILED"):
                continue
            k = (f, e["action"])
            count[k] = count.get(k, 0) + 1
            out[k + (count[k],)] = e
        return out
    base = key_all(seed.get("edges", []), lambda f: replaced.get(f, f))
    cur = key_all(new.edges, lambda f: f)
    res = {"added": [], "removed": [], "retargeted": []}
    for k, e in cur.items():
        b = base.get(k)
        if b is None:
            res["added"].append({"from": k[0], "action": k[1], "to": e["to"]})
            continue
        bto = replaced.get(b["to"], b["to"])
        if bto != e["to"]:
            res["retargeted"].append({"from": k[0], "action": k[1], "to": e["to"], "base_to": b["to"]})
    if getattr(new, "diff_controls", "all") == "none":
        res["note"] = "--diff-controls none: controls were not all read, so removed transitions are not listed"
        return res
    for k, b in base.items():
        if k not in cur:
            res["removed"].append({"from": k[0], "action": k[1], "base_to": b["to"]})
    return res


def read_png_rgba(path):
    """(width, height, rows) of an 8-bit RGB or RGBA PNG, each row as RGBA bytes; stdlib only (the crawler needs no
    imaging library). None for any other PNG."""
    import struct, zlib
    with open(path, "rb") as f:
        data = f.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    pos, idat, hdr = 8, [], None
    while pos + 8 <= len(data):
        n, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + n]
        pos += 12 + n
        if kind == b"IHDR":
            hdr = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat.append(body)
        elif kind == b"IEND":
            break
    if not hdr or hdr[2] != 8 or hdr[3] not in (2, 6) or hdr[6] != 0:
        return None
    w, h, ch = hdr[0], hdr[1], 4 if hdr[3] == 6 else 3
    raw = zlib.decompress(b"".join(idat))
    stride = w * ch
    rows, prev = [], bytearray(stride)
    for y in range(h):
        base = y * (stride + 1)
        ft = raw[base]
        cur = bytearray(raw[base + 1:base + 1 + stride])
        if ft == 1:
            for i in range(ch, stride):
                cur[i] = (cur[i] + cur[i - ch]) & 255
        elif ft == 2:
            for i in range(stride):
                cur[i] = (cur[i] + prev[i]) & 255
        elif ft == 3:
            for i in range(stride):
                cur[i] = (cur[i] + (((cur[i - ch] if i >= ch else 0) + prev[i]) >> 1)) & 255
        elif ft == 4:
            for i in range(stride):
                a = cur[i - ch] if i >= ch else 0
                b = prev[i]
                c = prev[i - ch] if i >= ch else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                cur[i] = (cur[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        elif ft != 0:
            return None
        prev = cur
        if ch == 3:
            rgba = bytearray(w * 4)
            rgba[0::4], rgba[1::4], rgba[2::4], rgba[3::4] = cur[0::3], cur[1::3], cur[2::3], b"\xff" * w
            rows.append(bytes(rgba))
        else:
            rows.append(bytes(cur))
    return w, h, rows


REGION_CELL = 8      # pixels: differing pixels are grouped on a grid of this size before areas are merged
REGION_GAP = 2       # cells: areas closer than this (16 px) are one region
REGION_MAX = 8       # regions kept per capture, largest first; the rest are counted


def diff_regions(map_path):
    """Bounding boxes of the connected areas of a `diff` map's red pixels (differences over the delta): merged when
    close, at most REGION_MAX, largest first, as {"size": [w, h], "regions": [[l, t, r, b], ...], "more": N}. The
    boxes are in the capture's pixels (r and b exclusive)."""
    png = read_png_rgba(map_path)
    if not png:
        return None
    w, h, rows = png
    red = b"\xff\x00\x00\xff"
    cells = {}  # (cx, cy) -> [minx, miny, maxx, maxy] of the red pixels in the cell
    for y, row in enumerate(rows):
        i = row.find(red)
        while i >= 0:
            if i & 3:
                i = row.find(red, i + 1)
                continue
            x = i >> 2
            c = cells.setdefault((x // REGION_CELL, y // REGION_CELL), [x, y, x, y])
            if x < c[0]: c[0] = x
            if x > c[2]: c[2] = x
            if y < c[1]: c[1] = y
            if y > c[3]: c[3] = y
            i = row.find(red, i + 4)
    if not cells:
        return None
    parent = {k: k for k in cells}

    def find(k):
        while parent[k] != k:
            parent[k] = parent[parent[k]]
            k = parent[k]
        return k

    for (cx, cy) in cells:
        for dx in range(-REGION_GAP, REGION_GAP + 1):
            for dy in range(0, REGION_GAP + 1):
                n = (cx + dx, cy + dy)
                if n != (cx, cy) and n in cells:
                    parent[find(n)] = find((cx, cy))
    boxes = {}
    for k, c in cells.items():
        r = find(k)
        b = boxes.setdefault(r, [c[0], c[1], c[2], c[3]])
        b[0], b[1], b[2], b[3] = min(b[0], c[0]), min(b[1], c[1]), max(b[2], c[2]), max(b[3], c[3])
    out = sorted(([b[0], b[1], b[2] + 1, b[3] + 1] for b in boxes.values()),
                 key=lambda b: (-(b[2] - b[0]) * (b[3] - b[1]), b[1], b[0]))
    return {"size": [w, h], "regions": out[:REGION_MAX], "more": max(0, len(out) - REGION_MAX)}


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
        try:
            found = diff_regions(sm) if res["differs"] else None  # noise under the threshold has no regions
        except Exception:  # a map that cannot be read gives no regions, never a failed comparison
            found = None
        if found:
            res["size"], res["regions"] = found["size"], found["regions"]
            if found["more"]:
                res["regions_more"] = found["more"]
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
    inline_first = embed or len(doc.get("screens", [])) <= 40
    for key in ("screens", "base_screens"):
        for s in doc.get(key, []):
            shots.setdefault(key, {})[s["id"]] = {c: img(p, inline_first and c in first) for c, p in s["captures"].items()}
    diffs = {k: {c: {**d, "map": img(d.get("map"), True)} for c, d in v.items()} for k, v in doc.get("diffs", {}).items()}
    sweep_images = {}
    for f in doc["findings"]:
        for k in ("before", "after", "diff"):
            if f.get(k):
                sweep_images[f[k]] = img(f[k], True)
    data = {"screens": [{k: v for k, v in s.items() if k not in ("captures", "deps")} for s in doc["screens"]],
            "edges": doc["edges"], "findings": doc["findings"], "shots": shots.get("screens", {}),
            "baseShots": shots.get("base_screens", {}), "compare": doc.get("compare"), "diffs": diffs,
            "incomplete": doc.get("incomplete", []), "sweep": doc.get("sweep"), "sweepImages": sweep_images, "seconds": doc.get("seconds"), "combos": doc["combos"],
            "baseScreens": [{k: v for k, v in s.items() if k not in ("captures", "deps")} for s in doc.get("base_screens", [])]}
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
 const changed=D.compare.filter(p=>p.status!=='same'),same=D.compare.filter(p=>p.status==='same');
 cp.append(E('p',{class:'muted'},changed.length+' changed/new/gone, '+same.length+' unchanged'));
 const mkTable=list=>{const t=E('table',{});list.forEach(p=>{const cl=p.status==='same'?'ok':p.status==='changed'?'warn':'bad';
  t.append(E('tr',{},E('td',{},E('span',{class:'badge '+cl},p.status)),E('td',{},p.id&&S[p.id]?E('a',{onclick:'select("'+p.id+'")'},p.id):(p.id||p.base)),E('td',{class:'muted'},p.why||'')))});return t};
 if(changed.length)cp.append(mkTable(changed));
 if(same.length<=10){if(same.length)cp.append(mkTable(same))}
 else{const det=E('details',{},E('summary',{class:'muted'},same.length+' unchanged screen(s)'),mkTable(same));cp.append(det)}
 changed.filter(p=>p.id&&(D.diffs[p.id]||p.ui_diff)).forEach(p=>{
  cp.append(E('h3',{},S[p.id]?E('a',{onclick:'select("'+p.id+'")'},p.id):p.id,' ',E('span',{class:'badge '+(p.status==='new'?'ok':'warn')},p.status)));
  if(p.ui_diff){const lines=[];
   if(p.ui_diff.title)lines.push('title: '+JSON.stringify(p.ui_diff.title.from)+' -> '+JSON.stringify(p.ui_diff.title.to));
   [['texts','text'],['controls','control']].forEach(([k,lbl])=>{const u=p.ui_diff[k]||{};
    (u.replaced||[]).forEach(([a,b])=>lines.push(lbl+': '+JSON.stringify(a)+' -> '+JSON.stringify(b)));
    (u.added||[]).forEach(a=>lines.push('+'+lbl+': '+JSON.stringify(a)));
    (u.removed||[]).forEach(a=>lines.push('-'+lbl+': '+JSON.stringify(a)))});
   if(lines.length)cp.append(E('pre',{},lines.join('\n')))}
  if(D.diffs[p.id]){const d=D.diffs[p.id];const row=E('div',{class:'shots'});
   Object.entries(d).forEach(([c,x])=>{if(x.map||x.differs){const b=(D.baseShots[p.base]||{})[c],n=(D.shots[p.id]||{})[c];
    if(b)row.append(E('figure',{},E('img',{src:b}),E('figcaption',{},'base, '+c)));if(n)row.append(E('figure',{},E('img',{src:n}),E('figcaption',{},'new, '+c)));
    row.append(E('figure',{},x.map?E('img',{src:x.map}):'',E('figcaption',{},'difference: '+(x.error||x.pixels+' px differ'))))}});
   if(row.childNodes.length)cp.append(row)}});
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
    p.add_argument("--standby", action="store_true",
                   help="hand each launch and relaunch to apksim's warm standby JVM when one is up (GPU-rendered walks only; "
                        "--diff-seed implies it; a --base, --base-option or --sweep walk renders with --snapshot and stays cold). "
                        "apksim keeps one standby per folder, so with -j N the relaunches share it and one that finds it "
                        "booting or taken launches cold; APKSIM_STANDBY=0 forces cold")
    p.add_argument("--freeze-network", action="store_true",
                   help="answer every launch from one recording, OUT/network.har (what it lacks is fetched once and kept), "
                        "with the clock at the time the recording began, so screens with server data replay the same")
    p.add_argument("--deterministic", action="store_true",
                   help="walk only routes that replay to the same screen: a screen whose path leads elsewhere, or a control "
                        "whose label is gone, is a flaky finding and is not walked past (no tries on what the path leads "
                        "to now, no taps at a label's old place)")
    p.add_argument("--no-dedup-variants", action="store_true",
                   help="disable collapsing in-place state variations (expanded accordions, filter chips, selected tabs) "
                        "and parameterized sibling-list duplicates into their canonical screen during plain walks")
    p.add_argument("--keep-data", action="store_true", help="start from a prepared data slot (the one this run's --data-slots names, else default): each instance runs on a copy of it, made again at each relaunch, and the slot itself is not written")
    p.add_argument("--data-ignore", action="append", default=[], metavar="PATTERN",
                   help="what the app writes at every action that no screen reads, left out of the data a replay is checked "
                        "against: a file of the data slot (fnmatch), or FILE#TABLE / FILE#KEY for a database table or a "
                        "SharedPreferences key (repeatable; `relaunch_files` names what to put here)")
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
    p.add_argument("--diff-seed", action="store_true",
                   help="with --seed: compare walked/new screens against the seed's graph and captures (text/control + pixel diffs) without re-walking the base build")
    p.add_argument("--diff-controls", choices=("changed", "none", "all"),
                   help="with --diff-seed: which controls on an --only screen to tap: changed (default: only new/renamed controls), none (screen UI+pixel diff only), or all (full re-exploration)")
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
                only += [x for x in (ln.split("#", 1)[0].strip() for ln in f) if x]
        except OSError as e:
            fail(f"cannot read {args.only_file}: {e}")
    args.only_ids = list(dict.fromkeys(only))
    if args.deps_packages and not args.record_deps:
        fail("--deps-packages goes with --record-deps")
    if args.diff_seed and not args.seed:
        fail("--diff-seed goes with --seed")
    if args.diff_controls and not args.diff_seed:
        fail("--diff-controls goes with --diff-seed")
    if args.diff_controls is None:
        args.diff_controls = "changed" if args.diff_seed else "all"
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


def walk_settings():
    """What crawl.json records of how the walk read its screens, so a seeded walk reads them the same way."""
    d = {"clock": CLOCK}
    if NET is not None:
        NET.merge_all()  # what launches fetched so far (a running one writes its file again when it ends: merged at close)
        doc = NetFreeze.read(NET.master)
        d["network"] = {"recording": "network.har", "entries": len(doc["log"]["entries"]) if doc else 0,
                        "fetched": NET.fetched, "not_kept": NET.not_kept}
        if NET.not_kept:
            d["network"]["not_kept_requests"] = list(NET.not_kept_requests)
    return d


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
    global SCREEN_IDS
    SCREEN_IDS = int(seed.get("screen_ids", 1))  # the seed's screens keep their ids, and walked ones are compared with them
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
            doc["screen_titles"] = TITLES_ARE_IDENTITY
            doc["screen_ids"] = SCREEN_IDS  # a resume (--seed on this walk) makes its ids the same way
            doc["dedup_variants"] = bool(walk.dedup_variants)
            if walk.variant_aliases:
                doc["variant_aliases"] = dict(walk.variant_aliases)
            if NET is not None:
                NET.merge_all()
            doc.update(walk_settings())
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
    global NET, CLOCK
    if seed is not None and seed.get("clock"):
        CLOCK = seed["clock"]  # the seed's screens were read at this time: the ones walked again are too
    elif args.freeze_network:
        CLOCK = time.strftime("%Y-%m-%dT%H:%M:00Z", time.gmtime())  # the recording's data is from now
    if args.freeze_network:
        if seed is None:
            shutil.rmtree(os.path.join(out, "network"), ignore_errors=True)
            for n in ("network.har", "network.har" + NetFreeze.SECRETS):
                if os.path.exists(os.path.join(out, n)):
                    os.remove(os.path.join(out, n))
        NET = NetFreeze(out, src_dir if seed is not None else None)
    t0 = time.time()
    snap_override = seed.get("snapshot") if (seed is not None and args.diff_seed and "snapshot" in seed) else None
    args._seed_dedup_variants = seed.get("dedup_variants") if (seed is not None and "dedup_variants" in seed) else None
    new = Explorer("new", apk, args, out, [], combos=combos, snapshot_override=snap_override)
    base = None
    STOP.update(walk=new, out=out, t0=t0, combos=combos)
    signal.signal(signal.SIGTERM, on_stop)
    signal.signal(signal.SIGINT, on_stop)
    try:
        n_jobs = args.jobs
        if seed is not None and args.only_ids and not seed.get("stopped"):
            n_jobs = min(args.jobs, len(args.only_ids))
        new.pool(n_jobs)
        if seed is not None:
            new.explore_seeded(seed, args.only_ids)
        else:
            new.explore()
        log(f"[new] {len(new.screens)} screens, {len(new.edges)} transitions in {new.t_explore:.1f} s")
        sc = None
        if seed is not None and args.diff_seed:
            doc_combos = [snapshot.combo_name(c) for c in combos]
            sc = SeedCompare(new, seed, src_dir, {"combos": doc_combos, "findings": new.findings}, args, out)
        if combos:
            ids = None
            if seed is not None:
                # The --only screens, and carried ones the seed has no captures for (a walk stopped before its captures).
                missing = [i for i in new.order if seed.get("stopped") and new.screens[i]["origin"] == "carried"
                           and any(snapshot.combo_name(c) not in (new.screens[i].get("captures") or {}) for c in combos)]
                new.captured_now = set(missing)
                ids = [i for i in new.order if new.screens[i]["origin"] == "walked"] + missing
            new.on_captured = sc.compare if sc is not None else None
            new.capture(combos, ids)
            log(f"[new] {len(combos)} combination(s) captured in {new.t_capture:.1f} s")
        if seed is not None:
            new.carry_captures(lambda rel: os.path.join(src_dir, rel))
        doc = new.doc()
        doc["combos"] = [snapshot.combo_name(c) for c in combos]
        doc["screen_titles"] = bool(args.screen_titles)
        doc["screen_ids"] = SCREEN_IDS
        doc.update(walk_settings())
        doc["snapshot"] = bool(new.use_snapshot)
        doc["dedup_variants"] = bool(new.dedup_variants)
        if new.variant_aliases:
            doc["variant_aliases"] = dict(new.variant_aliases)
        if sc is not None:
            sc.doc = doc
            sc.finish(new.instances[0] if new.instances else None)
        if seed is not None and os.path.basename(src_dir) == ".seed":
            shutil.rmtree(src_dir, ignore_errors=True)
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
                ud = ui_diff(bs, ns)
                if ud:
                    p["ui_diff"] = ud
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
    if NET is not None:
        doc["network"] = walk_settings()["network"]  # with what the last launches fetched, written as they ended
        if NET.not_kept:
            first = NET.not_kept_requests[0] if NET.not_kept_requests else {}
            log(f"network: {NET.not_kept} fetched 401/403 answer(s) were not kept in network.har: the app's sign-in was "
                f"answered from the recording and the server refused what followed (first: {first.get('status')} "
                f"{first.get('method')} {first.get('url')}: {first.get('reason')}). Screens walked then may show a "
                "signed-out or error state; see crawl.json network.not_kept_requests")
    html_report(out, doc)
    with open(os.path.join(out, "crawl.json"), "w") as f:
        json.dump(doc, f, indent=1)
    log(f"report: {os.path.join(out, 'report.html')}")
    if args.sweep:
        junit(args.junit or os.path.join(out, "junit.xml"), sweep)
    crashes = [f for f in doc["findings"] if f["kind"] in ("crash", "compare") or (f["kind"] == "sweep" and not f["allowed"])]
    differs = [p for p in doc.get("compare", []) if p["status"] not in ("same", "unknown")]
    for p in differs:
        log(f"{p['status']:>8}  {p['id'] or p['base']}  {p.get('why') or ''}")
    code = 1 if crashes or differs else 0
    if args.events:
        print(json.dumps({"event": "done", "exit": code, "screens": len(doc["screens"]), "edges": len(doc["edges"]),
                          "incomplete": doc["incomplete"], "seconds": doc["seconds"]}), flush=True)
    sys.exit(code)


if __name__ == "__main__":
    main()
